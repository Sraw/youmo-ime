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
 * Where the keys fell back to the built-in theme, none: the app's own icons are drawn. An icon is read when
 * first drawn on [ThemeAssets]' decode thread, not the main one (the largest took hundreds of ms, far past a
 * frame); till then the app's is drawn and the view told to draw again. Its length is looked at before its
 * file is read: a restored backup's pack was never held to the import's checks.
 */
data class PackIcons(val files: Map<String, String>) {

    /** Null: the app's own icon of that name. */
    fun file(name: String): String? = files[name]

    /** Null where gone, too long or no icon (logged once), or not read yet: [ready] told on the main thread once it is. */
    fun outline(file: String, ready: () -> Unit = {}): Outline? {
        kept[file]?.let { return it.takeIf { it !== NONE } }
        val waiting = waiters[file]
        if (waiting != null) {
            waiting += ready
            return null
        }
        waiters[file] = mutableListOf(ready)
        val generation = forgotten
        ThemeAssets.runner {
            // anything a read throws is no icon: left waiting, every frame would miss
            val read = runCatching { read(file) }.onFailure { Timber.w(it, "theme icon %s unread; the app's drawn", file) }.getOrNull() ?: NONE
            ThemeAssets.poster {
                // read before a forget (a pack's files written over): neither kept nor waited on any more
                if (generation != forgotten) return@poster
                kept[file] = read
                waiters.remove(file)?.forEach { it() }
            }
        }
        // in already where the runner and poster ran at once (tests)
        return kept[file]?.takeIf { it !== NONE }
    }

    fun pending(file: String): Boolean = file in waiters

    /** In the SVG's own units ([SvgSubset.Icon.fit] places them). */
    class Outline(val icon: SvgSubset.Icon, val paths: List<Path>)

    companion object {
        val EMPTY = PackIcons(emptyMap())

        fun of(theme: AppTheme): PackIcons = (theme as? AppTheme.Pack)?.drawn?.icons?.takeIf { it.isNotEmpty() }?.let(::PackIcons) ?: EMPTY

        private const val KEPT = 256
        private val kept = lruMap<String, Outline>(KEPT)
        private val waiters = HashMap<String, MutableList<() -> Unit>>()
        private var forgotten = 0
        private val NONE = Outline(SvgSubset.Icon(1.0, 1.0, emptyList()), emptyList())

        /** The views waiting on a read are told to draw again, as they would have been once it was in. */
        fun forget() {
            kept.clear()
            val waiting = waiters.values.flatten()
            waiters.clear()
            forgotten++
            waiting.forEach { it() }
        }

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
