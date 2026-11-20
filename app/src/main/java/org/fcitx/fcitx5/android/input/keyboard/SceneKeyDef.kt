/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.input.keys.KeyOutput
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupKeyboardUi
import org.fcitx.fcitx5.android.input.scene.SceneAction
import org.fcitx.fcitx5.android.input.scene.SceneBehavior
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import timber.log.Timber

// a function key's bare (`comma`), any other's whole (`full/q`): every reader asks KeyId.of, never compares the text
private fun viewName(name: String): String = KeyId.of(name).let { if (it.isFunction) it.key else it.name }

val NumLockState = KeyStates(KeyState.NumLock, KeyState.Virtual)

/** Made from its layout's node, in KeyDef's form, which the wiring reads. */
class SceneKeyDef(
    val behavior: SceneBehavior,
    behaviors: Set<Behavior>,
    popup: Array<Popup>?,
    /** The keyboard's own ([Table.action]): its long press's items' too. */
    val placeholder: (String) -> KeyAction? = { null },
) : KeyDef(behaviors, popup, name = behavior.id, shortcut = behavior.shortcut?.let(::viewName)) {

    override val swipesBothWays: Boolean get() = behavior.bothWays

    val sound: SoundKey get() = behavior.sound

    /** Where no theme's sound plays: its sound= alone. */
    val systemSound: SoundKey get() = behavior.systemSound

    /** Its sideways swipe moves the cursor. */
    val isSpace: Boolean get() = behavior.role == "space"

    /** Its sideways swipe selects what it deletes. */
    val isBackspace: Boolean get() = behavior.role == "backspace"

    /** What a keyboard adds to the layout's words for a key; null where it has none. */
    interface Table {
        fun action(name: String, node: KeyNode): KeyAction? = null
        /** A tap's place is added as it is pressed. */
        fun group(letters: String, node: KeyNode): KeyAction? = null

        /** The press again, but where the keyboard tells the two apart (the emoji search's ⌫). */
        fun repeat(press: KeyAction, node: KeyNode): KeyAction = press

        /**
         * A key made is kept for a node only while this is the same: 注音's code is its face's, which a
         * `<variant>` or another layout of the same ids changes. Null for nothing besides the layout's.
         */
        fun reads(node: KeyNode): Any? = null

        /** Null: the face's. */
        fun bubble(node: KeyNode): String? = null
        /** Handwriting's `reading_off`: the settings' wording. */
        fun menuLabel(label: String): String = label
    }

    companion object {
        fun of(node: KeyNode, table: Table): SceneKeyDef = of(SceneBehavior.of(node), node, table)

        fun of(b: SceneBehavior, node: KeyNode, table: Table): SceneKeyDef {
            fun act(a: SceneAction?) = a?.let {
                if (it is SceneAction.Group) {
                    table.group(it.letters, node).also { g -> if (g == null) Timber.w("scene key %s: no group of %s", b.id, it.letters) }
                } else {
                    action(it) { name -> table.action(name, node) }
                }
            }
            val behaviors = buildSet {
                // resolved once: a group's or a placeholder's table asked (and a missing one logged) once
                val press = act(b.press)
                press?.let { add(Behavior.Press(it)) }
                act(b.longPress)?.let { add(Behavior.LongPress(it)) }
                if (b.repeat) press?.let { add(Behavior.Repeat(table.repeat(it, node))) }
                act(b.swipe)?.let { add(Behavior.Swipe(it)) }
                act(b.doubleTap)?.let { add(Behavior.DoubleTap(it)) }
            }
            val popups = popupsOf(b, node, table, ::act)
            return SceneKeyDef(b, behaviors, popups.toTypedArray().takeIf { it.isNotEmpty() }) { name -> table.action(name, node) }
        }

        /** A menu the layout writes on a key is the key's own and stays: the number pad's Return offers Emoji too. */
        fun onBoard(b: SceneBehavior, board: Scene?): SceneBehavior = when {
            board == null || (board.preview && board.popups) -> b
            else -> b.copy(bubble = b.bubble.takeIf { board.preview }, popup = b.popup.takeIf { board.popups })
        }

        // its row (Q W 1 q w), the symbols above, the first under the finger; a digit corner a keypad's, its
        // candidate picked while typing; an item no build reads typed as a key
        private fun grouped(letters: String, corner: String?, placeholder: (String) -> KeyAction?): Popup.Keyboard.Explicit {
            val list = KeyDefaults.grouped(letters, corner)
            val items = list.row + list.symbols
            val actions = items.map { KeyOutput.of(it)?.toKeyAction(placeholder) ?: PopupKeyboardUi.keyAction(it.text) }
            return Popup.Keyboard.Explicit(items.map { it.text }.toTypedArray(), symbolsFrom = list.row.size, inRow = list.focus, actions = actions)
        }

        fun bubbleOf(b: SceneBehavior, node: KeyNode, table: Table): String? = b.bubble?.let { table.bubble(node) ?: it.content }

        private fun popupsOf(b: SceneBehavior, node: KeyNode, table: Table, act: (SceneAction?) -> KeyAction?): List<Popup> = buildList {
            b.bubble?.let {
                val content = bubbleOf(b, node, table)!!
                // a named key's swipe shows what the resolver swipes it to: its corner empty; the layout's only for a key of no name
                add(it.corner?.let { c -> Popup.AltPreview(content, c) } ?: Popup.Preview(content))
            }
            // a named key's long press is the resolver's, wired by its name: the layout's popup only says which default list
            if (node.shortcut == null) when (val p = b.popup) {
                is SceneBehavior.LongPopup.Preset -> add(Popup.Keyboard.Preset(p.label, p.transformPunctuation))
                is SceneBehavior.LongPopup.Listed -> add(Popup.Keyboard.Explicit(p.items.toTypedArray(), asIs = p.asIs))
                // a theme's own group of no name: the built-in list of its letters, none the user can change
                is SceneBehavior.LongPopup.Letters -> add(grouped(p.letters, b.swipe?.let(SceneBehavior::typed)) { name -> table.action(name, node) })
                // made by a key's name alone (a nine key's, 注音九键's group): a key of none has no long press
                is SceneBehavior.LongPopup.Composed -> Timber.w("scene key %s: a long press made by name on a key of none", b.id)
                null -> {}
            }
            if (b.menu.isNotEmpty()) {
                val items = b.menu.mapNotNull { m ->
                    act(m.action)?.let { Popup.Menu.Item(table.menuLabel(m.label), m.icon?.let(SceneIcons::of) ?: 0, it) }
                }
                add(Popup.Menu(items.toTypedArray()))
            }
        }

        // FcitxKeyMapping has neither
        private const val KP_SEPARATOR = 0xffac
        private const val KP_EQUAL = 0xffbd

        // sent with NumLock on as the number pad's keys were: the input method types its digit or sign, not a
        // punctuation of its own (，for ,). A name is not looked at for its KP_; a typing keyboard's corner digit is keypad: (no NumLock)
        private val KEYPAD = mapOf(
            "KP_Add" to FcitxKeyMapping.FcitxKey_KP_Add,
            "KP_Subtract" to FcitxKeyMapping.FcitxKey_KP_Subtract,
            "KP_Multiply" to FcitxKeyMapping.FcitxKey_KP_Multiply,
            "KP_Divide" to FcitxKeyMapping.FcitxKey_KP_Divide,
            "KP_Decimal" to FcitxKeyMapping.FcitxKey_KP_Decimal,
            "KP_Separator" to KP_SEPARATOR,
            "KP_Equal" to KP_EQUAL,
        ) + (0..9).associate { "KP_$it" to FcitxKeyMapping.FcitxKey_KP_0 + it }

        // not the keypad's: the number row's, as its keys sent them
        private val DIGITS = ('0'..'9').associate { it.toString() to it.code }

        private val SYMS = mapOf(
            "BackSpace" to FcitxKeyMapping.FcitxKey_BackSpace,
            "space" to FcitxKeyMapping.FcitxKey_space,
            "Return" to FcitxKeyMapping.FcitxKey_Return,
            "Escape" to FcitxKeyMapping.FcitxKey_Escape,
        ) + KEYPAD + DIGITS

        // layout-1's bare actions
        private val COMMANDS: Map<String, KeyAction> = mapOf(
            "lang" to KeyAction.LangSwitchAction,
            "ime-picker" to KeyAction.ShowInputMethodPickerAction,
            "quickphrase" to KeyAction.QuickPhraseAction,
            "unicode" to KeyAction.UnicodeAction,
            "panel-back" to KeyAction.PanelBackAction,
            "panel-lock" to KeyAction.PanelLockAction,
            "space-long-press" to KeyAction.SpaceLongPressAction(),
            "begin-selection-swipe" to KeyAction.BeginSelectionSwipeAction,
            "delete-selection" to KeyAction.DeleteSelectionAction(),
            "move-selection" to KeyAction.MoveSelectionAction(),
            "lookup" to KeyAction.SymAction(KeySym(UNICODE + Keyboard.LOOKUP), KeyStates.Virtual),
        )

        // ScancodeMapping knows capitals alone: the keyboard sends it in Shift's case
        private fun scancodeOf(text: String): Int =
            ScancodeMapping.charToScancode(text[0].let { c -> if (text.length == 1 && c in 'a'..'z') c.uppercaseChar() else c })

        /** Null for what the app has none of (logged: the key does nothing). */
        fun action(a: SceneAction, placeholder: (String) -> KeyAction?): KeyAction? = when (a) {
            is SceneAction.Key -> KeyAction.HostKeyAction(a.text, scancodeOf(a.text))
            is SceneAction.Sym -> SYMS[a.name]?.let { KeyAction.SymAction(KeySym(it), if (a.name in KEYPAD) NumLockState else KeyStates.Virtual) }
            is SceneAction.Layout -> KeyAction.LayoutSwitchAction(a.name)
            is SceneAction.Picker -> picker(a.name)
            is SceneAction.Commit -> KeyAction.CommitAction(a.text)
            is SceneAction.Code -> KeyAction.SymAction(KeySym(UNICODE + a.codePoint), KeyStates.Virtual)
            is SceneAction.Keypad -> KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_KP_0 + a.digit), KeyStates.Virtual)
            is SceneAction.Caps -> KeyAction.CapsAction(a.lock)
            is SceneAction.Shortcut -> KeyCommand.entries.firstOrNull { it.key == a.name }?.let { KeyAction.ShortcutAction(KeyShortcut.Command(it)) }
            is SceneAction.EmojiSearch -> KeyAction.EmojiSearchAction(a.arg == "on")
            is SceneAction.Command -> COMMANDS[a.name]
            is SceneAction.Placeholder -> placeholder(a.name)
            // the engine's codes are its groups'
            is SceneAction.Group -> null
        }.also { if (it == null) Timber.w("scene key: no action for %s", a) }

        // alone, the page shown last
        private fun picker(name: String?): KeyAction? = if (name == null) {
            KeyAction.PickerSwitchAction()
        } else {
            PickerWindow.Key.entries.firstOrNull { it.name == name }?.let { KeyAction.PickerSwitchAction(it) }
        }
    }
}
