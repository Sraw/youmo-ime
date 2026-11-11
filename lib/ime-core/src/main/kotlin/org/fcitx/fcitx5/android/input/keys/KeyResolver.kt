/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.input.keyboard.KeyMarks
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

/** What a key's long press, swipes and marks depend on that changes while a keyboard shows (§1.2). */
data class KeyState(
    /** Shift on: the full keyboard's letters' capitals' lists. */
    val caps: Boolean = false,
    /** A table looking up roots: a letter's roots, its long press showing them and its corner the letter. */
    val roots: ((Char) -> String?)? = null,
    /** The punctuation the engine types now (`.` 。): an item typed as a key shown as it would be typed. */
    val punctuation: Map<String, String> = emptyMap(),
    /** A table's keys labelled with its roots or their names: a letter's corner the letter (its long press as ever). */
    val letterCorners: Boolean = false,
    val ways: KeySwipe.Ways = KeySwipe.Ways.Both,
    val spots: KeyMarks.Spots = KeyMarks.Spots(KeyMarks.At.SymbolDefault, KeyMarks.At.ShortcutDefault),
)

/** A key's long press as it opens: its [items], the first [inRow] of them the row, [focus] under the finger. */
sealed interface KeyPopup {
    data class Items(val items: List<Item>, val labels: List<String>, val inRow: Int, val focus: Int) : KeyPopup

    /** A table's roots of the letter, shown alone. */
    data class Roots(val text: String) : KeyPopup
}

/**
 * A key's list as the settings' editor starts it (060-keys-unify S5): its [row] (the keyboard's, never stored),
 * its [symbols], its [first]'s text (null: none), and what the user set of it, null for none.
 */
data class KeyEdit(val row: List<String>, val symbols: List<String>, val first: String?, val user: UserList?)

/**
 * A key as everything reads it: its long press ([popup], null with none), its [first] (what its swipe's
 * 长按首选 types, its corner shows, the long press opens on), [firstShown] as drawn, its two swipe slots
 * ([swipe]) and their marks.
 */
data class ResolvedKey(
    val id: KeyId,
    val popup: KeyPopup?,
    val first: Item?,
    val firstShown: String?,
    val swipe: KeySwipe.Key,
    val upMark: MarkContent,
    val downMark: MarkContent,
)

/**
 * Every key of a keyboard as its defaults ([KeyDefaults], by the [variant] and the layout's [nodes]) and the
 * user's [lists] and [slots] make it (060-keys-unify §3): one rule for every key on every keyboard. A key's
 * list is made once; what [KeyState] changes (capitals, the punctuation shown, roots) is laid on it as asked.
 */
class KeyResolver(
    private val variant: KeyboardVariant,
    private val lists: KeyLists,
    private val slots: SwipeSlots,
    /** The layout's named keys ([DefaultMarks.nodes]): the layout's name of each and what its node says. */
    private val nodes: List<Pair<String, NodeFacts>>,
) {
    private val facts: Map<KeyId, NodeFacts> = nodes.associate { (name, f) -> KeyId.of(name) to f }

    // by the key, then Shift: the lists the user's are laid on are fixed while the keyboard shows
    private val made = HashMap<Pair<KeyId, Boolean>, Made?>()

    private var marksFor: KeyState? = null
    private var marksMade: MarkSettings? = null

    /** The default list [id] has here, with no setting of the user's (the editor's start, `KeyLists.with`'s). */
    fun defaultOf(id: KeyId, caps: Boolean = false): DefaultList? =
        KeyDefaults.of(id, variant, facts[id] ?: NodeFacts(), caps = caps && id.form == FULL)

    /**
     * [id]'s defaults on each of [variants] (the editor's page's: 拼音, 双拼, English) with this layout's node of
     * it: what KeyLists.with compares a list with.
     */
    fun defaultsOn(id: KeyId, variants: List<KeyboardVariant>): List<DefaultList?> =
        variants.map { KeyDefaults.of(id, it, facts[id] ?: NodeFacts()) }

    /** [id]'s list as the editor starts it: as the key pops up here with no Shift, the user's own where set. */
    fun edit(id: KeyId): KeyEdit {
        val user = lists[id]
        val m = made.getOrPut(id to false) { make(id, false) }
        if (m == null) return KeyEdit(defaultOf(id)?.row.orEmpty().map { it.text }, emptyList(), null, user)
        val row = m.inRow.coerceAtLeast(0)
        return KeyEdit(m.items.take(row).map { it.text }, m.items.drop(row).map { it.text }, m.first?.text, user)
    }

    fun resolve(id: KeyId, state: KeyState): ResolvedKey {
        val m = made.getOrPut(id to (state.caps && id.form == FULL)) { make(id, state.caps && id.form == FULL) }
        val roots = state.roots?.takeIf { id.form == FULL }?.let { r -> id.key.singleOrNull()?.let(r) }?.takeIf { it.isNotEmpty() }
        val popup = when {
            roots != null -> KeyPopup.Roots(roots)
            m == null -> null
            else -> KeyPopup.Items(m.items, m.items.map { DefaultMarks.shown(it, state.punctuation) }, m.inRow, m.focus)
        }
        val first = m?.first
        val shown = first?.let { DefaultMarks.shown(it, state.punctuation) }?.takeIf { it.isNotEmpty() }
        val swipe = KeySwipe.Key(SwipeSlots.typesAChar(id.name), slots.resolve(id.name, true), slots.resolve(id.name, false), hasFirst = first != null)
        // looking up roots, the corner is the letter the roots are of
        val letterCorner = roots != null || state.letterCorners && id.form == FULL && id.key.singleOrNull()?.isLetter() == true
        val firstMark = (if (letterCorner) id.key else shown)?.let(MarkContent::Symbol) ?: MarkContent.Empty
        return ResolvedKey(id, popup, first, shown, swipe, mark(swipe.up, firstMark), mark(swipe.down, firstMark))
    }

    /** The marks of every named key, as the scene draws them: made again only for another [state]. */
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
            upAt = SceneMarks.at(state.spots.symbol),
            downAt = SceneMarks.at(state.spots.shortcut),
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
        else -> SceneMarks.content(slot.shortcut) ?: MarkContent.Empty
    }

    private class Made(val items: List<Item>, val inRow: Int, val first: Item?, val focus: Int)

    private fun make(id: KeyId, caps: Boolean): Made? {
        val d = defaultOf(id, caps)
        val user = lists[id]
        if (user?.off == true) return null
        val row = d?.row.orEmpty()
        val symbols = if (user == null) d?.symbols.orEmpty() else symbolsOf(user, d, row, caps)
        if (row.isEmpty() && symbols.isEmpty()) return null
        val items = row + symbols
        val first = when (val f = user?.first ?: First.Default) {
            First.None -> null
            is First.Literal -> items.firstOrNull { it.text == f.text } ?: defaultFirst(d, user, symbols, items)
            First.Default -> defaultFirst(d, user, symbols, items)
        }
        val focus = first?.let(items::indexOf)?.takeIf { it >= 0 } ?: d?.focus?.takeIf { it < items.size } ?: 0
        return Made(items, if (row.isEmpty()) -1 else row.size, first, focus)
    }

    // the user's symbols as items: each committed as the default's item of its text is, else as the default's symbols are (§3.1)
    private fun symbolsOf(user: UserList, d: DefaultList?, row: List<Item>, caps: Boolean): List<Item> {
        val texts = if (caps) user.symbols.map(::upper).distinct() else user.symbols
        val rowTexts = row.map { it.text }.toSet()
        val commit = d?.symbols?.firstOrNull()?.commit?.takeIf { it == Commit.AsIs } ?: Commit.AsKey
        return texts.filter { it !in rowTexts }.map { t -> d?.items?.firstOrNull { it.text == t } ?: Item(t, commit) }
    }

    /**
     * The default's first where the list still has it: a row's (a digit, a corner) always; one the defaults put
     * first among the symbols (a letter's, ，'s), the user's list's first then.
     */
    private fun defaultFirst(d: DefaultList?, user: UserList?, symbols: List<Item>, items: List<Item>): Item? {
        val f = d?.first
        return when {
            d == null -> symbols.firstOrNull()
            f == null -> null
            f in d.row -> f.takeIf { it in items }
            user == null -> f
            else -> symbols.firstOrNull()
        }
    }

    // Shift shows a lower case list in capitals; "ß".uppercase() is "SS": such an item kept as typed
    private fun upper(item: String): String = item.uppercase().takeIf { it.length == item.length } ?: item

    private companion object {
        const val FULL = "full"
    }
}
