/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.ScancodeMapping
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.core.UNICODE
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.scene.SceneAction
import org.fcitx.fcitx5.android.input.scene.SceneBehavior
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import timber.log.Timber

// the old keys' name, a function key's bare (`comma`), as key_popups has it, until the keys are wired by
// their one name (060-keys-unify S4b)
private fun viewName(name: String): String = KeyId.of(name).let { if (it.isFunction) it.key else it.name }

/**
 * A key of the scene as BaseKeyboard wires it, made from its layout's node (060-render-p2 U4 B):
 * what it does is the layout's ([behavior]), the old keys' KeyDef its form only, so the one wiring
 * (sound, the space's and backspace's swipes, press, long press, swipes, popups) serves both. No
 * view is made of it: its appearance is none.
 */
class SceneKeyDef(
    val behavior: SceneBehavior,
    behaviors: Set<Behavior>,
    popup: Array<Popup>?,
    /** What the layout's `action:<name>` sends on this key, the keyboard's own ([Table.action]): its long press's items' too. */
    val placeholder: (String) -> KeyAction? = { null },
) : KeyDef(Appearance.Text("", 0f), behaviors, popup, name = behavior.id, shortcut = behavior.shortcut?.let(::viewName)) {

    override val swipesBothWays: Boolean get() = behavior.bothWays

    /** Its class of sound (layout-1's sound=, else its kind: SoundKey.of), as SpaceKey's, BackspaceKey's and ReturnKey's were theirs. */
    val sound: SoundKey get() = behavior.sound

    /** The system's click it plays where no theme's sound does: its sound= alone (SceneBehavior.systemSound). */
    val systemSound: SoundKey get() = behavior.systemSound

    /** The space: its sideways swipe moves the cursor (BaseKeyboard.wireSpace). */
    val isSpace: Boolean get() = behavior.role == "space"

    /** Backspace: its sideways swipe selects what it deletes (BaseKeyboard.wireBackspace). */
    val isBackspace: Boolean get() = behavior.role == "backspace"

    /**
     * What a keyboard adds to the layout's words (060-render-p2 U4 B), each for the key of [KeyNode];
     * null where it has none: its `action:` placeholders, a long press of its own (`popup from="nine"`),
     * and a group key's (`letters=`, 060-render-v2 §15) code and long press, as the engine reads the
     * keyboard's groups (SceneGroups).
     */
    interface Table {
        fun action(name: String, node: KeyNode): KeyAction? = null
        fun popup(from: String, node: KeyNode): Popup.Keyboard? = null

        /** A tap on the key of [letters]: its group's code (a tap's place added as it is pressed). */
        fun group(letters: String, node: KeyNode): KeyAction? = null

        /** The long press of the key of [letters], made as it opens again (the user's keys as they are then). */
        fun groupPopup(letters: String, node: KeyNode): Popup.Keyboard? = null

        /** What a hold of the key of [node] sends as it repeats, its press being [press]: the press again, but where the keyboard tells the two apart (the emoji search's ⌫). */
        fun repeat(press: KeyAction, node: KeyNode): KeyAction = press

        /** The bubble over the key of [node] as it is pressed, over the face the layout gives it; null: the face's. */
        fun bubble(node: KeyNode): String? = null
        /** A menu item's words as shown for the layout's [label] (handwriting's `reading_off`: the settings' wording). */
        fun menuLabel(label: String): String = label
    }

    companion object {
        /** [node]'s key: what its layout says it does ([SceneBehavior.of]), [table] the keyboard's placeholders. */
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
                // the press resolved once: a group's or a placeholder's table asked (and a missing one logged) once
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

        /**
         * [b] on [board], whose layout may say its keys pop up no preview ([Scene.preview]) and no long
         * press's keys ([Scene.popups]): the number pad's (layout-1's `preview="off" popups="off"`, rules
         * C §7). A menu the layout writes on a key is the key's own and stays: the number pad's Return
         * offers Emoji as every Return does (rules C 疑问 8, the old pad's swallowed it: 060-render-p2 §5.3).
         */
        fun onBoard(b: SceneBehavior, board: Scene?): SceneBehavior = when {
            board == null || (board.preview && board.popups) -> b
            else -> b.copy(bubble = b.bubble.takeIf { board.preview }, popup = b.popup.takeIf { board.popups })
        }

        /** What [b]'s bubble shows over the key of [node]: [table]'s, else its face's; null with no bubble. */
        fun bubbleOf(b: SceneBehavior, node: KeyNode, table: Table): String? = b.bubble?.let { table.bubble(node) ?: it.content }

        // its bubble, long press and menu: each as the old keys' KeyDef had it
        private fun popupsOf(b: SceneBehavior, node: KeyNode, table: Table, act: (SceneAction?) -> KeyAction?): List<Popup> = buildList {
            b.bubble?.let {
                val content = bubbleOf(b, node, table)!!
                // a named key's swipe shows what the resolver swipes it to ([KeySwipes.preview] asks swipeText), whatever
                // corner its layout wrote (060-keys-unify M6): the layout's corner only for a key of no name
                val corner = it.corner ?: "".takeIf { node.shortcut != null }
                add(corner?.let { c -> Popup.AltPreview(content, c) } ?: Popup.Preview(content))
            }
            when (val p = b.popup) {
                is SceneBehavior.LongPopup.Preset -> add(Popup.Keyboard.Preset(p.label, p.transformPunctuation))
                is SceneBehavior.LongPopup.Listed -> add(Popup.Keyboard.Explicit(p.items.toTypedArray(), asIs = p.asIs))
                is SceneBehavior.LongPopup.Composed -> table.popup(p.from, node)?.let(::add)
                    ?: Timber.w("scene key %s: no long press from %s", b.id, p.from)
                is SceneBehavior.LongPopup.Letters -> table.groupPopup(p.letters, node)?.let(::add)
                    ?: Timber.w("scene key %s: no long press of %s", b.id, p.letters)
                null -> {}
            }
            if (b.menu.isNotEmpty()) {
                val items = b.menu.mapNotNull { m ->
                    act(m.action)?.let { Popup.Menu.Item(table.menuLabel(m.label), m.icon?.let(SceneIcons::of) ?: 0, it) }
                }
                add(Popup.Menu(items.toTypedArray()))
            }
        }

        // the keypad's , and = (FcitxKeyMapping has neither): NumberKeyboard's KEYPAD
        private const val KP_SEPARATOR = 0xffac
        private const val KP_EQUAL = 0xffbd

        // the keypad's keysyms the layouts name (sym:), sent with NumLock on as the number pad's keys were
        // (NumPadKey): the input method types its digit or sign, not a punctuation of its own (，for ,).
        // Only these: a name is not looked at for its KP_ (xsd README 7). A typing keyboard's corner digit
        // is keypad: (no NumLock)
        private val KEYPAD = mapOf(
            "KP_Add" to FcitxKeyMapping.FcitxKey_KP_Add,
            "KP_Subtract" to FcitxKeyMapping.FcitxKey_KP_Subtract,
            "KP_Multiply" to FcitxKeyMapping.FcitxKey_KP_Multiply,
            "KP_Divide" to FcitxKeyMapping.FcitxKey_KP_Divide,
            "KP_Decimal" to FcitxKeyMapping.FcitxKey_KP_Decimal,
            "KP_Separator" to KP_SEPARATOR,
            "KP_Equal" to KP_EQUAL,
        ) + (0..9).associate { "KP_$it" to FcitxKeyMapping.FcitxKey_KP_0 + it }

        // the digits' own keysyms (XK_0…XK_9, their names the digits), not the keypad's: the number row's, as its keys sent them
        private val DIGITS = ('0'..'9').associate { it.toString() to it.code }

        // the keysyms the layouts name (sym:), FcitxKeyMapping's by their names
        private val SYMS = mapOf(
            "BackSpace" to FcitxKeyMapping.FcitxKey_BackSpace,
            "space" to FcitxKeyMapping.FcitxKey_space,
            "Return" to FcitxKeyMapping.FcitxKey_Return,
            "Escape" to FcitxKeyMapping.FcitxKey_Escape,
        ) + KEYPAD + DIGITS

        // the app's words (layout-1's bare actions), as the old keys did them (KeyDefPreset)
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

        // a letter's scancode is its capital's (ScancodeMapping knows those alone): the keyboard sends it in Shift's case
        private fun scancodeOf(text: String): Int =
            ScancodeMapping.charToScancode(text[0].let { c -> if (text.length == 1 && c in 'a'..'z') c.uppercaseChar() else c })

        /**
         * The app's action for the layout's [a] (the `action:` table, 060-render-p2 U4 B), [placeholder]
         * the keyboard's own `action:<name>`; null for what the app has none of (logged: the key does nothing).
         */
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
            is SceneAction.Reading -> reading(a.style)
            is SceneAction.EmojiSearch -> KeyAction.EmojiSearchAction(a.arg == "on")
            is SceneAction.Command -> COMMANDS[a.name]
            is SceneAction.Placeholder -> placeholder(a.name)
            // the keyboard's (Table.group): the engine's codes are its groups'
            is SceneAction.Group -> null
        }.also { if (it == null) Timber.w("scene key: no action for %s", a) }

        // picker: alone, the page shown last (ReturnKey's menu)
        private fun picker(name: String?): KeyAction? = if (name == null) {
            KeyAction.PickerSwitchAction()
        } else {
            PickerWindow.Key.entries.firstOrNull { it.name == name }?.let { KeyAction.PickerSwitchAction(it) }
        }

        private fun reading(style: String?): KeyAction? = if (style == null) {
            KeyAction.ReadingAction()
        } else {
            ReadingStyle.entries.firstOrNull { it.key.equals(style, ignoreCase = true) }?.let { KeyAction.ReadingAction(it) }
        }
    }
}
