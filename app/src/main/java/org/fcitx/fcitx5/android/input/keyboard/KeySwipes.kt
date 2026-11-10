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
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts

/** Whether a key's long press showed a popup in this touch: set by its popup's listener, read by its swipe. */
internal class LongPopup {
    var shown = false
}

/**
 * A keyboard's keys' vertical swipes ([KeySwipe]): to their symbol (a [KeyDef.Behavior.Swipe]) and
 * their shortcut ([KeyDef.shortcut]), and the marks that show both. Apart from [BaseKeyboard], which
 * makes the keys and has the rest of their touch; [keyboard] is what of it the swipes reach.
 */
internal class KeySwipes(
    private val board: View,
    /** Whether the keys' swipes reach their shortcuts: the typing keyboards', not a panel's. */
    private val shortcuts: Boolean,
    private val threshold: Float,
    private val disabledThreshold: Float,
    /** 滑选's step sideways: the backspace's selection's, a character there as far as one it deletes. */
    private val selectThreshold: Float,
    private val keyboard: Keyboard,
) {
    /** What of [BaseKeyboard] a swipe reads and acts on. */
    interface Keyboard {
        val inPasswordField: Boolean

        /** Whether a character key's shortcut is marked on it: its swipe runs it all the same. */
        val charShortcutMarks: Boolean
        fun swipeText(def: KeyDef): BaseKeyboard.SwipeChoice
        fun onAction(action: KeyAction, source: KeyActionListener.Source = KeyActionListener.Source.Keyboard)
        fun onPopupAction(action: PopupAction)
        fun onPopupChangeFocus(key: KeyRef, x: Float, y: Float): Boolean
        fun onPopupTrigger(key: KeyRef, cancelled: Boolean): Boolean

        /** The marks' settings changed with the keyboard up: a scene drawing the keys makes its marks again. */
        fun marksChanged()
    }

    private val prefs = AppPrefs.getInstance()
    private val swipeLayersChoice by prefs.keyboard.swipeLayers
    private val swipeLayers: SwipeLayers get() = swipeLayersChoice.layers
    private val hapticOnRepeat by prefs.keyboard.hapticOnRepeat

    /** The key whose touch 滑选 has: its bubble names 滑选 till the lift, wherever the finger drifts up or down. */
    private var selecting: KeyRef? = null

    @Keep
    private val swipeLayersListener = ManagedPreference.OnChangeListener<SwipeLayersChoice> { _, _ -> showMarks() }
    @Keep
    private val keyShortcutsListener = ManagedPreference.OnChangeListener<String> { _, _ -> showMarks() }
    @Keep
    private val markPositionListener = ManagedPreference.OnChangeListener<KeyMarkPosition> { _, _ -> showMarks() }

    init {
        if (shortcuts) {
            prefs.keyboard.swipeLayers.registerOnChangeListener(swipeLayersListener)
            prefs.internal.keyShortcuts.registerOnChangeListener(keyShortcutsListener)
            prefs.keyboard.shortcutMarkPosition.registerOnChangeListener(markPositionListener)
            prefs.keyboard.punctuationPosition.registerOnChangeListener(markPositionListener)
        }
    }

    /** What the key of [def] swipes to as the settings are now ([KeySwipe.Key]). */
    private fun swipeKey(def: KeyDef): KeySwipe.Key {
        val name = def.shortcut?.takeIf { shortcuts }
        val stored = KeyShortcutsCache.current
        return if (name == null || '/' in name) {
            val symbol = def.behaviors.any { it is KeyDef.Behavior.Swipe } && keyboard.swipeText(def) != BaseKeyboard.SwipeChoice.None
            KeySwipe.Key.Char(symbol, name?.let(stored::resolve) ?: KeyShortcut.None)
        } else {
            KeySwipe.Key.Function(stored.resolve(KeyShortcuts.functionKey(name, up = true)), stored.resolve(KeyShortcuts.functionKey(name, up = false)))
        }
    }

    /** What the preview of [key] of [def] shows, swiped [totalY]: its [popup]'s own content, or what the swipe reaches. */
    fun preview(key: KeyHandle, def: KeyDef, totalY: Int, popup: KeyDef.Popup.AltPreview, content: String = popup.content): PopupAction.PreviewUpdateAction =
        when (val preview = if (selecting == key.ref) SWIPE_SELECT else KeySwipe.preview(swipeKey(def), swipeLayers, totalY, keyboard.inPasswordField)) {
            KeySwipe.Preview.Symbol -> PopupAction.PreviewUpdateAction(key.ref, keyboard.swipeText(def).preview(popup.alternative))
            is KeySwipe.Preview.Shortcut -> PopupAction.PreviewUpdateAction(key.ref, shortcutLabel(preview.shortcut), words = true)
            KeySwipe.Preview.Own -> PopupAction.PreviewUpdateAction(key.ref, content)
        }

    /** What the preview shows of [shortcut]: a command's name, a text as it is (fitted to the bubble by [org.fcitx.fcitx5.android.input.popup.PopupComponent]). */
    private fun shortcutLabel(shortcut: KeyShortcut): String = when (shortcut) {
        is KeyShortcut.Command -> board.context.getString(KeyShortcutLooks.of(shortcut.command).label)
        is KeyShortcut.Text -> shortcut.text
        KeyShortcut.None -> ""
    }

    /**
     * [key]'s vertical swipe, of [def] ([KeySwipe]): a step past the threshold buzzing once, the edit
     * menu opened there and picked from as the finger moves on; none after its long press showed a
     * popup ([longPopup]). Its marks are the scene's.
     */
    fun attach(key: KeyHandle, def: KeyDef, longPopup: LongPopup) {
        val builtIn = def.behaviors.firstNotNullOfOrNull { (it as? KeyDef.Behavior.Swipe)?.action }
        if (builtIn == null && (def.shortcut == null || !shortcuts)) return
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

    /** What has a key's touch since its swipe passed the threshold: the edit menu, or 滑选 with its steps sideways. */
    private class Held {
        var menu = false
        var select = false
        val steps = SwipeSteps()
    }

    // the finger moved on, [held]: the menu's cell picked by where it is (no cursor moved under it either), or 滑选's steps
    private fun moved(key: KeyHandle, event: CustomGestureView.Event, held: Held): Boolean {
        if (held.menu) {
            keyboard.onPopupChangeFocus(key.ref, event.x, event.y)
            return true
        }
        val n = held.steps.step(event.x, selectThreshold)
        if (n != 0) {
            keyboard.onAction(KeyAction.SwipeSelectAction(n))
            if (hapticOnRepeat) InputFeedbacks.hapticFeedback(key.feedback)
        }
        return true
    }

    // let go over nothing (a greyed cell, back down on the key): the menu gone, no tap
    private fun menuLifted(key: KeyHandle, event: CustomGestureView.Event, held: Held): Boolean {
        held.menu = false
        if (!keyboard.onPopupTrigger(key.ref, event.cancelled)) keyboard.onPopupAction(PopupAction.DismissAction(key.ref))
        return true
    }

    // the selection stays as the finger left it, cancelled or not: nothing run, no tap
    private fun selectLifted(key: KeyHandle, held: Held): Boolean {
        held.select = false
        if (selecting == key.ref) selecting = null
        return true
    }

    /** The keys' marks made again by the scene as the settings are now: their symbols and their shortcuts. */
    fun showMarks() = keyboard.marksChanged()

    // the threshold just passed ([attach]): a buzz where it reaches anything; whether it took the touch, [held]
    // by the edit menu opened, or by 滑选, its steps counted from here
    private fun swipeReached(key: KeyHandle, def: KeyDef, event: CustomGestureView.Event, held: Held): Boolean {
        val preview = KeySwipe.preview(swipeKey(def), swipeLayers, event.totalY, keyboard.inPasswordField)
        if (preview == KeySwipe.Preview.Own) return false
        val editMenu = preview == KeySwipe.Preview.Shortcut(KeyShortcut.Command(KeyCommand.EditMenu))
        // the menu over a key the scene drawn last has not: nothing opens, so nothing felt
        val bounds = if (editMenu) key.bounds ?: return false else null
        InputFeedbacks.hapticFeedback(key.feedback)
        if (preview == SWIPE_SELECT) {
            held.select = true
            selecting = key.ref
            held.steps.start(event.x)
            keyboard.onAction(KeyAction.BeginSwipeSelectAction)
            return true
        }
        if (bounds == null) return false
        keyboard.onPopupAction(PopupAction.ShowEditMenuAction(key.ref, bounds, keyboard.inPasswordField))
        held.menu = true
        return true
    }

    private fun lifted(key: KeyHandle, def: KeyDef, event: CustomGestureView.Event, builtIn: KeyAction?, longPopup: Boolean): Boolean =
        when (val outcome = KeySwipe.decide(swipeKey(def), swipeLayers, event.totalY, event.swipedY, event.consumed || longPopup, event.cancelled, keyboard.inPasswordField)) {
            KeySwipe.Outcome.Symbol -> {
                builtIn?.let { keyboard.swipeText(def).typed(it) }?.let { (action, source) -> keyboard.onAction(action, source) }
                // consumed when it types nothing too, or a swipe that stayed on the key would type it as a tap
                true
            }
            KeySwipe.Outcome.Tap -> {
                // the key's click, here: one that left the key is no click to its touch; consumed, so one on it clicks once
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
    }
}
