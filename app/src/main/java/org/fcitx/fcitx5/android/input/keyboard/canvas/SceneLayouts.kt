/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.content.Context
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.R
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
}

/**
 * The layouts the app has: lib/keyboard-scene's commonMain/layouts, copied by the build as raw
 * resources (copySceneLayouts, a `-` made `_`), so Robolectric reads them too. A keyboard whose
 * layout is here is drawn by the scene where the canvas is on; one moved there (060-render-p2 S4,
 * S5) adds its line.
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
            "grouped-bottom" to R.raw.scene_layout_grouped_bottom,
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
 * (never its views again, U8); one whose layout the app has not, by its views still.
 */
object SceneLayouts {
    private sealed interface Read {
        class Ok(val layout: SceneLayout) : Read

        data object Unread : Read
    }

    // by the source's key and the layout's id
    private val read = HashMap<Pair<String, String>, Read>()

    /** Where the layouts come from first: the app's ([BuiltInLayouts]); a test's own. Set, those read are read again. */
    @VisibleForTesting
    var source: (Context) -> LayoutSource = ::BuiltInLayouts
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
        return readFrom(first, id) ?: readFrom(fallback(app), id)
    }

    /** The layouts read from the source of [key] (all of them where null) read again when next asked for: P3, a theme pack changed. */
    fun invalidate(key: String? = null) {
        if (key == null) read.clear() else read.keys.removeAll { it.first == key }
    }

    private fun readFrom(source: LayoutSource, id: String): SceneLayout? =
        when (val r = read.getOrPut(source.key to id) { load(source, id)?.let(Read::Ok) ?: Read.Unread }) {
            is Read.Ok -> r.layout
            Read.Unread -> null
        }

    private fun load(source: LayoutSource, id: String): SceneLayout? {
        val text = runCatching { source.text(id) }.onFailure { Timber.e(it, "scene layout %s unread", id) }.getOrNull() ?: return null
        val layout = when (val l = LayoutReader.layout(text)) {
            is Parsed.Ok -> l.value
            is Parsed.Failed -> return null.also { Timber.e("scene layout %s: %s", id, l.problems) }
        }
        val fragments = HashMap<String, Fragment>()
        for (src in layout.parts.filterIsInstance<Include>().map { it.src }.distinct()) {
            val t = runCatching { source.text(src) }.getOrNull() ?: return null.also { Timber.e("scene layout %s: no fragment %s", id, src) }
            when (val f = LayoutReader.fragment(t)) {
                is Parsed.Ok -> fragments[src] = f.value
                is Parsed.Failed -> return null.also { Timber.e("scene layout %s: fragment %s: %s", id, src, f.problems) }
            }
        }
        return SceneLayout(id, layout, fragments)
    }
}
