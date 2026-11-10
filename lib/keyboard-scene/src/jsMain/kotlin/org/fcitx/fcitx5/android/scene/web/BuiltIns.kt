/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.web

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Problem
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Include
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.xml.Xml

/**
 * The app's own layouts and themes in the library, by their files' names (`qwerty`, `grouped-bottom`; the
 * themes T3 writes, `material`…): [layoutFiles] and [themeFiles] are the files' texts (BuiltInFiles, which
 * the build writes of src/commonMain's layouts and themes), read when asked for. A layout file is a board
 * (`<keyboard>`) or a fragment boards include (`<fragment>`); only boards are offered.
 */
internal class BuiltIns(private val layoutFiles: Map<String, String>, private val themeFiles: Map<String, String>) {
    private val roots: Map<String, String?> by lazy { layoutFiles.mapValues { (_, text) -> Xml.parse(text).orNull()?.name } }

    /** The boards, by id, sorted. */
    val layouts: List<String> by lazy { roots.filterValues { it == "keyboard" }.keys.sorted() }

    /** The fragments boards include, by id. */
    val fragments: Map<String, Fragment> by lazy {
        roots.filterValues { it == "fragment" }.keys.mapNotNull { id -> LayoutReader.fragment(layoutFiles.getValue(id)).orNull()?.let { id to it } }.toMap()
    }

    /** The themes, by name, sorted. */
    val themes: List<String> get() = themeFiles.keys.sorted()

    fun layoutText(id: String): String? = layoutFiles[id]

    fun themeText(id: String): String? = themeFiles[id]

    private val read = HashMap<String, Parsed<Layout>>()

    /** The board [id] read (once), or its problems; a fragment's id or none, null. */
    fun layout(id: String): Parsed<Layout>? = if (id !in layouts) null else read.getOrPut(id) { LayoutReader.layout(layoutFiles.getValue(id)) }

    /**
     * The fragments [layout] includes, [own] first (a theme's layouts beside it, P3 T8b), then these; a problem for
     * one neither has, the include's line: a board drawn without its bottom row is not the board.
     */
    fun includes(layout: Layout, own: Map<String, Fragment> = emptyMap()): Parsed<Map<String, Fragment>> {
        val wanted = layout.parts.filterIsInstance<Include>()
        val missing = wanted.filter { it.src !in own && it.src !in fragments }
        if (missing.isNotEmpty()) return Parsed.Failed(missing.map { Problem(it.line, "no fragment \"${it.src}\" to include") })
        return Parsed.Ok(wanted.associate { it.src to (own[it.src] ?: fragments.getValue(it.src)) })
    }
}
