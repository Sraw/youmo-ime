/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.res.Resources
import android.graphics.PointF
import android.view.MotionEvent
import android.os.PowerManager
import android.os.SystemClock
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.BuiltinThemeFiles
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.KeyboardPerfBaselineTest.Companion.FAST_GAP_MS
import org.fcitx.fcitx5.android.input.KeyboardPerfBaselineTest.Companion.HOLD_MS
import org.fcitx.fcitx5.android.input.KeyboardPerfBaselineTest.Companion.IDLE_AFTER_MS
import org.fcitx.fcitx5.android.input.KeyboardPerfBaselineTest.Companion.IDLE_LEAD_MS
import org.fcitx.fcitx5.android.input.KeyboardPerfBaselineTest.Companion.LONG_PRESS_MS
import org.fcitx.fcitx5.android.input.KeyboardPerfBaselineTest.Companion.SHIFT_APART_MS
import org.fcitx.fcitx5.android.input.KeyboardPerfBaselineTest.Companion.shell
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Key-motion rows on a user's pack with motion, then again with 键盘动效 off and with battery saver on. Kept out of the test's class for detekt LargeClass. */
internal fun KeyboardPerfBaselineTest.motionRows() {
    val advanced = AppPrefs.getInstance().advanced
    val motionWas = advanced.keyboardMotion.getValue()
    val savingWas = service.getSystemService(PowerManager::class.java).isPowerSaveMode
    if (themeWas == null) themeWas = ThemeManager.prefs.normalModeTheme.getValue()
    // what motion adds is measured against the same pack without it, not a built-in theme
    val still = onMain { pack(service.resources, STILL, emptyList()) }
    val moving = onMain { pack(service.resources, MOVING, MOTION) }
    themesMade += listOf(still.name, moving.name)
    theme(still)
    fresh()
    val letters = "nihaoshiji"
    val at = (letters.toSet() + 'a').associateWith { keyAt(onKeyboard().text("$it")) }
    val space = keyAt(onKeyboard().res(KeyboardPerfBaselineTest.imePkg, "button_space"))
    val marks = onMain { DownMarks(service.window.window!!.callback, frames).also { service.window.window!!.callback = it } }
    fun made() = onMain { canvasView()?.animatorsMade ?: 0 }

    // at FAST_GAP_MS a key comes sooner than the 150 ms fade, so the fade does not play
    fun runs(label: String, gapMs: Long = FAST_GAP_MS, run: String = "10-key run") {
        val made = made()
        val samples = mutableListOf<KeyboardFrames.Sample>()
        val all = mutableListOf<KeyboardFrames.Frame>()
        val after = mutableListOf<Double>()
        repeat(warmUp + runs) { i ->
            val from = System.nanoTime()
            val ten = presses(marks, letters.map(at::getValue), gapMs = gapMs)
            SystemClock.sleep(IDLE_LEAD_MS)
            val still = System.nanoTime()
            SystemClock.sleep(IDLE_AFTER_MS)
            if (i >= warmUp) {
                samples += ten
                all += frames.between(from, still)
                after += frames.between(still, System.nanoTime()).size.toDouble()
            }
            endTyping(space)
        }
        row("anim", "$label, $run, every key", samples, "animators ${made() - made}")
        log("anim\t$label, $run, every frame\t${frames.summary(all)}")
        log("anim\t$label, idle after\tframes in 2 s ${Stats(after)} (median / p90)\tmax ${after.maxOrNull()?.toInt()}")
    }

    fun single(label: String) {
        val made = made()
        val single = mutableListOf<KeyboardFrames.Sample>()
        repeat(warmUp + runs) { i ->
            val one = presses(marks, listOf(at.getValue('a')), gapMs = HOLD_MS * 2)
            endTyping(space)
            if (i >= warmUp) single += one
        }
        row("anim", "$label, single key", single, "animators ${made() - made}")
    }

    try {
        onMain { advanced.keyboardMotion.setValue(true) }
        single("pack without motion")
        runs("pack without motion")
        runs("pack without motion", SLOW_GAP_MS, SLOW_RUN)
        shiftRows("pack without motion")
        popupRows("pack without motion")
        theme(moving)
        fresh()
        single("press scale")
        runs("release fade")
        runs("release fade", SLOW_GAP_MS, SLOW_RUN)
        shiftRows("shift transition")
        popupRows("popup appear")
        onMain { advanced.keyboardMotion.setValue(false) }
        runs("switch off")
        onMain { advanced.keyboardMotion.setValue(true) }
        // battery saver will not come on while charging over USB
        shell("dumpsys battery unplug")
        shell("cmd power set-mode 1")
        settle()
        val saving = service.getSystemService(PowerManager::class.java).isPowerSaveMode
        log("anim\tpower save\tbattery saver on: $saving")
        runs("power save")
    } finally {
        runCatching { shell("cmd power set-mode ${if (savingWas) 1 else 0}") }
        runCatching { shell("dumpsys battery reset") }
        onMain {
            advanced.keyboardMotion.setValue(motionWas)
            service.window.window!!.callback = marks.original
        }
    }
    // called here to keep the test's class under detekt's LargeClass
    effectRows()
}

private fun KeyboardPerfBaselineTest.shiftRows(label: String) {
    val shift = keyAt(onKeyboard().res(KeyboardPerfBaselineTest.imePkg, "button_caps"))
    val taps = mutableListOf<KeyboardFrames.Sample>()
    val all = mutableListOf<KeyboardFrames.Frame>()
    repeat(warmUp + runs) { i ->
        repeat(2) {
            val from = System.nanoTime()
            val sample = shiftTap(shift)
            SystemClock.sleep(SHIFT_APART_MS)
            if (i >= warmUp) {
                taps += sample
                all += frames.between(from, System.nanoTime())
            }
        }
    }
    row("anim", "$label, shift tap", taps)
    log("anim\t$label, shift, every frame\t${frames.summary(all)}")
}

private fun KeyboardPerfBaselineTest.popupRows(label: String) {
    val e = keyAt(onKeyboard().text("e"))
    val r = keyAt(onKeyboard().text("r"))
    val space = keyAt(onKeyboard().res(KeyboardPerfBaselineTest.imePkg, "button_space"))
    val all = mutableListOf<KeyboardFrames.Frame>()
    repeat(warmUp + runs) { i ->
        // from the down: the long-press setting is shorter than the LONG_PRESS_MS wait
        val from = System.nanoTime()
        val down = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, e, down, down)
        SystemClock.sleep(LONG_PRESS_MS + POPUP_FRAMES_MS)
        if (i >= warmUp) all += frames.between(from, System.nanoTime())
        inject(MotionEvent.ACTION_MOVE, PointF(r.x, e.y), down, SystemClock.uptimeMillis())
        inject(MotionEvent.ACTION_MOVE, e, down, SystemClock.uptimeMillis())
        inject(MotionEvent.ACTION_UP, e, down, SystemClock.uptimeMillis())
        settle()
        endTyping(space)
    }
    log("anim\t$label, long press, every frame\t${frames.summary(all)}")
}

private const val POPUP_FRAMES_MS = 300L

private const val MOVING = "perf-moving"
private const val STILL = "perf-still"

private const val SLOW_GAP_MS = 200L
private const val SLOW_RUN = "slow 10-key run (200 ms)"
private val MOTION = listOf(
    "<style select=\"key\" transition=\"150ms ease-out\"/>",
    "<style select=\"key:pressed\" transition=\"60ms linear\" press-scale=\"90%\" press-sink=\"2dp\"/>",
    "<style select=\"key:shifted, key:locked\" transition=\"200ms ease-in-out\"/>",
    "<style select=\"popup\" appear=\"scale 150ms\" transition=\"100ms ease-out\"/>",
)

// motion is never a built-in theme's: a user's pack
private fun pack(resources: Resources, name: String, styles: List<String>): Theme {
    val text = BuiltinThemeFiles.text(resources, R.raw.scene_theme_material)
        .replace("name=\"material\"", "name=\"$name\"")
        .replace("</theme>", styles.joinToString("\n", postfix = "\n</theme>"))
    val zip = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use {
            it.putNextEntry(ZipEntry("theme.xml"))
            it.write(text.encodeToByteArray())
        }
    }.toByteArray()
    val opened = ThemeFilesManager.openTheme(zip.inputStream(), ThemeManager.getAllThemes()).getOrThrow() as ThemeFilesManager.Opened.Pack
    val versions = opened.commit().getOrThrow()
    ThemeManager.packPlaced(versions[0].file)
    return versions.first { !it.isDark }
}

