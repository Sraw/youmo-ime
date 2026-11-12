/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

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

/**
 * A key's long press as it opens: its [items], the first [inRow] of them the row, [focus] under the finger (the
 * first, wherever it is: in the row, among the symbols), [own] the row's middle (its default's, the keypad digit
 * or the corner; -1 with no row): where the row centres when the first is not in it.
 */
sealed interface KeyPopup {
    data class Items(val items: List<Item>, val labels: List<String>, val inRow: Int, val focus: Int, val own: Int = -1) : KeyPopup

    /** A table's roots of the letter, shown alone. */
    data class Roots(val text: String) : KeyPopup
}

/**
 * A key's list as the settings' editor starts it (060-row-unlock §1): its [items] as the key pops up (its own
 * letters and digit as any other item), its [first]'s text (null: none), and what the user set of it, null for none.
 */
data class KeyEdit(val items: List<String>, val first: String?, val user: UserList?) {
    companion object {
        /** The [First] an edited list stores for the editor's [first]: `*` for the list's first item, `!` for none, else the item. */
        fun firstToStore(first: String?, items: List<String>): First = when (first) {
            null -> First.None
            UserList.firstOf(First.Default, items) -> First.Default
            else -> First.Literal(first)
        }
    }
}

/**
 * A key as everything reads it: its long press ([popup], null with none), its [first] (what its swipe's
 * 长按首选 types, its corner shows, the long press opens on), [firstShown] as drawn, its two swipe slots
 * ([swipe]) and their marks; [corner] what its first's mark shows (a labelled table's letter itself), as a screen reader says it.
 */
data class ResolvedKey(
    val id: KeyId,
    val popup: KeyPopup?,
    val first: Item?,
    val firstShown: String?,
    val swipe: KeySwipe.Key,
    val upMark: MarkContent,
    val downMark: MarkContent,
    val corner: String? = null,
)

/**
 * Every key of a keyboard as its defaults ([KeyDefaults], by the [variant] and the layout's [nodes]) and the
 * user's [lists] and [slots] make it (060-keys-unify §3): one rule for every key on every keyboard. A key's
 * list is made once; what [KeyState] changes (capitals, the punctuation shown, roots) is laid on it as asked,
 * each key's kept for the last state asked. Main thread only: its caches are not guarded.
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

    // each key as resolved for [resolvedFor], the state last asked: made again only for another
    private val resolved = HashMap<KeyId, ResolvedKey>()
    private var resolvedFor: KeyState? = null

    private var marksFor: KeyState? = null
    private var marksMade: MarkSettings? = null

    private var readingsFor: KeyState? = null
    private var readingsMade: Map<String, String>? = null

    /** The default list [id] has here, with no setting of the user's (the editor's start, `KeyLists.with`'s). */
    fun defaultOf(id: KeyId, caps: Boolean = false): DefaultList? =
        KeyDefaults.of(id, variant, facts[id] ?: NodeFacts(), caps = caps && id.form == FULL)

    /** [id]'s list as the editor starts it: as the key pops up here with no Shift, the user's own where set. */
    fun edit(id: KeyId): KeyEdit {
        val m = made.getOrPut(id to false) { make(id, false) }
        return KeyEdit(m?.items.orEmpty().map { it.text }, m?.first?.text, lists[id])
    }

    fun resolve(id: KeyId, state: KeyState): ResolvedKey {
        if (state != resolvedFor) {
            resolved.clear()
            resolvedFor = state
        }
        return resolved.getOrPut(id) { resolved(id, state) }
    }

    private fun resolved(id: KeyId, state: KeyState): ResolvedKey {
        val m = made.getOrPut(id to (state.caps && id.form == FULL)) { make(id, state.caps && id.form == FULL) }
        val roots = state.roots?.takeIf { id.form == FULL }?.let { r -> id.key.singleOrNull()?.let(r) }?.takeIf { it.isNotEmpty() }
        val popup = when {
            roots != null -> KeyPopup.Roots(roots)
            m == null -> null
            else -> KeyPopup.Items(m.items, m.items.map { DefaultMarks.shown(it, state.punctuation) }, m.inRow, m.focus, m.own)
        }
        // a first KeyOutput cannot make (a theme's `send=` of no action) is none, for the corner, the swipe's slot and
        // the keyboard's swipe (KeyResolving.swipe) alike; one the app cannot map (an unknown sym or command, an
        // `action:` with no placeholder) still shows a corner while the swipe types nothing
        val first = m?.first?.takeIf { KeyOutput.of(it) != null }
        val shown = first?.let { DefaultMarks.shown(it, state.punctuation) }?.takeIf { it.isNotEmpty() }
        val swipe = KeySwipe.Key(SwipeSlots.typesAChar(id.name), slots.resolve(id.name, true), slots.resolve(id.name, false), hasFirst = first != null)
        // looking up roots, the corner is the letter the roots are of
        val letterCorner = roots != null || state.letterCorners && id.form == FULL && id.key.singleOrNull()?.isLetter() == true
        val corner = if (letterCorner) id.key else shown?.takeUnless { firstHidden(id) }
        val firstMark = corner?.let(SceneMarks::symbol) ?: MarkContent.Empty
        return ResolvedKey(id, popup, first, shown, swipe, mark(swipe.up, firstMark), mark(swipe.down, firstMark), corner)
    }

    /**
     * Every named key's [ResolvedKey.corner] by its name, as Texts.alts reads them (a screen reader's 「Q W，1」, 060-keys-unify
     * §3.4): only where a way draws it (its slot the first's, the way on, its place not 无: as Content.ways and the
     * marks' slots draw it), as the corner a sighted user sees; a key with none left out. Made again only for another [state].
     */
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

    /**
     * The marks of every named key, as the scene draws them: made again only for another [state].
     * [MarkSettings.charShortcuts] here is only "no roots shown"; the keyboard's own rule (its labels the roots
     * table's and roots on) is the caller's to lay over it, as SceneBoard, KeyCaps and SwipeFates do.
     */
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
        else -> SceneMarks.content(slot.shortcut) ?: MarkContent.Empty
    }

    // a character key (any but fn/'s, as the scene has it: Content) of `mark="false"` (注音's) draws and says no first of its
    // defaults', any of a list the user set all the same (060-s6-review L4)
    private fun firstHidden(id: KeyId): Boolean = facts[id]?.markOff == true && !id.isFunction && lists[id] == null

    private class Made(val items: List<Item>, val inRow: Int, val first: Item?, val focus: Int, val own: Int)

    private fun make(id: KeyId, caps: Boolean): Made? {
        val d = defaultOf(id, caps)
        val user = lists[id] ?: return d?.takeIf { it.items.isNotEmpty() }?.let(::madeOf)
        if (user.off) return null
        val row = d?.row.orEmpty()
        // stored as typed lower case (a letter's): Shift's list has its capitals
        val texts = (if (caps) user.items.map(::upper).distinct() else user.items).filterNot { skipped(id, it) }
        val commit = d?.symbols?.firstOrNull()?.commit?.takeIf { it == Commit.AsIs } ?: Commit.AsKey
        // each committed as the default's item of its text is, else as the default's symbols are (§3.1)
        val items = texts.map { t -> d?.items?.firstOrNull { it.text == t } ?: Item(t, commit) }
        if (items.isEmpty()) return null
        val firstText = UserList.firstOf(if (caps) user.first.upper() else user.first, texts)
        val first = items.firstOrNull { it.text == firstText }
        // the row: the leading items of the default's row, or a single ASCII letter or digit (a letter of another
        // 按键字母 or scheme); none (-1), all as symbols (060-row-unlock §1.3)
        val rowTexts = row.map { it.text }.toSet()
        val inRow = if (row.isEmpty()) 0 else items.takeWhile { it.text in rowTexts || own(it.text) }.size
        val focus = first?.let(items::indexOf)?.takeIf { it >= 0 } ?: 0
        if (inRow == 0) return Made(items, -1, first, focus, -1)
        return Made(items, inRow, first, focus, focus.takeIf { first != null && it < inRow } ?: (inRow / 2))
    }

    private fun madeOf(d: DefaultList): Made {
        val own = if (d.row.isEmpty()) -1 else d.focus.takeIf { it < d.row.size } ?: (d.row.size / 2)
        val focus = d.first?.let(d.items::indexOf)?.takeIf { it >= 0 } ?: d.focus.takeIf { it < d.items.size } ?: 0
        return Made(d.items, if (d.row.isEmpty()) -1 else d.row.size, d.first, focus, own)
    }

    // the one exception (060-row-unlock §2): the 18 keys' L group types `;` only where the scheme does (KeyDefaults'
    // rule), and its capital `:` is shown only there
    private fun skipped(id: KeyId, text: String): Boolean =
        text == ":" && variant.form == KeyboardVariant.Form.Grouped18 && !variant.semicolon && facts[id]?.letters?.contains(';') == true

    private fun own(text: String): Boolean = text.length == 1 && text[0].let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

    private fun First.upper(): First = if (this is First.Literal) First.Literal(upper(text)) else this

    // Shift shows a lower case list in capitals; "ß".uppercase() is "SS": such an item kept as typed
    private fun upper(item: String): String = item.uppercase().takeIf { it.length == item.length } ?: item

    private companion object {
        const val FULL = "full"
    }
}
