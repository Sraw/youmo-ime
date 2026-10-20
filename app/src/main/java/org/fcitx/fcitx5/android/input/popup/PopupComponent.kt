/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.imeScope
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.longPressOf
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import java.util.LinkedList
import kotlin.math.ceil
import kotlin.math.max

class PopupComponent :
    UniqueComponent<PopupComponent>(), Dependent, ManagedHandler by managedHandler() {

    private val imeScope by manager.imeScope()
    private val context by manager.context()
    private val theme by manager.theme()
    private val punctuation: PunctuationComponent by manager.must()

    private val showingEntryUi = HashMap<Int, PopupEntryUi>()
    private val dismissJobs = HashMap<Int, Job>()
    private val freeEntryUi = LinkedList<PopupEntryUi>()

    private val showingContainerUi = HashMap<Int, PopupContainerUi>()

    private val keyBottomMargin by lazy {
        context.dp(ThemeManager.prefs.keyVerticalMargin.getValue())
    }
    private val minPopupWidth by lazy {
        context.dp(38)
    }
    private val popupPadding by lazy {
        context.dp(TEXT_PADDING)
    }
    private val popupHeight by lazy {
        context.dp(116)
    }
    private val popupKeyHeight by lazy {
        context.dp(48)
    }
    private val popupRadius by lazy {
        context.dp(ThemeManager.prefs.keyRadius.getValue()).toFloat()
    }
    private val hideThreshold = 100L

    private val rootLocation = intArrayOf(0, 0)
    private val rootBounds: Rect = Rect()

    val root by lazy {
        context.frameLayout {
            // we want (0, 0) at top left
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            isClickable = false
            isFocusable = false

            addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                val (x, y) = rootLocation.also { v.getLocationInWindow(it) }
                val width = right - left
                val height = bottom - top
                rootBounds.set(x, y, x + width, y + height)
            }
        }
    }

    private fun showPopup(viewId: Int, content: String, bounds: Rect) {
        showingEntryUi[viewId]?.apply {
            dismissJobs[viewId]?.also {
                dismissJobs.remove(viewId)?.cancel()
            }
            lastShowTime = System.currentTimeMillis()
            setText(content)
            place(this, bounds)
            return
        }
        val popup = (freeEntryUi.poll()
            ?: PopupEntryUi(context, theme, popupKeyHeight, popupRadius)).apply {
            lastShowTime = System.currentTimeMillis()
            setText(content)
        }
        place(popup, bounds)
        // make sure that popup.root does not have parent view before adding it under root container
        // it's wired that on some devices it would have a parent view despite it was newly created
        // or just polled from freeEntryUi
        if (popup.root.parent == null) {
            root.addView(popup.root)
        } else if (popup.root.parent !== root) {
            (popup.root.parent as? ViewGroup)?.removeView(popup.root)
            root.addView(popup.root)
        }
        showingEntryUi[viewId] = popup
    }

    private fun updatePopup(viewId: Int, content: String) {
        // under a long press's keys it stays as showKeyboard left it, empty and as wide as they are:
        // a letter's preview, wrapped by its long press, still sends this on every move
        if (viewId in showingContainerUi) return
        showingEntryUi[viewId]?.apply {
            if (textView.text.contentEquals(content)) return
            setText(content)
            place(this, trigger)
        }
    }

    /**
     * Over the key [bounds], [width] wide, by default as wide as the key's face (the bounds across)
     * and its text, never narrower than [minPopupWidth]: a fixed width over a wider key
     * (九键's, `.com`, any in landscape) left the key's pressed colour showing at both sides.
     * From [left], by default centred over the key.
     */
    private fun place(
        popup: PopupEntryUi,
        bounds: Rect,
        width: Int = entryWidth(popup, bounds),
        left: Int = (bounds.left + bounds.right - width) / 2,
    ) {
        popup.trigger.set(bounds)
        // align popup bottom with key border bottom [^1]
        val top = bounds.bottom - popupHeight - keyBottomMargin
        // new ones lay the whole input view out again: not for a move or a press that changes nothing
        if ((popup.root.layoutParams as? FrameLayout.LayoutParams)?.isAt(width, left, top) == true) return
        popup.root.layoutParams = FrameLayout.LayoutParams(width, popupHeight).apply {
            topMargin = top
            leftMargin = left
        }
    }

    private fun FrameLayout.LayoutParams.isAt(width: Int, left: Int, top: Int) =
        this.width == width && leftMargin == left && topMargin == top

    private fun entryWidth(popup: PopupEntryUi, bounds: Rect): Int {
        val text = ceil(popup.textWidth(popup.textView.text.toString())).toInt() + 2 * popupPadding
        return maxOf(minPopupWidth, bounds.width(), text)
    }

    private fun showKeyboard(viewId: Int, keyboard: KeyDef.Popup.Keyboard, bounds: Rect) {
        var keys: Array<String>
        var labels: Array<String>
        var split = Int.MAX_VALUE
        var asTyped = false
        when (keyboard) {
            is KeyDef.Popup.Keyboard.Preset -> {
                val preset = PopupOverridesCache.user.current.longPressOf(keyboard.label) ?: return
                keys = preset
                labels = if (keyboard.transformPunctuation && punctuation.enabled) {
                    Array(keys.size) { punctuation.transform(keys[it]) }
                } else keys
            }
            is KeyDef.Popup.Keyboard.Explicit -> {
                split = keyboard.symbolsFrom
                asTyped = keyboard.asTyped
                keys = keyboard.items
                labels = explicitLabels(keyboard) { if (punctuation.enabled) punctuation.transform(it) else it }
            }
        }
        // clear popup preview text         OR create empty popup preview
        showingEntryUi[viewId]?.setText("") ?: showPopup(viewId, "", bounds)
        val entry = showingEntryUi[viewId] ?: return
        val keyboardUi = PopupKeyboardUi(
            context,
            theme,
            rootBounds,
            bounds,
            { dismissPopup(viewId) },
            popupRadius,
            max(entry.root.layoutParams.width, minPopupWidth),
            popupKeyHeight,
            // position popup keyboard higher, because of [^1]
            popupHeight + keyBottomMargin,
            keys,
            labels,
            split,
            asTyped,
        )
        // the bubble under the key in focus, its width and place (moved in at an edge): one shape
        place(entry, bounds, keyboardUi.keyWidth, bounds.left + keyboardUi.focusedKeyLeft)
        showPopupContainer(viewId, keyboardUi)
    }

    private fun showMenu(viewId: Int, menu: KeyDef.Popup.Menu, bounds: Rect) {
        showingEntryUi[viewId]?.let {
            dismissPopupEntry(viewId, it)
        }
        val menuUi = PopupMenuUi(
            context,
            theme,
            rootBounds,
            bounds,
            { dismissPopup(viewId) },
            menu.items,
        )
        showPopupContainer(viewId, menuUi)
    }

    private fun showPopupContainer(viewId: Int, ui: PopupContainerUi) {
        root.apply {
            add(ui.root, lParams {
                leftMargin = ui.triggerBounds.left + ui.offsetX - rootBounds.left
                topMargin = ui.triggerBounds.top + ui.offsetY - rootBounds.top
            })
        }
        showingContainerUi[viewId] = ui
    }

    private fun changeFocus(viewId: Int, x: Float, y: Float): Boolean {
        return showingContainerUi[viewId]?.changeFocus(x, y) ?: false
    }

    private fun triggerFocused(viewId: Int): KeyAction? {
        return showingContainerUi[viewId]?.onTrigger()
    }

    private fun dismissPopup(viewId: Int) {
        dismissPopupContainer(viewId)
        showingEntryUi[viewId]?.also {
            val timeLeft = it.lastShowTime + hideThreshold - System.currentTimeMillis()
            if (timeLeft <= 0L) {
                dismissPopupEntry(viewId, it)
            } else {
                dismissJobs[viewId] = imeScope.launch {
                    delay(timeLeft)
                    dismissPopupEntry(viewId, it)
                    dismissJobs.remove(viewId)
                }
            }
        }
    }

    private fun dismissPopupContainer(viewId: Int) {
        showingContainerUi[viewId]?.also {
            showingContainerUi.remove(viewId)
            root.removeView(it.root)
        }
    }

    private fun dismissPopupEntry(viewId: Int, popup: PopupEntryUi) {
        showingEntryUi.remove(viewId)
        root.removeView(popup.root)
        freeEntryUi.add(popup)
    }

    fun dismissAll() {
        // avoid modifying collection while iterating
        dismissJobs.forEach { (_, job) ->
            job.cancel()
        }
        dismissJobs.clear()
        // too
        showingContainerUi.forEach { (_, container) ->
            root.removeView(container.root)
        }
        showingContainerUi.clear()
        // too too
        showingEntryUi.forEach { (_, entry) ->
            root.removeView(entry.root)
            freeEntryUi.add(entry)
        }
        showingEntryUi.clear()
    }

    val listener = PopupActionListener { action ->
        with(action) {
            when (this) {
                is PopupAction.ChangeFocusAction -> outResult = changeFocus(viewId, x, y)
                is PopupAction.DismissAction -> dismissPopup(viewId)
                is PopupAction.PreviewAction -> showPopup(viewId, content, bounds)
                is PopupAction.PreviewUpdateAction -> updatePopup(viewId, content)
                is PopupAction.ShowKeyboardAction -> showKeyboard(viewId, keyboard, bounds)
                is PopupAction.ShowMenuAction -> showMenu(viewId, menu, bounds)
                is PopupAction.TriggerAction -> outAction = triggerFocused(viewId)
            }
        }
    }

    companion object {
        /**
         * [keyboard]'s labels, those set apart ([KeyDef.Popup.Keyboard.Explicit.symbolsFrom]) as
         * [mapped]: shown as the engine will type them, as a Preset's are.
         */
        internal fun explicitLabels(keyboard: KeyDef.Popup.Keyboard.Explicit, mapped: (String) -> String): Array<String> =
            Array(keyboard.items.size) { if (it >= keyboard.symbolsFrom) mapped(keyboard.items[it]) else keyboard.items[it] }

        /**
         * Space each side of the bubble's text, in dp; a long press's keys leave as much each side
         * of their widest label, so the bubble and the key over it come out one width.
         */
        internal const val TEXT_PADDING = 6
    }
}
