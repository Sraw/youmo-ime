/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.popup

import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import java.util.LinkedList
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.UndoChipPlacement
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.dependency.TypedComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.imeScope
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.keyboard.KeyShortcutLooks
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyMotionSwitch
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupSceneView
import org.fcitx.fcitx5.android.input.keyboard.canvas.PopupScenes
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneBoard
import org.fcitx.fcitx5.android.input.keyboard.keyTextScale
import org.fcitx.fcitx5.android.input.keyboard.longPressOf
import org.fcitx.fcitx5.android.input.shortcut.EditMenuLayout
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.PopupMotion
import org.fcitx.fcitx5.android.scene.engine.Popups
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
    private val theme by manager.theme()
    private val punctuation: PunctuationComponent by manager.must()

    private val showingEntryUi = HashMap<KeyRef, PopupEntryUi>()
    private val dismissJobs = HashMap<KeyRef, Job>()
    private val freeEntryUi = LinkedList<PopupEntryUi>()

    private val showingContainerUi = HashMap<KeyRef, PopupContainerUi>()

    // 060-render-p3 A2: how each popup shown goes (its theme's `transition`, null: at once), and the views
    // fading out now, each with what is done once it has gone
    private val goings = HashMap<View, Transition>()
    private val fading = HashMap<View, () -> Unit>()

    // when the last bubble came up: one sooner than its motion after it does not move (as a key typed fast, A1)
    private var lastPreviewAt = Long.MIN_VALUE / 2

    private val keyBottomMargin by lazy {
        val gap = ThemeManager.shownLook(theme).keys.gap
        context.dp(if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) gap.vLand else gap.v)
    }
    private val hideThreshold = 100L

    // the popups of the keys the canvas draws, the engine's scenes (060-render-p2 S2), in the theme they
    // draw in now: made again where it changed (the keyboard shown now refused its keys' scene, or no
    // longer: another keyboard, a rotation), the pooled popups made for the old let go with them
    private var scenesMade: Pair<String, PopupScenes>? = null
    private val scenes: PopupScenes
        get() {
            val drawn = SceneBoard.drawnTheme(theme).name
            scenesMade?.takeIf { it.first == drawn }?.let { return it.second }
            freeEntryUi.clear()
            return PopupScenes(root, theme, measuring).also { scenesMade = drawn to it }
        }

    /** The popups' engine as they would draw now: a test's, that it follows the keys' theme. */
    @VisibleForTesting
    internal val scenesNow: PopupScenes get() = scenes

    /** The popups kept for the next: a test's, that none made in an old theme is. */
    @VisibleForTesting
    internal val pooled: List<PopupEntryUi> get() = freeEntryUi

    /** [key]'s bubble up now: a test's, that it draws in the theme the keys do. */
    @VisibleForTesting
    internal fun showing(key: KeyRef): PopupEntryUi? = showingEntryUi[key]

    /** The engine's text measured through this: a test's, to make it fail. */
    @VisibleForTesting
    internal var measuring: (TextMeasurer) -> TextMeasurer = { it }

    /**
     * The engine that draws [key]'s popups, every key's (a picker's cell too); null where the theme
     * did not convert: no popup then, logged, never another drawing (060: the views are gone).
     */
    private fun scenesFor(key: KeyRef): PopupScenes? =
        scenes.takeIf { it.ready } ?: null.also { Timber.w("no popup for %s: neither the theme's scene nor the built-in's converted", key) }

    // [dp] in px as the engine's rules size a scene's popup ([Popups.px])
    private fun PopupScenes.px(dp: Double) = Popups.px(dp, density).toInt()

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
        // a bubble fading back to the pool first, to be taken again (after scenesFor: a theme changed clears the pool)
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

    // a new bubble coming up as its theme says, unless it comes sooner than the motion after the last (any
    // fading out ended in showPopup, as a key's press ends the others' fades, A1)
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

    /**
     * [widget]'s motion (`popup`, `preview`, `menu`) in its theme, null where none or where nothing may move
     * now (键盘动效, battery saver: [KeyMotionSwitch], asked only of a theme that moves).
     */
    private fun motionOf(widget: String): PopupMotion? {
        val s = scenes.takeIf { it.ready && it.moves } ?: return null.also { motions = null }
        // read once for a theme's scenes in an environment (its size, and the input method, field, orientation… that
        // `@im=`-like styles are scoped by: an Env is a value), not a SceneBuilder made for each bubble shown
        val env = s.env()
        val known = motions?.takeIf { it.scenes == s && it.env == env }?.byWidget
            ?: HashMap<String, PopupMotion?>().also { motions = MotionCache(s, env, it) }
        // none kept too (getOrPut would ask again for a null)
        if (widget !in known) known[widget] = s.made("popup's motion", s.builder(env)?.popupMotion(widget))
        val m = known[widget] ?: return null
        return m.takeIf { KeyMotionSwitch.on(context) }
    }

    // the widgets' motions read for a theme's scenes in an environment ([motionOf])
    private class MotionCache(val scenes: PopupScenes, val env: Env, val byWidget: HashMap<String, PopupMotion?>)

    private var motions: MotionCache? = null

    // [view] gone from [root] in its fade, or at once ([atOnce], none set, or motion off now); then [gone]
    private fun goAway(view: View, atOnce: Boolean, gone: () -> Unit) {
        val t = goings.remove(view)
        val done = { root.removeView(view); PopupMoves.settle(view); gone() }
        // TalkBack: a view fading out would still take the hover over it (its keys read, the key under not)
        if (atOnce || t == null) return done()
        if (!KeyMotionSwitch.on(context) || exploring()) return done()
        fading[view] = done
        PopupMoves.go(view, t) { fading.remove(view)?.invoke() }
    }

    private fun exploring() = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    // every fade out ended at once: its view gone
    private fun endFades() {
        if (fading.isEmpty()) return
        fading.keys.toList().forEach { v ->
            v.animate().cancel()
            fading.remove(v)?.invoke()
        }
    }

    // [text] in [popup] over a key [keyWidth] px across, as [fit] has it (null: a character, its
    // size); false where the engine could not make its scene, the one before kept
    private fun show(popup: PopupEntryUi, text: String, fit: PreviewLabel.Fit?, keyWidth: Int): Boolean =
        popup.showScene(fit?.text ?: text, fit?.size?.toDouble(), keyWidth)

    private fun updatePopup(key: KeyRef, content: String, words: Boolean) {
        // a long press's keys shown, showKeyboard dismissed the bubble: a letter's preview, wrapped by
        // its long press, still sends this on every move, not to show it again over them
        if (key in showingContainerUi) return
        showingEntryUi[key]?.apply {
            val fit = if (words && PreviewLabel.isWords(content)) wordsFit(content, this) else null
            val text = fit?.text ?: content
            if (sceneText == text && sceneSize == fit?.size?.toDouble()) return
            if (!show(this, text, fit, trigger.width())) {
                // not the text before left up while a lift types this one: none, as showPopup's
                dismissJobs.remove(key)?.cancel()
                return dismissPopupEntry(key, this, atOnce = true)
            }
            place(this, trigger)
        }
    }

    /**
     * A swipe's shortcut named in [popup] ([PreviewLabel]): words at a function key's size, as the
     * keys' text is scaled (紧凑, floating), in a bubble no wider than five of them or the key.
     */
    private fun wordsFit(label: String, popup: PopupEntryUi): PreviewLabel.Fit? {
        val scale = keyTextScale(context)
        val width = popup.trigger.width()
        // asked again on every move of the finger past the threshold: measured once for the same label
        lastFit?.takeIf { it.label == label && it.scale == scale && it.width == width }?.let { return it.fit }
        return sceneWordsFit(label, width)?.also { lastFit = FitFor(label, scale, width, it) }
    }

    // the engine's: its sizes px, measured as the scene draws them ([PopupScenes]); none where it
    // could not say, the label then shown as a character is
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
     * Over the key [bounds], [width] wide, by default as the engine sizes it: as wide as the key's
     * face and its text, never narrower than its least (Popups).
     * From [left], by default centred over the key, kept inside the keyboard: a table's roots
     * (TextKeyboard) over A were wider than the half of it left of A.
     */
    private fun place(
        popup: PopupEntryUi,
        bounds: Rect,
        // the engine's: the key's, its text's or the least, whichever is widest (Popups)
        width: Int = popup.sceneView.sceneWidth,
        left: Int = ((bounds.left + bounds.right - width) / 2).let { if (root.width > width) it.coerceIn(0, root.width - width) else it },
    ) {
        popup.trigger.set(bounds)
        // the scene's its own height, and room round it for its shadow ([PopupSceneView.outset])
        val height = popup.sceneView.sceneHeight
        val out = popup.sceneView.outset
        // align popup bottom with key border bottom [^1]
        val top = bounds.bottom - height - keyBottomMargin - out.top
        val w = width + out.left + out.right
        val h = height + out.top + out.bottom
        val l = left - out.left
        // new ones lay the whole input view out again: not for a move or a press that changes nothing
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
        // the keys float over the key on their own: the bubble under the one in focus, as one shape
        // with it, was as wide as the key pressed, and so were they
        // sized by the engine's rules (Popups)
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

    private fun showEditMenu(key: KeyRef, bounds: Rect, password: Boolean) {
        val cells = EditMenuLayout.CELLS
        val scenes = scenesFor(key) ?: return
        val cell = scenes.px(Popups.MENU_CELL_DP)
        // up where the notices go, out from under the finger; a notice there put away, its window over the row
        val at = menuRoom?.invoke(cell * cells.size, cell)?.let { room ->
            EditMenuLayout.place(room.notice, bounds.centerX(), cell, rootBounds.left, rootBounds.right, rootBounds.top, room.barMiddle)
        }
        val menuUi = PopupMenuUi(
            context,
            theme,
            rootBounds,
            bounds,
            { dismissPopup(key) },
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
            scenes = scenes,
        )
        if (menuUi.unmade) return
        showingEntryUi[key]?.let {
            dismissPopupEntry(key, it, atOnce = true)
        }
        if (at != null) {
            menuAbove = key
            onMenuShown(Rect(at.left, at.top, at.left + cell * cells.size, at.top + cell))
        }
        showPopupContainer(key, menuUi, "menu")
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
        // a focus moved can change the shadow's room: the box kept where it is, the view moved round it
        (ui.root as? PopupSceneView)?.onOutsetChanged = { was, now ->
            (ui.root.layoutParams as? FrameLayout.LayoutParams)?.apply {
                leftMargin += was.left - now.left
                topMargin += was.top - now.top
                ui.root.layoutParams = this
            }
        }
        showingContainerUi[key] = ui
        // out of the key pressed (its middle, in the container's pixels); it goes at once, the
        // character picked typed already, or in its theme's fade (A2). Focus follows the finger by
        // position, not by its scale: a finger slides over it as it comes up
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

    // back to the pool only if made in the theme drawn now: one showing when it changed would
    // otherwise come back from the pool drawing in the old
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
                is PopupAction.ShowEditMenuAction -> showEditMenu(key, bounds, password)
                is PopupAction.TriggerAction -> outAction = triggerFocused(key)
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

        private val NO_OUTSET = Rect()
    }
}
