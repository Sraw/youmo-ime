/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.graphics.Rect
import android.os.SystemClock
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ListAdapter
import android.widget.ListPopupWindow
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineBridge
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.input.learning.CandidateLearningMenu
import org.fcitx.fcitx5.android.input.learning.UndoChip
import org.fcitx.fcitx5.android.utils.navbarFrameHeight
import org.fcitx.fcitx5.android.utils.popIn
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import splitties.dimensions.dp
import splitties.views.dsl.core.withTheme
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

abstract class BaseInputView(
    val service: FcitxInputMethodService,
    val fcitx: FcitxConnection,
    val theme: Theme
) : ConstraintLayout(service) {

    /**
     * Update UI (from cached events in FcitxAPI) to match fcitx's state, before ready to receive real events
     */
    protected abstract fun onStartHandleFcitxEvent()

    protected abstract fun handleFcitxEvent(it: FcitxEvent<*>)

    private var eventHandlerJob: Job? = null

    private fun setupFcitxEventHandler() {
        eventHandlerJob = service.lifecycleScope.launch {
            fcitx.runImmediately { eventFlow }.collect {
                // the menu holds an index: in a list since changed it names another candidate,
                // and "Forget word" would forget that one
                if (it is FcitxEvent.CandidateListEvent || it is FcitxEvent.PagedCandidateEvent) {
                    candidateActionMenu?.dismiss()
                }
                typed(it)
                handleFcitxEvent(it)
            }
        }
    }

    var handleEvents = false
        set(value) {
            field = value
            if (field) {
                onStartHandleFcitxEvent()
                if (eventHandlerJob == null) {
                    setupFcitxEventHandler()
                }
            } else {
                eventHandlerJob?.cancel()
                eventHandlerJob = null
                // the candidate events that close it are no longer collected: its index would age unchecked
                candidateActionMenu?.dismiss()
                undoChip?.dismiss()
            }
        }

    private fun triggerCandidateAction(idx: Int, actionIdx: Int, text: String) {
        fcitx.runIfReady { triggerCandidateAction(idx, actionIdx, text) }
    }

    private var candidateActionMenu: ListPopupWindow? = null

    val themedContext = context.withTheme(R.style.Theme_InputViewTheme)

    // when the menu last closed, see [dispatchTouchEvent]
    private var candidateActionMenuClosed = 0L

    // the touch that closed the menu, till it is lifted
    private var swallowing = false

    /** Whether a candidate's index counts from the page shown (the floating list's), not from the first of all. */
    protected open val pagedCandidates = false

    // the keyboard's one notice: what a long press's act did, to be taken back, and what it says
    private var undoChip: UndoChip? = null

    /** What the undo chip shows over the top of: the candidates' bar, not a window-high view. */
    protected open val undoAnchor: View get() = this

    /** What the chip goes above instead when over [undoAnchor] it would hide candidates. */
    protected open val undoAbove: View get() = this

    /** Whether [undoAnchor] shows candidates now: the chip does not cover them. */
    protected open val undoAnchorShowsCandidates: Boolean get() = true

    /** Whether the chip may go below [undoAbove] when there is no room above it. */
    protected open val undoBelowOk: Boolean get() = true

    /** The lowest window y the chip may reach when it goes below: the window's bottom less the navigation bar. */
    protected open val undoBottomLimit: Int get() = rootView.height - (rootWindowInsets?.let(::floatingBottomInset) ?: 0)

    /**
     * Floating, the keyboard's rectangle and the room it floats in, in window pixels, set into
     * [keyboard] and [screen]: the chip goes on the keyboard's side toward the screen's middle
     * ([UndoChipPlacement.floating]). False docked.
     */
    protected open fun undoFloating(keyboard: Rect, screen: Rect): Boolean = false

    private val undoKeyboard = Rect()
    private val undoScreen = Rect()

    private fun undoPlace(width: Int, height: Int, aboveKeys: Boolean): UndoChipPlacement.Place {
        val gap = dp(4)
        if (undoFloating(undoKeyboard, undoScreen)) {
            return UndoChipPlacement.floating(undoScreen.box(), undoKeyboard.box(), width, height, gap)
        }
        val bar = IntArray(2).also { undoAnchor.getLocationInWindow(it) }
        val over = undoAbove
        val aboveAt = IntArray(2).also { over.getLocationInWindow(it) }
        return UndoChipPlacement.docked(
            windowWidth = undoAnchor.rootView.width,
            chipWidth = width,
            candidates = aboveKeys || undoAnchorShowsCandidates,
            barTop = bar[1],
            aboveTop = aboveAt[1],
            chipHeight = height,
            gap = gap,
            belowBottom = if (undoBelowOk) aboveAt[1] + over.height else null,
            // off the bottom PopupWindow would clip it back over the keys
            bottomLimit = undoBottomLimit,
        )
    }

    private fun Rect.box() = UndoChipPlacement.Box(left, top, right, bottom)

    private val learningMenu by lazy { CandidateLearningMenu(themedContext, fcitx, service.lifecycleScope, ::chip) }

    private fun notice(message: String, undo: UndoChip.Undo?, aboveKeys: Boolean, steady: Boolean = false): Boolean {
        // one line per notice, never its text (it may hold a word typed): UAT counts these to
        // catch a notice said twice, which crossfades in the same chip and can't be seen as two
        Timber.i("say")
        chipTyping.chipShown()
        val chip = undoChip ?: UndoChip(undoAnchor, theme, ::undoPlace).also { undoChip = it }
        return chip.show(message, undo, aboveKeys, steady)
    }

    // a long press's act said over the bar, with what takes it back: only the last act, and only
    // while nothing else was learned after it; the engine says so (Unavailable) and so does the chip then
    private fun chip(message: String, token: Long) {
        val undo = if (token == LearningResult.NO_UNDO) null else UndoChip.Undo({ learningUndo(token) })
        notice(message, undo, aboveKeys = false)
    }

    private fun learningUndo(token: Long) {
        service.lifecycleScope.launch {
            when (fcitx.runOnReady { learning(LearningOp.Undo(token)) }) {
                LearningResult.Unavailable -> chip(context.getString(R.string.learning_undo_gone), LearningResult.NO_UNDO)
                // a disk that failed: said, as 清掉最近 says it, not the chip just gone
                LearningResult.Failed -> chip(context.getString(R.string.learning_try_again), LearningResult.NO_UNDO)
                else -> {}
            }
        }
    }

    /**
     * [message] said for a moment above the keyboard ([FcitxInputMethodService.say]), with 撤销
     * doing [undo] if there is one; not over the bar, whose buttons it hid (the user, 2026-10-22).
     * [steady]: not put away by typing ([UndoChip.show]). False if it could not be shown, [undo] lapsed.
     */
    fun say(message: String, undo: UndoChip.Undo? = null, steady: Boolean = false) =
        notice(message, undo, aboveKeys = true, steady)

    private val chipTyping = ChipTyping()

    private fun typed(event: FcitxEvent<*>) {
        val goes = when (event) {
            is FcitxEvent.ClientPreeditEvent -> chipTyping.editorPreedit(event.data.toString())
            is FcitxEvent.InputPanelEvent -> chipTyping.barPreedit(event.data.preedit.toString())
            // a key the engine let through, or a word put in: typing too
            is FcitxEvent.KeyEvent -> chipTyping.key(event.data.up)
            is FcitxEvent.CommitStringEvent -> chipTyping.typed()
            else -> false
        }
        // typing on: put away as a tap would, the act standing
        if (goes) undoChip?.typed()
    }

    /** The candidate [text] at [idx] deleted or its learning forgotten, as its long press would (Shift+Delete). */
    protected fun forgetCandidate(idx: Int, text: String) {
        candidateActionMenu?.dismiss()
        service.lifecycleScope.launch { learningMenu.forget(idx, text, pagedCandidates) }
    }

    fun showCandidateActionMenu(idx: Int, text: String, view: View) {
        candidateActionMenu?.dismiss()
        service.lifecycleScope.launch {
            // the engine's menu, worded by the app (U-82); else, for an input method not the engine's,
            // what the addon offers, as it words it: not the addon's words where the engine's list
            // changed under the press (review-E-ui N8)
            val im = fcitx.runImmediately { inputMethodEntryCached }.uniqueName
            val lines = learningMenu.lines(idx, text, pagedCandidates) ?: if (EngineBridge.isEngines(im)) return@launch else {
                fcitx.runOnReady { getCandidateActions(idx) }.map { action ->
                    CandidateLearningMenu.Line(action.text) {
                        triggerCandidateAction(idx, action.id, text)
                        null
                    }
                }
            }
            // another long press may have shown its menu meanwhile
            candidateActionMenu?.dismiss()
            // nor on a candidate view recycled meanwhile: no window to show it in
            if (lines.isEmpty() || !view.isAttachedToWindow) return@launch
            InputFeedbacks.hapticFeedback(view, longPress = true)
            val title = buildSpannedString {
                bold {
                    color(context.styledColorOrDefault(android.R.attr.colorAccent, theme.genericActiveForegroundColor)) {
                        append(text)
                    }
                }
            }
            // a line picked may show others in its place (a block's confirmation, the reason): the same popup
            var shown = lines
            val adapter = object : ArrayAdapter<CharSequence>(themedContext, android.R.layout.simple_list_item_1, ArrayList()) {
                override fun areAllItemsEnabled() = false
                override fun isEnabled(position: Int) = position > 0 && shown[position - 1].enabled
            }
            adapter.add(title)
            adapter.addAll(shown.map { it.label })
            // not a PopupMenu: that one takes the window focus from the editor, and some (the
            // launcher's search) finish composing when they lose it and start the input anew when
            // they get it back, so what was typed is gone before the action reaches it. Not
            // focusable, the menu lets a touch outside it through as well: see [dispatchTouchEvent]
            val menu = ListPopupWindow(themedContext, null, android.R.attr.popupMenuStyle)
            // as wide as PopupMenu lets its menus grow
            val widest = max(resources.displayMetrics.widthPixels / 2, dp(320))
            candidateActionMenu = menu.apply {
                isModal = false
                anchorView = view
                setAdapter(adapter)
                setContentWidth(min(adapter.widest(FrameLayout(themedContext)), widest))
                setOnItemClickListener { _, _, position, _ ->
                    val next = shown[position - 1].pick()
                    if (next == null) {
                        dismiss()
                        return@setOnItemClickListener
                    }
                    shown = next
                    adapter.clear()
                    adapter.add(title)
                    adapter.addAll(next.map { it.label })
                    setContentWidth(min(adapter.widest(FrameLayout(themedContext)), widest))
                    show()
                }
                setOnDismissListener {
                    candidateActionMenuClosed = SystemClock.uptimeMillis()
                    if (candidateActionMenu === menu) candidateActionMenu = null
                }
                // not the platform's drop-down animation: grown out of the candidate pressed (Motion)
                animationStyle = 0
                show()
                listView?.rootView?.let { decor -> decor.doOnPreDraw { growFrom(decor, view) } }
            }
        }
    }

    /**
     * Voice's hold to talk, while one goes on: it follows the finger that long-pressed space,
     * which the key itself no longer reports once its long press has fired, and ends with the
     * field or the keyboard (InputView's startInput and finishInput).
     */
    interface Hold {
        /** each touch event, before the keys see it */
        fun touched(ev: MotionEvent)

        fun inputEnded()
    }

    var hold: Hold? = null

    // where each finger down went down, in this view
    private val downYs = SparseArray<Float>()

    fun downY(pointer: Int): Float = downYs.get(pointer, 0f)

    /**
     * A touch that closes the menu only closes it, as it did when the menu was focusable: not a key
     * typed or a candidate picked by the way. The menu may see it first and close, so a touch
     * that went down before it closed counts too.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            downYs.put(ev.getPointerId(ev.actionIndex), ev.getY(ev.actionIndex))
        }
        hold?.touched(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            swallowing = candidateActionMenu != null || ev.downTime <= candidateActionMenuClosed
            candidateActionMenu?.dismiss()
        }
        if (!swallowing) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            swallowing = false
        }
        return true
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // the keyboard hidden with Back, which the menu does not take
        if (visibility != VISIBLE) {
            candidateActionMenu?.dismiss()
            undoChip?.dismiss()
        }
    }

    // the menu's window [decor] grown from the middle of the candidate [pressed], wherever it opened
    private fun growFrom(decor: View, pressed: View) {
        val at = IntArray(2).also(decor::getLocationOnScreen)
        val from = IntArray(2).also(pressed::getLocationOnScreen)
        decor.popIn((from[0] + pressed.width / 2 - at[0]).toFloat(), (from[1] + pressed.height / 2 - at[1]).toFloat())
    }

    private fun ListAdapter.widest(parent: ViewGroup): Int {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var widest = 0
        for (i in 0 until count) {
            val item = getView(i, null, parent)
            item.measure(unspecified, unspecified)
            widest = max(widest, item.measuredWidth)
        }
        return widest
    }

    private val navbarBackground by ThemeManager.prefs.navbarBackground

    protected fun getNavBarBottomInset(windowInsets: WindowInsets): Int {
        if (navbarBackground != ThemePrefs.NavbarBackground.Full) {
            return 0
        }
        return navBarBottomInset(windowInsets)
    }

    /**
     * What the navigation bar takes at the bottom whatever is drawn behind it: the floating keyboard
     * stays above it. Under gesture navigation the bar the system draws over the keyboard's window
     * (its switcher and hide buttons) is the frame's height, taller than the insets say: the
     * keyboard was dragged under it, where the finger could not follow.
     */
    protected fun floatingBottomInset(windowInsets: WindowInsets): Int {
        val insets = WindowInsetsCompat.toWindowInsetsCompat(windowInsets)
        val gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
        return max(navBarBottomInset(windowInsets), if (gestures > 0) context.navbarFrameHeight() else 0)
    }

    private var loggedInsets: List<Int>? = null

    private fun navBarBottomInset(windowInsets: WindowInsets): Int {
        val insets = WindowInsetsCompat.toWindowInsetsCompat(windowInsets)
        // use navigation bar insets when available
        val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        // in case navigation bar insets goes wrong (eg. on LineageOS 21+ with gesture navigation)
        // use mandatory system gesture insets
        val mandatory = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        var insetsBottom = max(navBars.bottom, mandatory.bottom)
        if (insetsBottom <= 0) {
            // check system gesture insets and fallback to navigation_bar_frame_height just in case
            val gesturesBottom = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
            if (gesturesBottom > 0) {
                insetsBottom = max(gesturesBottom, context.navbarFrameHeight())
            }
        }
        // some ROMs hiding the gesture line still report it, leaving a blank strip under the
        // keyboard: the raw values go to the log a user exports, once each time they change
        val seen = listOf(
            navBars.bottom, mandatory.bottom,
            insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom,
            insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom,
            context.navbarFrameHeight(), insetsBottom,
        )
        if (seen != loggedInsets) {
            loggedInsets = seen
            Timber.i("nav bar bottom inset: navigationBars=%d mandatorySystemGestures=%d systemGestures=%d tappableElement=%d navbarFrameHeight=%d -> %d", *seen.toTypedArray())
        }
        return insetsBottom
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // on API 35+, we must call requestApplyInsets() manually after replacing views,
        // otherwise View#onApplyWindowInsets won't be called. ¯\_(ツ)_/¯
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        handleEvents = false
        super.onDetachedFromWindow()
    }
}
