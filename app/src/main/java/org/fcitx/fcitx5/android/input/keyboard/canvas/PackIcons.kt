/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.graphics.Path
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.theme.ShapePath.Segment
import org.fcitx.fcitx5.android.scene.theme.SvgSubset
import timber.log.Timber
import java.io.File
import org.fcitx.fcitx5.android.data.theme.Theme as AppTheme

/**
 * The `<icon>`s of the theme a keyboard is actually drawn in (060-theme-sounds-icons §2.2, review S10): [files]
 * each icon's name to its file's path, as [AppTheme.Pack.drawn] has them. Where the keys fell back to the
 * built-in theme ([SceneBoard.drawnTheme]), it is the built-in's, none, and the app's own icons are drawn.
 *
 * An icon is read when first drawn, on [ThemeAssets]' decode thread, not the main one: 15 of the largest
 * (64 KB, 20 000 numbers of arcs each) took 786 ms the first time on youmo29, 220 ms after (specs/app.md),
 * far past a frame's 16 ms (060-theme-sounds-icons §2.4). Till it is in the app's icon is drawn and the view
 * told to draw again once it is, as a key image is. Its length is looked at before its file is read (a restored
 * backup's pack was never held to the import's checks); kept by path, the last [KEPT] read of every
 * keyboard's (main thread); [forget] lets them go with the painter's drawables.
 */
data class PackIcons(val files: Map<String, String>) {

    /** The file [name] is drawn from; null, the app's own icon of that name. */
    fun file(name: String): String? = files[name]

    /**
     * The icon read from [file]; null where it is gone, too long or no icon now (logged once: drawn as the app's then),
     * or not read yet: then read on the decode thread, [ready] told on the main thread once it is ([pending] till then).
     */
    fun outline(file: String, ready: () -> Unit = {}): Outline? {
        kept[file]?.let { return it.takeIf { it !== NONE } }
        val waiting = pending[file]
        if (waiting != null) {
            waiting += ready
            return null
        }
        pending[file] = mutableListOf(ready)
        val generation = forgotten
        ThemeAssets.runner {
            val read = read(file) ?: NONE
            ThemeAssets.poster {
                // read before a forget (a pack's files written over): neither kept nor waited on any more
                if (generation != forgotten) return@poster
                kept[file] = read
                pending.remove(file)?.forEach { it() }
            }
        }
        return kept[file]?.takeIf { it !== NONE }
    }

    /** Whether [file] is being read now: drawn as the app's icon this frame, again once it is in. */
    fun pending(file: String): Boolean = file in pending

    /** [icon] and its paths as Android draws them, in the SVG's own units ([SvgSubset.Icon.fit] places them). */
    class Outline(val icon: SvgSubset.Icon, val paths: List<Path>)

    companion object {
        val EMPTY = PackIcons(emptyMap())

        /** [theme]'s icons, the theme drawn in (SceneBoard.drawnTheme's). */
        fun of(theme: AppTheme): PackIcons = (theme as? AppTheme.Pack)?.drawn?.icons?.takeIf { it.isNotEmpty() }?.let(::PackIcons) ?: EMPTY

        private const val KEPT = 256
        private val kept = lruMap<String, Outline>(KEPT)
        private val pending = HashMap<String, MutableList<() -> Unit>>()
        private var forgotten = 0
        private val NONE = Outline(SvgSubset.Icon(1.0, 1.0, emptyList()), emptyList())

        /** The icons read let go: a pack's files written over in place ([SceneThemes.forgetAll]). */
        fun forget() {
            kept.clear()
            pending.clear()
            forgotten++
        }

        // [file] read as an icon, null where it is not (now)
        internal fun read(file: String): Outline? {
            val f = File(file)
            val length = f.length()
            if (!f.isFile || ThemeAssetLimits.icon(length) != null) {
                Timber.w("theme icon %s: %s; the app's drawn", file, if (f.isFile) "$length bytes" else "gone")
                return null
            }
            val text = runCatching { f.readText() }.getOrElse {
                Timber.w(it, "theme icon %s unread; the app's drawn", file)
                return null
            }
            return when (val read = SvgSubset.read(text)) {
                is Parsed.Ok -> Outline(read.value, read.value.paths.map { pathOf(it) })
                is Parsed.Failed -> null.also { Timber.w("theme icon %s: %s; the app's drawn", file, read.problems.firstOrNull()) }
            }
        }

        private fun pathOf(p: SvgSubset.Path): Path = Path().apply {
            fillType = if (p.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            for (s in p.segments) {
                when (s) {
                    is Segment.Move -> moveTo(s.x.toFloat(), s.y.toFloat())
                    is Segment.Line -> lineTo(s.x.toFloat(), s.y.toFloat())
                    is Segment.Quad -> quadTo(s.cx.toFloat(), s.cy.toFloat(), s.x.toFloat(), s.y.toFloat())
                    is Segment.Cubic -> cubicTo(s.c1x.toFloat(), s.c1y.toFloat(), s.c2x.toFloat(), s.c2y.toFloat(), s.x.toFloat(), s.y.toFloat())
                    Segment.Close -> close()
                }
            }
        }
    }
}
