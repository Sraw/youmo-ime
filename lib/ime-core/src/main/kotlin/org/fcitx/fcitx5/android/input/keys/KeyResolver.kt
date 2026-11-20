/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.engine.learn.LearnClock
import org.fcitx.fcitx5.android.engine.phrase.TextVariables
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmClock
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.fcitx.fcitx5.android.input.keyboard.KeySwipe
import org.fcitx.fcitx5.android.input.scene.SceneMarks
import org.fcitx.fcitx5.android.input.shortcut.SwipeOrientation
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlot
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.keys.Commit
import org.fcitx.fcitx5.android.scene.keys.DefaultList
import org.fcitx.fcitx5.android.scene.keys.DefaultMarks
import org.fcitx.fcitx5.android.scene.keys.Item
import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant
import org.fcitx.fcitx5.android.scene.keys.NodeFacts

/** What a key's long press, swipes and marks depend on that changes while a keyboard shows. */
data class KeyState(
    val caps: Boolean = false,
    val roots: ((Char) -> String?)? = null,
    /** The punctuation the engine types now (`.` 。): an item typed as a key is shown as it would be typed. */
    val punctuation: Map<String, String> = emptyMap(),
    /** A table's keys labelled with its roots: a letter's corner the letter. */
    val letterCorners: Boolean = false,
    val ways: KeySwipe.Ways = KeySwipe.Ways.Both,
    val spots: KeyMarks.Spots = KeyMarks.Spots(KeyMarks.At.SymbolDefault, KeyMarks.At.ShortcutDefault),
    /** The minute where the user's lists or slots fill in variables ([KeyResolver.fillsIn]), else 0: a new minute is a new state. */
    val minute: Long = 0,
)

/** [focus] the first item, under the finger; [own] the row's middle (-1 with no row), where the row centres when the first is not in it. */
sealed interface KeyPopup {
    /** [templates]: by item, the variables text it was filled in from (null: none; empty: no item has any). */
    data class Items(
        val items: List<Item>,
        val labels: List<String>,
        val inRow: Int,
        val focus: Int,
        val own: Int = -1,
        val templates: List<String?> = emptyList(),
    ) : KeyPopup

    data class Roots(val text: String) : KeyPopup
}

/** As the settings' editor starts it: [first] null for none, [user] null when unset. */
data class KeyEdit(val items: List<String>, val first: String?, val user: UserList?) {
    companion object {
        /** `*` for the list's first item, `!` for none, else the item. */
        fun firstToStore(first: String?, items: List<String>): First = when (first) {
            null -> First.None
            UserList.firstOf(First.Default, items) -> First.Default
            else -> First.Literal(first)
        }
    }
}

/** [first]: what 长按首选 types, the corner shows and the long press opens on; [corner] as a screen reader says it. */
data class ResolvedKey(
    val id: KeyId,
    val popup: KeyPopup?,
    val first: Item?,
    val firstShown: String?,
    val swipe: KeySwipe.Key,
    val upMark: MarkContent,
    val downMark: MarkContent,
    val corner: String? = null,
    val firstTemplate: String? = null,
)

/** Every key from its defaults and the user's [lists] and [slots], one rule for all; [KeyState] laid on per call. Main thread only: caches unguarded. */
class KeyResolver(
    private val variant: KeyboardVariant,
    private val lists: KeyLists,
    private val slots: SwipeSlots,
    private val nodes: List<Pair<String, NodeFacts>>,
    private val clock: LearnClock = JvmClock,
) {
    /** Then [KeyState.minute] is asked for, each minute's values made anew. */
    val fillsIn: Boolean = lists.lists.values.any { l -> l.marks.values.any { it == ItemMark.Variables } } || slots.fillsIn

    private var madeMinute = 0L

    private val facts: Map<KeyId, NodeFacts> = nodes.associate { (name, f) -> KeyId.of(name) to f }

    // by the key, then Shift: fixed while the keyboard shows
    private val made = HashMap<Pair<KeyId, Boolean>, Made?>()

    // made again only for another state
    private val resolved = HashMap<KeyId, ResolvedKey>()
    private var resolvedFor: KeyState? = null

    private var marksFor: KeyState? = null
    private var marksMade: MarkSettings? = null

    private var readingsFor: KeyState? = null
    private var readingsMade: Map<String, String>? = null

    /** With no user setting (the editor's start, `KeyLists.with`'s). */
    fun defaultOf(id: KeyId, caps: Boolean = false): DefaultList? =
        KeyDefaults.of(id, variant, facts[id] ?: NodeFacts(), caps = caps && id.form == FULL)

    /** As the key pops up with no Shift, the user's own where set, items of variables as their text. */
    fun edit(id: KeyId): KeyEdit {
        val user = lists[id]
        if (user != null && !user.off) {
            val texts = user.items.filterNot { skipped(id, it) }
            return KeyEdit(texts, UserList.firstOf(user.first, texts), user)
        }
        val m = made.getOrPut(id to false) { make(id, false) }
        return KeyEdit(m?.items.orEmpty().map { it.text }, m?.first?.text, user)
    }

    /** Filled in again now from [template], so a value drawn before midnight types the date after. */
    fun output(item: Item, template: String?): String = template?.let { TextVariables.expand(it, clock.calendar()) } ?: item.text

    fun resolve(id: KeyId, state: KeyState): ResolvedKey {
        if (state != resolvedFor) {
            resolved.clear()
            resolvedFor = state
        }
        return resolved.getOrPut(id) { resolved(id, state) }
    }

    private fun resolved(id: KeyId, state: KeyState): ResolvedKey {
        if (state.minute != madeMinute) {
            made.keys.removeAll { (key, _) -> lists[key]?.marks?.containsValue(ItemMark.Variables) == true }
            madeMinute = state.minute
        }
        val m = made.getOrPut(id to (state.caps && id.form == FULL)) { make(id, state.caps && id.form == FULL) }
        val roots = state.roots?.takeIf { id.form == FULL }?.let { r -> id.key.singleOrNull()?.let(r) }?.takeIf { it.isNotEmpty() }
        val items = m?.items(state.punctuation).orEmpty()
        val popup = when {
            roots != null -> KeyPopup.Roots(roots)
            m == null -> null
            else -> KeyPopup.Items(items, items.map { DefaultMarks.shown(it, state.punctuation) }, m.inRow, m.focus, m.own, m.templates)
        }
        // a first KeyOutput cannot make is none for the corner and swipes; one the app cannot map still shows a corner, its swipe typing nothing
        val first = m?.first?.let { items.getOrNull(m.items.indexOf(it)) }?.takeIf { KeyOutput.of(it) != null }
        val shown = first?.let { DefaultMarks.shown(it, state.punctuation) }?.takeIf { it.isNotEmpty() }
        val swipe = KeySwipe.Key(SwipeSlots.typesAChar(id.name), slots.resolve(id.name, true), slots.resolve(id.name, false), hasFirst = first != null)
        // looking up roots, the corner is the letter the roots are of
        val letterCorner = roots != null || state.letterCorners && id.form == FULL && id.key.singleOrNull()?.isLetter() == true
        val corner = if (letterCorner) id.key else shown?.takeUnless { firstHidden(id) }
        val firstMark = corner?.let(SceneMarks::symbol) ?: MarkContent.Empty
        val firstTemplate = first?.let { m.templates.getOrNull(items.indexOf(it)) }
        return ResolvedKey(id, popup, first, shown, swipe, mark(swipe.up, firstMark), mark(swipe.down, firstMark), corner, firstTemplate)
    }

    /** Every drawn corner by key name, as Texts.alts reads them for a screen reader; made again only for another [state]. */
    fun readings(state: KeyState): Map<String, String> {
        if (state == readingsFor) readingsMade?.let { return it }
        val up = state.ways.up && state.spots.symbol != KeyMarks.At.None
        val down = state.ways.down && state.spots.shortcut != KeyMarks.At.None
        val made = nodes.mapNotNull { (name, _) ->
            val r = resolve(KeyId.of(name), state)
            r.corner?.takeIf { up && r.swipe.up == SwipeSlot.First || down && r.swipe.down == SwipeSlot.First }?.let { name to it }
        }.toMap()
        readingsFor = state
        readingsMade = made
        return made
    }

    /** [MarkSettings.charShortcuts] is only "no roots shown": SceneBoard, KeyCaps and SwipeFates lay the keyboard's rule over it. */
    fun marks(state: KeyState): MarkSettings {
        if (state == marksFor) marksMade?.let { return it }
        val marks = HashMap<String, MarkContent>()
        // the ways the user set: their marks kept over a built-in one they meet, and told on the settings page (SwipeFates)
        val user = HashSet<String>()
        for ((name, _) in nodes) {
            val r = resolve(KeyId.of(name), state)
            marks["$name:up"] = r.upMark
            marks["$name:down"] = r.downMark
            if (slots.isSet(name, true)) user += "$name:up"
            if (slots.isSet(name, false)) user += "$name:down"
        }
        return MarkSettings(
            upAt = state.spots.symbol,
            downAt = state.spots.shortcut,
            // a table's roots take a letter's room: its shortcut not drawn there
            charShortcuts = state.roots == null,
            upOn = state.ways.up,
            downOn = state.ways.down,
            firstUp = slots.orientation == SwipeOrientation.FirstUp,
            shortcuts = marks,
            user = user,
        ).also {
            marksFor = state
            marksMade = it
        }
    }

    private fun mark(slot: SwipeSlot, first: MarkContent): MarkContent = when (slot) {
        SwipeSlot.First -> first
        else -> SceneMarks.content(slot.shortcut, clock::calendar) ?: MarkContent.Empty
    }

    // a character key of `mark="false"` (注音's) draws and says no default first; a user list's still shows
    private fun firstHidden(id: KeyId): Boolean = facts[id]?.markOff == true && !id.isFunction && lists[id] == null

    /** [unmarked]: what an as-is item is where the engine would not convert it anyway (English, punctuation off). */
    private class Made(
        val items: List<Item>, val inRow: Int, val first: Item?, val focus: Int, val own: Int,
        val templates: List<String?> = emptyList(), val unmarked: Map<Int, Item> = emptyMap(),
    ) {
        /** One marked as it is stays itself only where its text is converted. */
        fun items(punctuation: Map<String, String>): List<Item> =
            if (unmarked.isEmpty()) items else items.mapIndexed { i, it -> unmarked[i]?.takeIf { _ -> it.text !in punctuation } ?: it }
    }

    private fun firstOf(user: UserList, texts: List<String>, all: List<Item?>, items: List<Item>): Item? {
        if (user.first == First.Default) return items.firstOrNull()
        val firstText = all.getOrNull(texts.indexOf(UserList.firstOf(user.first, texts)))?.text
        return items.firstOrNull { it.text == firstText }
    }

    private fun make(id: KeyId, caps: Boolean): Made? {
        val d = defaultOf(id, caps)
        val user = lists[id] ?: return d?.takeIf { it.items.isNotEmpty() }?.let(::madeOf)
        if (user.off) return null
        val row = d?.row.orEmpty()
        // stored lower case: Shift's list has its capitals, one of variables as written
        fun shown(t: String) = if (caps && user.marks[t] != ItemMark.Variables) upper(t) else t
        val texts = user.items.filterNot { skipped(id, it) }
        val commit = d?.symbols?.firstOrNull()?.commit?.takeIf { it == Commit.AsIs } ?: Commit.AsKey
        val now by lazy { clock.calendar() }
        // committed as the default's item of its text, else as its symbols are
        fun plain(s: String) = d?.items?.firstOrNull { it.text == s } ?: Item(s, commit)
        val all = texts.map { t ->
            val s = shown(t)
            when (user.marks[t]) {
                ItemMark.Variables -> TextVariables.expand(t, now).takeIf { it.isNotEmpty() }?.let { Item(it, Commit.AsIs) }
                ItemMark.AsIs -> Item(s, Commit.AsIs)
                null -> plain(s)
            }
        }
        // one text once (Shift's capitals of two, a value of variables the same as another item's): the first kept
        val kept = all.indices.filter { i -> all[i] != null }.distinctBy { all[it]!!.text }
        val items = kept.map { all[it]!! }
        if (items.isEmpty()) return null
        val templates = kept.map { i -> texts[i].takeIf { user.marks[it] == ItemMark.Variables } }.takeIf { t -> t.any { it != null } }.orEmpty()
        val unmarked = kept.withIndex().filter { (_, i) -> user.marks[texts[i]] == ItemMark.AsIs }.associate { (at, i) -> at to plain(all[i]!!.text) }
        val first = firstOf(user, texts, all, items)
        // the row: the default row's leading items, or a single ASCII letter or digit; none (-1), all as symbols
        val rowTexts = row.map { it.text }.toSet()
        val inRow = if (row.isEmpty()) 0 else items.takeWhile { it.text in rowTexts || own(it.text) }.size
        val focus = first?.let(items::indexOf)?.takeIf { it >= 0 } ?: 0
        if (inRow == 0) return Made(items, -1, first, focus, -1, templates, unmarked)
        return Made(items, inRow, first, focus, focus.takeIf { first != null && it < inRow } ?: (inRow / 2), templates, unmarked)
    }

    private fun madeOf(d: DefaultList): Made {
        val own = if (d.row.isEmpty()) -1 else d.focus.takeIf { it < d.row.size } ?: (d.row.size / 2)
        val focus = d.first?.let(d.items::indexOf)?.takeIf { it >= 0 } ?: d.focus.takeIf { it < d.items.size } ?: 0
        return Made(d.items, if (d.row.isEmpty()) -1 else d.row.size, d.first, focus, own)
    }

    // the one exception: the 18 keys' L group types `;` only where the scheme does, and shows `:` only there
    private fun skipped(id: KeyId, text: String): Boolean =
        text == ":" && variant.form == KeyboardVariant.Form.Grouped18 && !variant.semicolon && facts[id]?.letters?.contains(';') == true

    private fun own(text: String): Boolean = text.length == 1 && text[0].let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

    // Shift shows a lower case list in capitals; "ß".uppercase() is "SS": such an item kept as typed
    private fun upper(item: String): String = item.uppercase().takeIf { it.length == item.length } ?: item

    private companion object {
        const val FULL = "full"
    }
}
