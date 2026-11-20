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
import org.fcitx.fcitx5.android.scene.keys.Commit
import org.fcitx.fcitx5.android.scene.keys.DefaultMarks
import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.NodeFacts
import org.fcitx.fcitx5.android.scene.rules.ScreenShape
import org.fcitx.fcitx5.android.scene.theme.BlankKeys
import org.fcitx.fcitx5.android.scene.theme.Theme
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.scene.xml.Xml
import org.fcitx.fcitx5.android.scene.xml.XmlElement

/** Whether a `.layout` may be imported or saved: one check shared by the app and the layout maker; not [strict] (a pack's converted layout), only what the old app refused is an error. */
object LayoutCheck {
    data class Finding(val line: Int, val key: String, val args: List<String> = emptyList()) {
        override fun toString(): String = (if (line > 0) "line $line: " else "") + key + args.joinToString("") { " [$it]" }
    }

    /** What [check] found; [commits] lists only `commit:` texts the base's same-id keys do not type (the import shows what is new). */
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

    /** The app's boards and fragments as text, read lazily once and shared across threads. */
    class Sources(val layouts: Map<String, String>, val fragments: Map<String, String>) {
        private val read: Map<String, Layout?> by lazy { layouts.mapValues { (_, t) -> (LayoutReader.layout(t) as? Parsed.Ok)?.value } }
        private val readFragments: Map<String, Fragment?> by lazy { fragments.mapValues { (_, t) -> (LayoutReader.fragment(t) as? Parsed.Ok)?.value } }

        internal val names: Set<String> by lazy { appNames(this) }

        internal val taps: Map<KeyId, List<Set<String>>> by lazy { tapsByName(this) }

        internal fun layout(id: String): Layout? = read[id]

        fun fragment(id: String): Fragment? = readFragments[id]
    }

    val FAMILIES = listOf("letters", "grouped", "nine", "number", "symbols")

    /** The boards a .theme's layouts convert from: the layout page offers what the app's import converts. */
    val BASES = KeyData.LISTED.toList()

    val FAMILY_BASE = mapOf("letters" to "qwerty", "grouped" to "k18", "nine" to "nine", "number" to "number", "symbols" to "symbols")

    /** Prefixes of the app's key names (SlotDefaults, KeyCaps, KeyListsMigration rely on them): no own id or name may use one. */
    val NAME_PREFIXES = listOf("full", "g14", "g18", "t9", "zy9", "zyfull", "num", "fn")

    val LAYOUT_TARGETS = listOf("Text", "Number", "Symbol")

    const val BASE_VERSION = "0.6.0"

    /** Features newer than [BASE_VERSION] and the first app reading each: the maker writes the newest used as `min-app`. */
    val FEATURES = mapOf(
        "meta" to "0.6.1",
        "based-on" to "0.6.1",
        "pads" to "0.6.1",
        "rows-middle" to "0.6.1",
        "row-when" to "0.6.1",
    )

    /** Every [Finding] key: the app's strings and the page's table must word each (LayoutCheckTest enforces it). */
    val KEYS = listOf(
        // errors
        "read", "newer", "id-length", "id-built-in", "id-prefix", "name-length", "family", "based-on", "too-big",
        "too-many-keys", "commit-long", "too-many-commits", "label-long", "include", "layout-target", "base", "scene",
        "blank", "name-no-slash", "name-prefix", "lacks", "groups", "hidden-chars", "command",
        // warnings
        "no-way", "narrow", "commits", "theme-blank", "unnamed", "held", "held-letters", "held-popups", "gap-look", "pad-lacks", "bottom-left",
        "split-scaled",
    )

    const val MAX_ID = 32
    const val MAX_NAME = 24
    const val MAX_KEYS = 150
    const val MAX_COMMIT = 64
    const val MAX_COMMITS = 100
    const val MAX_LABEL = 16

    /** Builds every scene the layout is shown in (tens of ms on a phone): call it off the main thread. */
    fun check(text: String, sources: Sources, appVersion: String, theme: Theme? = null, strict: Boolean = true): Report {
        val errors = ArrayList<Finding>()
        val warnings = ArrayList<Finding>()
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
        hiddenNames(layout).forEach(newRule)
        family(layout, fragments).forEach(newRule)
        typedHidden(layout).forEach(newRule)
        base?.let { b -> commands(layout, b, baseFragments(b, sources), sources).forEach(newRule) }
        val ims = ims(layout, sources)
        val shown = scenes(layout, fragments, ims, pads)
        shown.errors.forEach(newRule)
        val commits = newCommits(layout, base, base?.let { baseFragments(it, sources) }.orEmpty(), sources.names)
        warnings += told(layout, fragments, commits, theme)
        base?.let { b -> unnamed(layout, b, baseFragments(b, sources)).forEach { warnings += it } }
        // names are looked up by the layout's own based-on, as its styles are
        warnings += heldData(layout, fragments, sources, layout.basedOn ?: baseId(layout, sources))
        shown.padLacks.forEach { warnings += it }
        warnings += ScaledRows.find(layout, fragments, scenes(layout, ims, pads))
        if (strict) {
            gapLooks(source).forEach { warnings += it }
            shown.bottomLeft?.let { warnings += it }
        }
        return Report(errors, warnings, layout.family, ims, pads, commits, minApp)
    }

    private fun includes(layout: Layout, sources: Sources, errors: MutableList<Finding>): Map<String, Fragment> {
        val fragments = HashMap<String, Fragment>()
        for (inc in layout.parts.filterIsInstance<Include>()) {
            val f = sources.fragment(inc.src)
            if (f == null) errors += Finding(inc.line, "include", listOf(inc.src)) else fragments[inc.src] = f
        }
        return fragments
    }

    private fun targets(layout: Layout): List<Finding> =
        actions(layout).filter { it.second.startsWith("layout:") && it.second.removePrefix("layout:") !in LAYOUT_TARGETS }
            .map { Finding(it.first, "layout-target", listOf(it.second)) }

    /** The app board [layout] is held to and drawn as where it cannot be; its keys' data and styles follow its own `based-on`. */
    fun baseId(layout: Layout, sources: Sources): String? =
        layout.basedOn?.let { b -> if (sources.layout(b)?.family == layout.family) b else FAMILY_BASE[layout.family] }
            ?: layout.id.takeIf { it in sources.layouts } ?: FAMILY_BASE[layout.family]

    private fun told(layout: Layout, fragments: Map<String, Fragment>, commits: List<String>, theme: Theme?): List<Finding> {
        val out = ArrayList<Finding>()
        // its includes' keys too (the grouped boards' 123 and 符 are grouped-bottom's)
        val ways = cells(layout, fragments).filterIsInstance<Key>().flatMap { k -> listOf(k.attrs) + k.variants.map { it.attrs } }.mapNotNull { it.actions["send"] }
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

    /** Why [layout] drops something of [base] that must stay typeable; [digitsById] false: digits held by what they type. */
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

    fun minApp(layout: Layout): String =
        features(layout).mapNotNull { FEATURES[it] }.fold(BASE_VERSION) { v, f -> if (ThemeReader.newer(f, v)) f else v }

    fun features(layout: Layout): Set<String> = buildSet {
        if (layout.meta != null) add("meta")
        if (layout.basedOn != null) add("based-on")
        if (layout.pads != null) add("pads")
        val middle = layout.parts.filterIsInstance<Row>().any { row ->
            row.cells.withIndex().any { (i, c) -> i != 0 && i != row.cells.lastIndex && rowsOf(c, row, layout) > 1 }
        }
        if (middle) add("rows-middle")
        // a key id twice where one copy is conditional only by its row's when= (the old app took it for the same key twice)
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

    private fun identity(layout: Layout, sources: Sources): List<Finding> = buildList {
        if (layout.id.length > MAX_ID) add(Finding(0, "id-length", listOf(layout.id, MAX_ID.toString())))
        if (layout.id in sources.layouts || layout.id in sources.fragments) add(Finding(0, "id-built-in", listOf(layout.id)))
        if (layout.id in NAME_PREFIXES) add(Finding(0, "id-prefix", listOf(layout.id)))
        if (layout.name.isBlank() || layout.name.length > MAX_NAME) add(Finding(0, "name-length", listOf(layout.name, MAX_NAME.toString())))
        if (layout.family !in FAMILIES) add(Finding(0, "family", listOf(layout.family)))
        if (layout.basedOn == null || layout.basedOn !in BASES) add(Finding(0, "based-on", listOf(layout.basedOn.orEmpty())))
        // shown in the list and dialogs: no direction mark turning it around, nothing unseen
        val m = layout.meta
        val shown = listOf("name" to layout.name, "author" to m?.author, "version" to m?.version, "license" to m?.license, "description" to m?.description)
        shown.filter { (_, v) -> v != null && v.any(::hidden) }.forEach { (field, _) -> add(Finding(0, "hidden-chars", listOf(field))) }
    }

    /** Whether [c] may not appear in a layout's name or `<meta>`: controls, format characters, line or paragraph separators. */
    fun hidden(c: Char): Boolean = c.category in HIDDEN

    fun plain(text: String): String = text.filterNot(::hidden)

    private val HIDDEN = setOf(CharCategory.CONTROL, CharCategory.FORMAT, CharCategory.LINE_SEPARATOR, CharCategory.PARAGRAPH_SEPARATOR)

    private fun limits(layout: Layout): List<Finding> = buildList {
        val keys = ownCells(layout).filterIsInstance<Key>()
        if (keys.size > MAX_KEYS) add(Finding(0, "too-many-keys", listOf(keys.size.toString(), MAX_KEYS.toString())))
        val commits = actions(layout).filter { it.second.startsWith("commit:") }
        commits.filter { it.second.length - "commit:".length > MAX_COMMIT }.forEach { add(Finding(it.first, "commit-long", listOf(MAX_COMMIT.toString()))) }
        alts(layout).filter { (firstTyped(it.second)?.length ?: 0) > MAX_COMMIT }.forEach { add(Finding(it.first, "commit-long", listOf(MAX_COMMIT.toString()))) }
        if (commits.size > MAX_COMMITS) add(Finding(0, "too-many-commits", listOf(commits.size.toString(), MAX_COMMITS.toString())))
        for (k in keys) {
            for (a in listOf(k.attrs) + k.variants.map { it.attrs }) {
                val label = (a.label as? Text.Literal)?.text ?: continue
                if (label.length > MAX_LABEL) add(Finding(k.line, "label-long", listOf(k.id, MAX_LABEL.toString())))
            }
        }
    }

    // <defaults>' and rows' actions counted once however many keys take them
    private fun actions(layout: Layout): List<Pair<Int, String>> = ownCells(layout).filterIsInstance<Key>().flatMap { k ->
        actions(k).map { k.line to it }
    } + defaultsActions(layout).map { it.second to it.first }

    // a list item without its own send= types its text (SceneBehavior's Listed): checked as a commit:
    private fun actions(k: Key): List<String> =
        (listOf(k.attrs) + k.variants.map { it.attrs }).flatMap { written(it) } + listed(k)

    private fun listed(k: Key): List<String> = k.popup?.items.orEmpty().flatMap { listOfNotNull("commit:" + it.first, it.second) } + k.menu.map { it.action }

    private fun defaultsActions(layout: Layout): List<Pair<String, Int>> =
        written(layout.defaults).map { it to 0 } + layout.parts.filterIsInstance<Row>().flatMap { r -> written(r.defaults).map { it to r.line } }

    // a key of a base key's id typing that key's text is the base's; a literal alt= with no swipe-up= types as a first, so counts
    private fun newCommits(layout: Layout, base: Layout?, baseFragments: Map<String, Fragment>, app: Set<String>): List<String> {
        fun texts(actions: List<String>) = actions.filter { it.startsWith("commit:") }.map { it.removePrefix("commit:") }
        fun typed(k: Key, under: KeyAttrs): List<String> {
            val a = k.attrs.over(under)
            val each = (listOf(k.attrs) + k.variants.map { it.attrs }).flatMap { v -> if ((v.shortcut ?: a.shortcut) in app) listOfNotNull(v.actions["send"]) else written(v) }
            if (a.shortcut in app) return each
            return each + listed(k) + listOfNotNull((a.alt as? Text.Literal)?.text?.takeIf { a.actions["swipe-up"] == null }?.let(::firstTyped)?.let { "commit:$it" })
        }
        val theirs = base?.let { b -> cells(b, baseFragments).filterIsInstance<Key>().flatMap { k -> (texts(actions(k)) + alts(k).mapNotNull(::firstTyped)).map { k.id to it } } }.orEmpty().toSet()
        return layout.parts.filterIsInstance<Row>().flatMap { r -> r.cells.filterIsInstance<Key>().flatMap { k -> texts(typed(k, r.defaults.over(layout.defaults))).filter { (k.id to it) !in theirs } } } +
            texts(defaultsActions(layout).map { it.first })
    }

    private fun firstTyped(alt: String): String? = KeyDefaults.itemOf(alt).takeIf { it.commit !is Commit.Action }?.text?.ifEmpty { null }

    private fun alts(k: Key): List<String> = (listOf(k.attrs) + k.variants.map { it.attrs }).mapNotNull { (it.alt as? Text.Literal)?.text }

    private fun alts(layout: Layout): List<Pair<Int, String>> =
        ownCells(layout).filterIsInstance<Key>().flatMap { k -> alts(k).map { k.line to it } } + defaultsAlts(layout).map { it.second to it.first }

    private fun defaultsAlts(layout: Layout): List<Pair<String, Int>> = (listOf(layout.defaults to 0) + layout.parts.filterIsInstance<Row>().map { it.defaults to it.line })
        .mapNotNull { (d, line) -> (d.alt as? Text.Literal)?.let { it.text to line } }

    private fun written(a: KeyAttrs): Collection<String> = a.actions.filterKeys { it in ACTION_ATTRS }.values

    // the rest of KeyAttrs.actions are a key's ways (swipe-axis, repeat, preview), not actions
    private val ACTION_ATTRS = setOf("send", "swipe-up", "swipe-down", "long-press", "double-tap")

    // covers names a <defaults> gives every key under it too (a pack's row named so slipped by once)
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

    /** The pad a `lacks` finding names: a restore refuses such a board, and a pack's is made only for the pads it does not lack. */
    fun padLacking(finding: Finding): String? =
        finding.takeIf { it.key == "lacks" }?.args?.firstOrNull()?.takeIf { it.startsWith(PAD_LACKING) }
            ?.removePrefix(PAD_LACKING)?.substringBefore(':')

    internal fun nameProblem(name: String, sources: Sources): String? {
        if (name in sources.names) return null
        val id = KeyId(name)
        return when {
            id.isFunction -> "name-no-slash"
            id.form in NAME_PREFIXES -> "name-prefix"
            else -> null
        }
    }

    // as the app reads a file: byte limit, then the BOM dropped (PackLayouts.read)
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

    /** What stops a stored user layout from being drawn: the file is not trusted, so the scene-free errors are asked again. */
    fun loaded(layout: Layout, sources: Sources, appVersion: String): List<Finding> {
        val errors = ArrayList<Finding>()
        layout.meta?.minApp?.let { if (ThemeReader.newer(it, appVersion)) errors += Finding(0, "newer", listOf(it)) }
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

    // a direction mark reverses text in another app; line breaks and tabs too, since Return types those
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
        // KeyDefaults.itemOf decodes a `code:U+202E`, so judge what it types as a commit:
        fun typed(alt: String) = firstTyped(alt)?.let { "commit:$it" }
        val keys = ownCells(layout).filterIsInstance<Key>().flatMap { k ->
            (actions(k) + alts(k).mapNotNull(::typed)).filter(::hides).distinct().map { Finding(k.line, "hidden-chars", listOf(plain(k.id))) }
        }
        return keys + (defaultsActions(layout) + defaultsAlts(layout).mapNotNull { (alt, line) -> typed(alt)?.let { it to line } }).filter { hides(it.first) }
            .map { Finding(it.second, "hidden-chars", listOf("defaults")) }
    }

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

    // Kotlin's common Char has no category past the BMP
    private fun hiddenCode(cp: Int): Boolean =
        if (cp < SUPPLEMENTARY) hidden(cp.toChar()) else HIDDEN_ASTRAL.any { cp in it }

    private val HIDDEN_ASTRAL = listOf(0x110BD..0x110BD, 0x110CD..0x110CD, 0x13430..0x1343F, 0x1BCA0..0x1BCA3, 0x1D173..0x1D17A, 0xE0001..0xE0001, 0xE0020..0xE007F)

    /** An allow-list (a deny-list let the next shape through): a typing key may not gain commands or app function names its base's key of the same id lacks. */
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
            val copied = taken.filter { copies(k, a, KeyId.of(it)) }
            val taps = if (copied.isEmpty()) emptySet() else states(k, a).mapNotNull { it.actions["send"] }.toSet()
            // a base function key's id made a letter keeps none of its commands (the base's 回车 shown as e)
            val kept = if (k.id in letters) theirs[k.id].orEmpty() else emptySet()
            val commands = if (!held) emptyList() else actions(k).filter(::isCommand).filter { it !in kept && it !in taps }.distinct()
            val names = taken.filter { !(KeyId.of(it) in theirNames[k.id].orEmpty() && (k.id in letters || !held)) && it !in copied }
            commands.map { Finding(k.line, "command", listOf(k.id, it)) } + names.map { Finding(k.line, "command", listOf(k.id, "shortcut=$it")) }
        } + defaultsActions(layout).filter { isCommand(it.first) }.map { Finding(it.second, "command", listOf("defaults", it.first)) }
    }

    private fun keysOf(layout: Layout, fragments: Map<String, Fragment>): List<Pair<Key, KeyAttrs>> = layout.parts.flatMap { part ->
        val rows = when (part) {
            is Row -> listOf(part to layout.defaults)
            is Include -> fragments[part.src]?.let { f -> f.rows.map { it to f.defaults.over(layout.defaults) } }.orEmpty()
        }
        rows.flatMap { (row, defaults) -> row.cells.filterIsInstance<Key>().map { it to it.attrs.over(row.defaults.over(defaults)) } }
    }

    private fun tapsByName(sources: Sources): Map<KeyId, List<Set<String>>> = sources.layouts.keys.mapNotNull(sources::layout).flatMap { b ->
        keysOf(b, baseFragments(b, sources)).flatMap { (k, a) ->
            val states = states(k, a)
            val taps = states.mapNotNull { it.actions["send"] }.toSet()
            states.mapNotNull { it.shortcut?.let(KeyId::of) }.distinct().filter { it.isFunction }.map { it to taps }
        }
    }.groupBy({ it.first }, { it.second })

    private fun states(k: Key, a: KeyAttrs): List<KeyAttrs> = listOf(a) + k.variants.map { it.attrs.over(a) }

    private fun typing(k: Key, a: KeyAttrs): Boolean = k.letters != null || states(k, a).any { types(it.actions["send"]) || showsLetter(it.label) }

    // a letter binding, or text the eye takes for letters ([Sight.readsAsLetters])
    private fun showsLetter(label: Text?): Boolean = when (label) {
        is Text.Literal -> Sight.readsAsLetters(label.text)
        is Text.Binding -> label.name in Text.WITH_LETTER || label.name in Text.WITH_NUMBER || label.name == Text.GROUP ||
            (label.name == Text.PUNCT && Sight.readsAsLetters(label.arg.orEmpty()))
        null -> false
    }

    private fun types(send: String?): Boolean =
        typed(send)?.isNotEmpty() == true || send?.startsWith("code:") == true || send?.startsWith("action:zhuyin") == true

    private fun isCommand(a: String): Boolean =
        a.startsWith("shortcut:") || a in COMMANDS || (a.startsWith("sym:") && !types(a))

    // every bare word of LayoutReader's vocabulary but Shift, so a word added there is a command here too
    private val COMMANDS = LayoutReader.WORDS.toSet()

    // the rest is told once the app can read, build or look through it
    private val STOPS = setOf("newer", "include", "too-many-keys")

    private fun held(layout: Layout, fragments: Map<String, Fragment>, sources: Sources, strict: Boolean, errors: MutableList<Finding>): Layout? {
        val baseId = baseId(layout, sources)
        val base = baseId?.let(sources::layout)
        when {
            strict && layout.family !in FAMILIES -> Unit
            base == null -> errors += Finding(0, "base", listOf("the app has no ${baseId ?: layout.family} to hold it to"))
            else -> {
                val digitsById = !(strict && layout.family == "number")
                kept(layout, fragments, base, baseFragments(base, sources), digitsById)?.let { errors += Finding(0, "base", listOf(it)) }
            }
        }
        return base
    }

    private fun appNames(sources: Sources): Set<String> = buildSet {
        for (id in sources.layouts.keys) {
            val l = sources.layout(id) ?: continue
            val fragments = l.parts.filterIsInstance<Include>().mapNotNull { inc -> sources.fragment(inc.src)?.let { inc.src to it } }.toMap()
            DefaultMarks.nodes(l, fragments).forEach { add(it.first) }
        }
    }

    private fun family(layout: Layout, fragments: Map<String, Fragment>): List<Finding> {
        val cells = cells(layout, fragments)
        val keys = cells.filterIsInstance<Key>()
        fun lacks(what: String) = Finding(0, "lacks", listOf(what))
        val roles = keys.mapNotNull { it.attrs.role }.toSet()
        return when (layout.family) {
            "letters" -> listOfNotNull(lacks("space").takeIf { "space" !in roles }, lacks("shift").takeIf { "shift" !in roles })
            "grouped" -> listOfNotNull(
                Groups.problem(groups(layout, fragments))?.let { Finding(0, "groups", listOf(it)) },
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

    private fun groups(layout: Layout, fragments: Map<String, Fragment>): List<String> =
        cells(layout, fragments).filterIsInstance<Key>().mapNotNull { k -> k.letters?.rows?.joinToString(" ") }

    // 双拼 on a grouped board only with 14 or 18 key groups, each once, as NineKeyLetters.custom takes them
    private fun ims(layout: Layout, sources: Sources): List<String> = when (layout.family) {
        "letters" -> listOf("pinyin", "shuangpin", "english", "table")
        "nine" -> listOf("pinyin", "shuangpin", "english")
        "grouped" -> {
            fun of(l: Layout) = groups(l, baseFragments(l, sources)).map { it.replace(" ", "") }.sorted()
            val mine = of(layout)
            val app = listOf("k14", "k18").mapNotNull { id -> sources.layout(id)?.let(::of) }
            if (mine in app) listOf("pinyin", "shuangpin") else listOf("pinyin")
        }
        else -> emptyList()
    }

    private class Shown(val errors: List<Finding>, val padLacks: List<Finding>, val bottomLeft: Finding?)

    data class Screen(val widthDp: Int, val turned: Boolean, val tallDp: Int) {
        val large: Boolean get() = minOf(widthDp, tallDp) >= LARGE_SHORT_SIDE_DP
    }

    val SCREENS = listOf(
        Screen(360, false, 780), Screen(412, false, 915), Screen(800, true, 360), Screen(600, false, 841), Screen(344, false, 882),
    )

    /** The numbers are ime-core's (KeyboardSizes, KeyboardHeightLevels), which this module cannot read: LayoutCheckHeightsTest keeps them in step. */
    fun keysDp(s: Screen, compact: Boolean): Int = when {
        !ScreenShape.landscape(s.turned, s.widthDp) -> s.tallDp * (if (compact) COMPACT_PERCENT else NORMAL_PERCENT) / PERCENT
        else -> ROWS * if (s.large) (if (compact) LARGE_COMPACT_ROW_DP else LARGE_ROW_DP) else if (compact) COMPACT_ROW_DP else ROW_DP
    }

    /** The lowest the keys may get turned (KeyboardHeightCap): checked here too, so marks are seen at a small phone's height. */
    const val LOWEST_DP = 4 * 36

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

    class Scene(val name: String, val env: Env)

    /** Every [SCREENS] × normal and compact (× split), every field, 地球键 on and off, each of [ims] with and without composing; a number board per pad. */
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

    private val TEXTS = Texts(imeName = "拼音", returnLabel = "换行")

    private val PLAIN = Theme("check", false, "1", emptyMap(), emptyList(), emptyMap(), emptyMap(), null)

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
            if (bottomLeft == null && (layout.family == "number" || layout.family == "symbols")) bottomLeft = bottomLeft(layout.family, rows.lastOrNull()?.items.orEmpty())
        }
        return Shown(errors.values.toList(), padLacks, bottomLeft)
    }

    // a phone's `+ * #`, a date's `- / :`, the full pad's `-` and `.`, as every number field has it (warnings)
    private fun padLacks(pad: String, keys: List<ExpandedKey>): List<Pair<Boolean, Finding>> {
        val types = keys.mapNotNull { typed(it.attrs.actions["send"]) }.toSet()
        val roles = keys.mapNotNull { it.attrs.role }.toSet()
        val need = ArrayList<String>()
        need += (0..9).map { it.toString() }.filter { it !in types }
        listOf("backspace", "enter", "back").filter { it !in roles }.forEach { need += it }
        val told = when (pad) {
            "phone" -> listOf("+", "*", "#")
            "datetime" -> listOf("-", "/", ":")
            "full" -> listOf("-", ".")
            else -> emptyList()
        }.filter { it !in types }
        return listOfNotNull(
            need.takeIf { it.isNotEmpty() }?.let { true to Finding(0, "lacks", listOf("$PAD_LACKING$pad: " + it.joinToString(" "))) },
            told.takeIf { it.isNotEmpty() }?.let { false to Finding(0, "pad-lacks", listOf(pad, it.joinToString(" "))) },
        )
    }

    private fun typed(send: String?): String? = when {
        send == null -> null
        send.startsWith("sym:KP_") -> KEYPAD[send.removePrefix("sym:KP_")]
        send == "sym:space" -> " "
        send.startsWith("sym:") -> send.removePrefix("sym:").takeIf { it.length == 1 }
        send.startsWith("keypad:") -> send.removePrefix("keypad:")
        send.startsWith("action:tone-") -> TONES[send.removePrefix("action:tone-")]
        send.startsWith("commit:") -> send.removePrefix("commit:")
        send.startsWith("code:") -> LayoutReader.codePoint(send)?.takeIf { it < SUPPLEMENTARY }?.toChar()?.toString()
        ':' in send && send.length > 1 -> null
        else -> send.takeIf { it.codePointCount() == 1 }
    }

    private val TONES = mapOf("1" to "ˉ", "2" to "ˊ", "3" to "ˇ", "4" to "ˋ", "5" to "˙")

    private const val SUPPLEMENTARY = 0x10000

    private val KEYPAD = (0..9).associate { it.toString() to it.toString() } + mapOf(
        "Decimal" to ".", "Subtract" to "-", "Add" to "+", "Multiply" to "*", "Divide" to "/", "Separator" to ",", "Equal" to "=",
    )

    private fun bottomLeft(family: String, items: List<Item>): Finding? {
        val want = if (family == "number") setOf("symbols", "back") else setOf("back", "numbers")
        val first = items.take(2).map { (it as? ExpandedKey)?.attrs?.role }.toSet()
        return if (first == want) null else Finding(0, "bottom-left", listOf(want.joinToString(" ")))
    }

    // half a letter's: a tenth of a 10-column board's width
    private fun narrow(layout: Layout): List<Finding> = layout.parts.filterIsInstance<Row>().flatMap { row ->
        row.cells.filterIsInstance<Key>().mapNotNull { k ->
            val w = k.attrs.over(row.defaults.over(layout.defaults)).width as? Width.Units ?: return@mapNotNull null
            if (w.units < layout.columns / NARROW) Finding(k.line, "narrow", listOf(k.id)) else null
        }
    }

    private const val NARROW = 20

    // allowed where its base's key of the same id has no name either (the number pad's + - * /)
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

    // read but no longer drawn
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

    internal fun baseFragments(base: Layout, sources: Sources): Map<String, Fragment> =
        base.parts.filterIsInstance<Include>().mapNotNull { inc -> sources.fragment(inc.src)?.let { inc.src to it } }.toMap()

    private fun cells(layout: Layout, fragments: Map<String, Fragment>): List<Cell> = layout.parts.flatMap { part ->
        when (part) {
            is Row -> part.cells
            is Include -> fragments[part.src]?.rows.orEmpty().flatMap { it.cells }
        }
    }

    private fun ownCells(layout: Layout): List<Cell> = layout.parts.filterIsInstance<Row>().flatMap { it.cells }
}

private object Sight {
    // one letter or digit, or 1 to 4 letters, as [seen] reads them: e, ｅ, е, é, qw; not 123, .com, Emoji, nor DEL
    fun readsAsLetters(text: String): Boolean {
        val signs = seen(text)
        if (signs.size == 1) return signs[0] == 'L' || signs[0] == 'D'
        return signs.size in 2..4 && signs.all { it == 'L' || it == ';' } && 'L' in signs && text.trim().uppercase() !in KEY_WORDS
    }

    private val KEY_WORDS = setOf("DEL", "ESC", "TAB", "FN", "ALT", "CTRL", "OK", "GO", "DONE", "NEXT", "SEND", "BACK", "EN")

    private fun seen(text: String): List<Char> = codePoints(text).filterNot { cp ->
        cp in BLANKS || IGNORABLE.holds(cp) || ASTRAL_MARKS.holds(cp) && !ASTRAL_LETTERS.holds(cp) ||
            (cp < 0x10000 && cp.toChar().let { it.isWhitespace() || it.category in UNSEEN })
    }.map(::sign)

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

    // fullwidth forms count as ASCII (common code has no NFKC); the tone marks ˉ ˊ ˇ ˋ are letters on purpose
    private fun sign(cp: Int): Char {
        if (cp >= 0x10000) {
            return when {
                cp in 0x1D7CE..0x1D7FF || cp in 0x1FBF0..0x1FBF9 -> 'D'
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

    private val IGNORABLE = intArrayOf(
        0x00AD, 0x00AD, 0x034F, 0x034F, 0x061C, 0x061C, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180B, 0x180F, 0x200B, 0x200F,
        0x202A, 0x202E, 0x2060, 0x206F, 0x3164, 0x3164, 0xFE00, 0xFE0F, 0xFEFF, 0xFEFF, 0xFFA0, 0xFFA0, 0xFFF0, 0xFFF8,
        0x1BCA0, 0x1BCA3, 0x1D173, 0x1D17A, 0xE0000, 0xE0FFF,
    )

    // pairs of (first, last): smaller in the engine than IntRanges
    private fun IntArray.holds(cp: Int): Boolean = (indices step 2).any { cp >= this[it] && cp <= this[it + 1] }

    private val BLANKS = setOf(0x2800, 0x1D159)

    // generated from Python's unicodedata (Unicode 17), stretches under 256 apart merged: only makes the check stricter
    private val ASTRAL_MARKS = intArrayOf(
        0x101FD, 0x1037A, 0x10A01, 0x10AE6, 0x10D24, 0x10D6D, 0x10EAB, 0x1172B, 0x1182C, 0x11D97, 0x11EF3, 0x11F5A,
        0x13440, 0x13455, 0x1611E, 0x1612F, 0x16AF0, 0x16B36, 0x16F4F, 0x16FF1, 0x1BC9D, 0x1BC9E, 0x1CF00, 0x1CF46,
        0x1D165, 0x1D244, 0x1DA00, 0x1DAAF, 0x1E000, 0x1E136, 0x1E2AE, 0x1E2EF, 0x1E4EC, 0x1E6F5, 0x1E8D0, 0x1E94A,
        0xE0100, 0xE01EF,
    )

    private val ASTRAL_LETTERS = intArrayOf(
        0x1D18F, 0x1D194, 0x1F110, 0x1F12E, 0x1F130, 0x1F149, 0x1F150, 0x1F169, 0x1F170, 0x1F18A, 0x1F1E6, 0x1F1FF,
    )

    private val UNSEEN = setOf(CharCategory.FORMAT, CharCategory.NON_SPACING_MARK, CharCategory.ENCLOSING_MARK)
    private val NUMBERS = setOf(CharCategory.DECIMAL_DIGIT_NUMBER, CharCategory.LETTER_NUMBER, CharCategory.OTHER_NUMBER)

    private val NO_LETTERS = intArrayOf(
        0x1100, 0x11FF, 0x2E80, 0x2FDF, 0x3000, 0x31FF, 0x3400, 0x4DBF, 0x4E00, 0x9FFF, 0xA960, 0xA97F, 0xAC00, 0xD7FF,
        0xF900, 0xFAFF, 0xFF65, 0xFFDC,
    )
}

/** A key of an app name writing a long press, corner, swipe or letters other than the app key's: ignored, so the file is told. */
// outside LayoutCheck for detekt's LargeClass
private fun heldData(layout: Layout, fragments: Map<String, Fragment>, sources: LayoutCheck.Sources, base: String?): List<LayoutCheck.Finding> {
    val shape = LayoutCheck.baseId(layout, sources) ?: base ?: layout.id
    val ignored = KeyData.of(layout.id, layout, fragments, KeyData.Mode.Own, base, shape = shape).ignored.filter { it.file == layout.id }
    val told = ignored.filter { it.line > 0 }.map { Triple(it.line, "held", it.key) }.toMutableSet()
    val app = appKeys(sources, base)
    // letters of its own on an app-named key are told apart (giving it its own name is the way)
    for (row in layout.parts.filterIsInstance<Row>()) for (k in row.cells.filterIsInstance<Key>()) {
        val name = k.attrs.over(row.defaults.over(layout.defaults)).shortcut
        val letters = name?.let(app)?.letters
        if (k.letters != null && letters != null && k.letters.group != letters) told += Triple(k.line, "held-letters", name)
    }
    val held = told.sortedWith(compareBy({ it.first }, { it.second })).map { (line, what, key) -> LayoutCheck.Finding(line, what, listOf(key, (DefaultMarks.appBoard(key, base) ?: base).orEmpty())) }
    return held + listOfNotNull(base?.takeIf { ignored.any { it.what == "popups" } }?.let { LayoutCheck.Finding(0, "held-popups", listOf(it)) })
}

/** The import's findings print ids and the key page shows names, so the layout name's rule applies; not asked by `loaded`. */
private fun hiddenNames(layout: Layout): List<LayoutCheck.Finding> {
    val rows = layout.parts.filterIsInstance<Row>()
    val keys = rows.flatMap { it.cells }.filterIsInstance<Key>()
        .flatMap { k -> (listOf(k.id) + (listOf(k.attrs) + k.variants.map { it.attrs }).mapNotNull { it.shortcut }).map { k.line to it } }
    val defaults = listOfNotNull(layout.defaults.shortcut?.let { 0 to it }) + rows.mapNotNull { r -> r.defaults.shortcut?.let { r.line to it } }
    return (defaults + keys).filter { (_, name) -> name.any(LayoutCheck::hidden) }.distinct()
        .map { (line, name) -> LayoutCheck.Finding(line, "hidden-chars", listOf(LayoutCheck.plain(name))) }
}

/** The app's key of a name as KeyData finds it for a board of [base]; shared by LayoutCheck's held and PackLayouts' renaming. */
internal fun appKeys(sources: LayoutCheck.Sources, base: String?): (String) -> NodeFacts? {
    val boards = HashMap<String, Map<String, NodeFacts>>()
    return { name ->
        DefaultMarks.appBoard(name, base)?.let { board ->
            boards.getOrPut(board) { sources.layout(board)?.let { b -> DefaultMarks.nodes(b, LayoutCheck.baseFragments(b, sources)).toMap() }.orEmpty() }[name]
        }
    }
}
