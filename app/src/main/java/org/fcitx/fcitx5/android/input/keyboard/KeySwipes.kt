/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.res.Configuration
import android.view.View
import androidx.annotation.Keep
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.GestureType
import org.fcitx.fcitx5.android.input.keyboard.CustomGestureView.OnGestureListener
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts

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
    private val keyboard: Keyboard,
) {
    /** What of [BaseKeyboard] a swipe reads and acts on. */
    interface Keyboard {
        val inPasswordField: Boolean
        fun swipeText(view: KeyView): BaseKeyboard.SwipeChoice
        fun onAction(action: KeyAction, source: KeyActionListener.Source = KeyActionListener.Source.Keyboard)
        fun onPopupAction(action: PopupAction)
        fun onPopupChangeFocus(viewId: Int, x: Float, y: Float): Boolean
        fun onPopupTrigger(viewId: Int, cancelled: Boolean): Boolean
    }

    private val prefs = AppPrefs.getInstance()
    private val swipeLayersChoice by prefs.keyboard.swipeLayers
    private val swipeLayers: SwipeLayers get() = swipeLayersChoice.layers

    // the keys whose swipes reach something ([attach]), their marks drawn again as the settings change
    private val swipingKeys = mutableListOf<Pair<KeyView, KeyDef>>()

    @Keep
    private val swipeLayersListener = ManagedPreference.OnChangeListener<SwipeLayersChoice> { _, _ -> showMarks() }
    @Keep
    private val keyShortcutsListener = ManagedPreference.OnChangeListener<String> { _, _ -> showMarks() }

    init {
        if (shortcuts) {
            prefs.keyboard.swipeLayers.registerOnChangeListener(swipeLayersListener)
            prefs.internal.keyShortcuts.registerOnChangeListener(keyShortcutsListener)
        }
    }

    /** What [view] of [def] swipes to as the settings are now ([KeySwipe.Key]). */
    private fun swipeKey(view: KeyView, def: KeyDef): KeySwipe.Key {
        val name = def.shortcut?.takeIf { shortcuts }
        val stored = KeyShortcutsCache.current
        return if (name == null || '/' in name) {
            val symbol = def.behaviors.any { it is KeyDef.Behavior.Swipe } && keyboard.swipeText(view) != BaseKeyboard.SwipeChoice.None
            KeySwipe.Key.Char(symbol, name?.let(stored::resolve) ?: KeyShortcut.None)
        } else {
            KeySwipe.Key.Function(stored.resolve(KeyShortcuts.functionKey(name, up = true)), stored.resolve(KeyShortcuts.functionKey(name, up = false)))
        }
    }

    /** What the preview of [view] of [def] shows, swiped [totalY]: its [popup]'s own content, or what the swipe reaches. */
    fun preview(view: KeyView, def: KeyDef, totalY: Int, popup: KeyDef.Popup.AltPreview): String =
        when (val preview = KeySwipe.preview(swipeKey(view, def), swipeLayers, totalY, keyboard.inPasswordField)) {
            KeySwipe.Preview.Symbol -> keyboard.swipeText(view).preview(popup.alternative)
            is KeySwipe.Preview.Shortcut -> shortcutLabel(preview.shortcut)
            KeySwipe.Preview.Own -> popup.content
        }

    /** What the preview shows of [shortcut]: a command's name, a text as it is. */
    private fun shortcutLabel(shortcut: KeyShortcut): String = when (shortcut) {
        is KeyShortcut.Command -> board.context.getString(KeyShortcutLooks.of(shortcut.command).label)
        is KeyShortcut.Text -> shortcut.text.take(PREVIEW_TEXT)
        KeyShortcut.None -> ""
    }

    /**
     * [view]'s vertical swipe, of [def] ([KeySwipe]): a step past the threshold buzzing once, the edit
     * menu opened there and picked from as the finger moves on; none after its long press showed a
     * popup ([longPopup]).
     */
    fun attach(view: KeyView, def: KeyDef, longPopup: BooleanArray) {
        val builtIn = def.behaviors.firstNotNullOfOrNull { (it as? KeyDef.Behavior.Swipe)?.action }
        if (builtIn == null && (def.shortcut == null || !shortcuts)) return
        val both = def is SpaceKey || def is MiniSpaceKey || def is BackspaceKey
        if (shortcuts) swipingKeys.add(view to def)
        view.swipeEnabled = true
        view.swipeThresholdY = threshold
        if (!both) view.swipeThresholdX = disabledThreshold
        view.axisLock = both
        var menu = false
        val old = view.onGestureListener ?: OnGestureListener.Empty
        view.onGestureListener = OnGestureListener { v, event ->
            v as KeyView
            when (event.type) {
                GestureType.Down -> {
                    menu = false
                    longPopup[0] = false
                    false
                }
                GestureType.Move -> when {
                    // the menu has the touch: no cursor moved under it either
                    menu -> {
                        keyboard.onPopupChangeFocus(v.id, event.x, event.y)
                        true
                    }
                    longPopup[0] || event.countY == 0 || event.totalY - event.countY != 0 -> false
                    else -> {
                        menu = swipeReached(v, def, event.totalY)
                        menu
                    }
                }
                GestureType.Up -> if (menu) {
                    menu = false
                    // let go over nothing (a greyed cell, back down on the key): the menu gone, no tap
                    if (!keyboard.onPopupTrigger(v.id, event.cancelled)) keyboard.onPopupAction(PopupAction.DismissAction(v.id))
                    true
                } else {
                    lifted(v, def, event, builtIn, longPopup[0])
                }
            } || old.onGesture(v, event)
        }
    }

    /** Each swiping key's marks ([KeyMarks]) as the settings are now: its symbol and its shortcut. */
    fun showMarks() {
        val layers = swipeLayers
        val position = when (ThemeManager.prefs.punctuationPosition.getValue()) {
            PunctuationPosition.TopRight -> KeyMarks.Position.TopRight
            PunctuationPosition.Bottom -> KeyMarks.Position.Bottom
            PunctuationPosition.None -> KeyMarks.Position.None
        }
        val landscape = board.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        swipingKeys.forEach { (view, def) ->
            val placed = when (val key = swipeKey(view, def)) {
                is KeySwipe.Key.Char -> {
                    val symbol = KeyView.Mark.Symbol.takeIf { (view as? AltTextKeyView)?.altText?.text?.isNotEmpty() == true }
                    KeyMarks.place(symbol, markOf(key.shortcut), layers.wayOf(SwipeLayers.Layer.Shortcut), position, landscape)
                }
                is KeySwipe.Key.Function -> KeyMarks.placeFunction(
                    markOf(key.up), markOf(key.down), layers.up != SwipeLayers.Layer.Off, layers.down != SwipeLayers.Layer.Off, position,
                )
            }
            view.showMarks(placed.topRight, placed.bottomRight, placed.below)
        }
    }

    private fun markOf(shortcut: KeyShortcut): KeyView.Mark? = when (shortcut) {
        is KeyShortcut.Command -> KeyView.Mark.Icon(KeyShortcutLooks.of(shortcut.command).icon)
        is KeyShortcut.Text -> KeyView.Mark.Text(shortcut.text)
        KeyShortcut.None -> null
    }

    // the threshold just passed ([attach]): a buzz where it reaches anything; whether it opened the edit menu
    private fun swipeReached(view: KeyView, def: KeyDef, totalY: Int): Boolean {
        val preview = KeySwipe.preview(swipeKey(view, def), swipeLayers, totalY, keyboard.inPasswordField)
        if (preview == KeySwipe.Preview.Own) return false
        InputFeedbacks.hapticFeedback(view)
        if (preview != KeySwipe.Preview.Shortcut(KeyShortcut.Command(KeyCommand.EditMenu))) return false
        keyboard.onPopupAction(PopupAction.ShowEditMenuAction(view.id, view.bounds, keyboard.inPasswordField))
        return true
    }

    private fun lifted(view: KeyView, def: KeyDef, event: CustomGestureView.Event, builtIn: KeyAction?, longPopup: Boolean): Boolean =
        when (val outcome = KeySwipe.decide(swipeKey(view, def), swipeLayers, event.totalY, event.swipedY, event.consumed || longPopup, event.cancelled, keyboard.inPasswordField)) {
            KeySwipe.Outcome.Symbol -> {
                builtIn?.let { keyboard.swipeText(view).typed(it) }?.let { (action, source) -> keyboard.onAction(action, source) }
                // consumed when it types nothing too, or a swipe that stayed on the key would type it as a tap
                true
            }
            KeySwipe.Outcome.Tap -> {
                // the key's click, here: one that left the key is no click to the view; consumed, so one on it clicks once
                view.performClick()
                true
            }
            KeySwipe.Outcome.Cancel -> true
            is KeySwipe.Outcome.Shortcut -> {
                keyboard.onAction(KeyAction.ShortcutAction(outcome.shortcut))
                true
            }
            KeySwipe.Outcome.None -> false
        }
}

// a text shortcut's first characters in the preview, the rest of it too long to read there
private const val PREVIEW_TEXT = 12
