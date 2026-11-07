/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.input.scene.LookTheme
import org.fcitx.fcitx5.android.input.scene.SceneMarks
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.keyboard.KeyShortcutLooks
import org.fcitx.fcitx5.android.input.theme.ThemeLook
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.engine.TextMetrics
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import timber.log.Timber

/**
 * A scene's text in [look]'s fonts: `system` the system's, [LookTheme.KEY_FONT] the theme's key font,
 * at the weight asked (ThemeAssets, as KeyView's keyTypeface); null the default one.
 */
class SceneFonts(private val look: ThemeLook) {
    fun typeface(font: String, weight: Int): Typeface? =
        ThemeAssets.typeface(look, if (font == LookTheme.KEY_FONT) look.text.keyFont else null, weight)
}

/** The engine's text measured as Android draws it ([ScenePainter]): the same Paint settings, so what is placed is what is drawn. */
class AndroidTextMeasurer(private val fonts: SceneFonts) : TextMeasurer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Rect()
    private val metrics = Paint.FontMetrics()

    override fun measure(text: String, font: String, weight: Int, size: Double): TextMetrics {
        paint.typeface = fonts.typeface(font, weight)
        paint.textSize = size.toFloat()
        val advance = paint.measureText(text)
        paint.getTextBounds(text, 0, text.length, ink)
        paint.getFontMetrics(metrics)
        return TextMetrics(
            advance.toDouble(),
            Box(ink.left.toDouble(), ink.top.toDouble(), ink.right.toDouble(), ink.bottom.toDouble()),
            metrics.top.toDouble(),
            metrics.bottom.toDouble(),
        )
    }
}

/** The icons a scene names ([org.fcitx.fcitx5.android.scene.Op.Icon]) as the app's drawables. */
object SceneIcons {
    // Return's, by ReturnKeyAppearance's icon: the name the scene is given for it (Texts.returnIcon)
    private val returns = mapOf(
        "enter" to R.drawable.ic_baseline_keyboard_return_24,
        "go" to R.drawable.ic_baseline_arrow_forward_24,
        "search" to R.drawable.ic_baseline_search_24,
        "send" to R.drawable.ic_baseline_send_24,
        "next" to R.drawable.ic_baseline_keyboard_tab_24,
        "done" to R.drawable.ic_baseline_done_24,
        "previous" to R.drawable.ic_baseline_keyboard_tab_reverse_24,
    )

    private val all: Map<String, Int> = returns + mapOf(
        "backspace" to R.drawable.ic_baseline_backspace_24,
        "language" to R.drawable.ic_baseline_language_24,
        "space" to R.drawable.ic_baseline_space_bar_24,
        "shift" to R.drawable.ic_capslock_none,
        "shift-once" to R.drawable.ic_capslock_once,
        "shift-locked" to R.drawable.ic_capslock_lock,
        "number-pad" to R.drawable.ic_number_pad,
        "lock" to R.drawable.ic_baseline_lock_24,
        "lock-open" to R.drawable.ic_baseline_lock_open_24,
        "emoji" to R.drawable.ic_baseline_tag_faces_24,
        "voice" to R.drawable.ic_baseline_keyboard_voice_24,
    ) + KeyCommand.entries.associate { SceneMarks.icon(it) to KeyShortcutLooks.of(it).icon }

    @DrawableRes
    fun of(name: String): Int? = all[name]

    /** Return's icon's name for the scene: the one the keyboard shows it with now. */
    fun returnName(@DrawableRes icon: Int): String = returns.entries.firstOrNull { it.value == icon }?.key ?: "enter"
}

/** The layouts the app has (raw `scene_layout_*`, app/build.gradle.kts), read once a process: one that did not read, too (null). */
object SceneLayouts {
    private var qwerty: Result<Layout?>? = null

    fun qwerty(context: Context): Layout? =
        (qwerty ?: runCatching { read(context, R.raw.scene_layout_qwerty) }.also { qwerty = it }.onFailure { Timber.e(it, "scene layout unread") })
            .getOrNull()

    private fun read(context: Context, id: Int): Layout? {
        val text = context.resources.openRawResource(id).use { it.readBytes().decodeToString() }
        return when (val l = LayoutReader.layout(text)) {
            is Parsed.Ok -> l.value
            is Parsed.Failed -> null.also { Timber.e("scene layout: ${l.problems}") }
        }
    }
}

/** A theme 3.0 look as the engine's theme ([LookTheme]), made once for each look. */
object SceneThemes {
    private var made: Pair<Triple<ThemeLook, String, Boolean>, Theme>? = null

    fun of(look: ThemeLook, name: String, dark: Boolean): Theme? {
        val key = Triple(look, name, dark)
        made?.takeIf { it.first == key }?.let { return it.second }
        return when (val t = ThemeReader.theme(LookTheme.xml(look, name, dark))) {
            is Parsed.Ok -> t.value.also { made = key to it }
            is Parsed.Failed -> null.also { Timber.e("scene theme $name: ${t.problems}") }
        }
    }
}
