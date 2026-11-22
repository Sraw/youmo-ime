/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.theme

import org.fcitx.fcitx5.android.scene.Problems
import org.fcitx.fcitx5.android.scene.xml.Attributes
import org.fcitx.fcitx5.android.scene.xml.XmlElement

/** What 0.6.3 added to a theme: which theme uses it, so it says `min-app`, and the reader's checks of it. */
object ThemeFeatures {
    const val MIN_APP_063 = "0.6.3"

    /** A raster icon, `content`, a sticker or a dark version's picture. */
    fun since063(theme: Theme): Boolean =
        theme.icons.values.any(RasterIcon::named) ||
            theme.darkPicture != null ||
            theme.styles.any { s -> "content" in s.values || (s.values["sticker"] as? Value.Word)?.word.let { it != null && it != Values.NONE } }

    // 0.6.0–0.6.2 read any file name as a reserved sticker and ignored it: such a pack still imports
    internal fun oldSticker(values: MutableMap<String, Value>, line: Int, p: Problems): Int {
        val word = (values["sticker"] as? Value.Word)?.word?.takeIf { it != Values.NONE && !RasterIcon.named(it) } ?: return 0
        values.remove("sticker")
        p.warn(line, "sticker=\"$word\" is no PNG or WebP: ignored")
        return 1
    }

    internal fun stickers(styles: List<Style>, p: Problems) {
        val files = LinkedHashMap<String, Int>()
        for (s in styles) (s.values["sticker"] as? Value.Word)?.word?.takeIf { it != Values.NONE }?.let { files.getOrPut(it) { s.line } }
        if (files.size > RasterIcon.MAX_STICKERS) p.add(files.values.elementAt(RasterIcon.MAX_STICKERS), "over ${RasterIcon.MAX_STICKERS} sticker files")
    }

    // a key's whole face has a sticker, an icon part a content; elsewhere neither is drawn
    internal fun keyOnly(selectors: List<Selector>, props: Set<String>, line: Int, p: Problems) {
        if ("sticker" in props) {
            selectors.map { it.widget }.filter { it != "key" && !ThemeReader.readByTable(it) }.distinct()
                .forEach { p.warn(line, "sticker= on $it does nothing: only a key has one") }
            selectors.filter { it.widget == "key" }.mapNotNull { it.part }.distinct()
                .forEach { p.warn(line, "sticker= on a key's $it does nothing: it is the key's, over its face") }
        }
        if ("content" in props) {
            selectors.map { it.widget }.filter { it != "key" && !ThemeReader.readByTable(it) }.distinct()
                .forEach { p.warn(line, "content= on $it does nothing: only a key's icon is changed") }
            if (selectors.any { it.widget == "key" && "letter" in it.classes }) p.warn(line, "content= on a .letter key does nothing: it has no icon")
        }
    }

    // read but not drawn by an app older than 0.6.3, which refuses only a min-app= it is under
    internal fun minApp(theme: Theme, meta: Meta?, root: XmlElement, p: Problems) {
        if (!since063(theme)) return
        val min = meta?.minApp
        if (min == null || ThemeReader.newer(MIN_APP_063, min)) {
            p.warn(root.all("meta").firstOrNull()?.line ?: root.line, "the theme uses what ${MIN_APP_063} added, which an older app refuses or draws wrong: write min-app=\"${MIN_APP_063}\"")
        }
    }

    internal fun pictures(root: XmlElement, variants: Set<Boolean>, p: Problems, picture: (XmlElement) -> Picture?): Pair<Picture?, Picture?> {
        val (darkEls, lightEls) = root.all("picture").partition { it["variant"] == "dark" }
        if (lightEls.size > 1) p.add(lightEls[1].line, "<picture> given twice")
        if (darkEls.size > 1) p.add(darkEls[1].line, "<picture variant=\"dark\"> given twice")
        if (darkEls.isNotEmpty() && lightEls.isEmpty()) p.add(darkEls[0].line, "<picture variant=\"dark\"> needs a <picture> without variant=")
        if (darkEls.isNotEmpty() && true !in variants) p.warn(darkEls[0].line, "<picture variant=\"dark\"> in a theme with no dark version is not drawn")
        return lightEls.firstOrNull()?.let(picture) to darkEls.firstOrNull()?.let(picture)
    }

    // a PNG or WebP is drawn as it is unless tint= says it is a mask
    internal fun icon(a: Attributes, src: String?) {
        val tint = a.oneOf("tint", listOf("true", "false"))
        if (tint != null && src != null && !RasterIcon.named(src)) a.problem("tint= is a PNG's or WebP's: an SVG is drawn in icon-color")
    }
}
