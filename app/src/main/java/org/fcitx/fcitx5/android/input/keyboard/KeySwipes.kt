/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import androidx.annotation.Keep
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.keys.KeyId

/** Set by its popup's listener, read by its swipe. */
internal class LongPopup {
    var shown = false
}

/** Apart from [BaseKeyboard], which makes the keys and has the rest of their touch. */
internal class KeySwipes(
    private val board: View,
    private val threshold: Float,
    private val disabledThreshold: Float,
    /** A character there as far as one it deletes. */
    private val selectThreshold: Float,
    private val lineStep: Float,
    private val keyboard: Keyboard,
) {
    interface Keyboard {
        val inPasswordField: Boolean

        /** Its swipe runs it all the same. */
        val charShortcutMarks: Boolean

        fun swipeText(def: KeyDef): BaseKeyboard.SwipeChoice
        fun onAction(action: KeyAction, source: KeyActionListener.Source = KeyActionListener.Source.Keyboard)
        fun onPopupAction(action: PopupAction)
        fun onPopupChangeFocus(key: KeyRef, x: Float, y: Float): Boolean
        fun onPopupTrigger(key: KeyRef, cancelled: Boolean): Boolean

        fun lines(steps: Int): List<SpaceCursor.Line>

        fun marksChanged()
    }

    private val prefs = AppPrefs.getInstance()

    /** Its bubble names 滑选 till the lift, wherever the finger drifts up or down. */
    private var selecting: KeyRef? = null

    @Keep
    private val slotsListener = ManagedPreference.OnChangeListener<String> { _, _ -> showMarks() }
    @Keep
    private val waysListener = ManagedPreference.OnChangeListener<Boolean> { _, _ -> showMarks() }
    @Keep
    private val markPositionListener = ManagedPreference.OnChangeListener<KeyMarkPosition> { _, _ -> showMarks() }

    init {
        // the keys of no name have marks too (their built-in swipe), placed by the same two settings
        prefs.keyboard.swipeUpPosition.registerOnChangeListener(markPositionListener)
        prefs.keyboard.swipeDownPosition.registerOnChangeListener(markPositionListener)
        prefs.internal.keySwipes.registerOnChangeListener(slotsListener)
        prefs.keyboard.swipeUp.registerOnChangeListener(waysListener)
        prefs.keyboard.swipeDown.registerOnChangeListener(waysListener)
    }

    // worked out once a touch: its preview is asked on every move. Shift can change under it from another
    // finger: the swipe types the case its touch began in, as its preview showed it
    private class Reach(val def: KeyDef, val key: KeySwipe.Key, val first: BaseKeyboard.SwipeChoice)

    private val reaches = HashMap<KeyRef, Reach>()

    fun began(ref: KeyRef) {
        reaches.remove(ref)
    }

    private fun reach(ref: KeyRef, def: KeyDef): Reach = reaches[ref]?.takeIf { it.def === def }
        ?: keyboard.swipeText(def).let { Reach(def, swipeKey(def, it), it) }.also { reaches[ref] = it }

    /** A key of no name (注音九键's ˉ): its built-in swipe the way the slots' orientation puts a long press's first. */
    private fun swipeKey(def: KeyDef, first: BaseKeyboard.SwipeChoice): KeySwipe.Key {
        val name = def.shortcut
        val builtIn = def.behaviors.any { it is KeyDef.Behavior.Swipe }
        val hasFirst = when (first) {
            BaseKeyboard.SwipeChoice.BuiltIn -> builtIn
            BaseKeyboard.SwipeChoice.None -> false
            is BaseKeyboard.SwipeChoice.Output -> true
        }
        val slots = SwipeSlotsCache.current
        if (name == null) return KeySwipe.Key.unnamed(slots.orientation, hasFirst)
        val key = KeyId.of(name).name
        return KeySwipe.Key(tapsOnNothing(key), slots.resolve(key, up = true), slots.resolve(key, up = false), hasFirst)
    }

    // both off no swipe on any key
    private val ways: KeySwipe.Ways get() = swipeWays()

    fun preview(key: KeyHandle, def: KeyDef, totalY: Int, popup: KeyDef.Popup.AltPreview, content: String = popup.content): PopupAction.PreviewUpdateAction =
        when (val preview = if (selecting == key.ref) SWIPE_SELECT else KeySwipe.preview(reach(key.ref, def).key, ways, totalY, keyboard.inPasswordField, KeyClock::calendar)) {
            KeySwipe.Preview.Symbol -> reach(key.ref, def).first.let {
                PopupAction.PreviewUpdateAction(key.ref, it.preview(popup.alternative), shown = it is BaseKeyboard.SwipeChoice.Output)
            }
            is KeySwipe.Preview.Shortcut -> PopupAction.PreviewUpdateAction(key.ref, shortcutLabel(preview.shortcut), words = true)
            KeySwipe.Preview.Own -> PopupAction.PreviewUpdateAction(key.ref, content)
        }

    private fun shortcutLabel(shortcut: KeyShortcut): String = when (shortcut) {
        is KeyShortcut.Command -> board.context.getString(KeyShortcutLooks.of(shortcut.command).label)
        // the clock read only for variables: this runs each Move
        is KeyShortcut.Text -> if (shortcut.variables) shortcut.typed(KeyClock.calendar()) else shortcut.text
        KeyShortcut.None -> ""
    }

    /** None after its long press showed a popup ([longPopup]). */
    fun attach(key: KeyHandle, def: KeyDef, longPopup: LongPopup) {
        val builtIn = def.behaviors.firstNotNullOfOrNull { (it as? KeyDef.Behavior.Swipe)?.action }
        if (builtIn == null && def.shortcut == null) return
        val both = def.swipesBothWays
        val g = key.keyGesture
        g.swipeEnabled = true
        g.swipeThresholdY = threshold
        if (!both) g.swipeThresholdX = disabledThreshold
        g.axisLock = both
        val held = Held()
        val old = key.gestureListener
        key.gestureListener = { v, event ->
            when (event.type) {
                GestureType.Down -> {
                    began(v.ref)
                    held.menu = false
                    // a touch taken without an Up (BaseKeyboard's cancel) may have left 滑选 on
                    selectLifted(v, held)
                    longPopup.shown = false
                    false
                }
                GestureType.Move -> when {
                    held.menu || held.select -> moved(v, event, held)
                    longPopup.shown || event.countY == 0 || event.totalY - event.countY != 0 -> false
                    else -> swipeReached(v, def, event, held)
                }
                GestureType.Up -> when {
                    held.menu -> menuLifted(v, event, held)
                    held.select -> selectLifted(v, held)
                    else -> lifted(v, def, event, builtIn, longPopup.shown)
                }
            } || old?.invoke(v, event) ?: false
        }
    }

    private class Held {
        var menu = false
        var select = false
        val steps = SwipeSteps()
        val lines = CrossSteps()
    }

    // one that types a character, ，。 too, on every keyboard
    private fun tapsOnNothing(key: String) = SwipeSlots.typesAChar(key)

    // no cursor moved under the menu either
    private fun moved(key: KeyHandle, event: CustomGestureView.Event, held: Held): Boolean {
        if (held.menu) {
            keyboard.onPopupChangeFocus(key.ref, event.x, event.y)
            return true
        }
        val n = held.steps.step(event.x, selectThreshold)
        // once a sideways step was taken, so the swipe that reached 滑选 moves no line
        val lines = keyboard.lines(held.lines.step(event.y, n, lineStep))
        if (n != 0 || lines.isNotEmpty()) keyboard.onAction(KeyAction.SwipeSelectAction(n, lines))
        return true
    }

    // let go over nothing (a greyed cell, back down on the key): the menu gone, no tap
    private fun menuLifted(key: KeyHandle, event: CustomGestureView.Event, held: Held): Boolean {
        held.menu = false
        if (!keyboard.onPopupTrigger(key.ref, event.cancelled)) keyboard.onPopupAction(PopupAction.DismissAction(key.ref))
        return true
    }

    // cancelled or not: nothing run, no tap
    private fun selectLifted(key: KeyHandle, held: Held): Boolean {
        if (held.select) keyboard.onAction(KeyAction.EndSwipeSelectAction)
        held.select = false
        if (selecting == key.ref) selecting = null
        return true
    }

    fun showMarks() = keyboard.marksChanged()

    // a buzz where it reaches anything
    private fun swipeReached(key: KeyHandle, def: KeyDef, event: CustomGestureView.Event, held: Held): Boolean {
        val preview = KeySwipe.preview(reach(key.ref, def).key, ways, event.totalY, keyboard.inPasswordField, KeyClock::calendar)
        if (preview == KeySwipe.Preview.Own) return false
        val menu = preview == EDIT_MENU || preview == INPUT_METHOD_MENU
        // a key the scene drawn last has not: nothing opens, so nothing felt
        val bounds = if (menu) key.bounds ?: return false else null
        // a menu's once it opened
        if (!menu) InputFeedbacks.hapticFeedback(key.feedback)
        if (preview == SWIPE_SELECT) {
            held.select = true
            selecting = key.ref
            held.steps.start(event.x)
            held.lines.reset()
            keyboard.onAction(KeyAction.BeginSwipeSelectAction)
            return true
        }
        if (bounds == null) return false
        val down = event.totalY > 0
        val shown = if (preview == EDIT_MENU) {
            PopupAction.ShowEditMenuAction(key.ref, bounds, keyboard.inPasswordField, down).also(keyboard::onPopupAction).outShown
        } else {
            PopupAction.ShowInputMethodMenuAction(key.ref, bounds, down).also(keyboard::onPopupAction).outShown
        }
        // no room nor scene for it: as with no bounds, the lift runs the swipe's command (the input methods' picker)
        if (!shown) return false
        InputFeedbacks.hapticFeedback(key.feedback)
        // where it opened is where back up is measured from: a flick back onto the key is the first move after
        if (down) keyboard.onPopupChangeFocus(key.ref, event.x, event.y)
        held.menu = true
        return true
    }

    private fun lifted(key: KeyHandle, def: KeyDef, event: CustomGestureView.Event, builtIn: KeyAction?, longPopup: Boolean): Boolean {
        val outcome = KeySwipe.decide(reach(key.ref, def).key, ways, event.totalY, event.swipedY, event.consumed || longPopup, event.cancelled, keyboard.inPasswordField, KeyClock::calendar)
        return ran(key, def, outcome, builtIn)
    }

    private fun ran(key: KeyHandle, def: KeyDef, outcome: KeySwipe.Outcome, builtIn: KeyAction?): Boolean =
        when (outcome) {
            KeySwipe.Outcome.Symbol -> {
                when (val choice = reach(key.ref, def).first) {
                    is BaseKeyboard.SwipeChoice.Output -> keyboard.onAction(choice.fresh?.invoke() ?: choice.action, KeyActionListener.Source.Popup)
                    else -> builtIn?.let { choice.typed(it) }?.let { (action, source) -> keyboard.onAction(action, source) }
                }
                // consumed when it types nothing too, or a swipe that stayed on the key would type it as a tap
                true
            }
            KeySwipe.Outcome.Tap -> {
                // one that left the key is no click to its touch; consumed, so one on it clicks once
                key.click()
                true
            }
            KeySwipe.Outcome.Cancel -> true
            is KeySwipe.Outcome.Shortcut -> {
                keyboard.onAction(KeyAction.ShortcutAction(outcome.shortcut))
                true
            }
            KeySwipe.Outcome.None -> false
        }

    private companion object {
        val SWIPE_SELECT = KeySwipe.Preview.Shortcut(KeyShortcut.Command(KeyCommand.SwipeSelect))
        val EDIT_MENU = KeySwipe.Preview.Shortcut(KeyShortcut.Command(KeyCommand.EditMenu))
        val INPUT_METHOD_MENU = KeySwipe.Preview.Shortcut(KeyShortcut.Command(KeyCommand.ChooseInputMethod))
    }
}
