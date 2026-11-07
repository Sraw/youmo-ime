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
fun interface LayoutSource {
    fun text(id: String): String?
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

    companion object {
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
 * The keyboards' layouts read from [source], each once a process (main thread): one that did not
 * read, too (null, logged once). A keyboard whose layout this has ([of] not null) is drawn by the
 * canvas where it is on (060-render-p2 S4); one with none still by its views.
 */
object SceneLayouts {
    private val read = HashMap<String, SceneLayout?>()

    /** Where the layouts come from: the app's ([BuiltInLayouts]); a test's own. Set, those read are read again. */
    @VisibleForTesting
    var source: (Context) -> LayoutSource = ::BuiltInLayouts
        set(value) {
            field = value
            read.clear()
        }

    /** The layout [id], its fragments with it; null where there is none or it did not read. */
    fun of(context: Context, id: String): SceneLayout? {
        // getOrPut would read one that did not read again each time (its null is no entry to it)
        if (id in read) return read[id]
        return load(source(context.applicationContext ?: context), id).also { read[id] = it }
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
