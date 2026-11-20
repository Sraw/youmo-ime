/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.keys.NodeFacts
import org.fcitx.fcitx5.android.scene.theme.BlankKeys
import org.fcitx.fcitx5.android.scene.theme.Meta
import org.fcitx.fcitx5.android.scene.theme.ThemeReader

/** Whether a theme pack's layout may stand for the app's board; here so the page previews a pack only as the app draws it. */
object PackLayouts {
    private const val BOM = "\uFEFF"

    val FRAGMENTS = setOf("grouped-bottom")

    /** A board the user picks among their own layouts ([KeyData.LISTED]) is not stood for: its file becomes a user layout. */
    fun replaces(id: String): Boolean = (id in BuiltInKeyData.BOARDS && id !in KeyData.LISTED) || id in FRAGMENTS

    const val MAX_BYTES = 64L * 1024

    /** Any other is not the pack's: the app's own is drawn in its place. */
    fun readable(files: Map<String, String>, has: (String) -> Boolean): Map<String, String> =
        files.filter { (id, text) -> has(id) && text.encodeToByteArray().size <= MAX_BYTES }.mapValues { it.value.removePrefix(BOM) }

    fun judge(id: String, layout: Layout, fragments: Map<String, Fragment>, app: Layout?, appFragments: Map<String, Fragment>): String? {
        if (layout.id != id) return "its id is ${layout.id}"
        app ?: return "the app's $id does not read"
        if (layout.family != app.family) return "its family is ${layout.family}, the app's ${app.family}"
        if (layout.family == "letters") LayoutCheck.lettersLacking(layout, fragments).takeIf { it.isNotEmpty() }?.let { return "it has no key [${it.sorted().joinToString(", ")}]" }
        return LayoutCheck.kept(layout, fragments, app, appFragments)
    }

    /** [own] must hold only [readable] files: any other would be asked here and not drawn there. */
    fun drawn(app: List<BlankKeys.Board>, own: Map<String, String>, appText: (String) -> String?): List<BlankKeys.Board> =
        app.map { a -> own[a.id]?.let { packBoard(a, it, own, appText) } ?: a }

    sealed interface Converted {
        class Done(
            val id: String,
            val name: String,
            val text: String,
            val renames: Map<String, String>,
            val problems: List<LayoutCheck.Finding>,
            val own: String = name,
            val commits: List<String> = emptyList(),
        ) : Converted

        class Not(val why: String) : Converted
    }

    /**
     * A pack's file of a board the user picks among their own made one of those, shared by the import, upgrade, restore
     * and the layout maker; the same pack imported again gives the same id (an update). Renamed key names are returned so
     * the caller copies the settings under them.
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
        refused(id, layout, fragments, own, sources)?.let { return Converted.Not(it) }
        val (defaults, parts, renames) = renameKeys(layout.defaults, convertRows(layout.parts, own), newId, sources, appKeys(sources, id))
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

    private fun forPads(family: String, declared: List<String>?, made: (List<String>?) -> Made): ForPads {
        if (family != NUMBER) return ForPads(made(declared), null)
        val all = made(declared ?: LayoutReader.PADS)
        val lacking = all.report.padsLacking
        return when {
            lacking.isEmpty() -> ForPads(all, null)
            declared != null -> ForPads(null, "its pads lack keys: ${lacking.joinToString(" ")}")
            else -> LayoutReader.PADS.filter { it !in lacking }.takeIf { it.isNotEmpty() }
                ?.let { ForPads(made(it), null) } ?: ForPads(null, "it serves no pad")
        }
    }

    fun idOf(theme: String, id: String): String = "t" + hash(theme) + "-" + id

    private const val NUMBER = "number"

    private fun renameKeys(defaults: KeyAttrs, parts: List<Part>, newId: String, sources: LayoutCheck.Sources, app: (String) -> NodeFacts?): Renamed {
        val renames = LinkedHashMap<String, String>()
        val named = HashSet<String>()
        val rows = parts.filterIsInstance<Row>()
        rows.flatMap { it.cells }.filterIsInstance<Key>()
            .forEach { k -> (listOf(k.attrs) + k.variants.map { it.attrs }).mapNotNullTo(named) { it.shortcut } }
        (listOf(defaults) + rows.map { it.defaults }).mapNotNullTo(named) { it.shortcut }
        fun free(base: String): String = sequenceOf("$newId/$base").plus(
            generateSequence(2) { it + 1 }.map { n -> "$newId/${base.take(MAX_KEY_ID - 1 - n.toString().length)}-$n" },
        ).first { it !in named }.also { named += it }
        fun renamed(base: String, name: String?): String? {
            if (name == null || LayoutCheck.nameProblem(name, sources) == null) return name
            return renames.getOrPut(name) { free(base) }
        }
        val ownLetters = HashMap<Pair<String, String>, String>()
        fun lettered(name: String?, letters: Letters?): String? {
            val group = letters?.group ?: return null
            val theirs = name?.let(app)?.letters ?: return null
            if (theirs == group) return null
            return ownLetters.getOrPut(name to group) { free(group.filter { it in 'a'..'z' }.ifEmpty { "keys" }).also { renames.getOrPut(name) { it } } }
        }
        var row = 0
        val out = parts.map { part ->
            if (part !is Row) return@map part
            row++
            val rowDefaults = part.defaults.copy(shortcut = renamed(part.id ?: "row$row", part.defaults.shortcut))
            Row(part.id, part.indent, part.endIndent, part.height, part.condition, part.split, rowDefaults, part.cells, part.line).withCells(
                part.cells.map { c ->
                    if (c !is Key) return@map c
                    val own = lettered(c.attrs.shortcut ?: part.defaults.shortcut ?: defaults.shortcut, c.letters)
                    Key(
                        c.id, c.condition, c.attrs.copy(shortcut = own ?: renamed(c.id, c.attrs.shortcut)), c.popup, c.menu, c.line,
                        c.variants.map { v -> Variant(v.condition, v.attrs.copy(shortcut = v.attrs.shortcut?.let { lettered(it, c.letters) ?: renamed(c.id, it) }), v.line) },
                        c.letters, c.lean, c.place,
                    )
                },
            )
        }
        return Renamed(defaults.copy(shortcut = renamed(LAYOUT_DEFAULTS, defaults.shortcut)), out, renames)
    }

    private data class Renamed(val defaults: KeyAttrs, val parts: List<Part>, val renames: Map<String, String>)

    private const val LAYOUT_DEFAULTS = "keys"

    internal fun convertRows(parts: List<Part>, own: Map<String, Fragment>): List<Part> = parts.flatMap { part ->
        val f = (part as? Include)?.let { own[it.src] }
        f?.rows?.map { r -> Row(r.id, r.indent, r.endIndent, r.height, both(part.condition, r.condition), r.split, r.defaults.over(f.defaults), r.cells, r.line) }
            ?: listOf(part)
    }

    /** Two files of [MAX_BYTES] could make millions of alternatives before any size is checked. */
    internal const val MAX_ALTERNATIVES = 256

    // outside [convert] for detekt's complexity
    private fun refused(id: String, layout: Layout, fragments: Map<String, Fragment>, own: Map<String, Fragment>, sources: LayoutCheck.Sources): String? {
        tooManyAlternatives(layout.parts, own)?.let { return it }
        val app = sources.layout(id)
        val appFragments = app?.parts?.filterIsInstance<Include>()?.mapNotNull { inc -> sources.fragment(inc.src)?.let { inc.src to it } }?.toMap().orEmpty()
        return judge(id, layout, fragments, app, appFragments)
    }

    private fun tooManyAlternatives(parts: List<Part>, own: Map<String, Fragment>): String? = parts.any { part ->
        val include = part as? Include ?: return@any false
        val f = own[include.src] ?: return@any false
        val n = include.condition?.anyOf?.size ?: return@any false
        f.rows.any { r -> n.toLong() * (r.condition?.anyOf?.size ?: 1) > MAX_ALTERNATIVES }
    }.takeIf { it }?.let { "over $MAX_ALTERNATIVES when= alternatives an include's and its rows' make" }

    private const val MAX_KEY_ID = 32

    private const val MAX_META = 64

    private fun shown(text: String?): String? = text?.let(LayoutCheck::plain)?.trim()?.let { clip(it, MAX_META) }?.takeIf { it.isNotBlank() }

    private fun name(theme: String, own: String, id: String): String {
        val mine = ownName(own, id)
        val themes = LayoutCheck.plain(theme).trim()
        val room = LayoutCheck.MAX_NAME - SEPARATOR.length - mine.length
        return if (themes.isEmpty() || room < 1) clip(mine, LayoutCheck.MAX_NAME) else clip(themes, room).trim() + SEPARATOR + mine
    }

    private fun ownName(own: String, id: String): String = LayoutCheck.plain(own).trim().ifEmpty { id }

    private const val SEPARATOR = " · "

    // never cutting a surrogate pair
    private fun clip(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.take(max)
        return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
    }

    private fun both(a: Condition?, b: Condition?): Condition? = when {
        a == null -> b
        b == null -> a
        else -> Condition(a.anyOf.flatMap { x -> b.anyOf.map { y -> x + y } })
    }

    /** The same on the JVM and in JS. */
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
