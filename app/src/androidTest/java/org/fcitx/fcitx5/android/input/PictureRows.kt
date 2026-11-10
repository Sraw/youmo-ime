/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.BuiltinThemeFiles
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.KeyboardPerfBaselineTest.Companion.FAST_GAP_MS
import org.fcitx.fcitx5.android.input.KeyboardPerfBaselineTest.Companion.IDLE_LEAD_MS
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 060-render-p3 §4.3's rows of A4 (the animated picture): a pack whose picture is an animated WebP (1080 px
 * wide, 30 fps) against the same pack with its first frame still, shown and left alone 30 s (frames, the
 * process's CPU, memory), a 10-key run over it, and hidden 10 s (no frame; the picture stopped). With
 * `perfOnly=anim,soak`, instead, it plays [SOAK_MS] (10 minutes) and the memory is told at its start and each [SOAK_STEP_MS]
 * (a decoder's leak: §4 A4). Kept out of the test's class for its size (detekt LargeClass).
 */
internal fun KeyboardPerfBaselineTest.pictureRows(soak: Boolean) {
    if (themeWas == null) themeWas = ThemeManager.prefs.normalModeTheme.getValue()
    val (animated, still) = onMain { pictured(service.resources, ANIMATED, true) to pictured(service.resources, STILL_PICTURE, false) }
    themesMade += listOf(animated.name, still.name)
    fun running() = onMain { animatedBoard()?.isRunning }
    if (soak) {
        theme(animated)
        fresh()
        log("anim\tpicture soak\tplaying ${running()}")
        memory("picture soak, start")
        var at = 0L
        while (at < SOAK_MS) {
            SystemClock.sleep(SOAK_STEP_MS)
            at += SOAK_STEP_MS
            memory("picture soak, ${at / MS_A_MINUTE} min")
        }
        return
    }
    for ((label, t) in listOf("picture still" to still, "picture idle" to animated)) {
        theme(t)
        fresh()
        settle()
        log("anim\t$label\tplaying ${running()}\t${frames.idle(PICTURE_IDLE_MS)}")
        memory("$label, shown")
    }
    // the 10-key run over it: each key's press and every frame
    val letters = "nihaoshiji"
    val at = letters.toSet().associateWith { keyAt(onKeyboard().text("$it")) }
    val space = keyAt(onKeyboard().res(KeyboardPerfBaselineTest.imePkg, "button_space"))
    val marks = onMain { DownMarks(service.window.window!!.callback, frames).also { service.window.window!!.callback = it } }
    try {
        val samples = mutableListOf<KeyboardFrames.Sample>()
        val all = mutableListOf<KeyboardFrames.Frame>()
        repeat(warmUp + runs) { i ->
            val from = System.nanoTime()
            val ten = presses(marks, letters.map(at::getValue), gapMs = FAST_GAP_MS)
            SystemClock.sleep(IDLE_LEAD_MS)
            if (i >= warmUp) {
                samples += ten
                all += frames.between(from, System.nanoTime())
            }
            endTyping(space)
        }
        row("anim", "picture + press, 10-key run, every key", samples)
        log("anim\tpicture + press, 10-key run, every frame\t${frames.summary(all)}")
    } finally {
        onMain { service.window.window!!.callback = marks.original }
    }
    hideKeyboard()
    log("anim\thidden\tplaying ${running()}\t${frames.idle(HIDDEN_MS)}")
    memory("picture idle, hidden")
    showKeyboard()
    log("anim\tshown again\tplaying ${running()}")
}

// the animated picture under the keyboard shown, null where there is none
private fun KeyboardPerfBaselineTest.animatedBoard(): AnimatedBoardDrawable? {
    fun all(v: View): Sequence<View> = sequenceOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).asSequence().flatMap { all(g.getChildAt(it)) } } ?: emptySequence())
    return all(service.window.window!!.decorView).firstNotNullOfOrNull { it.background as? AnimatedBoardDrawable }
}

/**
 * Material's file under [name] with a picture of [FRAMES] frames under its keyboard: played ([animated]) or its
 * first frame still (the same file, `animated` false: what the frames add is measured against it).
 */
private fun pictured(resources: Resources, name: String, animated: Boolean): Theme {
    val frames = (0 until FRAMES).map(::frame)
    val webp = animatedWebp(frames)
    val first = ByteArrayOutputStream().also { frames[0].compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    frames.forEach(Bitmap::recycle)
    val picture = """<picture src="anim.webp" brightness="90%" animated="$animated"><cut orientation="portrait" file="still.png"/></picture>"""
    val text = BuiltinThemeFiles.text(resources, R.raw.scene_theme_material)
        .replace("name=\"material\"", "name=\"$name\"")
        .replace("<style select=\"keyboard\" fill=\"@background\"/>", "$picture\n  <style select=\"keyboard\" fill=\"picture\"/>")
    val zip = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use {
            for ((entry, data) in listOf("theme.xml" to text.encodeToByteArray(), "anim.webp" to webp, "still.png" to first)) {
                it.putNextEntry(ZipEntry(entry))
                it.write(data)
            }
        }
    }.toByteArray()
    val opened = ThemeFilesManager.openTheme(zip.inputStream(), ThemeManager.getAllThemes()).getOrThrow() as ThemeFilesManager.Opened.Pack
    val versions = opened.commit().getOrThrow()
    ThemeManager.packPlaced(versions[0].file)
    return versions.first { !it.isDark }
}

// a gradient turning and a band of dots moving across: each frame unlike the last, as a real one is
private fun frame(i: Int): Bitmap {
    val b = Bitmap.createBitmap(PICTURE_W, PICTURE_H, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
    val shift = PICTURE_W * i / FRAMES.toFloat()
    Canvas(b).apply {
        drawPaint(Paint().apply { shader = LinearGradient(shift, 0f, shift + PICTURE_W, PICTURE_H.toFloat(), 0xff3a6ea5.toInt(), 0xfff2c14e.toInt(), Shader.TileMode.MIRROR) })
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55ffffff }
        for (d in 0 until DOTS) drawCircle((d * 97 + shift) % PICTURE_W, (d * 61 % PICTURE_H).toFloat(), 20f + d % 7 * 9, dot)
    }
    return b
}

/**
 * [frames] as an animated WebP, [FRAME_MS] each: each frame compressed as a still lossy WebP (opaque: a lone
 * `VP8 ` chunk after its RIFF head) and put in an ANMF chunk (the container's own format; no encoder of
 * animated WebP is in the SDK).
 */
@Suppress("DEPRECATION")
private fun animatedWebp(frames: List<Bitmap>): ByteArray {
    val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
    val out = ByteArrayOutputStream()
    fun le(o: ByteArrayOutputStream, v: Int, n: Int) = repeat(n) { o.write((v shr (8 * it)) and 0xff) }
    fun chunk(o: ByteArrayOutputStream, tag: String, data: ByteArray) {
        o.write(tag.toByteArray())
        le(o, data.size, 4)
        o.write(data)
        if (data.size % 2 == 1) o.write(0)
    }
    val w = frames[0].width
    val h = frames[0].height
    out.write("RIFF".toByteArray())
    le(out, 0, 4)
    out.write("WEBP".toByteArray())
    chunk(out, "VP8X", ByteArrayOutputStream().also { o -> o.write(ANIMATION_FLAG); le(o, 0, 3); le(o, w - 1, 3); le(o, h - 1, 3) }.toByteArray())
    // background, loop for ever
    chunk(out, "ANIM", ByteArray(6))
    frames.forEach { f ->
        val still = ByteArrayOutputStream().also { f.compress(format, QUALITY, it) }.toByteArray()
        // the still file's image chunks (ALPH, VP8, VP8L), its RIFF head and any VP8X left out
        val body = ByteArrayOutputStream()
        var at = RIFF_HEAD
        while (at + CHUNK_HEAD <= still.size) {
            val size = (0 until 4).sumOf { (still[at + 4 + it].toInt() and 0xff) shl (8 * it) }
            val end = minOf(still.size, at + CHUNK_HEAD + size + (size and 1))
            if (String(still, at, 4, Charsets.US_ASCII) != "VP8X") body.write(still, at, end - at)
            at = end
        }
        val anmf = ByteArrayOutputStream().also { o ->
            le(o, 0, 6)
            le(o, w - 1, 3)
            le(o, h - 1, 3)
            le(o, FRAME_MS, 3)
            o.write(0)
            o.write(body.toByteArray())
        }.toByteArray()
        chunk(out, "ANMF", anmf)
    }
    return out.toByteArray().also { b ->
        val size = b.size - 8
        repeat(4) { b[4 + it] = (size shr (8 * it)).toByte() }
    }
}

private const val ANIMATED = "perf-picture-animated"
private const val STILL_PICTURE = "perf-picture-still"
private const val PICTURE_W = 1080
private const val PICTURE_H = 720
private const val FRAMES = 60
private const val FRAME_MS = 33
private const val QUALITY = 70
private const val DOTS = 40
private const val ANIMATION_FLAG = 0x02
private const val RIFF_HEAD = 12
private const val CHUNK_HEAD = 8
private const val PICTURE_IDLE_MS = 30_000L
private const val HIDDEN_MS = 10_000L
private const val SOAK_MS = 10 * 60_000L
private const val SOAK_STEP_MS = 2 * 60_000L
private const val MS_A_MINUTE = 60_000L
