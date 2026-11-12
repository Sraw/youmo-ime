/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keys.KeyListsCache
import org.fcitx.fcitx5.android.input.keys.KeyOutput
import org.fcitx.fcitx5.android.input.keys.KeyPopup
import org.fcitx.fcitx5.android.input.keys.KeyResolver
import org.fcitx.fcitx5.android.input.keys.KeyState
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.keys.KeyId

/**
 * A keyboard's named keys as the resolver has them (060-keys-unify §3): the resolver of the layout its keys are
 * drawn from, kept while nothing it is made of changed (asked on every swipe's lift and every long press);
 * what a key swipes to, and its long press made as it opens. Main thread only, as the keyboard.
 */
internal class KeyResolving(private val board: Board) {

    /** What the resolver is made of, the keyboard's: the layout drawn, its input method, its nine keys' letters. */
    interface Board {
        val layout: SceneLayout?
        val im: String
        val nineLetters: Map<Char, String>?
        fun state(): KeyState
    }

    // the keys' names by their scene id: a long press opened is the resolver's for its key's name
    private val namesById = HashMap<String, String>()

    // each named key's `action:` placeholders, its long press's items' and first's (a scene key's, the keyboard's table)
    private val placeholders = HashMap<String, (String) -> KeyAction?>()

    // what the resolver kept was made of (data class equals: the layout by instance, SceneLayout having no equals of its
    // own; the letters' map by its equals, the same instance at once; the lists by their version), nothing hashed. This
    // is the keyboard's own, asked on every touch; SceneBoard.resolver, asked only as it changes, keeps the resolvers
    // shared by every keyboard and the settings page (the same layout, variant and lists the same resolver)
    private data class MadeOf(
        val layout: SceneLayout,
        val im: String,
        val nineLetters: Map<Char, String>?,
        val listsVersion: Int,
        val slots: SwipeSlots,
        val scheme: ShuangpinScheme,
    )

    private var madeOf: MadeOf? = null
    private var made: KeyResolver? = null

    /** The resolver of [layout], by default the one the keys are drawn from now; null before a scene's made. */
    fun resolver(layout: SceneLayout? = board.layout): KeyResolver? {
        layout ?: return null
        val now = MadeOf(layout, board.im, board.nineLetters, KeyListsCache.user.versionNow, SwipeSlotsCache.current, EngineState.shuangpinScheme)
        if (now != madeOf) {
            made = SceneBoard.resolver(layout, now.im, now.nineLetters)
            madeOf = now
        }
        return made
    }

    /** [ref]'s key named [name], its `action:` placeholders [placeholder]'s: its long press the resolver's as it opens. */
    fun named(ref: String, name: String, placeholder: (String) -> KeyAction?) {
        namesById[ref] = name
        placeholders[ref] = placeholder
    }

    /** [ref]'s key wired again with no name (a theme's key of none in its place): no name of the last left to answer for it. */
    fun unnamed(ref: String) {
        namesById.remove(ref)
        placeholders.remove(ref)
    }

    /**
     * Whether the key of scene id [ref] has a long press as things are now: a list of anything it can type, or a
     * table's roots (the keyboard's own to show); asked as it would fire, a key's long press wired whatever its list.
     */
    fun opens(ref: String): Boolean {
        val name = namesById[ref] ?: return false
        return when (val p = resolver()?.resolve(KeyId.of(name), board.state())?.popup) {
            null -> false
            is KeyPopup.Roots -> true
            // as it would open, stopping at the first item it can type (asked for every key's node by a screen reader)
            is KeyPopup.Items -> (placeholders[ref] ?: { null }).let { ph -> p.items.any { KeyOutput.of(it)?.toKeyAction(ph) != null } }
        }
    }

    /**
     * What swiping the key named [name] to its first types (060-keys-unify §3.2): none where it has no first; null
     * for a function key with no list, built in or the user's (the space: its own swipe, as the layout has it).
     */
    fun swipe(name: String, placeholder: (String) -> KeyAction?): BaseKeyboard.SwipeChoice? {
        val id = KeyId.of(name)
        val resolver = resolver() ?: return null
        val r = resolver.resolve(id, board.state())
        val first = r.first
        if (first == null) {
            // a function key's theme popup whose first KeyOutput cannot make (dropped by the resolver) has no first
            // either: with no list of its own it keeps its layout's swipe, as one with no popup does
            val listed = KeyDefaults.function(id.key) != null || resolver.edit(id).user != null
            return if (id.isFunction && !listed) null else BaseKeyboard.SwipeChoice.None
        }
        val action = KeyOutput.of(first)?.toKeyAction(placeholder) ?: return BaseKeyboard.SwipeChoice.None
        return BaseKeyboard.SwipeChoice.Output(action, r.firstShown ?: first.text)
    }

    /** The long press of the key of scene id [ref] as it opens: the resolver's; null for a key of no name (its own). */
    fun popup(ref: String): Popup? {
        val name = namesById[ref] ?: return null
        val resolver = resolver() ?: return null
        return Popup(resolved(resolver.resolve(KeyId.of(name), board.state()).popup, placeholders[ref] ?: { null }))
    }

    /** A named key's long press: [keyboard] null where it has none (turned off, or none built in). */
    class Popup(val keyboard: KeyDef.Popup.Keyboard.Resolved?)

    /** [popup], the resolver's, as the popup shows it: its items typed as each says; null for none (roots are a keyboard's own). */
    private fun resolved(popup: KeyPopup?, placeholder: (String) -> KeyAction?): KeyDef.Popup.Keyboard.Resolved? {
        val items = (popup as? KeyPopup.Items) ?: return null
        val kept = items.items.indices.mapNotNull { i ->
            KeyOutput.of(items.items[i])?.toKeyAction(placeholder)?.let { Triple(i, it, items.labels[i]) }
        }
        if (kept.isEmpty()) return null
        val at = placesOf(kept.map { it.first }, items.inRow, items.focus, items.own)
        return KeyDef.Popup.Keyboard.Resolved(kept.map { it.second }, kept.map { it.third }, at.split, at.inRow, at.focus)
    }

    /** Where a long press's items kept go: [Resolved][KeyDef.Popup.Keyboard.Resolved]'s split, inRow and focus. */
    data class Places(val split: Int, val inRow: Int, val focus: Int)

    companion object {
        /**
         * The places of the items kept (their indices [kept] in the list, those it could not type dropped), the
         * list's row its first [inRow] items, its [focus] and [own] (its middle) indices in it: the row round the
         * first where it is kept in it, else round its own middle, else round the middle of what is kept of it; a
         * row all dropped (or none, -1), its symbols set apart.
         */
        internal fun placesOf(kept: List<Int>, inRow: Int, focus: Int, own: Int): Places {
            val row = kept.count { it < inRow }
            val f = kept.indexOf(focus)
            val o = kept.indexOf(own)
            if (row == 0) return Places(0, -1, f)
            val at = if (f in 0 until row) f else if (o in 0 until row) o else row / 2
            return Places(row, at, f)
        }
    }
}
