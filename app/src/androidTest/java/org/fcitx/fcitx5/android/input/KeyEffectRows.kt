/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 060-render-p3 §4.3's rows of A3 (a key's press effect), each against the same pack without it (material's
 * look): a ripple image (64 px, 100% of the key, 300 ms) and particles (12 a press, the most, 24dp, 600 ms).
 * A single press (its frame), 10-key runs typed fast (80 ms: effects play on every press, the pools full) and
 * slow (200 ms), every frame of them, and the frames of the 2 s after (none once they end). `perfOnly=effects`.
 */
internal fun KeyboardPerfBaselineTest.effectRows() {
    val advanced = AppPrefs.getInstance().advanced
    val motionWas = advanced.keyboardMotion.getValue()
    if (themeWas == null) themeWas = ThemeManager.prefs.normalModeTheme.getValue()
    val plain = onMain { effectPack(service.resources, PLAIN, null) }
    val ripple = onMain { effectPack(service.resources, RIPPLE, "ripple-image(ring.png, 100%, 300ms)") }
    val particles = onMain { effectPack(service.resources, PARTICLES, "particles(star.png, 12, 24dp, 600ms)") }
    themesMade += listOf(plain.name, ripple.name, particles.name)
    theme(plain)
    fresh()
    val letters = "nihaoshiji"
    val at = (letters.toSet() + 'a').associateWith { keyAt(onKeyboard().text("$it")) }
    val space = keyAt(onKeyboard().res(KeyboardPerfBaselineTest.imePkg, "button_space"))
    val marks = onMain { DownMarks(service.window.window!!.callback, frames).also { service.window.window!!.callback = it } }

    fun runs(label: String, gapMs: Long, run: String) {
        val samples = mutableListOf<KeyboardFrames.Sample>()
        val all = mutableListOf<KeyboardFrames.Frame>()
        val after = mutableListOf<Double>()
        repeat(warmUp + runs) { i ->
            val from = System.nanoTime()
            val ten = presses(marks, letters.map(at::getValue), gapMs = gapMs)
            // the last press's effect (600 ms at most) within the lead and a second, then 2 s that should draw nothing
            SystemClock.sleep(IDLE_LEAD_MS + EFFECT_LEAD_MS)
            val still = System.nanoTime()
            SystemClock.sleep(IDLE_AFTER_MS)
            if (i >= warmUp) {
                samples += ten
                all += frames.between(from, still)
                after += frames.between(still, System.nanoTime()).size.toDouble()
            }
            endTyping(space)
        }
        row("anim", "$label, $run, every key", samples)
        log("anim\t$label, $run, every frame\t${frames.summary(all)}")
        log("anim\t$label, $run, idle after\tframes in 2 s ${Stats(after)} (median / p90)\tmax ${after.maxOrNull()?.toInt()}")
    }

    fun single(label: String) {
        val single = mutableListOf<KeyboardFrames.Sample>()
        repeat(warmUp + runs) { i ->
            val one = presses(marks, listOf(at.getValue('a')), gapMs = HOLD_MS * 2)
            endTyping(space)
            if (i >= warmUp) single += one
        }
        row("anim", "$label, single key", single)
    }

    fun all(label: String) {
        single(label)
        runs(label, FAST_GAP_MS, "10-key run")
        runs(label, SLOW_GAP_MS, "slow 10-key run (200 ms)")
    }

    try {
        onMain { advanced.keyboardMotion.setValue(true) }
        all("pack without effects")
        theme(ripple)
        fresh()
        all("ripple image")
        theme(particles)
        fresh()
        all("particles")
    } finally {
        onMain {
            advanced.keyboardMotion.setValue(motionWas)
            service.window.window!!.callback = marks.original
        }
    }
}

private const val PLAIN = "perf-plain"
private const val RIPPLE = "perf-ripple"
private const val PARTICLES = "perf-particles"
private const val SLOW_GAP_MS = 200L
private const val EFFECT_LEAD_MS = 1000L
private const val RING_PX = 64
private const val STAR_PX = 24

// a [px]-square PNG of a white disc (a ring's or a star's stand-in: what costs is its size, not its shape)
private fun disc(px: Int): ByteArray {
    val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    Canvas(b).drawCircle(px / 2f, px / 2f, px / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 255, 255, 255) })
    return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}

// a user's pack: material's file under [name], every key's pressed look playing [effect] (null: none), its two images
private fun effectPack(resources: Resources, name: String, effect: String?): Theme {
    val style = effect?.let { "<style select=\"key:pressed\" effect=\"$it\"/>\n" }.orEmpty()
    val text = BuiltinThemeFiles.text(resources, R.raw.scene_theme_material)
        .replace("name=\"material\"", "name=\"$name\"")
        .replace("</theme>", "$style</theme>")
    val zip = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use {
            it.putNextEntry(ZipEntry("theme.xml"))
            it.write(text.encodeToByteArray())
            it.putNextEntry(ZipEntry("ring.png"))
            it.write(disc(RING_PX))
            it.putNextEntry(ZipEntry("star.png"))
            it.write(disc(STAR_PX))
        }
    }.toByteArray()
    val opened = ThemeFilesManager.openTheme(zip.inputStream(), ThemeManager.getAllThemes()).getOrThrow() as ThemeFilesManager.Opened.Pack
    val versions = opened.commit().getOrThrow()
    ThemeManager.packPlaced(versions[0].file)
    return versions.first { !it.isDark }
}
