/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.content.ClipData
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.SparseArray
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListAdapter
import android.widget.ListPopupWindow
import android.widget.ListView
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineBridge
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseManager
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.reading.CandidateCard
import org.fcitx.fcitx5.android.engine.reading.CharCard
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.reading.WordCard
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.host.QuickPhraseBook
import org.fcitx.fcitx5.android.input.candidates.CharCardView
import org.fcitx.fcitx5.android.input.candidates.CompactLineView
import org.fcitx.fcitx5.android.input.candidates.WordCardView
import org.fcitx.fcitx5.android.input.candidates.tapsTo
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.learning.CandidateLearningMenu
import org.fcitx.fcitx5.android.input.learning.UndoChip
import org.fcitx.fcitx5.android.input.popup.CardNav
import org.fcitx.fcitx5.android.input.popup.CharCardLayout
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.fcitx.fcitx5.android.utils.navbarFrameHeight
import org.fcitx.fcitx5.android.utils.popIn
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp
import splitties.resources.styledDrawable
import splitties.views.dsl.core.withTheme
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

abstract class BaseInputView(
    val service: FcitxInputMethodService,
    val fcitx: HostConnection,
    val theme: Theme
) : ConstraintLayout(service) {

    /**
     * Update UI (from cached events in HostApi) to match fcitx's state, before ready to receive real events
     */
    protected abstract fun onStartHandleFcitxEvent()

    protected abstract fun handleFcitxEvent(it: HostEvent)

    private var eventHandlerJob: Job? = null

    private fun setupFcitxEventHandler() {
        eventHandlerJob = service.lifecycleScope.launch {
            // the caches read once subscribed: an event in between (a rebuild's reset) comes after
            // them, not lost before the collector was there (the flow keeps none for late comers)
            fcitx.runImmediately { eventFlow }.onSubscription { onStartHandleFcitxEvent() }.collect {
                // the menu holds an index: in a list since changed it names another candidate,
                // and "Forget word" would forget that one
                if (it is HostEvent.Candidates || it is HostEvent.Paged) {
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
                if (eventHandlerJob == null) setupFcitxEventHandler() else onStartHandleFcitxEvent()
            } else {
                eventHandlerJob?.cancel()
                eventHandlerJob = null
                dismissPopups()
            }
        }

    // the candidate events that close the menu are no longer collected (or the keyboards under it are made
    // anew, InputView.remakeKeyboards): its index would age unchecked, and the chip's undo with it
    protected fun dismissPopups() {
        candidateActionMenu?.dismiss()
        undoChip?.dismiss()
    }

    private fun triggerCandidateAction(idx: Int, actionIdx: Int, text: String) {
        fcitx.runIfReady { triggerCandidateAction(idx, actionIdx, text) }
    }

    @VisibleForTesting
    internal var candidateActionMenu: ListPopupWindow? = null

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

    /** The floating code strip's rectangle in window pixels into [out] while it shows, for the chip to keep off; false else. */
    protected open fun undoStrip(out: Rect): Boolean = false

    /** The floating code strip came, went, moved or grew: the chip shown keeps off it. */
    protected fun undoStripMoved() {
        undoChip?.reposition()
    }

    private val undoKeyboard = Rect()
    private val undoScreen = Rect()
    private val undoStripAt = Rect()

    private fun undoPlace(width: Int, height: Int, aboveKeys: Boolean): UndoChipPlacement.Place {
        val gap = dp(4)
        val strip = if (undoStrip(undoStripAt)) undoStripAt else null
        if (undoFloating(undoKeyboard, undoScreen)) {
            return UndoChipPlacement.floating(undoScreen.box(), undoKeyboard.box(), width, height, gap, strip?.box())
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
            stripTop = strip?.top,
        )
    }

    private fun Rect.box() = UndoChipPlacement.Box(left, top, right, bottom)

    /** Where a notice [width] × [height] said now goes (above the keyboard, docked): the edit menu goes there too. */
    protected fun noticePlace(width: Int, height: Int): UndoChipPlacement.Place = undoPlace(width, height, aboveKeys = true)

    /** The edit menu opened at [menu] (window pixels): a notice its window meets put away, or a failure hidden ([UndoChip.covered]). */
    protected fun noticeCovered(menu: Rect) {
        undoChip?.covered(menu)
    }

    /** The edit menu gone: a failure it hid back ([UndoChip.uncovered]). */
    protected fun noticeUncovered() {
        undoChip?.uncovered()
    }

    private val learningMenu by lazy { CandidateLearningMenu(themedContext, fcitx, service.lifecycleScope, ::chip) }

    private fun notice(message: String, undo: UndoChip.Undo?, aboveKeys: Boolean, steady: Boolean = false, ms: Long? = null): Boolean {
        // one line per notice, never its text (it may hold a word typed): UAT counts these to
        // catch a notice said twice, which crossfades in the same chip and can't be seen as two
        Timber.i("say")
        chipTyping.chipShown()
        val chip = undoChip ?: UndoChip(undoAnchor, theme, ::undoPlace).also { undoChip = it }
        return chip.show(message, undo, aboveKeys, steady, ms)
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
                // the act on disk and the rewrite dropping it failed (or the file did not read): said, not the chip just
                // gone. No "try again": the token is dropped, the act no longer undoable; nor a restart, which keeps an
                // act on disk and gives no undo of it, and forgets one held in memory only, with the rest learned there
                // (R25r9 F8074, R25r10 F8084)
                LearningResult.Failed -> chip(context.getString(R.string.learning_undo_failed), LearningResult.NO_UNDO)
                else -> {}
            }
        }
    }

    /**
     * [message] said for a moment above the keyboard ([FcitxInputMethodService.say]), with 撤销
     * doing [undo] if there is one; not over the bar, whose buttons it hid (the user, 2026-10-22).
     * [steady]: not put away by typing ([UndoChip.show]); [ms] its own time, null its kind's. False if it could
     * not be shown, [undo] lapsed.
     */
    fun say(message: String, undo: UndoChip.Undo? = null, steady: Boolean = false, ms: Long? = null) =
        notice(message, undo, aboveKeys = true, steady, ms)

    private val chipTyping = ChipTyping()

    private fun typed(event: HostEvent) {
        val goes = when (event) {
            is HostEvent.ClientPreedit -> chipTyping.editorPreedit(event.text.toString())
            is HostEvent.InputPanel -> chipTyping.barPreedit(event.preedit.toString())
            // a key the engine let through, or a word put in: typing too
            is HostEvent.Key -> chipTyping.key(event.up)
            is HostEvent.Commit -> chipTyping.typed()
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

    /**
     * The long press's lines for the candidate [text] at [idx], and what heads them: quick input's own lines
     * for one of its entries, no card (numbered over the whole list, not a page's: a paged list's, the floating and physical
     * keyboards', has none of them, as the host has no page offset for it); else the engine's menu, worded by the
     * app (U-82), with the candidate's card ([CandidateCard]: its reading line, a character looked up its card;
     * the engine's alone) in the same trip; else, for an input method not the engine's, what the addon
     * offers, as it words it: not the addon's words where the engine's list changed under the press
     * (review-E-ui N8).
     */
    private suspend fun candidateMenu(idx: Int, text: String): Pair<List<CandidateLearningMenu.Line>, CandidateCard?> {
        val im = fcitx.runImmediately { inputMethodEntryCached }.uniqueName
        val engines = EngineBridge.isEngines(im)
        val phrase = if (pagedCandidates) null else fcitx.runOnReady { quickPhraseAt(idx) }
        if (phrase != null) return quickPhraseLines(idx, text, phrase) to null
        val op = learningMenu.menuOf(idx, text, pagedCandidates)
        val (learned, card) = fcitx.runOnReady { learning(op) to if (engines) candidateCard(idx, text, pagedCandidates) else null }
        // a field that learns nothing has no lines: the card alone (the user, 2026-11-14, Q4)
        val lines = learningMenu.linesOf(learned, idx, text, pagedCandidates) ?: if (engines) emptyList() else {
            fcitx.runOnReady { getCandidateActions(idx) }.map { action ->
                CandidateLearningMenu.Line(action.text) {
                    triggerCandidateAction(idx, action.id, text)
                    null
                }
            }
        }
        return lines to card
    }

    fun showCandidateActionMenu(idx: Int, text: String, view: View) {
        candidateActionMenu?.dismiss()
        service.lifecycleScope.launch {
            val (lines, card) = candidateMenu(idx, text)
            // another long press may have shown its menu meanwhile
            candidateActionMenu?.dismiss()
            // nor on a candidate view recycled meanwhile: no window to show it in
            if ((lines.isEmpty() && card == null) || !view.isAttachedToWindow) return@launch
            InputFeedbacks.hapticFeedback(view, longPress = true)
            val accent = context.styledColorOrDefault(android.R.attr.colorAccent, theme.genericActiveForegroundColor)
            val title = buildSpannedString {
                bold {
                    color(accent) {
                        append(text)
                    }
                }
            }
            // where the head is: its reading line, a card opened from it (CardNav); its view in [head], the menu's first row
            val nav = CardNav(card)
            val head = if (card == null) null else FrameLayout(themedContext)
            // a line picked may show others in its place (a block's confirmation, the reason): the same popup
            var shown = lines
            // the first line's row: after the head, the title or the card
            val firstLine = 1
            val adapter = object : ArrayAdapter<CharSequence>(themedContext, android.R.layout.simple_list_item_1, ArrayList()) {
                override fun areAllItemsEnabled() = false
                override fun isEnabled(position: Int) = position >= firstLine && shown[position - firstLine].enabled
                override fun getViewTypeCount() = 2
                override fun getItemViewType(position: Int) = if (position == 0 && head != null) 1 else 0
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    if (position == 0 && head != null) head else super.getView(position, convertView, parent)
            }
            adapter.add(if (head == null) title else text)
            adapter.addAll(shown.map { it.label })
            // not a PopupMenu: that one takes the window focus from the editor, and some (the
            // launcher's search) finish composing when they lose it and start the input anew when
            // they get it back, so what was typed is gone before the action reaches it. Not
            // focusable, the menu lets a touch outside it through as well: see [dispatchTouchEvent]
            val menu = ListPopupWindow(themedContext, null, android.R.attr.popupMenuStyle)
            fun twoColumns() = nav.twoColumns(KeyboardArea.landscape(context), floatingKeyboard, pagedCandidates)
            // a card's menu its width whatever its lines (as wide as PopupMenu lets its menus grow; a card's two
            // columns, in landscape, wider); a title's or a reading line's no wider than they need
            fun width(): Int {
                val widest = CharCardLayout.width(resources.displayMetrics.widthPixels, dp(320), twoColumns())
                return if (nav.expanded) widest else min(adapter.widest(FrameLayout(themedContext)), widest)
            }
            // the head as [nav] has it now, TalkBack's focus moved to its first node once laid out after a step
            var charOfWord: CharCard? = null
            fun showHead() {
                head ?: return
                val built = headView(card ?: return, nav, text, accent, charOfWord, twoColumns(), idx, menu, step = {
                    showHead()
                    menu.setContentWidth(width())
                    adapter.notifyDataSetChanged()
                    showStep(menu, nav, view)
                }, pickChar = { row, c ->
                    charOfWord = c
                    nav.pick(row)
                })
                head.removeAllViews()
                head.addView(built.first, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                built.second?.let { target -> head.doOnPreDraw { focusForTalkBack(target) } }
            }
            showHead()
            candidateActionMenu = menu.apply {
                isModal = false
                anchorView = view
                setAdapter(adapter)
                setContentWidth(width())
                setOnItemClickListener { _, _, position, _ ->
                    if (position < firstLine) return@setOnItemClickListener
                    val next = shown[position - firstLine].pick()
                    if (next == null) {
                        dismiss()
                        return@setOnItemClickListener
                    }
                    shown = next
                    adapter.clear()
                    adapter.add(if (head == null) title else text)
                    adapter.addAll(next.map { it.label })
                    setContentWidth(width())
                    show()
                }
                setOnDismissListener {
                    candidateActionMenuClosed = SystemClock.uptimeMillis()
                    if (candidateActionMenu === menu) candidateActionMenu = null
                }
                // not the platform's drop-down animation: grown out of the candidate pressed (Motion)
                animationStyle = 0
                show()
                // its later updates (a card's step, lines swapped) move it there at once
                listView?.rootView?.let(::notSlid)
                scrollbarShown(listView, card = head != null)
                listView?.rootView?.let { decor -> decor.doOnPreDraw { growFrom(decor, view) } }
            }
        }
    }

    /** The view TalkBack's focus was last moved to in a candidate's card, for tests: the move itself only works with TalkBack on. */
    @VisibleForTesting
    internal var cardFocused: View? = null

    private fun focusForTalkBack(target: View) {
        cardFocused = target
        target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
    }

    // a card's menu in landscape: the card fills it and its lines are scrolled to, so the bar stays to say there are more
    private fun scrollbarShown(list: ListView?, card: Boolean) {
        if (card && list != null && KeyboardArea.landscape(context)) list.isScrollbarFadingEnabled = false
    }

    /**
     * The menu's head for [card] where [nav] is: its reading line, the character's card or the word's (上屏 as the
     * candidate tapped, 复制 the candidate), a character of the word's ([charOfWord]: 复制 it alone, the way back
     * over it), each tap a [step] after [nav] moved; with the view TalkBack's focus goes to, null before any step.
     * A word's character tapped asks the engine for its card ([HostApi.charCardOf]), then [pickChar] it if [menu]
     * is still the one up.
     */
    private fun headView(
        card: CandidateCard,
        nav: CardNav,
        text: String,
        accent: Int,
        charOfWord: CharCard?,
        twoColumns: Boolean,
        idx: Int,
        menu: ListPopupWindow,
        step: () -> Unit,
        pickChar: (Int, CharCard) -> Unit,
    ): Pair<View, View?> {
        val textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, ThemeManager.prefs.candidateTextSize.getValue().toFloat(), resources.displayMetrics)
        val close: () -> Unit = { candidateActionMenu?.dismiss() }
        val commit = { fcitx.launchOnReady { it.select(idx, text) } }
        val focus = nav.focusTarget
        return when (nav.head) {
            CardNav.Head.TITLE -> error("no card")
            CardNav.Head.LINE -> CompactLineView(themedContext, text, (card as CandidateCard.Line).line, accent, wraps = true) to null
            CardNav.Head.COMPACT -> {
                val line = when (card) {
                    is CandidateCard.Char -> card.line
                    is CandidateCard.Word -> card.line
                    else -> ""
                }
                val label = context.getString(if (card is CandidateCard.Word) R.string.card_open_word else R.string.card_open_char)
                CompactLineView(themedContext, text, line, accent, wraps = false, openLabel = label) { if (nav.open()) step() } to null
            }
            CardNav.Head.LOOKED, CardNav.Head.CHAR -> {
                val c = if (card is CandidateCard.Looked) card.card else (card as CandidateCard.Char).card
                CharCardView(themedContext, c, textSize, twoColumns, accent, cardButtons(accent, close, commit, text)).let { it to it.takeIf { focus == CardNav.Focus.CARD } }
            }
            CardNav.Head.WORD -> {
                val word = (card as CandidateCard.Word).card
                val pick = { row: Int ->
                    val r = word.rows[row]
                    service.lifecycleScope.launch {
                        val c = fcitx.runOnReady { charCardOf(r.text, r.reading) } ?: return@launch
                        // closed meanwhile (another candidate's menu maybe up), or another row's card shown already
                        if (candidateActionMenu !== menu || nav.head != CardNav.Head.WORD) return@launch
                        pickChar(row, c)
                        step()
                    }
                    Unit
                }
                WordCardView(themedContext, word, textSize, twoColumns, accent, cardButtons(accent, close, commit, text), pick).let { it to it.head.takeIf { focus == CardNav.Focus.WORD } }
            }
            CardNav.Head.WORD_CHAR -> {
                val word = (card as CandidateCard.Word).card
                val c = checkNotNull(charOfWord)
                val back = backView(word, accent) { if (nav.back()) step() }
                CharCardView(themedContext, c, textSize, twoColumns, accent, cardButtons(accent, close, null, c.text), back).let { it to back.takeIf { focus == CardNav.Focus.BACK } }
            }
        }
    }

    // a word's character's card: the way back to the word's, ‹ 银行 yín háng
    private fun backView(word: WordCard, accent: Int, back: () -> Unit) = TextView(themedContext).apply {
        text = buildSpannedString {
            append(BACK)
            color(accent) { append(word.text) }
            append(SPACE + word.line(ReadingStyle.PINYIN))
        }
        setTextSize(TypedValue.COMPLEX_UNIT_SP, CARD_TEXT_SP)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(CharCardLayout.MIN_ROW_DP)
        background = context.styledDrawable(android.R.attr.selectableItemBackground)
        contentDescription = context.getString(R.string.card_back, word.text)
        setOnClickListener { back() }
        tapsTo(context.getString(R.string.card_back, word.text))
    }

    // 上屏 and 复制 for any; 编辑 and 删除 for the user's own, a deletion undone from the same menu
    private fun quickPhraseLines(idx: Int, text: String, found: QuickPhraseBook.Found): List<CandidateLearningMenu.Line> {
        val entry = found.entry
        val commit = CandidateLearningMenu.Line(context.getString(R.string.quick_phrase_commit)) {
            service.postFcitxJob { select(idx, text) }
            null
        }
        val copy = CandidateLearningMenu.Line(context.getString(R.string.quick_phrase_copy)) {
            copy(found.content)
            null
        }
        if (!found.mine) return listOf(commit, copy)
        val edit = CandidateLearningMenu.Line(context.getString(R.string.edit)) {
            AppUtil.launchQuickPhraseEditor(context, entry)
            null
        }
        val delete = CandidateLearningMenu.Line(context.getString(R.string.delete)) {
            // where it was and when last used, for 撤销 to put it back as it was
            var removed: QuickPhraseManager.Removed? = null
            quickPhraseChange { removed = QuickPhraseManager.remove(entry) }
            listOf(
                CandidateLearningMenu.Line(context.getString(R.string.quick_phrase_deleted), enabled = false),
                CandidateLearningMenu.Line(context.getString(R.string.undo)) {
                    // after the deletion (one at a time, in order): an entry the same added meanwhile is kept as it is
                    quickPhraseChange { removed?.let { QuickPhraseManager.restore(it) } }
                    null
                },
            )
        }
        return listOf(commit, copy, edit, delete)
    }

    // the menu's changes to quick input, one at a time in the order made: an undo never runs before its deletion
    private val quickPhraseChanges = Mutex()

    /**
     * [op] saved off the main thread, then quick input reads its files again. Failed (a file that cannot be
     * written or read), it says why and closes the menu: no 已删除 shown for an entry still there.
     */
    private fun quickPhraseChange(op: () -> Unit) {
        service.lifecycleScope.launch {
            try {
                quickPhraseChanges.withLock { withContext(Dispatchers.IO) { op() } }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                if (e is CancellationException) throw e
                Timber.w(e, "quick phrase")
                context.toast(e)
                candidateActionMenu?.dismiss()
                return@launch
            }
            fcitx.runIfReady { reloadConfig() }
        }
    }

    /**
     * Voice's talk, while one goes on (the space held, the bar's microphone tapped): it sees every
     * touch first (the finger that long-pressed space, which the key itself no longer reports once
     * its long press has fired), and hears of the field or the keyboard going (InputView's
     * startInput and finishInput).
     */
    interface Hold {
        /** How input ended under it. */
        enum class InputEnd {
            /** another field (or none) */
            LEFT,

            /** the keyboard hidden, the field kept and its connection still good */
            HIDDEN,

            /**
             * input started again, the new field current: maybe the same one (a WebView's or an
             * app's restartInput, the keyboard shown again, the screen turned), maybe not
             */
            STARTED,
        }

        /** each touch event, before the keys see it */
        fun touched(ev: MotionEvent)

        fun inputEnded(how: InputEnd)
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

    /** Whether the keyboard floats: a character's card is then in one column, as upright (CharCardLayout). */
    protected open val floatingKeyboard: Boolean get() = false

    /**
     * A card's buttons: 上屏 ([commit], as the candidate tapped; none for a word's character, Q2) and 复制 ([copied]
     * alone to the clipboard, nothing typed changed; 复制「行」 for a word's character), each closing the menu ([close]).
     */
    private fun cardButtons(accent: Int, close: () -> Unit, commit: (() -> Unit)?, copied: String): View = LinearLayout(themedContext).apply {
        fun button(label: String, act: () -> Unit) = TextView(themedContext).apply {
            text = label
            gravity = Gravity.CENTER
            minHeight = dp(BUTTON_HEIGHT)
            setTextColor(accent)
            background = context.styledDrawable(android.R.attr.selectableItemBackground)
            setOnClickListener {
                act()
                close()
            }
        }
        commit?.let { addView(button(context.getString(R.string.quick_phrase_commit), it), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        val copy = if (commit == null) context.getString(R.string.card_copy_char, copied) else context.getString(R.string.quick_phrase_copy)
        addView(button(copy) { copy(copied) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    // [text] alone to the clipboard (a card's character, a quick input entry's content): nothing committed, what is typed stays
    private fun copy(text: String) {
        context.clipboardManager.setPrimaryClip(ClipData.newPlainText(null, text))
    }

    /**
     * A card's step or lines swapped change the menu's height, and over the candidate its top with it: the
     * system slid the window there from where it was, its new height drawn at the old top first, over the
     * candidate bar. Put there at once. Set once on the window's
     * params after its first show: `PopupWindow.update` changes the same params, the flag kept.
     */
    private fun notSlid(decor: View) {
        val params = decor.layoutParams as? WindowManager.LayoutParams ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            params.setCanPlayMoveAnimation(false)
            return
        }
        // before 34 the same switch is the private flag under it; privateFlags is hidden API on the greylist
        // (reflection allowed whatever the targetSdk, logged so on API 29), and where it is missing the slide stays
        try {
            val field = WindowManager.LayoutParams::class.java.getField("privateFlags")
            field.setInt(params, field.getInt(params) or PRIVATE_FLAG_NO_MOVE_ANIMATION)
        } catch (e: ReflectiveOperationException) {
            Timber.w(e, "menu window: no move animation flag")
        }
    }

    /**
     * A card's step shown: grown anew from the candidate [pressed] where the line opened to a card or the window
     * went to the candidate's other side ([CardNav.growsAnew]: a word card under it, the character's taller one
     * over it, seen on a floating keyboard moved up); else the card changed in place (grown again on each tap,
     * it went blank for two frames and slid in from the candidate).
     */
    private fun showStep(menu: ListPopupWindow, nav: CardNav, pressed: View) {
        val wasAbove = menu.listView?.rootView?.let { above(it, pressed) }
        menu.show()
        scrollbarShown(menu.listView, card = true)
        val decor = menu.listView?.rootView ?: return
        decor.doOnPreDraw { if (nav.growsAnew(wasAbove, above(decor, pressed))) growFrom(decor, pressed) }
    }

    // whether the menu's window is over the candidate [pressed] (its middle above the candidate's), else under it
    private fun above(decor: View, pressed: View): Boolean {
        val at = IntArray(2).also(decor::getLocationOnScreen)
        val from = IntArray(2).also(pressed::getLocationOnScreen)
        return at[1] + decor.height / 2 < from[1] + pressed.height / 2
    }

    /** How many times a candidate's menu window has grown from the candidate pressed, for tests. */
    @VisibleForTesting
    internal var menuGrown = 0

    // the menu's window [decor] grown from the middle of the candidate [pressed], wherever it opened
    private fun growFrom(decor: View, pressed: View) {
        menuGrown++
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
        val gesturesBottom = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
        val frame = context.navbarFrameHeight()
        var insetsBottom = max(navBars.bottom, mandatory.bottom)
        if (insetsBottom <= 0) {
            // check system gesture insets and fallback to navigation_bar_frame_height just in case
            if (gesturesBottom > 0) {
                insetsBottom = max(gesturesBottom, frame)
            }
        }
        // some ROMs hiding the gesture line still report it, leaving a blank strip under the
        // keyboard: the raw values go to the log a user exports, once each time they change
        val seen = listOf(
            navBars.bottom, mandatory.bottom,
            gesturesBottom,
            insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom,
            frame, insetsBottom,
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

    private companion object {
        // 上屏 and 复制's height, as a menu line's
        const val BUTTON_HEIGHT = CharCardLayout.MIN_ROW_DP
        private const val CARD_TEXT_SP = 16f

        // WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION (hidden; 0x40 from API 29 to 33)
        private const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x40
        private const val BACK = "‹ "
        private const val SPACE = "　"
    }
}
