/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.theme.BlankKeys
import org.fcitx.fcitx5.android.scene.theme.Meta
import org.fcitx.fcitx5.android.scene.theme.ThemeReader

/**
 * Whether a theme pack's layout may stand for the app's keyboard of its id. It lives here, not in the app, so the
 * theme maker's page previews a pack's keyboard only where the app would draw it (060-render-p3 M4).
 */
object PackLayouts {
    private const val BOM = "\uFEFF"

    /** The app's fragments boards include (`<fragment>`): a pack's own of the id is read for its boards. */
    val FRAGMENTS = setOf("grouped-bottom")

    /**
     * Whether a pack's layout file [id] (`zhuyin`, `grouped-bottom`) is read at all: one of the app's boards it would
     * stand for, or a fragment those include. One rule for the app's import and drawing (ThemePackLayouts.read),
     * [readable] and the theme maker's page (061-custom-layout §11): a board the app has no more (手写,
     * 061-handwriting-toolbar) leaves its pack's file unread and uncounted, and since 0.6.1 a board the user picks among
     * their own layouts ([KeyData.LISTED]: 26 键, 14/18 键, 九键, 数字, 符号) is not stood for: the pack's file of it is
     * one of the user's layouts once imported ([convert]). The pack's `grouped-bottom` is then read for 注音's boards alone.
     */
    fun replaces(id: String): Boolean = (id in BuiltInKeyData.BOARDS && id !in KeyData.LISTED) || id in FRAGMENTS

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

    /** What [convert] makes of a pack's layout. */
    sealed interface Converted {
        /**
         * The layout of the user's it makes: its [id] and [name], its [text] (LayoutWriter's), the names it gave keys
         * ([renames]: the name read to the new one, KeyId's), what the loose check tells of it ([problems]); [own], the
         * pack's layout's own name as shown (its id where it has none): what the app's notices call it beside the theme's;
         * [commits], the `commit:` texts its keys type that its base's do not (§13: the import's confirmation counts them).
         */
        class Done(
            val id: String,
            val name: String,
            val text: String,
            val renames: Map<String, String>,
            val problems: List<LayoutCheck.Finding>,
            val own: String = name,
            val commits: List<String> = emptyList(),
        ) : Converted

        /** Not made one: [why] (not of the boards listed, does not read, not as typable as the app's). */
        class Not(val why: String) : Converted
    }

    /**
     * A theme pack's layout file of an id the user picks among their own layouts (`k18`, [KeyData.LISTED]) made one of
     * those (061-custom-layout §2.3, §11): what the app's import, its upgrade, its restore of a backup and the layout
     * maker's opening of a `.theme` share, alike on the JVM and in JS. Taken as 0.6.0 took it (it reads, its includes
     * read, [judge] against the app's [id] in [sources], its `min-app` not past [appVersion]), else [Converted.Not]; then:
     * - its id [newId], by default [idOf] (the same pack imported again gives the same: an update; the app passes another
     *   where that one is not this pack's to replace), `based-on` [id], its name 「主题名 · 原名」 within
     *   [LayoutCheck.MAX_NAME], `<meta>` the theme's author and license ([meta]);
     * - a number board without `pads=` (0.6.0's stood for every pad) written for each pad it serves (none it lacks a key of
     *   a field of the pad types; none: not made), one with `pads=` not made where one of them lacks one (as L5's restore);
     * - the pack's own fragments ([packFragments], `grouped-bottom` to its text) written into it in place of their
     *   `<include>` (a layout of the user's includes the app's alone); one the pack has not stays an include of the app's;
     * - a key's name §3.2 第 7 条 refuses (`fn/x` the app has not, `g18/x` the app has not) `<new id>/<key id>`, each name
     *   read renamed once (two keys sharing one still share it), returned for the caller to copy the settings under it;
     *   one a `<defaults>` gives (the layout's, a row's, the pack's fragment's) alike (`<new id>/<row id>`, `…/keys`).
     * Its [Converted.Done.problems]: [LayoutCheck.check]'s loose one ([appVersion]) of the text made, its errors refusing it.
     */
    fun convert(
        theme: String,
        meta: Meta?,
        id: String,
        text: String,
        packFragments: Map<String, String>,
        sources: LayoutCheck.Sources,
        appVersion: String,
        newId: String = idOf(theme, id),
    ): Converted {
        if (id !in KeyData.LISTED) return Converted.Not("not a keyboard listed")
        if (text.encodeToByteArray().size > MAX_BYTES) return Converted.Not("too big")
        val layout = (LayoutReader.layout(text.removePrefix(BOM)) as? Parsed.Ok)?.value ?: return Converted.Not("does not read")
        layout.meta?.minApp?.let { if (ThemeReader.newer(it, appVersion)) return Converted.Not("for a newer app ($it)") }
        val own = HashMap<String, Fragment>()
        val fragments = HashMap<String, Fragment>()
        for (src in layout.parts.filterIsInstance<Include>().map { it.src }.distinct()) {
            val packs = packFragments[src]?.takeIf { src in FRAGMENTS }
            val f = (if (packs != null) LayoutReader.fragment(packs.removePrefix(BOM)) as? Parsed.Ok else null)?.value
            if (packs != null && f == null) return Converted.Not("its fragment $src does not read")
            if (f != null) own[src] = f
            fragments[src] = f ?: sources.fragment(src) ?: return Converted.Not("its fragment $src does not read")
        }
        val app = sources.layout(id)
        val appFragments = app?.parts?.filterIsInstance<Include>()?.mapNotNull { inc -> sources.fragment(inc.src)?.let { inc.src to it } }?.toMap().orEmpty()
        judge(id, layout, fragments, app, appFragments)?.let { return Converted.Not(it) }
        val (defaults, parts, renames) = renameKeys(layout.defaults, convertRows(layout.parts, own), newId, sources)
        val name = name(theme, layout.name, id)
        fun made(pads: List<String>?): Made {
            val converted = Layout(
                newId, name, layout.family, layout.columns, layout.onSplit, defaults, parts, layout.preview, layout.popups,
                basedOn = id,
                meta = LayoutMeta(shown(meta?.author), null, shown(meta?.license), null, null).takeIf { it.author != null || it.license != null },
                pads = pads,
            )
            val out = LayoutWriter.write(converted)
            return Made(out, LayoutCheck.check(out, sources, appVersion, strict = false))
        }
        val (out, report) = forPads(layout.family, layout.pads, ::made).let { it.made ?: return Converted.Not(it.why!!) }
        report.errors.firstOrNull()?.let { return Converted.Not("made one that does not take: $it") }
        return Converted.Done(newId, name, out, renames, report.warnings, ownName(layout.name, id), report.commits)
    }

    private data class Made(val text: String, val report: LayoutCheck.Report)

    private class ForPads(val made: Made?, val why: String?)

    // a board of [family] made ([made]); a number board's for the pads it serves (S3): [declared], not where one lacks a
    // key (L5's restore alike, N1); none declared (0.6.0's stood for each), each of LayoutReader.PADS but those it lacks
    private fun forPads(family: String, declared: List<String>?, made: (List<String>?) -> Made): ForPads {
        if (family != NUMBER) return ForPads(made(declared), null)
        val all = made(declared ?: LayoutReader.PADS)
        val lacking = padsLacking(all.report)
        return when {
            lacking.isEmpty() -> ForPads(all, null)
            declared != null -> ForPads(null, "its pads lack keys: ${lacking.joinToString(" ")}")
            else -> LayoutReader.PADS.filter { it !in lacking }.takeIf { it.isNotEmpty() }
                ?.let { ForPads(made(it), null) } ?: ForPads(null, "it serves no pad")
        }
    }

    /** The id [convert] makes of a pack [theme]'s board [id] by default: `t<8 hex of the name>-<id>`. */
    fun idOf(theme: String, id: String): String = "t" + hash(theme) + "-" + id

    private const val NUMBER = "number"

    // the pads a number board's [report] says it lacks a key of (told where not strict)
    private fun padsLacking(report: LayoutCheck.Report): List<String> =
        (report.errors + report.warnings).mapNotNull(LayoutCheck::padLacking).distinct()

    /**
     * [defaults] and [parts] with each key's name §3.2 第 7 条 refuses (and its variants', a `<defaults>`') `<newId>/<key
     * id>` (`-2`… where taken; a row's `<defaults>` `<newId>/<row id>`, else `row<n>`; the layout's `<newId>/keys`), each
     * name renamed once; the renames, the name read to the new one.
     */
    private fun renameKeys(defaults: KeyAttrs, parts: List<Part>, newId: String, sources: LayoutCheck.Sources): Renamed {
        val renames = LinkedHashMap<String, String>()
        val named = HashSet<String>()
        val rows = parts.filterIsInstance<Row>()
        rows.flatMap { it.cells }.filterIsInstance<Key>()
            .forEach { k -> (listOf(k.attrs) + k.variants.map { it.attrs }).mapNotNullTo(named) { it.shortcut } }
        (listOf(defaults) + rows.map { it.defaults }).mapNotNullTo(named) { it.shortcut }
        fun renamed(base: String, name: String?): String? {
            if (name == null || LayoutCheck.nameProblem(name, sources) == null) return name
            return renames.getOrPut(name) {
                val first = "$newId/$base"
                generateSequence(2) { it + 1 }.map { n -> "$newId/${base.take(MAX_KEY_ID - 1 - n.toString().length)}-$n" }
                    .let { sequenceOf(first) + it }.first { it !in named }.also { named += it }
            }
        }
        var row = 0
        val out = parts.map { part ->
            if (part !is Row) return@map part
            row++
            val rowDefaults = part.defaults.copy(shortcut = renamed(part.id ?: "row$row", part.defaults.shortcut))
            Row(part.id, part.indent, part.endIndent, part.height, part.condition, part.split, rowDefaults, part.cells, part.line).withCells(
                part.cells.map { c ->
                    if (c !is Key) return@map c
                    Key(
                        c.id, c.condition, c.attrs.copy(shortcut = renamed(c.id, c.attrs.shortcut)), c.popup, c.menu, c.line,
                        c.variants.map { v -> Variant(v.condition, v.attrs.copy(shortcut = renamed(c.id, v.attrs.shortcut)), v.line) },
                        c.letters, c.lean, c.place,
                    )
                },
            )
        }
        return Renamed(defaults.copy(shortcut = renamed(LAYOUT_DEFAULTS, defaults.shortcut)), out, renames)
    }

    private data class Renamed(val defaults: KeyAttrs, val parts: List<Part>, val renames: Map<String, String>)

    // what the name a layout's own <defaults> gives is renamed under
    private const val LAYOUT_DEFAULTS = "keys"

    /**
     * [parts] with each include of a fragment of [own] (the pack's) in its rows where it was, the fragment's `<defaults>`
     * and the include's `when=` on each as Expand lays them; an include of another (the app's) as it is.
     */
    internal fun convertRows(parts: List<Part>, own: Map<String, Fragment>): List<Part> = parts.flatMap { part ->
        val f = (part as? Include)?.let { own[it.src] }
        f?.rows?.map { r -> Row(r.id, r.indent, r.endIndent, r.height, both(part.condition, r.condition), r.split, r.defaults.over(f.defaults), r.cells, r.line) }
            ?: listOf(part)
    }

    // a key id's most characters (LayoutReader): a name's part after its /
    private const val MAX_KEY_ID = 32

    // a meta text of the theme's as the layout's: nothing unseen, at most a theme's (the same limit)
    private const val MAX_META = 64

    private fun shown(text: String?): String? = text?.let(LayoutCheck::plain)?.trim()?.let { clip(it, MAX_META) }?.takeIf { it.isNotBlank() }

    // 「主题名 · 原名」 within the most a name may be: the theme's name shortened first, the layout's alone where no room
    private fun name(theme: String, own: String, id: String): String {
        val mine = ownName(own, id)
        val themes = LayoutCheck.plain(theme).trim()
        val room = LayoutCheck.MAX_NAME - SEPARATOR.length - mine.length
        return if (themes.isEmpty() || room < 1) clip(mine, LayoutCheck.MAX_NAME) else clip(themes, room).trim() + SEPARATOR + mine
    }

    // a layout's own name as shown: nothing unseen, its id where nothing is left
    private fun ownName(own: String, id: String): String = LayoutCheck.plain(own).trim().ifEmpty { id }

    private const val SEPARATOR = " · "

    // at most [max] UTF-16 units, a surrogate pair not cut in two
    private fun clip(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.take(max)
        return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }

    // both conditions holding (each a set of alternatives: every pair of them)
    private fun both(a: Condition?, b: Condition?): Condition? = when {
        a == null -> b
        b == null -> a
        else -> Condition(a.anyOf.flatMap { x -> b.anyOf.map { y -> x + y } })
    }

    /** [text]'s FNV-1a (32 bits) of its UTF-8, 8 lowercase hex digits: the same on the JVM and in JS. */
    internal fun hash(text: String): String {
        var h = FNV_OFFSET
        for (b in text.encodeToByteArray()) h = (h xor (b.toUInt() and 0xFFu)) * FNV_PRIME
        return h.toString(HEX).padStart(HEX_DIGITS, '0')
    }

    private const val FNV_OFFSET = 2166136261u
    private const val FNV_PRIME = 16777619u
    private const val HEX = 16
    private const val HEX_DIGITS = 8

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
