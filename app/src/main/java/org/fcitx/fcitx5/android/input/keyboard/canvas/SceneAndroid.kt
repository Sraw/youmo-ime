/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Trace
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.input.scene.LookTheme
import org.fcitx.fcitx5.android.input.scene.SceneMarks
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.keyboard.KeyShortcutLooks
import org.fcitx.fcitx5.android.input.theme.ThemeLook
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.engine.Phases
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.engine.TextMetrics
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.theme.Cascades
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

/**
 * The engine's text measured as Android draws it ([ScenePainter]): the same Paint settings, so what is
 * placed is what is drawn. Kept, the last [KEPT] texts by text, font, weight and size: a scene made
 * again (Shift, Return's label) measures only what it had not before. Measuring is a small part of a
 * scene (youmo29: some 60 texts, 0.1 ms, specs/app.md), so this saves little, but nothing twice.
 *
 * The main thread's only, as the keyboard draws: its Paint and its map are one caller's at a time.
 */
class AndroidTextMeasurer(private val fonts: SceneFonts) : TextMeasurer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Rect()
    private val metrics = Paint.FontMetrics()

    private data class Asked(val text: String, val font: String, val weight: Int, val size: Double)

    // the least recently asked let go first (access order), so a table's roots or another font for a
    // while do not keep growing it
    private val kept = object : LinkedHashMap<Asked, TextMetrics>(KEPT, LOAD, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Asked, TextMetrics>) = size > KEPT
    }

    /** The texts measured with Paint, not found kept: for tests. */
    @VisibleForTesting
    var measured = 0
        private set

    override fun measure(text: String, font: String, weight: Int, size: Double): TextMetrics =
        kept.getOrPut(Asked(text, font, weight, size)) { measured(text, font, weight, size) }

    private fun measured(text: String, font: String, weight: Int, size: Double): TextMetrics {
        measured++
        Trace.beginSection("measure text")
        try {
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
        } finally {
            Trace.endSection()
        }
    }

    companion object {
        // the 26 keys' texts in each state with Shift's and a table's: a few hundred
        const val KEPT = 512
        private const val LOAD = 0.75f
    }
}

/** The engine's phases ([org.fcitx.fcitx5.android.scene.engine.SceneBuilder]) as Trace sections: a system trace shows where a scene's time goes. */
object SceneTrace : Phases {
    override fun begin(name: String) = Trace.beginSection(name)

    override fun end() = Trace.endSection()
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

    // the drawables named by id ([nameOf]): only these read back, so a name no menu gave (a theme's
    // icon="drawable:123") is no resource looked up that may not exist
    private val named = mutableSetOf<Int>()

    @DrawableRes
    fun of(name: String): Int? = all[name]
        ?: name.takeIf { it.startsWith(DRAWABLE) }?.substring(DRAWABLE.length)?.toIntOrNull()?.takeIf { synchronized(named) { it in named } }

    /**
     * [icon]'s name for a scene (a menu's item, KeyDef's drawable): its own where it has one, else
     * the drawable's id as a name ([of] reads it back), as the menus' icons are any drawable.
     */
    fun nameOf(@DrawableRes icon: Int): String =
        all.entries.firstOrNull { it.value == icon }?.key ?: "$DRAWABLE$icon".also { synchronized(named) { named += icon } }

    private const val DRAWABLE = "drawable:"

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

/**
 * A theme 3.0 look as the engine's theme ([LookTheme]), and its [Cascades], kept for the last
 * [KEPT] looks (main thread). A theme switched back to (day and night, the settings' preview and
 * back) is not read and matched again: each switch rebuilds the input view, and the 700 KiB a theme
 * read and its first scene's matching allocate were what made the canvas's switch wait for a GC.
 */
object SceneThemes {
    private const val KEPT = 4

    private class Made(val theme: Theme) {
        val cascades = Cascades(theme)
    }

    // in the order last asked for, the oldest first
    private val made = LinkedHashMap<Triple<ThemeLook, String, Boolean>, Made>(KEPT, 0.75f, true)

    fun of(look: ThemeLook, name: String, dark: Boolean): Theme? {
        val key = Triple(look, name, dark)
        made[key]?.let { return it.theme }
        return when (val t = ThemeReader.theme(LookTheme.xml(look, name, dark))) {
            is Parsed.Ok -> t.value.also {
                made[key] = Made(it)
                if (made.size > KEPT) made.remove(made.keys.first())
            }
            is Parsed.Failed -> null.also { Timber.e("scene theme $name: ${t.problems}") }
        }
    }

    /** [theme]'s cascades, [of]'s theme kept; a theme not kept (none [of] made), new ones. */
    fun cascades(theme: Theme): Cascades = made.values.firstOrNull { it.theme === theme }?.cascades ?: Cascades(theme)
}
