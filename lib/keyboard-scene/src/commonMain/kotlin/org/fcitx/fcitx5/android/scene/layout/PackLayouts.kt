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
    /** The function keys every keyboard of the app that has them must keep: 删除, 回车, 切换 (060-render-v2 §16). */
    private val ROLES = setOf("backspace", "enter", "lang")
    private val DIGIT = Regex("[0-9]")
    private const val BOM = "\uFEFF"

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
     * on kept (§4, §16): the slots by kind (the symbols' grid, the nine keys' side column, the handwriting pad), the
     * function keys by role ([ROLES]), the digit keys (the nine keys' 2–9, the number pad's). Refused where the app's
     * does not read.
     */
    fun judge(id: String, layout: Layout, fragments: Map<String, Fragment>, app: Layout?, appFragments: Map<String, Fragment>): String? {
        if (layout.id != id) return "its id is ${layout.id}"
        app ?: return "the app's $id does not read"
        if (layout.family != app.family) return "its family is ${layout.family}, the app's ${app.family}"
        val mine = cells(layout, fragments)
        val theirs = cells(app, appFragments)
        fun slots(c: List<Cell>) = c.filterIsInstance<Slot>().map { it.kind }.toSet()
        fun roles(c: List<Cell>) = c.filterIsInstance<Key>().mapNotNull { it.attrs.role }.filter { it in ROLES }.toSet()
        fun digits(c: List<Cell>) = c.filterIsInstance<Key>().map { it.id }.filter { DIGIT.matches(it) }.toSet()
        (slots(theirs) - slots(mine)).takeIf { it.isNotEmpty() }?.let { return "it has no slot $it" }
        (roles(theirs) - roles(mine)).takeIf { it.isNotEmpty() }?.let { return "it has no key $it" }
        (digits(theirs) - digits(mine)).takeIf { it.isNotEmpty() }?.let { return "it has no key $it" }
        return null
    }

    /**
     * The boards a pack is drawn on, as the app draws them (SceneLayouts.load): each of the app's [app], replaced by
     * the pack's file of its id in [own] (`<id>` to its text) only where that reads, every fragment it includes reads
     * (the pack's of [own] first, else [appText]'s) and [judge] takes it; else the app's, with the app's fragments.
     * What the blank-key check asks, the app's import and the theme maker's page alike (BlankKeys). [own] holds only the
     * files the app reads ([readable]): one it does not would be asked here and not drawn there.
     */
    fun drawn(app: List<BlankKeys.Board>, own: Map<String, String>, appText: (String) -> String?): List<BlankKeys.Board> =
        app.map { a -> own[a.layout.id]?.let { packBoard(a, it, own, appText) } ?: a }

    private fun packBoard(app: BlankKeys.Board, text: String, own: Map<String, String>, appText: (String) -> String?): BlankKeys.Board? {
        val layout = (LayoutReader.layout(text) as? Parsed.Ok)?.value ?: return null
        val fragments = HashMap<String, Fragment>()
        for (src in layout.parts.filterIsInstance<Include>().map { it.src }.distinct()) {
            val t = own[src] ?: appText(src) ?: return null
            fragments[src] = (LayoutReader.fragment(t) as? Parsed.Ok)?.value ?: return null
        }
        return BlankKeys.Board(layout, fragments).takeIf { judge(app.layout.id, layout, fragments, app.layout, app.fragments) == null }
    }

    // every cell of [layout], its includes' rows in place
    private fun cells(layout: Layout, fragments: Map<String, Fragment>): List<Cell> = layout.parts.flatMap { part ->
        when (part) {
            is Row -> part.cells
            is Include -> fragments[part.src]?.rows.orEmpty().flatMap { it.cells }
        }
    }
}
