/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Trace
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.data.theme.ThemePackFiles
import org.fcitx.fcitx5.android.input.bar.BarIcons
import org.fcitx.fcitx5.android.input.bar.ToolbarButtonLooks
import org.fcitx.fcitx5.android.input.keyboard.KeyShortcutLooks
import org.fcitx.fcitx5.android.input.scene.SceneMarks
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Icons
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.engine.Phases
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.engine.TextMetrics
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.SystemColors
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import timber.log.Timber
import org.fcitx.fcitx5.android.data.theme.Theme as AppTheme

/** `system` the system's font, a pack's ([files]) by name, at the weight asked; null the default one. */
class SceneFonts(private val look: ThemeLook, private val files: Map<String, String> = emptyMap()) {
    fun typeface(font: String, weight: Int): Typeface? = ThemeAssets.typeface(look, files[font] ?: path(look, font), weight)

    companion object {
        fun of(theme: AppTheme, look: ThemeLook) = SceneFonts(look, (theme as? AppTheme.Pack)?.drawn?.fonts.orEmpty())

        /** Null: the system's. */
        fun path(look: ThemeLook, font: String): String? = when (font) {
            LookTheme.KEY_FONT -> look.text.keyFont
            LookTheme.CANDIDATE_FONT -> look.text.candidateFont
            else -> null
        }
    }
}

/**
 * Measured with the same Paint settings [ScenePainter] draws with, so what is placed is what is drawn.
 * Main thread only: its Paint and its map are one caller's at a time.
 */
class AndroidTextMeasurer(val fonts: SceneFonts) : TextMeasurer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Rect()
    private val metrics = Paint.FontMetrics()

    private data class Asked(val text: String, val font: String, val weight: Int, val size: Double, val spacing: Double)

    // access order, so a table's roots or another font for a while do not keep growing it
    private val kept = lruMap<Asked, TextMetrics>(KEPT)

    /** For tests. */
    @VisibleForTesting
    var measured = 0
        private set

    override fun measure(text: String, font: String, weight: Int, size: Double): TextMetrics = measure(text, font, weight, size, 0.0)

    // Paint measures a theme's letter-spacing itself (its getTextBounds too), so not the default's arithmetic
    override fun measure(text: String, font: String, weight: Int, size: Double, letterSpacing: Double): TextMetrics =
        kept.getOrPut(Asked(text, font, weight, size, letterSpacing)) { measured(text, font, weight, size, letterSpacing) }

    private fun measured(text: String, font: String, weight: Int, size: Double, letterSpacing: Double): TextMetrics {
        measured++
        Trace.beginSection("measure text")
        try {
            paint.typeface = fonts.typeface(font, weight)
            paint.textSize = size.toFloat()
            paint.letterSpacing = letterSpacing.toFloat()
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
    }
}

object SceneTrace : Phases {
    override fun begin(name: String) = Trace.beginSection(name)

    override fun end() = Trace.endSection()
}

object SceneIcons {
    // by ReturnKeyAppearance's icon (Texts.returnIcon)
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
        "arrow-upward" to R.drawable.ic_baseline_arrow_upward_24,
        "arrow-downward" to R.drawable.ic_baseline_arrow_downward_24,
        "short-text" to R.drawable.ic_baseline_short_text_24,
    ) + KeyCommand.entries.associate { SceneMarks.icon(it) to KeyShortcutLooks.of(it).icon } +
        // a name shared with a key's or a command's is drawn on a key as the key's (hide-keyboard's ⌄, not the bar's ▾)
        (BarIcons.BAR + BarIcons.PANELS).filter { it.name !in Icons.KEYS && it.name !in Icons.COMMANDS }
            .associate { it.name to ToolbarButtonLooks.drawable(it.fallback) }

    // only these read back, so a name no menu gave (a theme's icon="drawable:123") looks up no resource that may not exist
    private val named = mutableSetOf<Int>()

    val names: Set<String> get() = all.keys

    // each logged once a process: a name the app does not have draws nothing, every frame
    private val unknown = mutableSetOf<String>()

    @DrawableRes
    fun of(name: String): Int? {
        val id = all[name]
            ?: name.takeIf { it.startsWith(DRAWABLE) }?.substring(DRAWABLE.length)?.toIntOrNull()?.takeIf { synchronized(named) { it in named } }
        if (id == null && synchronized(unknown) { unknown.add(name) }) Timber.w("scene icon %s: the app has none, drawn as nothing", name)
        return id
    }

    /** Its own where it has one, else the drawable's id as a name ([of] reads it back): the menus' icons are any drawable. */
    fun nameOf(@DrawableRes icon: Int): String =
        all.entries.firstOrNull { it.value == icon }?.key ?: "$DRAWABLE$icon".also { synchronized(named) { named += icon } }

    private const val DRAWABLE = "drawable:"

    fun returnName(@DrawableRes icon: Int): String = returns.entries.firstOrNull { it.value == icon }?.key ?: "enter"
}

internal fun <K, V> lruMap(max: Int): LinkedHashMap<K, V> = object : LinkedHashMap<K, V>(max, LRU_LOAD, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
}

private const val LRU_LOAD = 0.75f

/**
 * Kept for the last [KEPT] looks (main thread): each theme switch rebuilds the input view, and a theme's
 * read and first matching allocate enough to make the switch wait for a GC.
 */
object SceneThemes {
    private const val KEPT = 4

    private class Made(val theme: Theme, val pack: AppTheme.Pack? = null) {
        val cascades = Cascades(theme)
    }

    private val made = lruMap<Triple<ThemeLook, String, Boolean>, Made>(KEPT)

    // not read nor logged again at every scene; a look edited is another look, read
    private val refused = lruMap<Triple<ThemeLook, String, Boolean>, Unit>(KEPT)

    /** A test's own, one setting more than a look can; the themes kept forgotten as it is set. */
    @VisibleForTesting
    internal var source: (ThemeLook, String, Boolean) -> String = LookTheme::xml
        set(value) {
            field = value
            made.clear()
            refused.clear()
            packs.clear()
        }

    // by the system's colours their system() took
    private val packs = lruMap<Pair<AppTheme.Pack, SystemColors>, Made>(KEPT)

    fun of(theme: AppTheme, look: ThemeLook): Theme? {
        if (theme !is AppTheme.Pack) return of(look, theme.name, theme.isDark)
        val key = theme to ThemePackFiles.systemColors
        // this instance's alone: a copy() equal to an older one (system colours set anew) has its own
        packs[key]?.takeIf { it.pack === theme }?.let { return it.theme }
        return theme.drawn.also { packs[key] = Made(it, theme) }
    }

    fun of(look: ThemeLook, name: String, dark: Boolean): Theme? {
        val key = Triple(look, name, dark)
        made[key]?.let { return it.theme }
        if (refused.containsKey(key)) return null
        // writing it can throw (a broken 3.0 theme): refused as an unreadable look, not a crash in onDraw or layout
        val xml = runCatching { source(look, name, dark) }
            .onFailure { Timber.e(it, "scene theme $name: not written") }
            .getOrNull()
        val t = xml?.let { ThemeReader.theme(it, maxStyles = LookTheme.MAX_STYLES) }
        if (t is Parsed.Ok) {
            made[key] = Made(t.value)
            return t.value
        }
        if (t is Parsed.Failed) Timber.e("scene theme $name: ${t.problems}")
        refused[key] = Unit
        return null
    }

    /** A look names its files by path, so a theme's file written over in place changes no look. */
    fun forgetAll() {
        made.clear()
        refused.clear()
        packs.clear()
        ScenePainter.forgetIcons()
        SceneBoard.forgetLooks()
    }

    /** A theme not kept, new ones. */
    fun cascades(theme: Theme): Cascades =
        (made.values.firstOrNull { it.theme === theme } ?: packs.values.firstOrNull { it.theme === theme })?.cascades ?: Cascades(theme)
}
