/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.theme.BlankKeys

/**
 * Whether a theme pack's layout may stand for the app's keyboard of its id. It lives here, not in the app, so the
 * theme maker's page previews a pack's keyboard only where the app would draw it (060-render-p3 M4).
 */
object PackLayouts {
    private const val BOM = "\uFEFF"

    /** The app's fragments boards include (`<fragment>`): a pack's own of the id is read for its boards. */
    val FRAGMENTS = setOf("grouped-bottom")

    /**
     * Whether a pack's layout file [id] (`k18`, `grouped-bottom`) is read at all: one of the app's boards it would stand
     * for, or a fragment those include. One rule for the app's import and drawing (ThemePackLayouts.read), [readable]
     * and the theme maker's page (061-custom-layout §11): a board the app has no more (手写, 061-handwriting-toolbar)
     * leaves its pack's file unread and uncounted.
     */
    fun replaces(id: String): Boolean = id in BuiltInKeyData.BOARDS || id in FRAGMENTS

    /** The most bytes of a pack's layout file the app reads (ThemeAssetLimits.MAX_LAYOUT_BYTES): a bigger one is not read. */
    const val MAX_BYTES = 64L * 1024

    /**
     * Of a pack's layout [files] (`<id>` to its text), those the app reads (ThemePackLayouts.read): an id the app has
     * ([has]: a board's or a fragment's), within [MAX_BYTES] as UTF-8, its byte-order mark gone. A file of another
     * name, or bigger, is not the pack's: the app's own is drawn in its place.
     */
    fun readable(files: Map<String, String>, has: (String) -> Boolean): Map<String, String> =
        files.filter { (id, text) -> has(id) && text.encodeToByteArray().size <= MAX_BYTES }.mapValues { it.value.removePrefix(BOM) }

    /**
     * Why the pack's [layout] (its [fragments] with it) may not stand for the app's keyboard [id] ([app], its
     * [appFragments]), null where it may: its id and family the app's, and everything the app's must have to be typed
     * on kept ([LayoutCheck.kept], which a layout of its own is held to its base by). Refused where the app's does not read.
     */
    fun judge(id: String, layout: Layout, fragments: Map<String, Fragment>, app: Layout?, appFragments: Map<String, Fragment>): String? {
        if (layout.id != id) return "its id is ${layout.id}"
        app ?: return "the app's $id does not read"
        if (layout.family != app.family) return "its family is ${layout.family}, the app's ${app.family}"
        return LayoutCheck.kept(layout, fragments, app, appFragments)
    }

    /**
     * The boards a pack is drawn on, as the app draws them (SceneLayouts.load): each of the app's [app], replaced by
     * the pack's file of its id in [own] (`<id>` to its text) only where that reads, every fragment it includes reads
     * (the pack's of [own] first, else [appText]'s) and [judge] takes it; else the app's, with the app's fragments.
     * What the blank-key check asks, the app's import and the theme maker's page alike (BlankKeys). [own] holds only the
     * files the app reads ([readable]): one it does not would be asked here and not drawn there.
     */
    fun drawn(app: List<BlankKeys.Board>, own: Map<String, String>, appText: (String) -> String?): List<BlankKeys.Board> =
        app.map { a -> own[a.id]?.let { packBoard(a, it, own, appText) } ?: a }

    private fun packBoard(app: BlankKeys.Board, text: String, own: Map<String, String>, appText: (String) -> String?): BlankKeys.Board? {
        val layout = (LayoutReader.layout(text) as? Parsed.Ok)?.value ?: return null
        val fragments = HashMap<String, Fragment>()
        for (src in layout.parts.filterIsInstance<Include>().map { it.src }.distinct()) {
            val t = own[src] ?: appText(src) ?: return null
            fragments[src] = (LayoutReader.fragment(t) as? Parsed.Ok)?.value ?: return null
        }
        return BlankKeys.Board(layout, fragments, app.id).takeIf { judge(app.id, layout, fragments, app.layout, app.fragments) == null }
    }
}
