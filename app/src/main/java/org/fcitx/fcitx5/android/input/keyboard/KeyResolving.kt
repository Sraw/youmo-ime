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
import org.fcitx.fcitx5.android.scene.keys.Item
import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.keys.KeyId

/** Kept while nothing it is made of changed: asked on every swipe's lift and every long press. Main thread only. */
internal class KeyResolving(private val board: Board) {

    interface Board {
        val layout: SceneLayout?
        val im: String
        val nineLetters: Map<Char, String>?
        fun state(): KeyState
    }

    private val namesById = HashMap<String, String>()

    private val placeholders = HashMap<String, (String) -> KeyAction?>()

    // picking an item of variables types them filled in again ([output])
    private val filledIn = HashMap<String, Map<KeyAction, Pair<Item, String>>>()

    // data class equals: the layout by instance (SceneLayout has no equals), the lists by their version; nothing
    // hashed. Asked on every touch; SceneBoard.resolver keeps the resolvers every keyboard shares
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

    /** Null before a scene's made. */
    fun resolver(layout: SceneLayout? = board.layout): KeyResolver? {
        layout ?: return null
        val now = MadeOf(layout, board.im, board.nineLetters, KeyListsCache.user.versionNow, SwipeSlotsCache.current, EngineState.shuangpinScheme)
        if (now != madeOf) {
            made = SceneBoard.resolver(layout, now.im, now.nineLetters)
            madeOf = now
        }
        return made
    }

    fun named(ref: String, name: String, placeholder: (String) -> KeyAction?) {
        namesById[ref] = name
        placeholders[ref] = placeholder
    }

    /** A theme's key of none in its place: no name of the last left to answer for it. */
    fun unnamed(ref: String) {
        namesById.remove(ref)
        placeholders.remove(ref)
        filledIn.remove(ref)
    }

    /** A table's roots count too. Asked as it would fire, a key's long press wired whatever its list. */
    fun opens(ref: String): Boolean {
        val name = namesById[ref] ?: return false
        return when (val p = resolver()?.resolve(KeyId.of(name), board.state())?.popup) {
            null -> false
            is KeyPopup.Roots -> true
            // stops at the first item it can type (asked for every key's node by a screen reader)
            is KeyPopup.Items -> (placeholders[ref] ?: { null }).let { ph -> p.items.any { KeyOutput.of(it)?.toKeyAction(ph) != null } }
        }
    }

    /** None where it has no first; null for a function key with no list (the space: its own swipe). */
    fun swipe(name: String, placeholder: (String) -> KeyAction?): BaseKeyboard.SwipeChoice? {
        val id = KeyId.of(name)
        val resolver = resolver() ?: return null
        val r = resolver.resolve(id, board.state())
        val first = r.first
        if (first == null) {
            // a function key's theme popup whose first KeyOutput cannot make has no first either: it keeps its layout's swipe
            val listed = KeyDefaults.function(id.key) != null || resolver.edit(id).user != null
            return if (id.isFunction && !listed) null else BaseKeyboard.SwipeChoice.None
        }
        val action = KeyOutput.of(first)?.toKeyAction(placeholder) ?: return BaseKeyboard.SwipeChoice.None
        // filled in as the swipe lifts, not as it began
        val now = r.firstTemplate?.let { t -> { KeyAction.CommitAction(resolver.output(first, t)) } }
        return BaseKeyboard.SwipeChoice.Output(action, r.firstShown ?: first.text, now)
    }

    /** Filled in by the item picked, never by its text (a plain 14 beside a `$day` drawn 14 before midnight types 14). */
    fun output(ref: String, action: KeyAction): KeyAction {
        val (item, template) = filledIn[ref]?.get(action) ?: return action
        val resolver = resolver() ?: return action
        return KeyAction.CommitAction(resolver.output(item, template))
    }

    /** Null for a key of no name (its own). */
    fun popup(ref: String): Popup? {
        val name = namesById[ref] ?: return null
        val resolver = resolver() ?: return null
        return Popup(resolved(ref, resolver.resolve(KeyId.of(name), board.state()).popup, placeholders[ref] ?: { null }))
    }

    /** [keyboard] null where it has none (turned off, or none built in). */
    class Popup(val keyboard: KeyDef.Popup.Keyboard.Resolved?)

    /** Null for none (roots are a keyboard's own). */
    private fun resolved(ref: String, popup: KeyPopup?, placeholder: (String) -> KeyAction?): KeyDef.Popup.Keyboard.Resolved? {
        filledIn.remove(ref)
        val items = (popup as? KeyPopup.Items) ?: return null
        val kept = items.items.indices.mapNotNull { i ->
            KeyOutput.of(items.items[i])?.toKeyAction(placeholder)?.let { Triple(i, it, items.labels[i]) }
        }
        // the list's values are each once (KeyResolver), so each action is its item's alone
        val templates = kept.mapNotNull { (i, action, _) -> items.templates.getOrNull(i)?.let { action to (items.items[i] to it) } }
        if (templates.isNotEmpty()) filledIn[ref] = templates.toMap()
        if (kept.isEmpty()) return null
        val at = placesOf(kept.map { it.first }, items.inRow, items.focus, items.own)
        return KeyDef.Popup.Keyboard.Resolved(kept.map { it.second }, kept.map { it.third }, at.split, at.inRow, at.focus)
    }

    data class Places(val split: Int, val inRow: Int, val focus: Int)

    companion object {
        /**
         * The row round the first where it is kept in it, else round its own middle, else round the middle of
         * what is kept of it; a row all dropped (or none, -1), its symbols set apart.
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
