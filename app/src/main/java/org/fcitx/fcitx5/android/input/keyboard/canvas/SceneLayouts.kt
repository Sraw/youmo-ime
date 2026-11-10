/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.content.Context
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemePackLayouts
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Include
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import timber.log.Timber

/**
 * Where a keyboard's layout is read from, by its id (`qwerty`, `k14`, a fragment's `grouped-bottom`):
 * the text of layout-1's XML, null for none. Never the files themselves (060-render-v2 §15): the app's
 * own now ([BuiltInLayouts]); P3 a theme pack's `layouts/<id>.xml` first and the app's under it,
 * keyboard by keyboard. Ids are kept as they are: a theme's styles name them.
 */
interface LayoutSource {
    fun text(id: String): String?

    /**
     * What the layouts read from it are kept under, with their ids ([SceneLayouts.invalidate]): each
     * source its own, the same each time it is made. The app's: [BuiltInLayouts.KEY]; P3, a theme
     * pack's by the pack's id, so a pack changed is read again by its key and no other's.
     */
    val key: String

    /**
     * Why [layout], read as [id] from this source, is not taken (the app's drawn instead, logged once); null where it
     * is: a theme pack's held to what the app's of [id] must have to be typed on ([ThemePackLayouts.judge]).
     */
    fun refuses(id: String, layout: Layout, fragments: Map<String, Fragment>): String? = null
}

/**
 * The layouts the app has: lib/keyboard-scene's commonMain/layouts, copied by the build as raw
 * resources (copySceneLayouts, a `-` made `_`), so Robolectric reads them too: each board's, which
 * the scene draws; a new one adds its line.
 */
class BuiltInLayouts(private val context: Context) : LayoutSource {
    override fun text(id: String): String? =
        RAW[id]?.let { res -> context.resources.openRawResource(res).use { it.readBytes().decodeToString() } }

    override val key: String get() = KEY

    companion object {
        const val KEY = "built-in"

        /** Whether the keyboard of the layout [id] is moved to the scene: the app has its layout (060-render-p2 S4). */
        fun has(id: String): Boolean = id in RAW

        // named, not looked up by name (Resources.getIdentifier): the build keeps what is used
        val RAW: Map<String, Int> = mapOf(
            "qwerty" to R.raw.scene_layout_qwerty,
            "k14" to R.raw.scene_layout_k14,
            "k18" to R.raw.scene_layout_k18,
            "zhuyin" to R.raw.scene_layout_zhuyin,
            "zhuyin-nine" to R.raw.scene_layout_zhuyin_nine,
            "grouped-bottom" to R.raw.scene_layout_grouped_bottom,
            "number" to R.raw.scene_layout_number,
            "nine" to R.raw.scene_layout_nine,
            "handwriting" to R.raw.scene_layout_handwriting,
            "symbols" to R.raw.scene_layout_symbols,
            "emoji" to R.raw.scene_layout_emoji,
            "emoticon" to R.raw.scene_layout_emoticon,
            "emoji-search" to R.raw.scene_layout_emoji_search,
            "expanded-side" to R.raw.scene_layout_expanded_side,
            "number-row" to R.raw.scene_layout_number_row,
        )
    }
}

/** A keyboard's layout and the fragments it includes (a shared bottom row), by their ids: what [SceneBuilder][org.fcitx.fcitx5.android.scene.engine.SceneBuilder.build] takes. */
class SceneLayout(val id: String, val layout: Layout, val fragments: Map<String, Fragment>)

/**
 * The keyboards' layouts, each read once a process from each source (main thread), one that did not
 * read too (logged once): [source]'s, and where it has none or it does not read, the app's own
 * ([BuiltInLayouts]; P3, a theme pack's first). A keyboard whose layout the app has is drawn by the
 * canvas where it is on (060-render-p2 S4): one whose layout does not read, nothing drawn, logged
 * (never its views again, U8); one whose layout the app has not, nothing (logged).
 */
object SceneLayouts {
    private sealed interface Read {
        class Ok(val layout: SceneLayout) : Read

        data object Unread : Read
    }

    // by the source's key and the layout's id
    private val read = HashMap<Pair<String, String>, Read>()

    // the key of the source [of] read from last
    private var current: String? = null

    /**
     * Where the layouts come from first: the theme pack in use's, else the app's ([ThemePackLayouts.current]: asked each
     * time, so a theme changed is followed, the input view made again); a test's own. Set, those read are read again.
     */
    @VisibleForTesting
    var source: (Context) -> LayoutSource = ThemePackLayouts::current
        set(value) {
            field = value
            read.clear()
        }

    /** Where a layout [source] has none of, or one that does not read, comes from: the app's own; a test's. */
    @VisibleForTesting
    var fallback: (Context) -> LayoutSource = ::BuiltInLayouts
        set(value) {
            field = value
            read.clear()
        }

    /** The layout [id], its fragments with it: [source]'s, else [fallback]'s (the one step back; a source of the same key not read again); null where neither reads. */
    fun of(context: Context, id: String): SceneLayout? {
        val app = context.applicationContext ?: context
        val first = source(app)
        val back = fallback(app)
        // a pack left (re-imported, another in use): what was read of it dropped
        if (first.key != current) {
            current = first.key
            read.keys.removeAll { it.first != first.key && it.first != back.key }
        }
        return readFrom(first, id, back) ?: readFrom(back, id, back)
    }

    /**
     * The layout [id] as the app has it ([fallback]'s), whatever [source] has: what a keyboard that cannot
     * take its source's layout draws instead, the block gone by whole (a grouping it cannot type, 060-render-p3 T9b).
     */
    fun builtIn(context: Context, id: String): SceneLayout? = fallback(context.applicationContext ?: context).let { readFrom(it, id, it) }

    /** The app's layout [id] read anew, the cache (main thread only) left alone: for checks off the main thread (a pack's import). */
    fun parseBuiltIn(context: Context, id: String): SceneLayout? = fallback(context.applicationContext ?: context).let { load(it, id, it) }

    /** How many layouts are kept read (tests). */
    internal val cached: Int get() = read.size

    /** The layouts read from the source of [key] (all of them where null) read again when next asked for: P3, a theme pack changed. */
    fun invalidate(key: String? = null) {
        if (key == null) read.clear() else read.keys.removeAll { it.first == key }
    }

    private fun readFrom(source: LayoutSource, id: String, back: LayoutSource): SceneLayout? =
        when (val r = read.getOrPut(source.key to id) { load(source, id, back)?.let(Read::Ok) ?: Read.Unread }) {
            is Read.Ok -> r.layout
            Read.Unread -> null
        }

    // a fragment the source has not, [back]'s (a pack's k18 including the app's grouped-bottom)
    private fun load(source: LayoutSource, id: String, back: LayoutSource): SceneLayout? {
        val text = runCatching { source.text(id) }.onFailure { Timber.e(it, "scene layout %s unread", id) }.getOrNull() ?: return null
        val layout = when (val l = LayoutReader.layout(text)) {
            is Parsed.Ok -> l.value
            is Parsed.Failed -> return null.also { Timber.e("scene layout %s: %s", id, l.problems) }
        }
        val fragments = HashMap<String, Fragment>()
        for (src in layout.parts.filterIsInstance<Include>().map { it.src }.distinct()) {
            val t = runCatching { source.text(src) ?: back.takeIf { it.key != source.key }?.text(src) }
                .getOrElse { return null.also { _ -> Timber.e(it, "scene layout %s: fragment %s unread", id, src) } }
                ?: return null.also { Timber.e("scene layout %s: no fragment %s", id, src) }
            when (val f = LayoutReader.fragment(t)) {
                is Parsed.Ok -> fragments[src] = f.value
                is Parsed.Failed -> return null.also { Timber.e("scene layout %s: fragment %s: %s", id, src, f.problems) }
            }
        }
        source.refuses(id, layout, fragments)?.let { why ->
            return null.also { Timber.e("scene layout %s of %s not taken: %s", id, source.key, why) }
        }
        return SceneLayout(id, layout, fragments)
    }
}
