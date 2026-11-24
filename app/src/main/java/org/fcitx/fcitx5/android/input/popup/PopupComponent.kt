/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import java.util.LinkedList
import kotlin.math.floor
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.UndoChipPlacement
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.dependency.TypedComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.imeScope
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.keyboard.KeyShortcutLooks
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyMotionSwitch
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupSceneView
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupScenes
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.keyTextScale
import org.fcitx.fcitx5.android.input.shortcut.EditMenuLayout
import org.fcitx.fcitx5.android.input.shortcut.InputMethodMenu
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.PopupMotion
import org.fcitx.fcitx5.android.scene.engine.Popups
import org.fcitx.fcitx5.android.scene.keys.KeyDefaults
import org.fcitx.fcitx5.android.scene.theme.Transition
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.utils.popIn
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import timber.log.Timber

class PopupComponent :
    TypedComponent<PopupComponent>(), Dependent, ManagedHandler by managedHandler() {

    private val imeScope by manager.imeScope()
    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val theme by manager.theme()
    private val punctuation: PunctuationComponent by manager.must()

    private val showingEntryUi = HashMap<KeyRef, PopupEntryUi>()
    private val dismissJobs = HashMap<KeyRef, Job>()
    private val freeEntryUi = LinkedList<PopupEntryUi>()

    private val showingContainerUi = HashMap<KeyRef, PopupContainerUi>()

    // each popup's theme `transition` (null: at once), and the views fading out now with what follows
    private val goings = HashMap<View, Transition>()
    private val fading = HashMap<View, () -> Unit>()

    // a bubble sooner than its motion after the last does not move (as a key typed fast)
    private var lastPreviewAt = Long.MIN_VALUE / 2

    private val keyBottomMargin by lazy {
        val gap = ThemeManager.shownLook(theme).keys.gap
        context.dp(if (KeyboardArea.landscape(context)) gap.vLand else gap.v)
    }
    private val hideThreshold = 100L

    // in the theme the popups draw in now: remade where that changed (the shown keyboard's keys fell back
    // or no longer), the pooled popups let go with them
    private var scenesMade: Pair<String, PopupScenes>? = null
    private val scenes: PopupScenes
        get() {
            val drawn = SceneBoard.drawnTheme(theme).name
            scenesMade?.takeIf { it.first == drawn }?.let { return it.second }
            freeEntryUi.clear()
            return PopupScenes(root, theme, measuring).also { scenesMade = drawn to it }
        }

    @VisibleForTesting
    internal val scenesNow: PopupScenes get() = scenes

    @VisibleForTesting
    internal val pooled: List<PopupEntryUi> get() = freeEntryUi

    @VisibleForTesting
    internal fun showing(key: KeyRef): PopupEntryUi? = showingEntryUi[key]

    /** A test's, to make it fail. */
    @VisibleForTesting
    internal var measuring: (TextMeasurer) -> TextMeasurer = { it }

    /** Null where the theme did not convert: no popup then, logged, never another drawing. */
    private fun scenesFor(key: KeyRef): PopupScenes? =
        scenes.takeIf { it.ready } ?: null.also { Timber.w("no popup for %s: neither the theme's scene nor the built-in's converted", key) }

    private fun PopupScenes.px(dp: Double) = Popups.px(dp, density).toInt()

    /** In window pixels ([EditMenuLayout.place]). Null: over the key. */
    var menuRoom: ((width: Int, height: Int) -> MenuRoom?)? = null

    /** The notice's window over it is put away or hidden. */
    var onMenuShown: (menu: Rect) -> Unit = {}

    var onMenuGone: () -> Unit = {}

    /** How many cells a row fits: a floating keyboard is narrower than the window this component covers. */
    var keyboardWidth: () -> Int = { rootBounds.width() }

    private var menuAbove: KeyRef? = null

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

    private fun showPopup(key: KeyRef, content: String, bounds: Rect) {
        showingEntryUi[key]?.apply {
            dismissJobs[key]?.also {
                dismissJobs.remove(key)?.cancel()
            }
            lastShowTime = SystemClock.uptimeMillis()
            lastPreviewAt = SystemClock.uptimeMillis()
            // not one with what it showed before: none, as below
            if (!show(this, content, null, bounds.width())) return dismissPopupEntry(key, this, atOnce = true)
            place(this, bounds)
            return
        }
        val scenes = scenesFor(key) ?: return
        // a fading bubble back to the pool first (after scenesFor: a theme change clears the pool)
        endFades()
        val popup = (freeEntryUi.pollFirst() ?: PopupEntryUi(context, scenes)).apply {
            lastShowTime = SystemClock.uptimeMillis()
        }
        if (!show(popup, content, null, bounds.width())) {
            // the engine could not make it (logged): no bubble this time
            pool(popup)
            return
        }
        place(popup, bounds)
        PopupMoves.settle(popup.root)
        // make sure that popup.root does not have parent view before adding it under root container
        // it's wired that on some devices it would have a parent view despite it was newly created
        // or just polled from freeEntryUi
        if (popup.root.parent == null) {
            root.addView(popup.root)
        } else if (popup.root.parent !== root) {
            (popup.root.parent as? ViewGroup)?.removeView(popup.root)
            root.addView(popup.root)
        }
        showingEntryUi[key] = popup
        previewMoves(popup, bounds)
    }

    // unless it comes sooner than the motion after the last
    private fun previewMoves(popup: PopupEntryUi, bounds: Rect) {
        val now = SystemClock.uptimeMillis()
        val since = now - lastPreviewAt
        lastPreviewAt = now
        val m = motionOf("preview") ?: return
        if (since < maxOf(m.appear?.ms ?: 0, m.going?.ms ?: 0)) return
        m.going?.let { goings[popup.root] = it }
        val a = m.appear ?: return
        val lp = popup.root.layoutParams as FrameLayout.LayoutParams
        PopupMoves.appear(popup.root, a, (bounds.centerX() - lp.leftMargin).toFloat(), (bounds.centerY() - lp.topMargin).toFloat())
    }

    /** Null where none, or where nothing may move now ([KeyMotionSwitch], asked only of a theme that moves). */
    private fun motionOf(widget: String): PopupMotion? {
        val s = scenes.takeIf { it.ready && it.moves } ?: return null.also { motions = null }
        // an Env is a value: read once per theme's scenes in an environment, not a SceneBuilder per bubble
        val env = s.env()
        val known = motions?.takeIf { it.scenes == s && it.env == env }?.byWidget
            ?: HashMap<String, PopupMotion?>().also { motions = MotionCache(s, env, it) }
        // none kept too (getOrPut would ask again for a null)
        if (widget !in known) known[widget] = s.made("popup's motion", s.builder(env)?.popupMotion(widget))
        val m = known[widget] ?: return null
        return m.takeIf { KeyMotionSwitch.on(context) }
    }

    private class MotionCache(val scenes: PopupScenes, val env: Env, val byWidget: HashMap<String, PopupMotion?>)

    private var motions: MotionCache? = null

    // at once where [atOnce], none set, or motion off now
    private fun goAway(view: View, atOnce: Boolean, gone: () -> Unit) {
        val t = goings.remove(view)
        val done = { root.removeView(view); PopupMoves.settle(view); gone() }
        // TalkBack: a fading view would still take the hover over it
        if (atOnce || t == null) return done()
        if (!KeyMotionSwitch.on(context) || exploring()) return done()
        fading[view] = done
        PopupMoves.go(view, t) { fading.remove(view)?.invoke() }
    }

    private fun exploring() = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    private fun endFades() {
        if (fading.isEmpty()) return
        fading.keys.toList().forEach { v ->
            v.animate().cancel()
            fading.remove(v)?.invoke()
        }
    }

    // [fit] null: a character, its size; false where the engine could not make its scene, the one before kept
    private fun show(popup: PopupEntryUi, text: String, fit: PreviewLabel.Fit?, keyWidth: Int): Boolean =
        popup.showScene(fit?.text ?: text, fit?.size?.toDouble(), keyWidth)

    private fun updatePopup(key: KeyRef, content: String, words: Boolean) {
        // a long press's keys shown: a letter's preview still sends this on every move, not to show it over them
        if (key in showingContainerUi) return
        showingEntryUi[key]?.apply {
            val fit = if (words && PreviewLabel.isWords(content)) wordsFit(content, this) else null
            val text = fit?.text ?: content
            if (sceneText == text && sceneSize == fit?.size?.toDouble()) return
            if (!show(this, text, fit, trigger.width())) {
                // not the text before left up while a lift types this one
                dismissJobs.remove(key)?.cancel()
                return dismissPopupEntry(key, this, atOnce = true)
            }
            place(this, trigger)
        }
    }

    /** Words at a function key's size, scaled as the keys' text is, in a bubble no wider than five keys or the key. */
    private fun wordsFit(label: String, popup: PopupEntryUi): PreviewLabel.Fit? {
        val scale = keyTextScale(context)
        val width = popup.trigger.width()
        // asked on every move past the threshold: measured once per label
        lastFit?.takeIf { it.label == label && it.scale == scale && it.width == width }?.let { return it.fit }
        return sceneWordsFit(label, width)?.also { lastFit = FitFor(label, scale, width, it) }
    }

    // none where the engine could not say: the label then shown as a character is
    private fun sceneWordsFit(label: String, width: Int): PreviewLabel.Fit? {
        val b = scenes.builder() ?: return null
        val (size, least) = scenes.made("words' size", b.previewWordsSize()) ?: return null
        val padding = scenes.made("bubble's padding", b.previewPadding()) ?: return null
        val room = PreviewLabel.room(width.toFloat(), size.toFloat(), padding.toFloat())
        var measured = true
        val fit = PreviewLabel.fit(label, size.toFloat(), least.toFloat(), room) { s, px ->
            scenes.made("words' width", b.previewWidth(s, px.toDouble()))?.toFloat() ?: 0f.also { measured = false }
        }
        return fit.takeIf { measured }
    }

    private class FitFor(val label: String, val scale: Float, val width: Int, val fit: PreviewLabel.Fit)

    private var lastFit: FitFor? = null

    /**
     * [width] by default as the engine sizes it ([Popups]). [left] by default centred and kept inside the
     * keyboard: a table's roots over A were wider than the half of it left of A.
     */
    private fun place(
        popup: PopupEntryUi,
        bounds: Rect,
        width: Int = popup.sceneView.sceneWidth,
        left: Int = ((bounds.left + bounds.right - width) / 2).let { if (root.width > width) it.coerceIn(0, root.width - width) else it },
    ) {
        popup.trigger.set(bounds)
        // room round it for its shadow ([PopupSceneView.outset])
        val height = popup.sceneView.sceneHeight
        val out = popup.sceneView.outset
        // align popup bottom with key border bottom [^1]
        val top = bounds.bottom - height - keyBottomMargin - out.top
        val w = width + out.left + out.right
        val h = height + out.top + out.bottom
        val l = left - out.left
        // new params lay the whole input view out again: not for a move that changes nothing
        if ((popup.root.layoutParams as? FrameLayout.LayoutParams)?.isAt(w, h, l, top) == true) return
        popup.root.layoutParams = FrameLayout.LayoutParams(w, h).apply {
            topMargin = top
            leftMargin = l
        }
    }

    private fun FrameLayout.LayoutParams.isAt(width: Int, height: Int, left: Int, top: Int) =
        this.width == width && this.height == height && leftMargin == left && topMargin == top

    private fun showKeyboard(key: KeyRef, keyboard: KeyDef.Popup.Keyboard, bounds: Rect) {
        var keys: Array<String>
        var labels: Array<String>
        val split: Int
        var asIs = false
        var inRow = -1
        var focus = -1
        var actions: List<KeyAction>? = null
        when (keyboard) {
            // a key of no name (a theme's): its character's built-in list, no setting reaches it
            is KeyDef.Popup.Keyboard.Preset -> {
                val preset = KeyDefaults.preset(keyboard.label, keyboard.caps) ?: return
                keys = (preset.row + preset.symbols).map { it.text }.toTypedArray()
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
                actions = keyboard.actions
                labels = explicitLabels(keyboard) { if (punctuation.enabled) punctuation.transform(it) else it }
            }
            is KeyDef.Popup.Keyboard.Resolved -> {
                labels = keyboard.labels.toTypedArray()
                keys = labels
                split = keyboard.split
                inRow = keyboard.inRow
                focus = keyboard.focus
                actions = keyboard.actions
            }
            // made as it opens (BaseKeyboard.onPopupAction): none here is no list now
            is KeyDef.Popup.Keyboard.Named -> return
        }
        // the keys float over the key on their own: a bubble under the one in focus made them as wide as the key
        val scenes = scenesFor(key) ?: return
        val keyboardUi = PopupKeyboardUi(
            context,
            theme,
            rootBounds,
            bounds,
            { dismissPopup(key) },
            scenes.px(Popups.KEY_MIN_WIDTH_DP),
            scenes.px(Popups.KEY_HEIGHT_DP),
            scenes.px(Popups.LIFT_DP),
            keys,
            labels,
            split,
            asIs,
            inRow,
            actions,
            opensOn = focus,
            onFocusMoved = { InputFeedbacks.hapticFeedback(it) },
            scenes = scenes,
        )
        if (keyboardUi.unmade) return
        showingEntryUi[key]?.let {
            dismissJobs.remove(key)?.cancel()
            dismissPopupEntry(key, it, atOnce = true)
        }
        showPopupContainer(key, keyboardUi, "popup")
    }

    private fun showMenu(key: KeyRef, menu: KeyDef.Popup.Menu, bounds: Rect) {
        val scenes = scenesFor(key) ?: return
        val menuUi = PopupMenuUi(
            context,
            theme,
            rootBounds,
            bounds,
            { dismissPopup(key) },
            menu.items,
            onFocusMoved = { InputFeedbacks.hapticFeedback(it) },
            scenes = scenes,
        )
        if (menuUi.unmade) return
        showingEntryUi[key]?.let {
            dismissPopupEntry(key, it, atOnce = true)
        }
        showPopupContainer(key, menuUi, "menu")
    }

    private fun showEditMenu(key: KeyRef, bounds: Rect, password: Boolean, down: Boolean): Boolean {
        val cells = EditMenuLayout.CELLS
        val scenes = scenesFor(key) ?: return false
        val cell = scenes.px(Popups.MENU_CELL_DP)
        val items = cells.map {
            // what TalkBack reads of the cell
            val looks = KeyShortcutLooks.of(it)
            KeyDef.Popup.Menu.Item(context.getString(looks.label), looks.icon, KeyAction.ShortcutAction(KeyShortcut.Command(it)))
        }
        val disabled = cells.indices.filter { EditMenuLayout.disabled(cells[it], password) }.toSet()
        return showFixedMenu(key, bounds, scenes, items, PopupMenuUi.Fixed(EditMenuLayout.START, down = down), disabled, cell)
    }

    private fun showInputMethodMenu(key: KeyRef, bounds: Rect, down: Boolean): Boolean {
        val scenes = scenesFor(key) ?: return false
        // cells of words, as wide as the engine draws those (Popups.menuKeys)
        val width = floor(scenes.px(Popups.MENU_CELL_DP) * Popups.MENU_WORDS_WIDTH).toInt().coerceAtLeast(1)
        val (enabled, current) = fcitx.runImmediately { enabledImeCached to inputMethodEntryCached.uniqueName }
        val byName = enabled.associateBy { it.uniqueName }
        val menu = InputMethodMenu.of(enabled.map { it.uniqueName }, current, keyboardWidth() / width)
        val items = menu.cells.map { cell ->
            when (cell) {
                // the names the space shows
                is InputMethodMenu.Cell.Method -> KeyDef.Popup.Menu.Item(
                    InputMethodNames.of(context, byName.getValue(cell.uniqueName)), 0, KeyAction.SwitchInputMethodAction(cell.uniqueName),
                )
                InputMethodMenu.Cell.More -> KeyDef.Popup.Menu.Item(context.getString(R.string.input_method_menu_more), 0, KeyAction.ShowInputMethodPickerAction)
            }
        }
        return showFixedMenu(key, bounds, scenes, items, PopupMenuUi.Fixed(menu.start, down = down), menu.disabled, width)
    }

    /** A row picked by the finger's travel, [fixed]'s start over the key, where notices go ([EditMenuLayout.place]). */
    private fun showFixedMenu(
        key: KeyRef, bounds: Rect, scenes: PopupScenes, items: List<KeyDef.Popup.Menu.Item>, fixed: PopupMenuUi.Fixed, disabled: Set<Int>, cellWidth: Int,
    ): Boolean {
        val cell = scenes.px(Popups.MENU_CELL_DP)
        // out from under the finger; a notice there put away
        val at = menuRoom?.invoke(cellWidth * items.size, cell)?.let { room ->
            EditMenuLayout.place(room.notice, bounds.centerX(), cellWidth, rootBounds.left, rootBounds.right, rootBounds.top, room.barMiddle, items.size, fixed.start)
        }
        val menuUi = PopupMenuUi(
            context,
            theme,
            rootBounds,
            bounds,
            { dismissPopup(key) },
            items.toTypedArray(),
            onFocusMoved = { InputFeedbacks.hapticFeedback(it) },
            fixed = PopupMenuUi.Fixed(fixed.start, at, fixed.down),
            scenes = scenes,
            disabled = disabled,
        )
        if (menuUi.unmade) return false
        showingEntryUi[key]?.let {
            dismissPopupEntry(key, it, atOnce = true)
        }
        if (at != null) {
            menuAbove = key
            onMenuShown(Rect(at.left, at.top, at.left + cellWidth * items.size, at.top + cell))
        }
        showPopupContainer(key, menuUi, "menu")
        return true
    }

    private fun showPopupContainer(key: KeyRef, ui: PopupContainerUi, widget: String) {
        // a scene's room round it for its shadow: its box where the views' would be
        val out = (ui.root as? PopupSceneView)?.outset ?: NO_OUTSET
        root.apply {
            add(ui.root, lParams {
                leftMargin = ui.triggerBounds.left + ui.offsetX - rootBounds.left - out.left
                topMargin = ui.triggerBounds.top + ui.offsetY - rootBounds.top - out.top
            })
        }
        // a focus move can change the shadow's room: the box kept, the view moved round it
        (ui.root as? PopupSceneView)?.onOutsetChanged = { was, now ->
            (ui.root.layoutParams as? FrameLayout.LayoutParams)?.apply {
                leftMargin += was.left - now.left
                topMargin += was.top - now.top
                ui.root.layoutParams = this
            }
        }
        showingContainerUi[key] = ui
        // grows out of the key pressed. Focus follows the finger by position, not scale: it slides over as it comes up
        val pivotX = (ui.triggerBounds.width() / 2 - ui.offsetX + out.left).toFloat()
        val pivotY = (ui.triggerBounds.height() / 2 - ui.offsetY + out.top).toFloat()
        val m = motionOf(widget)
        m?.going?.let { goings[ui.root] = it }
        val a = m?.appear
        if (a != null) PopupMoves.appear(ui.root, a, pivotX, pivotY) else ui.root.popIn(pivotX, pivotY)
    }

    private fun changeFocus(key: KeyRef, x: Float, y: Float): Boolean {
        return showingContainerUi[key]?.changeFocus(x, y) ?: false
    }

    private fun triggerFocused(key: KeyRef): KeyAction? {
        return showingContainerUi[key]?.onTrigger()
    }

    private fun dismissPopup(key: KeyRef) {
        dismissPopupContainer(key)
        showingEntryUi[key]?.also {
            val timeLeft = it.lastShowTime + hideThreshold - SystemClock.uptimeMillis()
            if (timeLeft <= 0L) {
                dismissPopupEntry(key, it)
            } else {
                dismissJobs[key] = imeScope.launch {
                    delay(timeLeft)
                    dismissPopupEntry(key, it)
                    dismissJobs.remove(key)
                }
            }
        }
    }

    private fun dismissPopupContainer(key: KeyRef) {
        showingContainerUi[key]?.also {
            showingContainerUi.remove(key)
            goAway(it.root, atOnce = false) {}
        }
        menuGone(key)
    }

    private fun menuGone(key: KeyRef?) {
        if (key == null || key != menuAbove) return
        menuAbove = null
        onMenuGone()
    }

    private fun dismissPopupEntry(key: KeyRef, popup: PopupEntryUi, atOnce: Boolean = false) {
        showingEntryUi.remove(key)
        goAway(popup.root, atOnce) { pool(popup) }
    }

    // only if made in the theme drawn now, else it would come back drawing the old one
    private fun pool(popup: PopupEntryUi) {
        PopupMoves.settle(popup.root)
        if (popup.scenes === scenesMade?.second) freeEntryUi.add(popup)
    }

    fun dismissAll() {
        // avoid modifying collection while iterating
        dismissJobs.forEach { (_, job) ->
            job.cancel()
        }
        dismissJobs.clear()
        endFades()
        goings.clear()
        // too
        showingContainerUi.forEach { (_, container) ->
            root.removeView(container.root)
        }
        showingContainerUi.clear()
        menuGone(menuAbove)
        // too too
        showingEntryUi.forEach { (_, entry) ->
            root.removeView(entry.root)
            pool(entry)
        }
        showingEntryUi.clear()
    }

    val listener = PopupActionListener { action ->
        with(action) {
            when (this) {
                is PopupAction.ChangeFocusAction -> outResult = changeFocus(key, x, y)
                is PopupAction.DismissAction -> dismissPopup(key)
                is PopupAction.PreviewAction -> showPopup(key, content, bounds)
                is PopupAction.PreviewUpdateAction -> updatePopup(key, content, words)
                is PopupAction.ShowKeyboardAction -> showKeyboard(key, keyboard, bounds)
                is PopupAction.ShowMenuAction -> showMenu(key, menu, bounds)
                is PopupAction.ShowEditMenuAction -> outShown = showEditMenu(key, bounds, password, down)
                is PopupAction.ShowInputMethodMenuAction -> outShown = showInputMethodMenu(key, bounds, down)
                is PopupAction.TriggerAction -> outAction = triggerFocused(key)
            }
        }
    }

    companion object {
        /**
         * Those set apart are [mapped], shown as the engine will type them, as a Preset's are; so is a mark in
         * the row before them (the 14 keys' corner). Letters and digits are as they are.
         */
        internal fun explicitLabels(keyboard: KeyDef.Popup.Keyboard.Explicit, mapped: (String) -> String): Array<String> =
            Array(keyboard.items.size) {
                val item = keyboard.items[it]
                val maps = !keyboard.asIs && (it >= keyboard.symbolsFrom || item.none(Char::isLetterOrDigit))
                if (maps) mapped(item) else item
            }

        private val NO_OUTSET = Rect()
    }
}
