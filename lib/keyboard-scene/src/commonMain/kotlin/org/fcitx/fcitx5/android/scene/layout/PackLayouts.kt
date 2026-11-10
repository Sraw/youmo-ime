/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

/**
 * Whether a theme pack's layout may stand for the app's keyboard of its id. It lives here, not in the app, so the
 * theme maker's page previews a pack's keyboard only where the app would draw it (060-render-p3 M4).
 */
object PackLayouts {
    /** The function keys every keyboard of the app that has them must keep: 删除, 回车, 切换 (060-render-v2 §16). */
    private val ROLES = setOf("backspace", "enter", "lang")
    private val DIGIT = Regex("[0-9]")

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
        fun slots(c: List<Any>) = c.filterIsInstance<Slot>().map { it.kind }.toSet()
        fun roles(c: List<Any>) = c.filterIsInstance<Key>().mapNotNull { it.attrs.role }.filter { it in ROLES }.toSet()
        fun digits(c: List<Any>) = c.filterIsInstance<Key>().map { it.id }.filter { DIGIT.matches(it) }.toSet()
        (slots(theirs) - slots(mine)).takeIf { it.isNotEmpty() }?.let { return "it has no slot $it" }
        (roles(theirs) - roles(mine)).takeIf { it.isNotEmpty() }?.let { return "it has no key $it" }
        (digits(theirs) - digits(mine)).takeIf { it.isNotEmpty() }?.let { return "it has no key $it" }
        return null
    }

    // every cell of [layout], its includes' rows in place
    private fun cells(layout: Layout, fragments: Map<String, Fragment>): List<Any> = layout.parts.flatMap { part ->
        when (part) {
            is Row -> part.cells
            is Include -> fragments[part.src]?.rows.orEmpty().flatMap { it.cells }
        }
    }
}
