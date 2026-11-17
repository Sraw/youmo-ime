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

        // each key of the app's boards taking a function name, its states' taps: what a true copy of it sends
        internal val taps: Map<KeyId, List<Set<String>>> by lazy { tapsByName(this) }

        internal fun layout(id: String): Layout? = read[id]

        fun fragment(id: String): Fragment? = readFragments[id]
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
        "blank", "name-no-slash", "name-prefix", "lacks", "groups", "hidden-chars", "command",
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
        typedHidden(layout).forEach(newRule)
        base?.let { b -> commands(layout, b, baseFragments(b, sources), sources).forEach(newRule) }
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

    /**
     * The app's board [layout] is held to, and drawn as where it cannot be: the one it is made from, or its family's where
     * that is of another family (a `based-on` of another family's board is let in) or none.
     */
    fun baseId(layout: Layout, sources: Sources): String? =
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
            BlankKeys.find(t, listOf(BlankKeys.Board(layout, fragments, base = layout.basedOn)), limit = BLANK_LIMIT).forEach { out += Finding(0, "theme-blank", listOf(it.toString())) }
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
    private const val PAD_LACKING = "pad="
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

    // a key's: its tap's and swipes', its variants', its long press's, its menu's. A long press's list item types its
    // text where it has no send= of its own (SceneKeyDef, SceneBehavior's Listed): a commit: of it, held to what one is
    private fun actions(k: Key): List<String> =
        (listOf(k.attrs) + k.variants.map { it.attrs }).flatMap { written(it) } +
            k.popup?.items.orEmpty().flatMap { listOfNotNull("commit:" + it.first, it.second) } + k.menu.map { it.action }

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

    // §3.2 第 7 条: a name not the app's has a / and none of the app's prefixes; a key's, its variants', and one a
    // <defaults> gives every key under it (the layout's, a row's: L7 review S4, a pack's 0.6.0 row named so slipped by)
    private fun names(layout: Layout, sources: Sources): List<Finding> {
        val rows = layout.parts.filterIsInstance<Row>()
        val keys = rows.flatMap { it.cells }.filterIsInstance<Key>()
            .flatMap { k -> (listOf(k.attrs) + k.variants.map { it.attrs }).mapNotNull { it.shortcut }.distinct().map { k.line to it } }
        val defaults = listOfNotNull(layout.defaults.shortcut?.let { 0 to it }) + rows.mapNotNull { r -> r.defaults.shortcut?.let { r.line to it } }
        return (defaults + keys).mapNotNull { (line, name) ->
            when (nameProblem(name, sources)) {
                "name-no-slash" -> Finding(line, "name-no-slash", listOf(KeyId(name).key))
                "name-prefix" -> Finding(line, "name-prefix", listOf(name))
                else -> null
            }
        }
    }

    /**
     * The pad a number board lacks what its fields type of, where [finding] says so (`lacks` `pad=<pad>: …`); null for
     * any other finding. A restore refuses such a board, a theme pack's made one is made for the pads it does not lack
     * (061-custom-layout §7; L5 review N1, L7 review S3): one rule for both.
     */
    fun padLacking(finding: Finding): String? =
        finding.takeIf { it.key == "lacks" }?.args?.firstOrNull()?.takeIf { it.startsWith(PAD_LACKING) }
            ?.removePrefix(PAD_LACKING)?.substringBefore(':')

    /**
     * What §3.2 第 7 条 says of a key's [name] (as read: a bare one `fn/`'s), null where it may be: one not the app's
     * (`name-no-slash`) without a `/`, (`name-prefix`) under one of the app's prefixes. What a theme pack's layout made
     * one of the user's renames (PackLayouts.convert).
     */
    internal fun nameProblem(name: String, sources: Sources): String? {
        if (name in sources.names) return null
        val id = KeyId(name)
        return when {
            id.isFunction -> "name-no-slash"
            id.form in NAME_PREFIXES -> "name-prefix"
            else -> null
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

    /**
     * What stops [layout], a file of the user's layouts read to be drawn, from being drawn (061-custom-layout §14, L3's
     * security): the file is not trusted (one restored was held to the loose check alone, one of an older build to its
     * rules), so the errors of [check] that need no scene are asked again: its `min-app` for an app of [appVersion], its
     * id, family and base, the limits, the includes, the `layout:` targets, what its base has that must be typed on
     * ([kept]), the keys' names, its family's keys, no [hidden] character typed, no command on a letter or the space its
     * base has not there. Milliseconds: asked on the main thread as a keyboard is made. Empty where it may be drawn.
     */
    fun loaded(layout: Layout, sources: Sources, appVersion: String): List<Finding> {
        val errors = ArrayList<Finding>()
        layout.meta?.minApp?.let { if (ThemeReader.newer(it, appVersion)) errors += Finding(0, "newer", listOf(it)) }
        // its name and <meta> shown plain wherever shown (Entry.name): nothing of them is drawn on a key nor typed
        errors += identity(layout, sources).filter { it.key != "hidden-chars" && it.key != "name-length" }
        errors += limits(layout)
        val fragments = includes(layout, sources, errors)
        if (errors.any { it.key in STOPS }) return errors
        errors += targets(layout)
        val base = held(layout, fragments, sources, true, errors)
        errors += names(layout, sources)
        errors += family(layout, fragments)
        errors += typedHidden(layout)
        base?.let { b -> errors += commands(layout, b, baseFragments(b, sources), sources) }
        return errors
    }

    // a character typed (a send= of one, a commit:'s text, a code:) that is not seen: a direction mark turning around
    // what is typed into another app, a control (L2 security review S4). Line breaks and tabs too: Return types those
    private fun typedHidden(layout: Layout): List<Finding> {
        fun hides(a: String): Boolean {
            val text = when {
                a.startsWith("commit:") -> a.removePrefix("commit:")
                a.startsWith("code:") -> return LayoutReader.codePoint(a)?.let(::hiddenCode) == true
                a.isNotEmpty() && a.codePointCount() == 1 -> a
                else -> return false
            }
            return text.any(::hidden) || supplementary(text).any(::hiddenCode)
        }
        val keys = ownCells(layout).filterIsInstance<Key>().flatMap { k -> actions(k).filter(::hides).map { Finding(k.line, "hidden-chars", listOf(k.id)) } }
        return keys + defaultsActions(layout).filter { hides(it.first) }.map { Finding(it.second, "hidden-chars", listOf("defaults")) }
    }

    // [text]'s code points past the BMP
    private fun supplementary(text: String): List<Int> = buildList {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                add(((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) + SUPPLEMENTARY)
                i += 2
            } else {
                i++
            }
        }
    }

    private fun String.codePointCount(): Int = length - supplementary(this).size

    // [cp] one [hidden] says, or a format character past the BMP (Kotlin's common Char has no category for those)
    private fun hiddenCode(cp: Int): Boolean =
        if (cp < SUPPLEMENTARY) hidden(cp.toChar()) else HIDDEN_ASTRAL.any { cp in it }

    // Unicode's Cf past the BMP: Kaithi's and Egyptian's format controls, the shorthand ones, musical symbols', the tags
    private val HIDDEN_ASTRAL = listOf(0x110BD..0x110BD, 0x110CD..0x110CD, 0x13430..0x1343F, 0x1BCA0..0x1BCA3, 0x1D173..0x1D17A, 0xE0001..0xE0001, 0xE0020..0xE007F)

    /**
     * What a letter's or the space's key does that its base's key of its id does not (L2 security review S4, the second
     * way: a layout imported must not make a letter's swipe 清空 what is being written, nor a key that types make its tap
     * act as another's). An allow-list over the key's every state, its root and each variant laid over it as Expand lays
     * them (the L3 fix's second re-check: a deny-list of shapes let the next shape through):
     * - a command (a `shortcut:`, a word of the app's, a symbol typing nothing: [isCommand]) written on a key any state
     *   of which types or shows a letter ([typing]; a group's letters, a base letter's id too) is one only where the
     *   base's key of its id has it. A key no state of which does may do any (符, 123): a function key, seen as one, its
     *   swipes' and list's texts too (its tap visibly types none);
     * - a name of the app's function keys (`shortcut="backspace"`: that key's tap, swipes, 清空 among them, and what is
     *   set on it; a name not the app's is [names]'), on any key, is one only where the base's key of its id has it (on a
     *   key that types, only where that key types too: the base's 删除 made a letter takes not its name), or
     *   where the key is a true copy of a key of the app's taking that name: every state's tap a send that key's tap is
     *   (the base's 分词 or 空格 added again, a 删除 labelled ⌫ or 删, the full keyboard's ⇧ kept on a board grouped),
     *   never on a base letter's id (the space's among them), nor on a key showing a letter whose tap is a command (a
     *   回车 or 删除 labelled e looks like e).
     */
    private fun commands(layout: Layout, base: Layout, baseFragments: Map<String, Fragment>, sources: Sources): List<Finding> {
        val appNames = sources.names
        val baseKeys = keysOf(base, baseFragments)
        val theirs = baseKeys.groupBy({ it.first.id }, { actions(it.first) }).mapValues { (_, a) -> a.flatten().toSet() }
        val theirNames = baseKeys.groupBy({ it.first.id }, { states(it.first, it.second).mapNotNull { s -> s.shortcut?.let(KeyId::of) } })
            .mapValues { (_, n) -> n.flatten().toSet() }
        val named = sources.taps
        val letters = baseKeys.filter { (k, a) -> typing(k, a) }.map { it.first.id }.toSet()
        fun copies(k: Key, a: KeyAttrs, name: KeyId): Boolean {
            if (k.id in letters) return false
            val states = states(k, a)
            if (states.any { showsLetter(it.label) } && states.any { s -> s.actions["send"]?.let(::isCommand) == true }) return false
            val taps = states.map { it.actions["send"].orEmpty() }
            return taps.none { it.isEmpty() } && named[name].orEmpty().any { it.containsAll(taps) }
        }
        return keysOf(layout, emptyMap()).flatMap { (k, a) ->
            val held = k.id in letters || typing(k, a)
            val taken = states(k, a).mapNotNull { it.shortcut }.distinct().filter { it in appNames && KeyId.of(it).isFunction }
            // a true copy's taps are its app key's (one showing a letter types: [copies] takes none whose tap is a command)
            val copied = taken.filter { copies(k, a, KeyId.of(it)) }
            val taps = if (copied.isEmpty()) emptySet() else states(k, a).mapNotNull { it.actions["send"] }.toSet()
            // a base function key's id made a letter keeps none of its commands, as none of its name (the base's 回车 shown as e)
            val kept = if (k.id in letters) theirs[k.id].orEmpty() else emptySet()
            val commands = if (!held) emptyList() else actions(k).filter(::isCommand).filter { it !in kept && it !in taps }.distinct()
            val names = taken.filter { !(KeyId.of(it) in theirNames[k.id].orEmpty() && (k.id in letters || !held)) && it !in copied }
            commands.map { Finding(k.line, "command", listOf(k.id, it)) } + names.map { Finding(k.line, "command", listOf(k.id, "shortcut=$it")) }
        } + defaultsActions(layout).filter { isCommand(it.first) }.map { Finding(it.second, "command", listOf("defaults", it.first)) }
    }

    // [layout]'s keys (its own rows' where [fragments] is empty), each with what its row's and the layout's defaults make of it
    private fun keysOf(layout: Layout, fragments: Map<String, Fragment>): List<Pair<Key, KeyAttrs>> = layout.parts.flatMap { part ->
        val rows = when (part) {
            is Row -> listOf(part to layout.defaults)
            is Include -> fragments[part.src]?.let { f -> f.rows.map { it to f.defaults.over(layout.defaults) } }.orEmpty()
        }
        rows.flatMap { (row, defaults) -> row.cells.filterIsInstance<Key>().map { it to it.attrs.over(row.defaults.over(defaults)) } }
    }

    // the keys of [sources]' boards (with what they include) by each function name they take, each its states' taps
    private fun tapsByName(sources: Sources): Map<KeyId, List<Set<String>>> = sources.layouts.keys.mapNotNull(sources::layout).flatMap { b ->
        keysOf(b, baseFragments(b, sources)).flatMap { (k, a) ->
            val states = states(k, a)
            val taps = states.mapNotNull { it.actions["send"] }.toSet()
            states.mapNotNull { it.shortcut?.let(KeyId::of) }.distinct().filter { it.isFunction }.map { it to taps }
        }
    }.groupBy({ it.first }, { it.second })

    // [k]'s states: [a], its root as its row and layout make it, and each variant laid over it (Expand's row)
    private fun states(k: Key, a: KeyAttrs): List<KeyAttrs> = listOf(a) + k.variants.map { it.attrs.over(a) }

    // whether a state of [k] types: a group's letters, a tap that types ([types]), or a letter shown ([showsLetter])
    private fun typing(k: Key, a: KeyAttrs): Boolean = k.letters != null || states(k, a).any { types(it.actions["send"]) || showsLetter(it.label) }

    // a label a letter's or a digit's: a letter binding ({case:e}, {group:qw}, {root:e}, {nine-letters:2}), or a text
    // the eye takes for letters ([Sight.readsAsLetters]), written or a {punct:} with none of its own (Expand draws it as is).
    // 符, 123, 拼, 中, Emoji are no letters
    private fun showsLetter(label: Text?): Boolean = when (label) {
        is Text.Literal -> Sight.readsAsLetters(label.text)
        is Text.Binding -> label.name in Text.WITH_LETTER || label.name in Text.WITH_NUMBER || label.name == Text.GROUP ||
            (label.name == Text.PUNCT && Sight.readsAsLetters(label.arg.orEmpty()))
        null -> false
    }

    // whether [send] types anything: text of any length, a space, a digit, a tone, a zhuyin's symbol ([typed]'s)
    private fun types(send: String?): Boolean =
        typed(send)?.isNotEmpty() == true || send?.startsWith("code:") == true || send?.startsWith("action:zhuyin") == true

    // a symbol is a command but one that types (the space's, a digit's, a keypad's): Return sends, BackSpace and Escape take away
    private fun isCommand(a: String): Boolean =
        a.startsWith("shortcut:") || a in COMMANDS || (a.startsWith("sym:") && !types(a))

    // the bare words of the vocabulary (LayoutReader's) that do something to the text or the keyboard, Shift aside
    private val COMMANDS = setOf(
        "lang", "ime-picker", "quickphrase", "unicode", "panel-back", "panel-lock", "space-long-press", "begin-selection-swipe",
        "delete-selection", "move-selection", "lookup",
    )

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
            need.takeIf { it.isNotEmpty() }?.let { true to Finding(0, "lacks", listOf("$PAD_LACKING$pad: " + it.joinToString(" "))) },
            told.takeIf { it.isNotEmpty() }?.let { false to Finding(0, "pad-lacks", listOf(pad, it.joinToString(" "))) },
        )
    }

    // what a send= types: a character (not a word of the vocabulary, `lang`), a commit:'s text, a keypad key's (sym:KP_1,
    // sym:KP_Decimal, keypad:1), a symbol's of one (sym:space, sym:5), a tone's mark
    private fun typed(send: String?): String? = when {
        send == null -> null
        send.startsWith("sym:KP_") -> KEYPAD[send.removePrefix("sym:KP_")]
        send == "sym:space" -> " "
        send.startsWith("sym:") -> send.removePrefix("sym:").takeIf { it.length == 1 }
        send.startsWith("keypad:") -> send.removePrefix("keypad:")
        send.startsWith("action:tone-") -> TONES[send.removePrefix("action:tone-")]
        send.startsWith("commit:") -> send.removePrefix("commit:")
        // one past the BMP is no pad's key
        send.startsWith("code:") -> LayoutReader.codePoint(send)?.takeIf { it < SUPPLEMENTARY }?.toChar()?.toString()
        ':' in send && send.length > 1 -> null
        else -> send.takeIf { it.codePointCount() == 1 }
    }

    // 注音's tones 1–5 (LayoutReader's action:tone-n)
    private val TONES = mapOf("1" to "ˉ", "2" to "ˊ", "3" to "ˇ", "4" to "ˋ", "5" to "˙")

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

// what the eye takes a label's text for ([LayoutCheck]'s letters shown)
private object Sight {
    // one letter or digit, or 1 to 4 letters (a `;` among them: a 14/18 键 key's), as [seen] reads them: e, ｅ, е, é,
    // " e", qw, abc; not 123, .com, Emoji, nor a key's own name ([KEY_WORDS]: DEL)
    fun readsAsLetters(text: String): Boolean {
        val signs = seen(text)
        if (signs.size == 1) return signs[0] == 'L' || signs[0] == 'D'
        return signs.size in 2..4 && signs.all { it == 'L' || it == ';' } && 'L' in signs && text.trim().uppercase() !in KEY_WORDS
    }

    // a function key's name written as its label, not a letter key's letters
    private val KEY_WORDS = setOf("DEL", "ESC", "TAB", "FN", "ALT", "CTRL", "OK", "GO", "DONE", "NEXT", "SEND", "BACK", "EN")

    // [text]'s characters as the eye takes them ([sign]'s), what draws nothing left out: spaces, what Unicode says
    // draws nothing (Default_Ignorable_Code_Point: a zero-width space, a tag, a 한글 filler), the braille blank and the
    // null notehead, marks over a letter (e + ´, e + 𝅥: beyond the BMP by [ASTRAL_MARKS])
    private fun seen(text: String): List<Char> = codePoints(text).filterNot { cp ->
        cp in BLANKS || IGNORABLE.holds(cp) || ASTRAL_MARKS.holds(cp) ||
            (cp < 0x10000 && cp.toChar().let { it.isWhitespace() || it.category in UNSEEN })
    }.map(::sign)

    // [text]'s code points, a surrogate pair one
    private fun codePoints(text: String): List<Int> {
        val out = mutableListOf<Int>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val pair = c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()
            out += if (pair) 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00) else c.code
            i += if (pair) 2 else 1
        }
        return out
    }

    // L a letter, D a digit, ; itself, ? anything else. Fullwidth forms are their ASCII's (common code has no NFKC); a
    // letter or number of 汉字, 注音, kana or 한글 is no letter; beyond the BMP only the mathematical letters and digits
    // (𝐞), the boxed and circled capitals (🄰) and the regional indicators (🇪) are. The tone marks ˉ ˊ ˇ ˋ are letters
    // (Lm) on purpose: a tone key types anyway
    private fun sign(cp: Int): Char {
        if (cp >= 0x10000) {
            return when {
                cp in 0x1D7CE..0x1D7FF -> 'D'
                cp in 0x1D400..0x1D7CD || ASTRAL_LETTERS.holds(cp) -> 'L'
                else -> '?'
            }
        }
        val f = if (cp in 0xFF01..0xFF5E) (cp - 0xFEE0).toChar() else cp.toChar()
        return when {
            f == ';' -> ';'
            NO_LETTERS.holds(f.code) -> '?'
            f.isLetter() || f.code in 0x249C..0x24E9 -> 'L'
            f.category in NUMBERS -> 'D'
            else -> '?'
        }
    }

    // Unicode's Default_Ignorable_Code_Point (DerivedCoreProperties)
    private val IGNORABLE = intArrayOf(
        0x00AD, 0x00AD, 0x034F, 0x034F, 0x061C, 0x061C, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180B, 0x180F, 0x200B, 0x200F,
        0x202A, 0x202E, 0x2060, 0x206F, 0x3164, 0x3164, 0xFE00, 0xFE0F, 0xFEFF, 0xFEFF, 0xFFA0, 0xFFA0, 0xFFF0, 0xFFF8,
        0x1BCA0, 0x1BCA3, 0x1D173, 0x1D17A, 0xE0000, 0xE0FFF,
    )

    // whether [cp] is in one of the ranges these pairs bound (first, last), as IntRanges would but smaller in the engine
    private fun IntArray.holds(cp: Int): Boolean = (indices step 2).any { cp >= this[it] && cp <= this[it + 1] }

    // the braille blank and the musical null notehead, drawn as nothing
    private val BLANKS = setOf(0x2800, 0x1D159)

    // the stretches beyond the BMP holding every Mn, Mc and Me of Unicode 17, those under 256 apart merged (made by
    // a script over Python's unicodedata): what else lies between, those scripts' letters and the musical symbols,
    // is no letter of [sign]'s, and left out with a letter beside it the label is held the stricter
    private val ASTRAL_MARKS = intArrayOf(
        0x101FD, 0x1037A, 0x10A01, 0x10AE6, 0x10D24, 0x10D6D, 0x10EAB, 0x1172B, 0x1182C, 0x11D97, 0x11EF3, 0x11F5A,
        0x13440, 0x13455, 0x1611E, 0x1612F, 0x16AF0, 0x16B36, 0x16F4F, 0x16FF1, 0x1BC9D, 0x1BC9E, 0x1CF00, 0x1CF46,
        0x1D165, 0x1D244, 0x1DA00, 0x1DAAF, 0x1E000, 0x1E136, 0x1E2AE, 0x1E2EF, 0x1E4EC, 0x1E6F5, 0x1E8D0, 0x1E94A,
        0xE0100, 0xE01EF,
    )

    // the parenthesized, squared, circled and negative squared capitals (🄐 🄰 🅐 🅰) and the regional indicators
    private val ASTRAL_LETTERS = intArrayOf(
        0x1F110, 0x1F129, 0x1F12B, 0x1F12E, 0x1F130, 0x1F149, 0x1F150, 0x1F169, 0x1F170, 0x1F189, 0x1F1E6, 0x1F1FF,
    )

    private val UNSEEN = setOf(CharCategory.FORMAT, CharCategory.NON_SPACING_MARK, CharCategory.ENCLOSING_MARK)
    private val NUMBERS = setOf(CharCategory.DECIMAL_DIGIT_NUMBER, CharCategory.LETTER_NUMBER, CharCategory.OTHER_NUMBER)

    // 한글 jamo, CJK radicals and symbols (〇), kana, 注音, compatibility jamo, 汉字 (A and the unified), 한글
    // syllables, compatibility ideographs, halfwidth kana and jamo
    private val NO_LETTERS = intArrayOf(
        0x1100, 0x11FF, 0x2E80, 0x2FDF, 0x3000, 0x31FF, 0x3400, 0x4DBF, 0x4E00, 0x9FFF, 0xA960, 0xA97F, 0xAC00, 0xD7FF,
        0xF900, 0xFAFF, 0xFF65, 0xFFDC,
    )
}
