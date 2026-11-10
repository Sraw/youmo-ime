/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import java.io.File
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.ceil

/**
 * The input method's service, found from this process's windows. The instrumented tests run in the
 * app's process, where the system binds the input method too; no public call reaches the service,
 * so its window is found as Espresso finds its roots: WindowManagerGlobal's views, the one whose
 * context is the service. Main thread only.
 */
internal fun findInputMethodService(): FcitxInputMethodService? {
    val global = Class.forName("android.view.WindowManagerGlobal")
    val instance = global.getMethod("getInstance").invoke(null)
    val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<*>
    return views.filterIsInstance<View>().firstNotNullOfOrNull { serviceOf(it.context) }
}

private tailrec fun serviceOf(context: Context?): FcitxInputMethodService? = when (context) {
    is FcitxInputMethodService -> context
    is ContextWrapper -> serviceOf(context.baseContext)
    else -> null
}

/** Views in [view]'s tree, itself included. */
internal fun viewCount(view: View): Int =
    1 + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).sumOf { viewCount(g.getChildAt(it)) } } ?: 0)

/**
 * The frames of the input method's [window], timed: [FrameMetrics] of every frame it draws, and a
 * [Mark] that picks out one frame, the first drawn after something happened.
 *
 * A mark is an `OnPreDrawListener` on the window's root: the first traversal in which [mark]'s
 * condition holds is the frame that shows it. There the Choreographer's frame time is read (the
 * frame's [FrameMetrics.VSYNC_TIMESTAMP], which finds its metrics later) and a frame commit callback
 * is registered: it runs once the render thread has issued that frame to the GPU. A frame is done at
 * its intended vsync plus [FrameMetrics.TOTAL_DURATION]: to the GPU's end from API 31, to the swap
 * before. What the compositor and the display add after is not in any number here.
 */
internal class KeyboardFrames(private val window: Window) : AutoCloseable {

    class Frame(
        val vsync: Long,
        val intended: Long,
        val total: Long,
        val layoutMeasure: Long,
        val draw: Long,
        val sync: Long,
        val issue: Long,
        val input: Long,
        val animation: Long,
        val swap: Long,
        // API 31+: the frame's deadline (FrameMetrics.DEADLINE); 0 before
        val deadline: Long,
    ) {
        val done get() = intended + total

        // the CPU's part of a frame, as Jetpack Macrobenchmark's FrameTimingMetric sums it (frameDurationCpuMs)
        val cpu get() = input + animation + layoutMeasure + draw + sync + issue + swap
    }

    /** One frame asked for: the time it is counted [from] (System.nanoTime), and what was seen of it. */
    class Mark(@Volatile var from: Long) {
        @Volatile var vsync = 0L
        @Volatile var preDraw = 0L
        @Volatile var committed = 0L

        // when an input event reached the window (DownMarks), 0 for anything else
        @Volatile var dispatched = 0L
    }

    /** A mark's frame, in milliseconds from its [Mark.from]: to the frame done, null without its metrics. */
    class Sample(mark: Mark, frame: Frame?) {
        val toDone: Double? = frame?.let { ms(it.done - mark.from) }
        val toCommit = ms(mark.committed - mark.from)

        // the main thread's part before the frame is drawn: the work itself, a wait for the vsync, measure and layout
        val toPreDraw = ms(mark.preDraw - mark.from)
        val dispatch: Double? = mark.dispatched.takeIf { it != 0L }?.let { ms(it - mark.from) }

        // from the window's own receipt of the event to the frame done: the app's part alone
        val fromDispatch: Double? = frame?.takeIf { mark.dispatched != 0L }?.let { ms(it.done - mark.dispatched) }
        val frameTotal: Double? = frame?.let { ms(it.total) }
        val layoutMeasure: Double? = frame?.let { ms(it.layoutMeasure) }
        val draw: Double? = frame?.let { ms(it.draw) }

        /** The number compared: to the frame done, to its commit without metrics. */
        val latency get() = toDone ?: toCommit

        /** What the process allocated for it (bytes) and the GCs that ran, from its action till it settled ([Heap]); null untold. */
        var allocated: Double? = null
        var gcs: Double? = null

        /** [step]'s allocations and GCs as this sample's, or [share] of them (a run of keys, one heap step for all). */
        fun heap(step: Heap, share: Int = 1) = apply {
            allocated = step.bytes.toDouble() / share
            gcs = step.gcs.toDouble() / share
        }
    }

    private val thread = HandlerThread("keyboard-frames").apply { start() }
    private val frames = CopyOnWriteArrayList<Frame>()
    private val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
        frames += Frame(
            m.getMetric(FrameMetrics.VSYNC_TIMESTAMP),
            m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
            m.getMetric(FrameMetrics.TOTAL_DURATION),
            m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
            m.getMetric(FrameMetrics.DRAW_DURATION),
            m.getMetric(FrameMetrics.SYNC_DURATION),
            m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
            m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION),
            m.getMetric(FrameMetrics.ANIMATION_DURATION),
            m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) m.getMetric(FrameMetrics.DEADLINE) else 0L,
        )
        // a long run keeps only the recent ones: marks are looked up within seconds
        if (frames.size > MAX_FRAMES) frames.subList(0, frames.size - MAX_FRAMES / 2).clear()
    }

    // main thread
    init {
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
    }

    /** Main thread: marks the first frame drawn once [ready] holds. */
    fun mark(from: Long, ready: () -> Boolean = { true }): Mark {
        val mark = Mark(from)
        val root = window.decorView
        // the frame time is public only to a frame callback, which runs before the traversal of the
        // same frame: kept from each frame till the mark's
        val choreographer = Choreographer.getInstance()
        var frameTime = 0L
        choreographer.postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frameTime = frameTimeNanos
                if (mark.preDraw == 0L) choreographer.postFrameCallback(this)
            }
        })
        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!ready()) return true
                root.viewTreeObserver.removeOnPreDrawListener(this)
                mark.vsync = frameTime
                mark.preDraw = System.nanoTime()
                // taken before this frame is drawn (ViewRootImpl.performDraw), so it is this frame's
                root.viewTreeObserver.registerFrameCommitCallback { mark.committed = System.nanoTime() }
                return true
            }
        })
        return mark
    }

    /** Waits for [mark]'s frame and its metrics. */
    fun await(mark: Mark, timeoutMs: Long = 5_000): Sample {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (mark.committed == 0L) {
            check(SystemClock.uptimeMillis() < deadline) { "no frame drawn within $timeoutMs ms" }
            SystemClock.sleep(2)
        }
        // the metrics come a little after the frame, on their own thread
        val metricsBy = SystemClock.uptimeMillis() + METRICS_WAIT_MS
        var frame = frameOf(mark)
        while (frame == null && SystemClock.uptimeMillis() < metricsBy) {
            SystemClock.sleep(5)
            frame = frameOf(mark)
        }
        return Sample(mark, frame)
    }

    // by its frame time; a mark made while a frame's input was handled has none (its callback is due
    // the frame after), and takes the last frame begun before its pre-draw that is done after it
    private fun frameOf(mark: Mark): Frame? = frames.lastOrNull { it.vsync == mark.vsync }
        ?: frames.lastOrNull { it.intended <= mark.preDraw && it.done >= mark.preDraw }.takeIf { mark.vsync == 0L }

    /** The frames whose intended vsync is in [from, to) (System.nanoTime), as far as they are kept. */
    fun between(from: Long, to: Long): List<Frame> = frames.filter { it.intended in from until to }

    /**
     * [frames] summed up as design 060-render-p3 §4.3 asks: the CPU's part a frame (p50/p90/p99 ms) and
     * the share of frames over their deadline: TOTAL_DURATION past DEADLINE from API 31, past one
     * refresh period before (an approximation, compared on the same device only).
     */
    fun summary(frames: List<Frame>): String {
        if (frames.isEmpty()) return "frames 0"
        val cpu = Stats(frames.map { ms(it.cpu) })
        val period = (1e9 / (window.decorView.display?.refreshRate ?: DEFAULT_HZ)).toLong()
        val over = frames.map { ms(it.total - (if (it.deadline > 0) it.deadline else period)) }
        val late = over.count { it > 0 }
        return "frames ${frames.size}\tcpu ms %.2f / %.2f / %.2f\toverrun p90 %.2f ms\tlate %.1f%%".format(
            Locale.ROOT, cpu.median, cpu.p90, cpu.p99, Stats(over).p90, 100.0 * late / frames.size,
        )
    }

    /** [lengthMs] of nothing done (not the main thread): the frames drawn and the process's share of a core ([ProcessCpu]). */
    fun idle(lengthMs: Long): String {
        val cpu = ProcessCpu.ms()
        val from = System.nanoTime()
        SystemClock.sleep(lengthMs)
        val to = System.nanoTime()
        val share = 100 * (ProcessCpu.ms() - cpu) / ms(to - from)
        return "%d s\tframes %d\tprocess cpu %.2f%% of a core".format(Locale.ROOT, lengthMs / 1000, between(from, to).size, share)
    }

    override fun close() {
        window.removeOnFrameMetricsAvailableListener(listener)
        thread.quitSafely()
    }

    private companion object {
        const val MAX_FRAMES = 400
        const val METRICS_WAIT_MS = 1_000L
        const val DEFAULT_HZ = 60f

        fun ms(ns: Long) = ns / 1e6
    }
}

/**
 * The input method window's touches, a mark at each finger down while [recording]: the frame after a
 * down reaches the window is the one that shows the key pressed, counted from the event's own time
 * (when it was made, so the system's way to the window is in). Put in as the window's callback, the
 * first that sees a touch there whatever draws the keys.
 */
internal class DownMarks(val original: Window.Callback, private val frames: KeyboardFrames) : Window.Callback by original {

    @Volatile var recording = false
    val marks = CopyOnWriteArrayList<KeyboardFrames.Mark>()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (recording && event.actionMasked == MotionEvent.ACTION_DOWN) {
            // the press is drawn by the traversal after this event: the mark is in before it
            marks += frames.mark(event.eventTime * NS_PER_MS).also { it.dispatched = System.nanoTime() }
        }
        return original.dispatchTouchEvent(event)
    }

    companion object {
        const val NS_PER_MS = 1_000_000L
    }
}

/**
 * What the process has allocated (bytes) and how many GCs have run, ART's counts since it began
 * ([Debug.getRuntimeStat]); one minus another is what happened between, every thread's (the test's own
 * waiting allocates a little, the same with either drawing).
 */
internal data class Heap(val bytes: Long, val gcs: Long) {
    operator fun minus(o: Heap) = Heap(bytes - o.bytes, gcs - o.gcs)

    companion object {
        fun now() = Heap(stat("art.gc.bytes-allocated"), stat("art.gc.gc-count"))

        private fun stat(name: String) = Debug.getRuntimeStat(name)?.toLongOrNull() ?: 0L
    }
}

/** Median, 90th percentile (nearest rank) and mean of [values]. */
internal class Stats(values: List<Double>) {
    private val sorted = values.sorted()
    val n = sorted.size
    val mean: Double = if (sorted.isEmpty()) Double.NaN else sorted.sum() / n
    val median: Double = when {
        sorted.isEmpty() -> Double.NaN
        n % 2 == 1 -> sorted[n / 2]
        else -> (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }
    val p90: Double = rank(0.9)
    val p99: Double = rank(0.99)

    private fun rank(q: Double) = if (sorted.isEmpty()) Double.NaN else sorted[ceil(q * n).toInt() - 1]

    override fun toString() = "%.1f / %.1f".format(Locale.ROOT, median, p90)
}

/** design 060-render-p3 §4.3's rows that need motion in the engine, and the stage that brings it. */
internal val ANIM_NOT_YET = listOf(

    "popup appear" to "A2", "ripple image" to "A3", "particles" to "A3", "picture idle" to "A4", "picture + press" to "A4",
)

/**
 * The CPU time this process has used, user and system ([/proc/self/stat]'s utime + stime), in ms:
 * one minus another over a wall time is the process's share of a core.
 */
internal object ProcessCpu {
    private val tick = 1000.0 / Os.sysconf(OsConstants._SC_CLK_TCK)

    fun ms(): Double {
        // the name (field 2) may hold spaces: the fields after its closing parenthesis, utime the 12th of them
        val after = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ')
        return (after[UTIME].toLong() + after[UTIME + 1].toLong()) * tick
    }

    private const val UTIME = 11
}

/** The process's memory as `dumpsys meminfo` sums it up (App Summary), in kB. */
internal data class Memory(val javaHeap: Int, val nativeHeap: Int, val graphics: Int, val totalPss: Int) {
    companion object {
        private fun row(text: String, name: String): Int =
            Regex("^\\s*$name:\\s+(\\d+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.toInt() ?: -1

        fun parse(meminfo: String): Memory {
            val summary = meminfo.substringAfter("App Summary", meminfo)
            // "TOTAL PSS:" from Android 12 on, "TOTAL:" before
            val total = row(summary, "TOTAL PSS").takeIf { it >= 0 } ?: row(summary, "TOTAL")
            return Memory(row(summary, "Java Heap"), row(summary, "Native Heap"), row(summary, "Graphics"), total)
        }
    }
}
