/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Expand
import org.fcitx.fcitx5.android.scene.engine.ExpandedKey
import org.fcitx.fcitx5.android.scene.engine.FixedMeasurer
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.Item
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.keys.DefaultMarks
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.rules.ScreenShape
import org.fcitx.fcitx5.android.scene.theme.BlankKeys
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.xml.Xml
import org.fcitx.fcitx5.android.scene.xml.XmlElement

/**
 * Whether a layout of its own (a `.layout`, 061-custom-layout §3) may be imported or saved: one check for the app's import
 * and the layout maker's page, so the two never disagree on a file. What is wrong comes back as [Finding]s, a key and its
 * words each ([KEYS]), the app and the page saying them in their own words.
 *
 * Errors (§3.2): it reads, its `min-app` is not past the app's; its id, name, family and `based-on=` as §2.2 has them,
 * its name and `<meta>` with no [hidden] character; the limits of §2.6; an `<include>` only of the app's fragments, a
 * `layout:` only to Text, Number, Symbol; what its base has that must be typed on kept ([kept]); every key's name its
 * own (`<id>/<key>`, §3.2 第 7 条) or the app's; the keys its family needs (§3.3); and a scene made in every environment
 * of [scenes] (the screens a phone and a foldable show it on, compact, split, the fields, the input methods it is for,
 * the pads it is for) with no problem and no key without words or a picture. Warnings: no way to the number pad or the
 * symbols, a key narrower than half a letter's, `commit:`s (listed too, the import's confirmation shows them), a key
 * [theme] leaves blank, a key without a name, `<gap look= class=>` (0.6.0's, no longer drawn), a number or symbols
 * board whose bottom row's first two are not the way back and the other board.
 *
 * Not [strict] (a theme pack's layout made into one of the list, on import, upgrade and restore, §3.2 两种检查强度): only
 * what 0.6.0 refused is an error (it reads, [kept]); the rest are warnings, but the gap's and the bottom row's, which the
 * user decided such packs are not told of.
 */
object LayoutCheck {
    /** Something wrong at [line] (0: the file as a whole): [key], one of [KEYS], with [args] to say it with. */
    data class Finding(val line: Int, val key: String, val args: List<String> = emptyList()) {
        override fun toString(): String = (if (line > 0) "line $line: " else "") + key + args.joinToString("") { " [$it]" }
    }

    /**
     * What [check] found: [errors] (not imported, not saved), [warnings]; and what the app reads of it once it is: its
     * [family], the input methods it may be chosen for ([ims], §5.1), the pads it is for ([pads], a number board's), the
     * `commit:` texts its keys type that its base's do not ([commits]: a key of its base's id typing the same text left
     * out, the import's confirmation listing only what is new), the oldest app that reads it ([minApp], what the maker writes).
     */
    data class Report(
        val errors: List<Finding>,
        val warnings: List<Finding>,
        val family: String?,
        val ims: List<String>,
        val pads: List<String>,
        val commits: List<String>,
        val minApp: String,
    ) {
        val ok: Boolean get() = errors.isEmpty()
    }

    /**
     * The app's boards and fragments, by id, as text: BuiltInLayouts on the phone, BuiltIns on the page. Read once, on the
     * first check that asks (all of them: a few milliseconds), and kept: one Sources may be shared by checks on several
     * threads (an import's, a migration's), each first read made once ([lazy] is synchronized on the JVM).
     */
    class Sources(val layouts: Map<String, String>, val fragments: Map<String, String>) {
        private val read: Map<String, Layout?> by lazy { layouts.mapValues { (_, t) -> (LayoutReader.layout(t) as? Parsed.Ok)?.value } }
        private val readFragments: Map<String, Fragment?> by lazy { fragments.mapValues { (_, t) -> (LayoutReader.fragment(t) as? Parsed.Ok)?.value } }

        internal val names: Set<String> by lazy { appNames(this) }

        internal fun layout(id: String): Layout? = read[id]

        internal fun fragment(id: String): Fragment? = readFragments[id]
    }

    /** The families a layout of its own may be (0.6.1). */
    val FAMILIES = listOf("letters", "grouped", "nine", "number", "symbols")

    /** The app's boards one may be made from. */
    val BASES = listOf("qwerty", "k14", "k18", "nine", "number", "symbols")

    /** The base a family's needs are held to where `based-on=` is of another family (§2.2: 26 keys made grouped). */
    val FAMILY_BASE = mapOf("letters" to "qwerty", "grouped" to "k18", "nine" to "nine", "number" to "number", "symbols" to "symbols")

    /** What the app's key names begin with (SlotDefaults, KeyCaps, KeyListsMigration go by them): no id nor own name may. */
    val NAME_PREFIXES = listOf("full", "g14", "g18", "t9", "zy9", "zyfull", "num", "fn")

    /** The boards a `layout:` may go to (§13). */
    val LAYOUT_TARGETS = listOf("Text", "Number", "Symbol")

    /** The version a layout of none of [FEATURES] needs: layout-1 as 0.6.0 reads it. */
    const val BASE_VERSION = "0.6.0"

    /**
     * What came after 0.6.0, and the app that first reads it (§2.5): a layout using one is refused by an older app, so
     * the maker writes the newest of those it uses as `min-app`. Kept with the reader by LayoutCheckTest.
     */
    val FEATURES = mapOf(
        "meta" to "0.6.1",
        "based-on" to "0.6.1",
        "pads" to "0.6.1",
        "rows-middle" to "0.6.1",
        "row-when" to "0.6.1",
    )

    /** Every key a [Finding] may have: the app's strings and the page's table say each (LayoutCheckTest holds them to it). */
    val KEYS = listOf(
        // errors
        "read", "newer", "id-length", "id-built-in", "id-prefix", "name-length", "family", "based-on", "too-big",
        "too-many-keys", "commit-long", "too-many-commits", "label-long", "include", "layout-target", "base", "scene",
        "blank", "name-no-slash", "name-prefix", "lacks", "groups", "hidden-chars",
        // warnings
        "no-way", "narrow", "commits", "theme-blank", "unnamed", "gap-look", "pad-lacks", "bottom-left",
    )

    const val MAX_ID = 32
    const val MAX_NAME = 24
    const val MAX_KEYS = 150
    const val MAX_COMMIT = 64
    const val MAX_COMMITS = 100
    const val MAX_LABEL = 16

    /**
     * [text] checked as a layout of its own for an app of [appVersion], against the app's boards [sources]; [theme] the
     * one in use, for the blank-key warning (none: not asked). [strict]: see the class. It builds every scene the layout
     * is shown in (tens of milliseconds on a phone, more the first time): an import or a migration calls it off the main
     * thread.
     */
    fun check(text: String, sources: Sources, appVersion: String, theme: Theme? = null, strict: Boolean = true): Report {
        val errors = ArrayList<Finding>()
        val warnings = ArrayList<Finding>()
        // a new rule's finding: an error where strict, else a warning
        val newRule: (Finding) -> Unit = { if (strict) errors += it else warnings += it }
        val source = text.removePrefix(BOM)
        val layout = read(text, errors) ?: return Report(errors, warnings, null, emptyList(), emptyList(), emptyList(), BASE_VERSION)
        val minApp = minApp(layout)
        val pads = if (layout.family == "number") layout.pads ?: listOf("full") else emptyList()
        layout.meta?.minApp?.let { if (ThemeReader.newer(it, appVersion)) errors += Finding(0, "newer", listOf(it)) }
        identity(layout, sources).forEach(newRule)
        limits(layout).forEach(newRule)
        val fragments = includes(layout, sources, errors)
        if (errors.any { it.key in STOPS }) return Report(errors, warnings, layout.family, emptyList(), pads, emptyList(), minApp)
        targets(layout).forEach(newRule)
        val base = held(layout, fragments, sources, strict, errors)
        names(layout, sources).forEach(newRule)
        family(layout, fragments).forEach(newRule)
        val ims = ims(layout, sources)
        val shown = scenes(layout, fragments, ims, pads)
        shown.errors.forEach(newRule)
        val commits = newCommits(layout, base, base?.let { baseFragments(it, sources) }.orEmpty())
        warnings += told(layout, fragments, commits, theme)
        base?.let { b -> unnamed(layout, b, baseFragments(b, sources)).forEach { warnings += it } }
        shown.padLacks.forEach { warnings += it }
        if (strict) {
            gapLooks(source).forEach { warnings += it }
            shown.bottomLeft?.let { warnings += it }
        }
        return Report(errors, warnings, layout.family, ims, pads, commits, minApp)
    }

    // the fragments [layout] includes, one the app has not an error
    private fun includes(layout: Layout, sources: Sources, errors: MutableList<Finding>): Map<String, Fragment> {
        val fragments = HashMap<String, Fragment>()
        for (inc in layout.parts.filterIsInstance<Include>()) {
            val f = sources.fragment(inc.src)
            if (f == null) errors += Finding(inc.line, "include", listOf(inc.src)) else fragments[inc.src] = f
        }
        return fragments
    }

    // a layout: action to a board the app has no switch to
    private fun targets(layout: Layout): List<Finding> =
        actions(layout).filter { it.second.startsWith("layout:") && it.second.removePrefix("layout:") !in LAYOUT_TARGETS }
            .map { Finding(it.first, "layout-target", listOf(it.second)) }

    // held to its base: the app's board it is made from, or its family's where that is of another family
    private fun baseId(layout: Layout, sources: Sources): String? =
        layout.basedOn?.let { b -> if (sources.layout(b)?.family == layout.family) b else FAMILY_BASE[layout.family] }
            ?: layout.id.takeIf { it in sources.layouts } ?: FAMILY_BASE[layout.family]

    // what is only told: no way to the number or symbol board, keys too narrow, what it types as it is, keys a theme leaves blank
    private fun told(layout: Layout, fragments: Map<String, Fragment>, commits: List<String>, theme: Theme?): List<Finding> {
        val out = ArrayList<Finding>()
        // its includes' keys too (the grouped boards' 123 and 符 are grouped-bottom's)
        val ways = cells(layout, fragments).filterIsInstance<Key>().flatMap { k -> listOf(k.attrs) + k.variants.map { it.attrs } }.mapNotNull { it.actions["send"] }
        // each of the number pad and the symbols one may not get to (a board needs no way to itself)
        val own = mapOf("number" to "Number", "symbols" to "Symbol")[layout.family]
        val none = listOf("Number", "Symbol").filter { it != own && "layout:$it" !in ways }
        if (none.isNotEmpty()) out += Finding(0, "no-way", none)
        out += narrow(layout)
        if (commits.isNotEmpty()) out += Finding(0, "commits", listOf(commits.size.toString()))
        theme?.let { t ->
            BlankKeys.find(t, listOf(BlankKeys.Board(layout, fragments)), limit = BLANK_LIMIT).forEach { out += Finding(0, "theme-blank", listOf(it.toString())) }
        }
        return out
    }

    /**
     * Why [layout] (its [fragments] with it) does not keep what [base] (its [baseFragments]) must be typed on, null where it
     * does: the slots by kind (the symbols' grid, the nine keys' side column), the function keys by role (删除, 回车, 切换),
     * the digit keys (the nine keys' 2–9, the number pad's) (060-render-v2 §4, §16). A theme pack's board is held to the
     * app's of its id by it (PackLayouts.judge), a layout of its own to its base's; [digitsById] false: the digit keys not
     * held by their ids (a number board of one's own, whose pads' keys are held by what they type).
     */
    fun kept(
        layout: Layout,
        fragments: Map<String, Fragment>,
        base: Layout,
        baseFragments: Map<String, Fragment>,
        digitsById: Boolean = true,
    ): String? {
        val mine = cells(layout, fragments)
        val theirs = cells(base, baseFragments)
        fun slots(c: List<Cell>) = c.filterIsInstance<Slot>().map { it.kind }.toSet()
        fun roles(c: List<Cell>) = c.filterIsInstance<Key>().mapNotNull { it.attrs.role }.filter { it in ROLES }.toSet()
        fun digits(c: List<Cell>) = c.filterIsInstance<Key>().map { it.id }.filter { DIGIT.matches(it) }.toSet()
        (slots(theirs) - slots(mine)).takeIf { it.isNotEmpty() }?.let { return "it has no slot $it" }
        (roles(theirs) - roles(mine)).takeIf { it.isNotEmpty() }?.let { return "it has no key $it" }
        if (digitsById) (digits(theirs) - digits(mine)).takeIf { it.isNotEmpty() }?.let { return "it has no key $it" }
        return null
    }

    /** The newest app version [layout]'s [features] need, [BASE_VERSION] where none. */
    fun minApp(layout: Layout): String =
        features(layout).mapNotNull { FEATURES[it] }.fold(BASE_VERSION) { v, f -> if (ThemeReader.newer(f, v)) f else v }

    /** Which of [FEATURES] [layout] uses. */
    fun features(layout: Layout): Set<String> = buildSet {
        if (layout.meta != null) add("meta")
        if (layout.basedOn != null) add("based-on")
        if (layout.pads != null) add("pads")
        val middle = layout.parts.filterIsInstance<Row>().any { row ->
            row.cells.withIndex().any { (i, c) -> i != 0 && i != row.cells.lastIndex && rowsOf(c, row, layout) > 1 }
        }
        if (middle) add("rows-middle")
        // a key id twice, one of them conditional only by its row's when= (0.6.0 took it for the same key twice)
        // every cell with an id, as the reader holds them (LayoutReader.checkKeys): a gap's and a slot's too
        val ids = layout.parts.filterIsInstance<Row>().flatMap { r -> r.cells.filter { c -> c.id != null } }.groupBy { it.id }
        if (ids.values.any { cells -> cells.size > 1 && cells.any { it.condition == null } }) add("row-when")
    }

    private fun rowsOf(c: Cell, row: Row, layout: Layout): Int = when (c) {
        is Key -> c.attrs.over(row.defaults.over(layout.defaults)).rows ?: 1
        is Slot -> c.rows
        is Gap -> 1
    }

    private const val BOM = "\uFEFF"
    private val ROLES = setOf("backspace", "enter", "lang")
    private val DIGIT = Regex("[0-9]")
    private const val BLANK_LIMIT = 5

    // §2.2's rules of the id, name, family and base
    private fun identity(layout: Layout, sources: Sources): List<Finding> = buildList {
        if (layout.id.length > MAX_ID) add(Finding(0, "id-length", listOf(layout.id, MAX_ID.toString())))
        if (layout.id in sources.layouts || layout.id in sources.fragments) add(Finding(0, "id-built-in", listOf(layout.id)))
        if (layout.id in NAME_PREFIXES) add(Finding(0, "id-prefix", listOf(layout.id)))
        if (layout.name.isBlank() || layout.name.length > MAX_NAME) add(Finding(0, "name-length", listOf(layout.name, MAX_NAME.toString())))
        if (layout.family !in FAMILIES) add(Finding(0, "family", listOf(layout.family)))
        if (layout.basedOn == null || layout.basedOn !in BASES) add(Finding(0, "based-on", listOf(layout.basedOn.orEmpty())))
        // what the list, its dialogs and the file saver show: no direction mark turning it around, nothing unseen
        val m = layout.meta
        val shown = listOf("name" to layout.name, "author" to m?.author, "version" to m?.version, "license" to m?.license, "description" to m?.description)
        shown.filter { (_, v) -> v != null && v.any(::hidden) }.forEach { (field, _) -> add(Finding(0, "hidden-chars", listOf(field))) }
    }

    /**
     * Whether [c] is one a layout's name and `<meta>` may not hold (L2 security review N2): a control (a line break among
     * them), a format character (the direction marks and overrides, the zero-width ones), a line or paragraph separator.
     * A name with one is refused strictly; one taken without the strict check is shown [plain].
     */
    fun hidden(c: Char): Boolean = c.category in HIDDEN

    /** [text] without what [hidden] says. */
    fun plain(text: String): String = text.filterNot(::hidden)

    private val HIDDEN = setOf(CharCategory.CONTROL, CharCategory.FORMAT, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR)

    // §2.6's limits
    private fun limits(layout: Layout): List<Finding> = buildList {
        val keys = ownCells(layout).filterIsInstance<Key>()
        if (keys.size > MAX_KEYS) add(Finding(0, "too-many-keys", listOf(keys.size.toString(), MAX_KEYS.toString())))
        val commits = actions(layout).filter { it.second.startsWith("commit:") }
        commits.filter { it.second.length - "commit:".length > MAX_COMMIT }.forEach { add(Finding(it.first, "commit-long", listOf(MAX_COMMIT.toString()))) }
        if (commits.size > MAX_COMMITS) add(Finding(0, "too-many-commits", listOf(commits.size.toString(), MAX_COMMITS.toString())))
        for (k in keys) {
            for (a in listOf(k.attrs) + k.variants.map { it.attrs }) {
                val label = (a.label as? Text.Literal)?.text ?: continue
                if (label.length > MAX_LABEL) add(Finding(k.line, "label-long", listOf(k.id, MAX_LABEL.toString())))
            }
        }
    }

    // every action it writes, with its line: its keys' tap's and swipes', their variants', long presses', menus'; its
    // <defaults>' and its rows' (each written once, however many keys take it)
    private fun actions(layout: Layout): List<Pair<Int, String>> = ownCells(layout).filterIsInstance<Key>().flatMap { k ->
        actions(k).map { k.line to it }
    } + defaultsActions(layout).map { it.second to it.first }

    // a key's: its tap's and swipes', its variants', its long press's, its menu's
    private fun actions(k: Key): List<String> =
        (listOf(k.attrs) + k.variants.map { it.attrs }).flatMap { written(it) } + k.popup?.items.orEmpty().mapNotNull { it.second } + k.menu.map { it.action }

    // its <defaults>' and its rows' (line 0: the layout's), each written once
    private fun defaultsActions(layout: Layout): List<Pair<String, Int>> =
        written(layout.defaults).map { it to 0 } + layout.parts.filterIsInstance<Row>().flatMap { r -> written(r.defaults).map { it to r.line } }

    // the commit: texts it types its [base] does not, in order: a key of a base key's id typing a text that key types is
    // the base's (qwerty's / @ . .com), one typing another or a key of its own is new; its defaults' always are
    private fun newCommits(layout: Layout, base: Layout?, baseFragments: Map<String, Fragment>): List<String> {
        fun texts(actions: List<String>) = actions.filter { it.startsWith("commit:") }.map { it.removePrefix("commit:") }
        val theirs = base?.let { b -> cells(b, baseFragments).filterIsInstance<Key>().flatMap { k -> texts(actions(k)).map { k.id to it } } }.orEmpty().toSet()
        return ownCells(layout).filterIsInstance<Key>().flatMap { k -> texts(actions(k)).filter { (k.id to it) !in theirs } } +
            texts(defaultsActions(layout).map { it.first })
    }

    private fun written(a: KeyAttrs): Collection<String> = a.actions.filterKeys { it in ACTION_ATTRS }.values

    // the attributes that hold an action (the rest of KeyAttrs.actions are a key's ways: swipe-axis, repeat, preview)
    private val ACTION_ATTRS = setOf("send", "swipe-up", "swipe-down", "long-press", "double-tap")

    // §3.2 第 7 条: a name not the app's has a / and none of the app's prefixes
    private fun names(layout: Layout, sources: Sources): List<Finding> {
        val app = sources.names
        return ownCells(layout).filterIsInstance<Key>().flatMap { k -> (listOf(k.attrs) + k.variants.map { it.attrs }).mapNotNull { it.shortcut }.distinct().map { k to it } }
            .filter { it.second !in app }
            .mapNotNull { (k, name) ->
                val id = KeyId(name)
                when {
                    id.isFunction -> Finding(k.line, "name-no-slash", listOf(id.key))
                    id.form in NAME_PREFIXES -> Finding(k.line, "name-prefix", listOf(name))
                    else -> null
                }
            }
    }

    // [text] read as the app reads a file: no more than its bytes, then its byte-order mark gone (PackLayouts.read); null
    // where it is not, what stopped it in [errors]
    private fun read(text: String, errors: MutableList<Finding>): Layout? {
        val bytes = text.encodeToByteArray().size
        if (bytes > PackLayouts.MAX_BYTES) {
            errors += Finding(0, "too-big", listOf(bytes.toString(), PackLayouts.MAX_BYTES.toString()))
            return null
        }
        val parsed = LayoutReader.layout(text.removePrefix(BOM))
        parsed.problems().takeIf { parsed !is Parsed.Ok }?.forEach { errors += Finding(it.line, "read", listOf(it.message)) }
        return (parsed as? Parsed.Ok)?.value
    }

    // what an app that cannot read it or build it, or a board too big to look through, says is enough: the rest told once it can
    private val STOPS = setOf("newer", "include", "too-many-keys")

    // its base (null: the app has none), what it must keep of it held to, into [errors]
    private fun held(layout: Layout, fragments: Map<String, Fragment>, sources: Sources, strict: Boolean, errors: MutableList<Finding>): Layout? {
        val baseId = baseId(layout, sources)
        val base = baseId?.let(sources::layout)
        when {
            // a family a layout of one's own cannot be has no base to hold it to: said once, as "family"
            strict && layout.family !in FAMILIES -> Unit
            base == null -> errors += Finding(0, "base", listOf("the app has no ${baseId ?: layout.family} to hold it to"))
            else -> {
                // a number board of one's own: its digits by what they type, each pad's (padLacks), not by the base's ids (§16 风险 4)
                val digitsById = !(strict && layout.family == "number")
                kept(layout, fragments, base, baseFragments(base, sources), digitsById)?.let { errors += Finding(0, "base", listOf(it)) }
            }
        }
        return base
    }

    // the names the app's boards give their keys
    private fun appNames(sources: Sources): Set<String> = buildSet {
        for (id in sources.layouts.keys) {
            val l = sources.layout(id) ?: continue
            val fragments = l.parts.filterIsInstance<Include>().mapNotNull { inc -> sources.fragment(inc.src)?.let { inc.src to it } }.toMap()
            DefaultMarks.nodes(l, fragments).forEach { add(it.first) }
        }
    }

    // §3.3: the keys a family needs (a number board's by pad: scenes)
    private fun family(layout: Layout, fragments: Map<String, Fragment>): List<Finding> {
        val cells = cells(layout, fragments)
        val keys = cells.filterIsInstance<Key>()
        fun lacks(what: String) = Finding(0, "lacks", listOf(what))
        val roles = keys.mapNotNull { it.attrs.role }.toSet()
        return when (layout.family) {
            "letters" -> listOfNotNull(lacks("space").takeIf { "space" !in roles }, lacks("shift").takeIf { "shift" !in roles })
            "grouped" -> listOfNotNull(
                Groups.problem(groups(layout))?.let { Finding(0, "groups", listOf(it)) },
                lacks("space").takeIf { "space" !in roles },
            )
            "nine" -> listOfNotNull(
                (2..9).map { it.toString() }.filter { d -> keys.none { it.id == d } }.takeIf { it.isNotEmpty() }?.let { lacks(it.joinToString(" ")) },
                lacks("nine-side").takeIf { cells.filterIsInstance<Slot>().none { it.kind == "nine-side" } },
            )
            "symbols" -> listOfNotNull(
                listOf("symbol-categories", "symbol-grid").filter { s -> cells.filterIsInstance<Slot>().none { it.kind == s } }
                    .takeIf { it.isNotEmpty() }?.let { lacks(it.joinToString(" ")) },
                lacks("panel-back").takeIf { keys.none { it.attrs.actions["send"] == "panel-back" } },
                lacks("backspace").takeIf { "backspace" !in roles },
            )
            else -> emptyList()
        }
    }

    // a grouped board's groups, its keys' letters in order
    private fun groups(layout: Layout): List<String> =
        ownCells(layout).filterIsInstance<Key>().mapNotNull { k -> k.letters?.rows?.joinToString(" ") }

    // §5.1: which input methods may use it (a grouped board: 双拼 only on 14 or 18 keys' groups, in any order)
    private fun ims(layout: Layout, sources: Sources): List<String> = when (layout.family) {
        "letters" -> listOf("pinyin", "shuangpin", "english", "table")
        "nine" -> listOf("pinyin", "shuangpin", "english")
        "grouped" -> {
            val mine = groups(layout).map { it.replace(" ", "") }.toSet()
            val app = listOf("k14", "k18").mapNotNull { id -> sources.layout(id)?.let { l -> groups(l).map { it.replace(" ", "") }.toSet() } }
            if (mine in app) listOf("pinyin", "shuangpin") else listOf("pinyin")
        }
        else -> emptyList()
    }

    private class Shown(val errors: List<Finding>, val padLacks: List<Finding>, val bottomLeft: Finding?)

    /**
     * A screen the scenes are made on: [widthDp] wide, [tallDp] high, [turned] sideways (61-wide-as-landscape: 横 by
     * ScreenShape).
     */
    data class Screen(val widthDp: Int, val turned: Boolean, val tallDp: Int) {
        /** A foldable's inner screen, a tablet: the display's short side [LARGE_SHORT_SIDE_DP] or more (KeyboardHeightLevels). */
        val large: Boolean get() = minOf(widthDp, tallDp) >= LARGE_SHORT_SIDE_DP
    }

    /**
     * The screens (§3.2 第 5 条): two phones upright, one sideways, a foldable's inner screen upright (横 by its width) and its
     * outer one.
     */
    val SCREENS = listOf(
        Screen(360, false, 780), Screen(412, false, 915), Screen(800, true, 360), Screen(600, false, 841), Screen(344, false, 882),
    )

    /**
     * The keys' height on [s], dp, as the app asks for it at 标准 or [compact] 紧凑: upright the level's share of the screen's
     * height; 横 (ScreenShape) four rows of the level's row, a phone's or a [Screen.large] one's (061-landscape-height
     * §5.1). The app's numbers are ime-core's (KeyboardSizes, KeyboardHeightLevels), which this module cannot read:
     * ime-core's LayoutCheckHeightsTest holds these to them.
     */
    fun keysDp(s: Screen, compact: Boolean): Int = when {
        !ScreenShape.landscape(s.turned, s.widthDp) -> s.tallDp * (if (compact) COMPACT_PERCENT else NORMAL_PERCENT) / PERCENT
        else -> ROWS * if (s.large) (if (compact) LARGE_COMPACT_ROW_DP else LARGE_ROW_DP) else if (compact) COMPACT_ROW_DP else ROW_DP
    }

    /**
     * The lowest the keys are let get turned (KeyboardHeightCap: four rows of 36 dp where the app keeps its 120 dp): a
     * layout checked on [SCREENS] is checked there too, so its keys' marks and labels are seen at the height a small
     * phone gives them.
     */
    const val LOWEST_DP = 4 * 36

    // KeyboardHeightLevels' 标准 and 紧凑: upright percent, turned rows of dp; its large screen's short side
    private const val NORMAL_PERCENT = 30
    private const val COMPACT_PERCENT = 20
    private const val PERCENT = 100
    private const val ROWS = 4
    private const val ROW_DP = 48
    private const val COMPACT_ROW_DP = 34
    private const val LARGE_ROW_DP = 56
    private const val LARGE_COMPACT_ROW_DP = 48
    private const val LARGE_SHORT_SIDE_DP = 600

    private const val DENSITY = 2.625

    /** One environment a layout is built in, and how a finding names it. */
    class Scene(val name: String, val env: Env)

    /**
     * The environments a layout of [family] is built in ([layout]'s split ones where it splits): every [SCREENS] × normal and
     * compact (× split), the first screen with every field, 地球键 on and off, each of [ims] (双拼 by three schemes, one
     * typing `;`) with and without composing; a number board's in each of [pads] instead of the input methods.
     */
    fun scenes(layout: Layout, ims: List<String>, pads: List<String>): List<Scene> {
        val out = ArrayList<Scene>()
        val splits = if (layout.onSplit == OnSplit.Split) listOf(false, true) else listOf(false)
        val padInputs = pads.map { it to Input(pad = it) }
        val first = padInputs.firstOrNull()?.second ?: Input()
        for (s in SCREENS) for (compact in listOf(false, true)) for (split in splits) {
            val name = "${s.widthDp}dp" + (if (s.turned) " turned" else "") + (if (compact) " compact" else "") + (if (split) " split" else "")
            out += Scene(name, env(s, compact, split, first))
        }
        for (s in SCREENS.filter { it.turned }) out += Scene("${s.widthDp}dp turned ${LOWEST_DP}dp", env(s, false, false, first, LOWEST_DP))
        val s = SCREENS.first()
        val inputs = if (pads.isNotEmpty()) {
            padInputs.drop(1).map { (pad, input) -> "pad=$pad" to input }
        } else {
            imInputs(ims).flatMap { (name, input) ->
                FIELDS.flatMap { field ->
                    listOf(true, false).flatMap { lang ->
                        listOf(false, true).map { composing ->
                            "$name field=$field" + (if (lang) "" else " no-lang-key") + (if (composing) " composing" else "") to
                                input.copy(field = field, langKey = lang, composing = composing)
                        }
                    }
                }
            }
        }
        for ((name, input) in inputs) out += Scene("${s.widthDp}dp $name", env(s, false, false, input))
        return out
    }

    private val FIELDS = listOf("text", "url", "email")

    private fun imInputs(ims: List<String>): List<Pair<String, Input>> = ims.flatMap { im ->
        when (im) {
            "shuangpin" -> listOf(
                "shuangpin" to Input(ime = "shuangpin", scheme = "Ziranma"),
                "shuangpin(Xiaohe)" to Input(ime = "shuangpin", scheme = "Xiaohe"),
                "shuangpin(MS)" to Input(ime = "shuangpin", scheme = "MS", schemeTypes = setOf("semicolon")),
            )
            "table" -> listOf(
                "table" to Input(ime = "table", table = "wbx", roots = true),
                "table(star)" to Input(ime = "table", table = "cangjie", tableExtra = "star"),
            )
            else -> listOf(im to Input(ime = im))
        }
    }.ifEmpty { listOf("" to Input()) }

    private fun env(s: Screen, compact: Boolean, split: Boolean, input: Input, heightDp: Int = keysDp(s, compact)): Env {
        val landscape = ScreenShape.landscape(s.turned, s.widthDp)
        return Env(
            width = s.widthDp * DENSITY,
            height = heightDp * DENSITY,
            density = DENSITY,
            landscape = landscape,
            compact = compact,
            split = split,
            input = input,
            texts = TEXTS,
        )
    }

    // what bindings read to: a name for the space, Return's words
    private val TEXTS = Texts(imeName = "拼音", returnLabel = "换行")

    // a theme of nothing: the scenes are asked where things go, not how they look
    private val PLAIN = Theme("check", false, "1", emptyMap(), emptyList(), emptyMap(), emptyMap(), null)

    // §3.2 第 5、6 条: a scene in each environment, no problem and no blank key; a number board's pads' keys
    private fun scenes(layout: Layout, fragments: Map<String, Fragment>, ims: List<String>, pads: List<String>): Shown {
        val errors = LinkedHashMap<Pair<Int, String>, Finding>()
        val padLacks = ArrayList<Finding>()
        var bottomLeft: Finding? = null
        val padsChecked = HashSet<String>()
        for (scene in scenes(layout, ims, pads)) {
            val built = SceneBuilder(PLAIN, scene.env, FixedMeasurer).build(layout, fragments)
            built.problems().forEach { errors.getOrPut(it.line to it.message) { Finding(it.line, "scene", listOf(scene.name, it.message)) } }
            val rows = Expand.rows(layout, fragments, scene.env).orNull() ?: continue
            val keys = rows.flatMap { it.items }.filterIsInstance<ExpandedKey>()
            // the space bar is its face (the symbols' is blank)
            keys.filter { it.label.isNullOrBlank() && it.icon == null && it.key.letters == null && it.attrs.kind != "space" }.forEach {
                errors.getOrPut(it.key.line to "blank") { Finding(it.key.line, "blank", listOf(it.key.id, scene.name)) }
            }
            val pad = scene.env.input.pad
            if (pad != null && padsChecked.add(pad)) padLacks(pad, keys).forEach { (error, f) -> if (error) errors[f.line to f.toString()] = f else padLacks += f }
            // null: not looked at yet (the first scene's bottom row decides; a finding, once it is, stays)
            if (bottomLeft == null && (layout.family == "number" || layout.family == "symbols")) bottomLeft = bottomLeft(layout.family, rows.lastOrNull()?.items.orEmpty())
        }
        return Shown(errors.values.toList(), padLacks, bottomLeft)
    }

    // §3.3 a number pad's keys: 0–9, ⌫, ⏎, the way back; a decimal one's `.`, a signed one's `-` (errors); a phone's `+ * #`,
    // a date's `- / :` (told). A key is what it types: its send=, a keypad's digit by its keysym
    private fun padLacks(pad: String, keys: List<ExpandedKey>): List<Pair<Boolean, Finding>> {
        val types = keys.mapNotNull { typed(it.attrs.actions["send"]) }.toSet()
        val roles = keys.mapNotNull { it.attrs.role }.toSet()
        val need = ArrayList<String>()
        need += (0..9).map { it.toString() }.filter { it !in types }
        listOf("backspace", "enter", "back").filter { it !in roles }.forEach { need += it }
        if (pad.endsWith("decimal") && "." !in types) need += "."
        if (pad.startsWith("signed") && "-" !in types) need += "-"
        val told = when (pad) {
            "phone" -> listOf("+", "*", "#")
            "datetime" -> listOf("-", "/", ":")
            else -> emptyList()
        }.filter { it !in types }
        return listOfNotNull(
            need.takeIf { it.isNotEmpty() }?.let { true to Finding(0, "lacks", listOf("pad=$pad: " + it.joinToString(" "))) },
            told.takeIf { it.isNotEmpty() }?.let { false to Finding(0, "pad-lacks", listOf(pad, it.joinToString(" "))) },
        )
    }

    // what a send= types: a character, or a keypad key's (sym:KP_1, sym:KP_Decimal)
    private fun typed(send: String?): String? = when {
        send == null -> null
        send.startsWith("sym:KP_") -> KEYPAD[send.removePrefix("sym:KP_")]
        send.startsWith("commit:") -> send.removePrefix("commit:")
        // one past the BMP is no pad's key
        send.startsWith("code:") -> LayoutReader.codePoint(send)?.takeIf { it < SUPPLEMENTARY }?.toChar()?.toString()
        ':' in send && send.length > 1 -> null
        else -> send
    }

    private const val SUPPLEMENTARY = 0x10000

    private val KEYPAD = (0..9).associate { it.toString() to it.toString() } + mapOf(
        "Decimal" to ".", "Subtract" to "-", "Add" to "+", "Multiply" to "*", "Divide" to "/", "Separator" to ",", "Equal" to "=",
    )

    // §3.3: the bottom row's first two a number board's 符 and back, a symbols board's back and 123 (as the letters' are)
    private fun bottomLeft(family: String, items: List<Item>): Finding? {
        val want = if (family == "number") setOf("symbols", "back") else setOf("back", "numbers")
        val first = items.take(2).map { (it as? ExpandedKey)?.attrs?.role }.toSet()
        return if (first == want) null else Finding(0, "bottom-left", listOf(want.joinToString(" ")))
    }

    // a key narrower than half a letter's (a tenth of a 10-column board's width)
    private fun narrow(layout: Layout): List<Finding> = layout.parts.filterIsInstance<Row>().flatMap { row ->
        row.cells.filterIsInstance<Key>().mapNotNull { k ->
            val w = k.attrs.over(row.defaults.over(layout.defaults)).width as? Width.Units ?: return@mapNotNull null
            if (w.units < layout.columns / NARROW) Finding(k.line, "narrow", listOf(k.id)) else null
        }
    }

    private const val NARROW = 20

    // a key with no name, but where its base's of its id has none either (the number pad's + - * /)
    private fun unnamed(layout: Layout, base: Layout, baseFragments: Map<String, Fragment>): List<Finding> {
        fun named(k: Key, defaults: KeyAttrs) = k.attrs.over(defaults).shortcut != null || k.variants.any { it.attrs.shortcut != null }
        val baseNamed = HashMap<String, Boolean>()
        for (part in base.parts) {
            val rows = when (part) {
                is Row -> listOf(part to base.defaults)
                is Include -> baseFragments[part.src]?.let { f -> f.rows.map { it to f.defaults.over(base.defaults) } }.orEmpty()
            }
            for ((row, defaults) in rows) for (k in row.cells.filterIsInstance<Key>()) baseNamed[k.id] = (baseNamed[k.id] ?: false) || named(k, row.defaults.over(defaults))
        }
        return layout.parts.filterIsInstance<Row>().flatMap { row ->
            row.cells.filterIsInstance<Key>().filter { k -> !named(k, row.defaults.over(layout.defaults)) && baseNamed[k.id] != false }
                .map { Finding(it.line, "unnamed", listOf(it.id)) }
        }
    }

    // `<gap look= class=>`: read, no longer drawn (0.6.0 §1.1)
    private fun gapLooks(text: String): List<Finding> {
        val root = Xml.parse(text).orNull() ?: return emptyList()
        val out = ArrayList<Finding>()
        fun walk(e: XmlElement) {
            if (e.name == "gap" && ("look" in e.attributes || "class" in e.attributes)) out += Finding(e.line, "gap-look")
            e.children.forEach(::walk)
        }
        walk(root)
        return out
    }

    // the base's fragments, the app's
    private fun baseFragments(base: Layout, sources: Sources): Map<String, Fragment> =
        base.parts.filterIsInstance<Include>().mapNotNull { inc -> sources.fragment(inc.src)?.let { inc.src to it } }.toMap()

    // every cell of [layout], its includes' rows in place
    private fun cells(layout: Layout, fragments: Map<String, Fragment>): List<Cell> = layout.parts.flatMap { part ->
        when (part) {
            is Row -> part.cells
            is Include -> fragments[part.src]?.rows.orEmpty().flatMap { it.cells }
        }
    }

    // the cells the file writes itself
    private fun ownCells(layout: Layout): List<Cell> = layout.parts.filterIsInstance<Row>().flatMap { it.cells }
}
