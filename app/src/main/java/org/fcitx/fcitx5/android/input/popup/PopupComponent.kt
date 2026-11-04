/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.res.Configuration
import android.graphics.Rect
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.util.LinkedList
import kotlin.math.ceil
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.input.UndoChipPlacement
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.imeScope
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyShortcutLooks
import org.fcitx.fcitx5.android.input.keyboard.keyTextScale
import org.fcitx.fcitx5.android.input.keyboard.longPressOf
import org.fcitx.fcitx5.android.input.shortcut.EditMenuLayout
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.utils.popIn
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams

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
        val gap = theme.look.keys.gap
        context.dp(if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) gap.vLand else gap.v)
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

    // a long press's keys: one size whatever the key pressed, a 九键 key's as small as a letter's.
    // They were as wide as the key pressed: a 九键 key's 7 filled the screen across (A B C 2 a b c)
    private val longPressKeyWidth by lazy {
        context.dp(LONG_PRESS_KEY_WIDTH)
    }
    private val longPressKeyHeight by lazy {
        context.dp(LONG_PRESS_KEY_HEIGHT)
    }

    // the long press's keys over the key pressed, this much above its top: clear of the finger
    private val longPressLift by lazy {
        context.dp(LONG_PRESS_LIFT)
    }
    private val popupRadius by lazy {
        context.dp(theme.look.keys.radiusDp).toFloat()
    }
    private val hideThreshold = 100L

    /**
     * Where the edit menu goes, the input view's to say: a notice [width] × [height]'s place now
     * and the bar's middle, in window pixels ([EditMenuLayout.place]). Null, or none given: over the key.
     */
    var menuRoom: ((width: Int, height: Int) -> MenuRoom?)? = null

    /** The edit menu opened where the notices go ([menuRoom]): the notice's window, over it, put away or hidden. */
    var onMenuShown: (menu: Rect) -> Unit = {}

    /** That menu gone: a notice [onMenuShown] hid is back. */
    var onMenuGone: () -> Unit = {}

    // the key whose edit menu is up where the notices go, for [onMenuGone]
    private var menuAbove: Int? = null

    class MenuRoom(val notice: UndoChipPlacement.Place, val barMiddle: Int)

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
            setText(content, symbolSize())
            place(this, bounds)
            return
        }
        val popup = (freeEntryUi.poll()
            ?: PopupEntryUi(context, theme, popupKeyHeight, popupRadius)).apply {
            lastShowTime = System.currentTimeMillis()
            setText(content, symbolSize())
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

    private fun updatePopup(viewId: Int, content: String, words: Boolean) {
        // a long press's keys shown, showKeyboard dismissed the bubble: a letter's preview, wrapped by
        // its long press, still sends this on every move, not to show it again over them
        if (viewId in showingContainerUi) return
        showingEntryUi[viewId]?.apply {
            val fit = if (words && PreviewLabel.isWords(content)) wordsFit(content, this) else null
            val text = fit?.text ?: content
            val px = fit?.size ?: symbolSize()
            if (textView.text.contentEquals(text) && textView.textSize == px) return
            setText(text, px)
            place(this, trigger)
        }
    }

    /**
     * A swipe's shortcut named in [popup] ([PreviewLabel]): words at a function key's size, as the
     * keys' text is scaled (紧凑, floating), in a bubble no wider than five of them or the key.
     */
    private fun wordsFit(label: String, popup: PopupEntryUi): PreviewLabel.Fit {
        val scale = keyTextScale(context)
        val width = popup.trigger.width()
        // asked again on every move of the finger past the threshold: measured once for the same label
        lastFit?.takeIf { it.label == label && it.scale == scale && it.width == width }?.let { return it.fit }
        val size = sp(PreviewLabel.WORDS_SP * scale)
        val room = PreviewLabel.room(width.toFloat(), size, popupPadding.toFloat())
        val paint = TextPaint(popup.textView.paint)
        val fit = PreviewLabel.fit(label, size, sp(PreviewLabel.MIN_SP * scale), room) { s, px ->
            paint.textSize = px
            paint.measureText(s)
        }
        lastFit = FitFor(label, scale, width, fit)
        return fit
    }

    private class FitFor(val label: String, val scale: Float, val width: Int, val fit: PreviewLabel.Fit)

    private var lastFit: FitFor? = null

    /** A key's letter or symbol in the bubble: scaled as the keys' text is (紧凑, floating), as the words are. */
    private fun symbolSize() = sp(PreviewLabel.symbolSize(keyTextScale(context)))

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)

    /**
     * Over the key [bounds], [width] wide, by default as wide as the key's face (the bounds across)
     * and its text, never narrower than [minPopupWidth]: a fixed width over a wider key
     * (九键's, `.com`, any in landscape) left the key's pressed colour showing at both sides.
     * From [left], by default centred over the key, kept inside the keyboard: a table's roots
     * (TextKeyboard) over A were wider than the half of it left of A.
     */
    private fun place(
        popup: PopupEntryUi,
        bounds: Rect,
        width: Int = entryWidth(popup, bounds),
        left: Int = ((bounds.left + bounds.right - width) / 2).let { if (root.width > width) it.coerceIn(0, root.width - width) else it },
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
        val split: Int
        var asIs = false
        var inRow = -1
        when (keyboard) {
            is KeyDef.Popup.Keyboard.Preset -> {
                val preset = PopupOverridesCache.user.current.longPressOf(keyboard.label) ?: return
                keys = preset
                labels = if (keyboard.transformPunctuation && punctuation.enabled) {
                    Array(keys.size) { punctuation.transform(keys[it]) }
                } else keys
                split = keys.size
            }
            is KeyDef.Popup.Keyboard.Explicit -> {
                split = keyboard.symbolsFrom
                asIs = keyboard.asIs
                inRow = keyboard.inRow
                keys = keyboard.items
                labels = explicitLabels(keyboard) { if (punctuation.enabled) punctuation.transform(it) else it }
            }
        }
        // the keys float over the key on their own: the bubble under the one in focus, as one shape
        // with it, was as wide as the key pressed, and so were they
        showingEntryUi[viewId]?.let {
            dismissJobs.remove(viewId)?.cancel()
            dismissPopupEntry(viewId, it)
        }
        val keyboardUi = PopupKeyboardUi(
            context,
            theme,
            rootBounds,
            bounds,
            { dismissPopup(viewId) },
            popupRadius,
            longPressKeyWidth,
            longPressKeyHeight,
            longPressLift,
            keys,
            labels,
            split,
            asIs,
            inRow,
            onFocusMoved = { InputFeedbacks.hapticFeedback(it) },
        )
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
            onFocusMoved = { InputFeedbacks.hapticFeedback(it) },
        )
        showPopupContainer(viewId, menuUi)
    }

    private fun showEditMenu(viewId: Int, bounds: Rect, password: Boolean) {
        showingEntryUi[viewId]?.let {
            dismissPopupEntry(viewId, it)
        }
        val cells = EditMenuLayout.CELLS
        val cell = context.dp(PopupMenuUi.CELL_DP)
        // up where the notices go, out from under the finger; a notice there put away, its window over the row
        val at = menuRoom?.invoke(cell * cells.size, cell)?.let { room ->
            EditMenuLayout.place(room.notice, bounds.centerX(), cell, rootBounds.left, rootBounds.right, rootBounds.top, room.barMiddle)
        }
        if (at != null) {
            menuAbove = viewId
            onMenuShown(Rect(at.left, at.top, at.left + cell * cells.size, at.top + cell))
        }
        val menuUi = PopupMenuUi(
            context,
            theme,
            rootBounds,
            bounds,
            { dismissPopup(viewId) },
            cells.map {
                // its name as the settings and the swipe's preview say it: what TalkBack reads of the cell
                val looks = KeyShortcutLooks.of(it)
                KeyDef.Popup.Menu.Item(context.getString(looks.label), looks.icon, KeyAction.ShortcutAction(KeyShortcut.Command(it)))
            }.toTypedArray(),
            onFocusMoved = { InputFeedbacks.hapticFeedback(it) },
            fixed = PopupMenuUi.Fixed(
                start = EditMenuLayout.START,
                disabled = cells.indices.filter { EditMenuLayout.disabled(cells[it], password) }.toSet(),
                at = at,
            ),
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
        // out of the key pressed (its middle, in the container's pixels); it goes at once, the
        // character picked typed already. Focus follows the finger by position, not by its scale
        ui.root.popIn(
            (ui.triggerBounds.width() / 2 - ui.offsetX).toFloat(),
            (ui.triggerBounds.height() / 2 - ui.offsetY).toFloat(),
        )
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
        menuGone(viewId)
    }

    private fun menuGone(viewId: Int?) {
        if (viewId == null || viewId != menuAbove) return
        menuAbove = null
        onMenuGone()
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
        menuGone(menuAbove)
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
                is PopupAction.PreviewUpdateAction -> updatePopup(viewId, content, words)
                is PopupAction.ShowKeyboardAction -> showKeyboard(viewId, keyboard, bounds)
                is PopupAction.ShowMenuAction -> showMenu(viewId, menu, bounds)
                is PopupAction.ShowEditMenuAction -> showEditMenu(viewId, bounds, password)
                is PopupAction.TriggerAction -> outAction = triggerFocused(viewId)
            }
        }
    }

    companion object {
        /**
         * [keyboard]'s labels, those set apart ([KeyDef.Popup.Keyboard.Explicit.symbolsFrom]) as
         * [mapped]: shown as the engine will type them, as a Preset's are. So is a mark in the row
         * before them (the 14 keys' corner, as its key shows it); its letters and digits are as
         * they are, and nothing of one committed as it is ([KeyDef.Popup.Keyboard.Explicit.asIs]).
         */
        internal fun explicitLabels(keyboard: KeyDef.Popup.Keyboard.Explicit, mapped: (String) -> String): Array<String> =
            Array(keyboard.items.size) {
                val item = keyboard.items[it]
                val maps = !keyboard.asIs && (it >= keyboard.symbolsFrom || item.none(Char::isLetterOrDigit))
                if (maps) mapped(item) else item
            }

        /**
         * Space each side of the bubble's text, in dp; a long press's keys leave as much each side
         * of their widest label, so the bubble and the key over it come out one width.
         */
        internal const val TEXT_PADDING = 6

        /** A long press's keys' least width, their height and their lift over the key, in dp. */
        internal const val LONG_PRESS_KEY_WIDTH = 34
        internal const val LONG_PRESS_KEY_HEIGHT = 40
        internal const val LONG_PRESS_LIFT = 6
    }
}
