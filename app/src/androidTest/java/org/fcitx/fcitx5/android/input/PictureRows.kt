/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
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
 * perfOnly's motion rows: anim, A1's and A3's (motionRows, which runs effectRows) then A4's; anim,soak: the animated picture played 10 minutes
 * instead; picture: A4's alone (A5: one change to it measured in minutes).
 */
internal fun KeyboardPerfBaselineTest.animRows(only: List<String>) {
    if ("anim" in only) motionRows()
    if ("anim" in only || "picture" in only) pictureRows(soak = "soak" in only)
}

/**
 * 060-render-p3 §4.3's rows of A4 (the animated picture): a pack whose picture is an animated WebP (720 px,
 * the widest one plays at, 20 fps, [ASSET]) against the same pack with its first frame still, shown and left
 * alone 30 s (frames, the process's CPU, memory), a 10-key run over it, and hidden 10 s (no frame; the picture stopped). With
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
 * Material's file under [name] with [ASSET] under its keyboard (720×480, 30 frames of 50 ms, made by
 * uat/tools/perf_picture.py: the SDK has no encoder of animated WebP), played ([animated]) or its first frame
 * still (the same files, `animated` false: what playing adds is measured against it).
 */
private fun pictured(resources: Resources, name: String, animated: Boolean): Theme {
    val webp = InstrumentationRegistry.getInstrumentation().context.assets.open(ASSET).use { it.readBytes() }
    val first = BitmapFactory.decodeByteArray(webp, 0, webp.size)
    val still = ByteArrayOutputStream().also { first.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    first.recycle()
    val picture = """<picture src="anim.webp" brightness="90%" animated="$animated"><cut orientation="portrait" file="still.png"/></picture>"""
    val text = BuiltinThemeFiles.text(resources, R.raw.scene_theme_material)
        .replace("name=\"material\"", "name=\"$name\"")
        .replace("<style select=\"keyboard\" fill=\"@background\"/>", "$picture\n  <style select=\"keyboard\" fill=\"picture\"/>")
    val zip = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use {
            for ((entry, data) in listOf("theme.xml" to text.encodeToByteArray(), "anim.webp" to webp, "still.png" to still)) {
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

private const val ASSET = "perf-picture.webp"
private const val ANIMATED = "perf-picture-animated"
private const val STILL_PICTURE = "perf-picture-still"
private const val PICTURE_IDLE_MS = 30_000L
private const val HIDDEN_MS = 10_000L
private const val SOAK_MS = 10 * 60_000L
private const val SOAK_STEP_MS = 2 * 60_000L
private const val MS_A_MINUTE = 60_000L
