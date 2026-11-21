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
import android.view.accessibility.AccessibilityManager
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
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseManager
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.reading.CandidateCard
import org.fcitx.fcitx5.android.engine.reading.CharCard
import org.fcitx.fcitx5.android.engine.reading.ReadingStyle
import org.fcitx.fcitx5.android.engine.reading.WordCard
import org.fcitx.fcitx5.android.engine.session.Action
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

    protected abstract fun onStartHandleFcitxEvent()

    protected abstract fun handleFcitxEvent(it: HostEvent)

    private var eventHandlerJob: Job? = null

    private fun setupFcitxEventHandler() {
        eventHandlerJob = service.lifecycleScope.launch {
            // caches read once subscribed: an event in between (a rebuild's reset) comes after them; the flow
            // keeps none for late comers
            fcitx.runImmediately { eventFlow }.onSubscription { onStartHandleFcitxEvent() }.collect {
                // the menu holds an index: in a changed list it names another candidate, and "Forget word" would
                // forget that one
                if (it is HostEvent.Candidates || it is HostEvent.Paged) {
                    candidateActionMenu?.dismiss()
                }
                typed(it)
                words(it)
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

    // the events that close the menu are no longer collected: its index would age unchecked, the chip's undo with it
    protected fun dismissPopups() {
        candidateActionMenu?.dismiss()
        undoChip?.dismiss()
        dropOffer()
    }

    private fun triggerCandidateAction(idx: Int, actionIdx: Int, text: String) {
        fcitx.runIfReady { triggerCandidateAction(idx, actionIdx, text) }
    }

    @VisibleForTesting
    internal var candidateActionMenu: ListPopupWindow? = null

    val themedContext = context.withTheme(R.style.Theme_InputViewTheme)

    private var candidateActionMenuClosed = 0L

    private var swallowing = false

    protected open val pagedCandidates = false

    private var undoChip: UndoChip? = null

    protected open val undoAnchor: View get() = this

    /** Used instead when over [undoAnchor] it would hide candidates. */
    protected open val undoAbove: View get() = this

    protected open val undoAnchorShowsCandidates: Boolean get() = true

    protected open val undoBelowOk: Boolean get() = true

    protected open val undoBottomLimit: Int get() = rootView.height - (rootWindowInsets?.let(::floatingBottomInset) ?: 0)

    /** Floating: the chip goes on the keyboard's side toward the screen's middle ([UndoChipPlacement.floating]). */
    protected open fun undoFloating(keyboard: Rect, screen: Rect): Boolean = false

    protected open fun undoStrip(out: Rect): Boolean = false

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

    protected fun noticePlace(width: Int, height: Int): UndoChipPlacement.Place = undoPlace(width, height, aboveKeys = true)

    /** A notice its window meets is put away, or a failure hidden ([UndoChip.covered]). */
    protected fun noticeCovered(menu: Rect) {
        undoChip?.covered(menu)
    }

    protected fun noticeUncovered() {
        undoChip?.uncovered()
    }

    private val learningMenu by lazy { CandidateLearningMenu(themedContext, fcitx, service.lifecycleScope, ::chip) }

    private fun notice(
        message: String,
        undo: UndoChip.Undo?,
        aboveKeys: Boolean,
        steady: Boolean = false,
        ms: Long? = null,
        choices: List<UndoChip.Choice> = emptyList(),
    ): Boolean {
        // one line per notice, never its text (it may hold a typed word): UAT counts these to catch a notice
        // said twice, which crossfades in the same chip and looks like one
        Timber.i("say")
        chipTyping.chipShown()
        // another notice: a word offer waiting is not shown over it
        removeCallbacks(offerDue)
        offerGate.noticed()
        offerShown = choices.isNotEmpty()
        val chip = undoChip ?: UndoChip(undoAnchor, theme, ::undoPlace).also {
            it.dismissed = offerGate::hidden
            undoChip = it
        }
        return chip.show(message, undo, aboveKeys, steady, ms, choices)
    }

    /** Whether the letters are up: a word offer is for them, not over symbols or emoji. */
    protected open val lettersShown: Boolean get() = false

    /** The keyboard showed another window or layout: an offer up stays only over the letters. */
    protected fun keysChanged() {
        if (lettersShown) return
        if (offerShown) undoChip?.dismiss(animated = true)
        dropOffer()
    }

    private val offerGate = WordOfferGate()

    private val offerDue = Runnable { showOffer() }

    private val offerSeen = Runnable { offerGate.seen(SystemClock.uptimeMillis())?.let { id -> fcitx.runIfReady { offerShown(id) } } }

    // the notice shown is a word offer
    private var offerShown = false

    private fun words(event: HostEvent) {
        when (event) {
            is HostEvent.WordOffer -> {
                removeCallbacks(offerDue)
                // withdrawn: the field changed, or the run ended
                if (event.offer == null && offerShown) undoChip?.dismiss(animated = true)
                if (offerGate.offered(event.offer)) postDelayed(offerDue, WordOfferGate.PAUSE_MS)
            }
            is HostEvent.WordRemembered -> {
                val (text, code) = event.word
                val message = context.getString(R.string.word_remembered, text, imName(), code)
                say(message, UndoChip.Undo({ fcitx.runIfReady { unrememberWord(text, code) } }))
            }
            else -> {}
        }
    }

    private fun imName() = InputMethodNames.of(context, fcitx.runImmediately { inputMethodEntryCached })

    private fun showOffer() {
        val exploring = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        val offer = offerGate.due(noticeShown = undoChip?.showing == true, touchExploring = exploring, letters = lettersShown) {
            service.currentInputConnection?.getTextBeforeCursor(Action.Context.HOST_CHARS, 0)
        } ?: return
        val im = imName()
        val choices = offer.words.map { (i, w) ->
            val said = context.getString(R.string.word_offer_said, w.text, im, w.code.toList().joinToString(" "))
            UndoChip.Choice("${w.text} ${w.code}", said) { remember(offer.id, i) }
        }
        if (notice(context.getString(R.string.word_offer), null, aboveKeys = true, ms = NoticeState.STAYS, choices = choices)) {
            offerGate.shown(offer.id, SystemClock.uptimeMillis())
            removeCallbacks(offerSeen)
            postDelayed(offerSeen, WordOfferGate.SEEN_MS)
        }
    }

    // the field's text as tapped: the session checks the run is still what ends it
    private fun remember(id: Int, index: Int) {
        offerShown = false
        val before = service.currentInputConnection?.getTextBeforeCursor(Action.Context.HOST_CHARS, 0)?.toString() ?: return
        fcitx.runIfReady { rememberWord(id, index, before) }
    }

    private fun dropOffer() {
        removeCallbacks(offerDue)
        removeCallbacks(offerSeen)
        offerGate.released()
        offerGate.noticed()
        offerShown = false
    }

    private fun chip(message: String, token: Long) {
        val undo = if (token == LearningResult.NO_UNDO) null else UndoChip.Undo({ learningUndo(token) })
        notice(message, undo, aboveKeys = false)
    }

    private fun learningUndo(token: Long) {
        service.lifecycleScope.launch {
            when (fcitx.runOnReady { learning(LearningOp.Undo(token)) }) {
                LearningResult.Unavailable -> chip(context.getString(R.string.learning_undo_gone), LearningResult.NO_UNDO)
                // the rewrite dropping the act failed: said, not just a chip gone. No "try again": the act is no longer
                // undoable, and a restart would keep it on disk while dropping what was held in memory
                LearningResult.Failed -> chip(context.getString(R.string.learning_undo_failed), LearningResult.NO_UNDO)
                else -> {}
            }
        }
    }

    /**
     * Not over the bar, whose buttons it would hide. [steady]: not put away by typing. False if it could
     * not be shown: [undo] lapsed.
     */
    fun say(message: String, undo: UndoChip.Undo? = null, steady: Boolean = false, ms: Long? = null) =
        notice(message, undo, aboveKeys = true, steady, ms)

    /** A tip [say]s nothing over it. */
    val holdsUndo: Boolean get() = undoChip?.holdsUndo == true

    private val chipTyping = ChipTyping()

    private fun typed(event: HostEvent) {
        val goes = when (event) {
            is HostEvent.ClientPreedit -> chipTyping.editorPreedit(event.text.toString())
            is HostEvent.InputPanel -> chipTyping.barPreedit(event.preedit.toString())
            is HostEvent.Key -> chipTyping.key(event.up)
            is HostEvent.Commit -> chipTyping.typed()
            else -> false
        }
        if (goes) undoChip?.typed()
    }

    protected fun forgetCandidate(idx: Int, text: String) {
        candidateActionMenu?.dismiss()
        service.lifecycleScope.launch { learningMenu.forget(idx, text, pagedCandidates) }
    }

    /**
     * Quick input's own lines (a paged list has none, as the host has no page offset for it); else the engine's
     * menu with the card in the same trip; else, for an addon's input method, its own words.
     */
    private suspend fun candidateMenu(idx: Int, text: String): Pair<List<CandidateLearningMenu.Line>, CandidateCard?> {
        val im = fcitx.runImmediately { inputMethodEntryCached }.uniqueName
        val engines = EngineBridge.isEngines(im)
        val phrase = if (pagedCandidates) null else fcitx.runOnReady { quickPhraseAt(idx) }
        if (phrase != null) return quickPhraseLines(idx, text, phrase) to null
        val op = learningMenu.menuOf(idx, text, pagedCandidates)
        val (learned, card) = fcitx.runOnReady { learning(op) to if (engines) candidateCard(idx, text, pagedCandidates) else null }
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
            val nav = CardNav(card)
            val head = if (card == null) null else FrameLayout(themedContext)
            // a picked line may show others in its place (a block's confirmation): the same popup
            var shown = lines
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
            // not a PopupMenu: it takes the window focus from the editor, and some (the launcher's search) finish
            // composing when they lose it, so what was typed is gone before the action. Not focusable, it lets an
            // outside touch through: see [dispatchTouchEvent]
            val menu = ListPopupWindow(themedContext, null, android.R.attr.popupMenuStyle)
            fun twoColumns() = nav.twoColumns(KeyboardArea.landscape(context), floatingKeyboard, pagedCandidates)
            // a card's menu has a fixed width (two columns in landscape); a title's or reading line's only what it needs
            fun width(): Int {
                val widest = CharCardLayout.width(resources.displayMetrics.widthPixels, dp(320), twoColumns())
                return if (nav.expanded) widest else min(adapter.widest(FrameLayout(themedContext)), widest)
            }
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
                    cardFocused = null
                }
                // not the platform's drop-down animation: grown out of the candidate pressed (Motion)
                animationStyle = 0
                show()
                listView?.rootView?.let(::notSlid)
                scrollbarShown(listView, card = head != null)
                listView?.rootView?.let { decor -> decor.doOnPreDraw { growFrom(decor, view) } }
            }
        }
    }

    @VisibleForTesting
    internal var cardFocused: View? = null

    private fun focusForTalkBack(target: View) {
        cardFocused = target
        target.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
    }

    private fun scrollbarShown(list: ListView?, card: Boolean) {
        if (card && list != null && KeyboardArea.landscape(context)) list.isScrollbarFadingEnabled = false
    }

    /** Returns the view TalkBack's focus goes to, null before any step. */
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
            var removed: QuickPhraseManager.Removed? = null
            quickPhraseChange { removed = QuickPhraseManager.remove(entry) }
            listOf(
                CandidateLearningMenu.Line(context.getString(R.string.quick_phrase_deleted), enabled = false),
                CandidateLearningMenu.Line(context.getString(R.string.undo)) {
                    // after the deletion: an equal entry added meanwhile is kept as it is
                    quickPhraseChange { removed?.let { QuickPhraseManager.restore(it) } }
                    null
                },
            )
        }
        return listOf(commit, copy, edit, delete)
    }

    // in order: an undo never runs before its deletion
    private val quickPhraseChanges = Mutex()

    /** Saved off the main thread; on failure it says why and closes the menu: no 已删除 for an entry still there. */
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
     * Voice's talk while one goes on: it sees every touch first (the key no longer reports the finger
     * once its long press fired), and hears of the field or keyboard going.
     */
    interface Hold {
        enum class InputEnd {
            LEFT,

            HIDDEN,

            /** input started again: maybe the same field (restartInput, the screen turned), maybe not */
            STARTED,
        }

        fun touched(ev: MotionEvent)

        fun inputEnded(how: InputEnd)
    }

    var hold: Hold? = null

    private val downYs = SparseArray<Float>()

    fun downY(pointer: Int): Float = downYs.get(pointer, 0f)

    /**
     * A touch that closes the menu only closes it: not a key typed by the way. The menu may see it first
     * and close, so a touch that went down before it closed counts too.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            downYs.put(ev.getPointerId(ev.actionIndex), ev.getY(ev.actionIndex))
        }
        offerTouched(ev.actionMasked)
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

    // a word offer waits for the keys to be let go of: timed from the last finger up
    private fun offerTouched(action: Int) {
        val timeFrom = when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(offerDue)
                offerGate.down()
                false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> offerGate.up()
            MotionEvent.ACTION_CANCEL -> offerGate.released()
            else -> false
        }
        if (timeFrom) postDelayed(offerDue, WordOfferGate.PAUSE_MS)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            candidateActionMenu?.dismiss()
            undoChip?.dismiss()
            dropOffer()
        }
    }

    protected open val floatingKeyboard: Boolean get() = false

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

    private fun copy(text: String) {
        context.clipboardManager.setPrimaryClip(ClipData.newPlainText(null, text))
    }

    /**
     * A card's step changes the menu's height, and the system slides the window there, drawing the new
     * height at the old top over the candidate bar first. Set once after the first show:
     * `PopupWindow.update` keeps the flag.
     */
    private fun notSlid(decor: View) {
        val params = decor.layoutParams as? WindowManager.LayoutParams ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            params.setCanPlayMoveAnimation(false)
            return
        }
        // before 34 the switch is the private flag; privateFlags is greylisted hidden API (reflection allowed,
        // logged on API 29); where it is missing the slide stays
        try {
            val field = WindowManager.LayoutParams::class.java.getField("privateFlags")
            field.setInt(params, field.getInt(params) or PRIVATE_FLAG_NO_MOVE_ANIMATION)
        } catch (e: ReflectiveOperationException) {
            Timber.w(e, "menu window: no move animation flag")
        }
    }

    /**
     * Grown anew from [pressed] where the window went to the candidate's other side ([CardNav.growsAnew]);
     * else changed in place (grown on each tap, it went blank for two frames).
     */
    private fun showStep(menu: ListPopupWindow, nav: CardNav, pressed: View) {
        val wasAbove = menu.listView?.rootView?.let { above(it, pressed) }
        menu.show()
        scrollbarShown(menu.listView, card = true)
        val decor = menu.listView?.rootView ?: return
        decor.doOnPreDraw { if (nav.growsAnew(wasAbove, above(decor, pressed))) growFrom(decor, pressed) }
    }

    private fun above(decor: View, pressed: View): Boolean {
        val at = IntArray(2).also(decor::getLocationOnScreen)
        val from = IntArray(2).also(pressed::getLocationOnScreen)
        return at[1] + decor.height / 2 < from[1] + pressed.height / 2
    }

    @VisibleForTesting
    internal var menuGrown = 0

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

    protected fun getNavBarBottomInset(windowInsets: WindowInsets): Int = navBarBottomInset(windowInsets)

    /**
     * The floating keyboard stays above it. Under gesture navigation the bar the system draws over the
     * keyboard's window is the frame's height, taller than the insets say.
     */
    protected fun floatingBottomInset(windowInsets: WindowInsets): Int {
        val insets = WindowInsetsCompat.toWindowInsetsCompat(windowInsets)
        val gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
        return max(navBarBottomInset(windowInsets), if (gestures > 0) context.navbarFrameHeight() else 0)
    }

    private var loggedInsets: List<Int>? = null

    private fun navBarBottomInset(windowInsets: WindowInsets): Int {
        val insets = WindowInsetsCompat.toWindowInsetsCompat(windowInsets)
        val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        // in case navigation bar insets goes wrong (eg. on LineageOS 21+ with gesture navigation)
        // use mandatory system gesture insets
        val mandatory = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        val gesturesBottom = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
        val frame = context.navbarFrameHeight()
        var insetsBottom = max(navBars.bottom, mandatory.bottom)
        if (insetsBottom <= 0) {
            if (gesturesBottom > 0) {
                insetsBottom = max(gesturesBottom, frame)
            }
        }
        // some ROMs hiding the gesture line still report it: the raw values go to the exported log once per change
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
        const val BUTTON_HEIGHT = CharCardLayout.MIN_ROW_DP
        private const val CARD_TEXT_SP = 16f

        // WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION (hidden; 0x40 from API 29 to 33)
        private const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x40
        private const val BACK = "‹ "
        private const val SPACE = "　"
    }
}
