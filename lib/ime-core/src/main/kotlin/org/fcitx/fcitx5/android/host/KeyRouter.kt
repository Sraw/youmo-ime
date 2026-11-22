/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.CoreLog
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.TextFormatFlag
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.EngineEvent
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.runtime.KeyboardPair
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.engine.session.WordOffer

/**
 * The native `androidengine` addon ported (tag `native-last`): keys to [EngineEvent]s, snapshots shown,
 * Chinese punctuation. A session that throws is handled as `engineEventCallback`
 * did: the key goes to the app, the panel cleared, the session reset unless it never answered.
 */
class KeyRouter(
    private val host: EngineHost,
    private val services: HostServices,
    private val strings: HostStrings = HostStrings.ENGLISH,
    var punctuation: EnginePunctuation? = null,
) : KeyEngine {

    /** The engine options the router reads (`AndroidEngineConfig`). */
    data class Options(val pageSize: Int = DEFAULT_PAGE_SIZE, val englishSuggest: Boolean = true)

    var options = Options()

    // English without suggestions: the last key was a glide the session took, its word held; cleared by any
    // other key, a reset or activate. Not per context: keys reach only the focused one and focus changes reset.
    private var glidedAsIs = false

    // input methods whose session has answered: one that never did has nothing to drop
    private val answered = HashSet<String>()

    // what was typed since nothing was, for 清除's undo; null once part was committed or a candidate acted on
    private var typed: MutableList<Typed>? = ArrayList()

    // failures by call/im/event/class: the trace at the first, a count at the 2nd, 4th, 8th…
    private val failures = HashMap<String, Long>()

    /** `AndroidEngine::activate`. */
    fun activate(ic: InputContext, im: String) {
        ic.preeditEnabled = composesText(im)
        glidedAsIs = false
        send(ic, im, EngineEvent.RESET)
    }

    /** `AndroidEngine::reset`: handwriting's text put in, or with [drop] dropped (清除, the backspace swipe). */
    fun reset(ic: InputContext, im: String, drop: Boolean = false) {
        glidedAsIs = false
        send(ic, im, EngineEvent.RESET, if (drop) EngineEvent.RESET_DROP else 0)
    }

    /** 联想's ×: drops the predictions shown, not the setting nor the context (a reset would lose English's space); false if none show. */
    fun dropPredictions(ic: InputContext, im: String): Boolean {
        if (ic.panel.candidateList?.predicting != true) return false
        send(ic, im, EngineEvent.DROP_PREDICTIONS)
        return true
    }

    /** `AndroidEngine::deactivate`: [switching] keeps what was typed, as libime's pinyin does; losing focus drops it. */
    fun deactivate(ic: InputContext, im: String, switching: Boolean) {
        if (switching) enter(ic, im)
        reset(ic, im)
    }

    /** What [im] shows committed as Return commits it (pinyin's code as typed), marks on offer taken first. */
    fun enter(ic: InputContext, im: String) {
        endPunctuation(ic, take = true)
        send(ic, im, EngineEvent.ENTER)
    }

    // androidengine's branches one for one, in its order, to be read against it
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
    override fun keyEvent(ic: InputContext, e: KeyEvent) {
        if (e.release) return
        val key = e.key
        if (key.isModifier) return
        // shortcuts are the app's: a mark on offer goes in first, so the app's action (a paste) comes after it
        if (key.states and SHORTCUT != 0) {
            endPunctuation(ic, take = true)
            // it may start quick phrase's or Unicode's code, shown above the candidates; not with English's word in
            // the app, which would stay there as composing text
            if (ic.panel.clientPreedit.isEmpty()) ic.preeditEnabled = false
            return
        }
        // Backspace takes back a punctuation key's marks on offer; any other key takes the first
        if (key.sym == KeySyms.BACKSPACE && endPunctuation(ic, take = false)) {
            e.filterAndAccept()
            return
        }
        val picks = key.sym == KeySyms.SPACE || key.sym == KeySyms.RETURN || key.sym == KeySyms.KP_ENTER
        if (picks && endPunctuation(ic, take = true)) {
            e.filterAndAccept()
            return
        }
        endPunctuation(ic, take = true)
        val im = services.inputMethod(ic)
        // grouped keyboards' codes, taps with places and strokes are never let through (the app would type them):
        // read by the input method whose keyboard it is, swallowed by any other (a switch not yet caught up)
        val cp = KeySyms.toUnicode(key.sym)
        val code = InputMethodCatalog.isCode(cp) || cp in Keyboard.STROKES
        val reads = if (cp in Keyboard.STROKES) InputMethodCatalog.filtersByStrokes(im) else InputMethodCatalog.readsCode(im, cp)
        if (code && !reads) {
            e.filterAndAccept()
            return
        }
        val chinese = InputMethodCatalog.listed(im)?.chinese == true
        if (!chinese && !suggestsEnglish(ic, e.virtual)) {
            asIs(ic, im, e)
            return
        }
        // a hardware - and = turn pages while typing; otherwise they are marks
        val pageKey = key.sym == KeySyms.MINUS || key.sym == KeySyms.EQUAL
        val typing = ic.panel.clientPreedit.isNotEmpty() || ic.panel.preedit.isNotEmpty()
        if (chinese && pageKey && typing && !e.virtual &&
            send(ic, im, if (key.sym == KeySyms.MINUS) EngineEvent.PAGE_UP else EngineEvent.PAGE_DOWN)
        ) {
            e.filterAndAccept()
            return
        }
        val (what, arg) = eventOf(key)
        // 双拼's pair tells a hardware ; (微软's ing) from the screen's mark: KeyboardPair
        val sent = if (what == EngineEvent.CHAR && !e.virtual && im == InputMethodCatalog.SHUANGPIN && !KeyboardPair.isLetter(arg)) EngineEvent.HARD_CHAR else what
        // pinyin failing to take a code (its session not made) still swallows it
        if (send(ic, im, sent, arg, e.payload) || code || (what == EngineEvent.CHAR && chinese && pushPunctuation(ic, key))) {
            e.filterAndAccept()
        }
    }

    private fun eventOf(key: Key): Pair<Int, Int> = when (key.sym) {
        KeySyms.BACKSPACE -> EngineEvent.BACKSPACE to 0
        KeySyms.RETURN, KeySyms.KP_ENTER -> EngineEvent.ENTER to 0
        KeySyms.ESCAPE -> EngineEvent.ESCAPE to 0
        KeySyms.PAGE_UP -> EngineEvent.PAGE_UP to 0
        KeySyms.PAGE_DOWN -> EngineEvent.PAGE_DOWN to 0
        else -> {
            val unicode = KeySyms.toUnicode(key.sym)
            // a control character is a key like an arrow; a keypad's is told apart (the nine keys read 2 as abc)
            if (unicode >= SPACE && unicode != DEL) {
                (if (key.isKeyPad) EngineEvent.KEYPAD else EngineEvent.CHAR) to unicode
            } else {
                EngineEvent.OTHER to key.sym
            }
        }
    }

    /** English without suggestions: the keys type themselves, what was typed going in first. */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
    private fun asIs(ic: InputContext, im: String, e: KeyEvent) {
        val key = e.key
        val unicode = KeySyms.toUnicode(key.sym)
        // swallowed whatever the session does: let through, the app would type the private-use char. No Enter
        // first: it would have the session forget the char before the cursor
        if (unicode == GLIDE_CHAR) {
            glidedAsIs = send(ic, im, EngineEvent.CHAR, GLIDE_CHAR, e.payload)
            e.filterAndAccept()
            return
        }
        // never let through (private-use char); a letter a long press pinned is typed in its case
        if (unicode in NINE_KEYS_FIRST until NINE_KEYS_END) {
            if (unicode >= NINE_LETTERS_FIRST) {
                val n = unicode - NINE_LETTERS_FIRST
                services.commit(ic, ((if (n >= LETTERS) 'A' else 'a') + n % LETTERS).toString())
            }
            e.filterAndAccept()
            return
        }
        // right after a glide, Backspace and other chars are the session's; an ASCII letter types itself
        val afterGlide = glidedAsIs.also { glidedAsIs = false }
        val letter = unicode in 'a'.code..'z'.code || unicode in 'A'.code..'Z'.code
        val mark = unicode >= SPACE && unicode != DEL && !letter
        val erases = key.sym == KeySyms.BACKSPACE
        if (!afterGlide || (!erases && !mark)) {
            // the char a screen key types, for the session's context; never from a password field or a hardware key
            val told = (mark || letter || erases) && e.virtual && !ic.has(Capability.PASSWORD_OR_SENSITIVE)
            if (told) {
                send(ic, im, EngineEvent.TYPED, if (erases) ERASED else unicode)
            } else {
                send(ic, im, EngineEvent.ENTER)
            }
            return
        }
        if (send(ic, im, if (erases) EngineEvent.BACKSPACE else EngineEvent.CHAR, unicode)) e.filterAndAccept()
        // put away through the session, so its keyboard knows the panel is empty
        if (ic.panel.candidateList is EngineCandidateList) send(ic, im, EngineEvent.TYPED, 0)
    }

    /** `AndroidEngine::suggestsEnglish`. */
    private fun suggestsEnglish(ic: InputContext, virtual: Boolean) =
        options.englishSuggest && virtual && !ic.has(Capability.PASSWORD or Capability.NO_SPELL_CHECK or Capability.URL or Capability.EMAIL)

    /**
     * `AndroidEngine::invokeActionImpl`: a tap in the composed text moves the session's cursor; untaken, it
     * does nothing (fcitx's default would lose the code). In the app it is left to fcitx's default ([HostCore.moveCursor]).
     */
    override fun invokeAction(ic: InputContext, e: InvokeActionEvent) {
        if (e.action != InvokeActionEvent.Action.LeftClick || e.cursor < 0) return
        val taken = send(ic, services.inputMethod(ic), EngineEvent.CURSOR, e.cursor)
        // read after send, which sets it
        if (taken || !ic.has(Capability.PREEDIT)) e.filter()
    }

    /** `AndroidEngine::send`: [event] to [im]'s session, and what comes back shown; whether it took it. */
    fun send(ic: InputContext, im: String, event: Int, arg: Int = 0, payload: Action? = null): Boolean {
        ic.preeditEnabled = composesText(im)
        val learning = !ic.has(Capability.PASSWORD_OR_SENSITIVE)
        val s = call(im, event, arg, learning, payload)
        keep(Typed(event, arg, payload), s)
        if (event != EngineEvent.CURSOR || s.handled) show(ic, im, s)
        // after the commit shown: the app puts its notices away as text goes in
        // told only as it changes
        if (s.wordOffer != offered) {
            offered = s.wordOffer
            services.wordOffer(ic, s.wordOffer)
        }
        s.remembered?.let { services.wordRemembered(ic, it) }
        return s.handled
    }

    // the table's word offer last told
    private var offered: WordOffer? = null

    private fun keep(key: Typed, s: Snapshot) {
        val log = typed
        typed = when {
            s.preedit.isEmpty() -> ArrayList()
            s.commit.isNotEmpty() -> null
            log == null || !s.handled -> log
            // an action or pick by index could land on another word once reordered: never typed again
            !s.picked && key.event in RETYPED -> log.apply { add(key) }
            else -> null
        }
    }

    /** 清除: the keys that typed it, for [retype]; null when a part was chosen or in a password. */
    fun clearTyped(ic: InputContext, im: String): List<Typed>? {
        val keys = typed?.toList()?.takeIf { it.isNotEmpty() && !ic.has(Capability.PASSWORD_OR_SENSITIVE) }
        reset(ic, im, drop = true)
        return keys
    }

    fun retype(ic: InputContext, im: String, keys: List<Typed>) {
        reset(ic, im)
        keys.forEach { send(ic, im, it.event, it.arg, it.payload) }
    }

    data class Typed(val event: Int, val arg: Int, val payload: Action?)

    @Suppress("TooGenericExceptionCaught")
    private fun call(im: String, event: Int, arg: Int, learning: Boolean, payload: Action?): Snapshot {
        val s = try {
            host.event(im, event, arg, learning, payload)
        } catch (e: Throwable) {
            failed("onEvent", im, event, e)
            // the session must be reset with its cleared panel, or its state comes back; one failing to reset is skipped till it answers
            if (event != EngineEvent.RESET && im in answered) {
                try {
                    host.event(im, EngineEvent.RESET, EngineEvent.RESET_DROP, learning, payload = null)
                } catch (again: Throwable) {
                    failed("onEvent", im, EngineEvent.RESET, again)
                    answered.remove(im)
                }
            }
            return UNHANDLED
        }
        answered += im
        return s ?: UNHANDLED
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun fetch(im: String, from: Int, count: Int) = try {
        host.candidates(im, from, count)
    } catch (e: Throwable) {
        failed("candidates", im, -1, e)
        emptyList()
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun offers(im: String, index: Int): Set<Offer> = try {
        host.offers(im, index)
    } catch (e: Throwable) {
        failed("offers", im, -1, e)
        emptySet()
    }

    /** `AndroidEngine::pushSettings`. */
    @Suppress("TooGenericExceptionCaught")
    fun settings(settings: EngineSettings) = try {
        host.settings(settings)
    } catch (e: Throwable) {
        failed("configure", "", -1, e)
    }

    // `engineFailed`: the class only, never a message or argument (the log is exported; an argument is what was typed)
    private fun failed(call: String, im: String, event: Int, e: Throwable) {
        val where = "$call $im${if (event < 0) "" else " $event"} ${e.javaClass.name}"
        val count = (failures[where] ?: 0) + 1
        failures[where] = count
        if (count == 1L) {
            CoreLog.e("engine failed: $where\n${e.stackTrace.joinToString("\n") { "  at $it" }}")
        } else if (count and (count - 1) == 0L) {
            CoreLog.e("engine failed again: $where, $count times")
        }
    }

    /** `AndroidEngine::show`. */
    @Suppress("ComplexCondition")
    private fun show(ic: InputContext, im: String, s: Snapshot) {
        val panel = ic.panel
        if (!s.handled && s.deleteBefore <= 0 && s.commit.isEmpty() && s.preedit.isEmpty() &&
            s.candidates.isEmpty() && panel.empty
        ) {
            return
        }
        // English's space after a word, moved behind the punctuation typed next
        if (s.deleteBefore > 0) services.deleteSurrounding(ic, s.deleteBefore, 0)
        if (s.commit.isNotEmpty()) services.commit(ic, s.commit)
        panel.reset()
        if (s.preedit.isNotEmpty()) showPreedit(ic, s.preedit, s.cursor)
        // the code on the panel, not in the field: the app may write it there
        ic.panel.code = ic.panel.preedit.isNotEmpty()
        if (s.lookingUp) panel.auxUp = TempKeys.text(LOOKING_UP)
        if (s.candidates.isNotEmpty()) panel.candidateList = EngineCandidateList(ic, im, s)
        services.updatePreedit(ic)
        services.updatePanel(ic)
    }

    /** `showPreedit`: [cursor] in UTF-16 units (-1 or past its end: the end); in the app where [composesText], else above the candidates. */
    private fun showPreedit(ic: InputContext, text: String, cursor: Int = -1) {
        val client = ic.has(Capability.PREEDIT)
        val at = if (cursor < 0 || cursor >= text.length) text.length else cursor
        val preedit = FormattedText(arrayOf(text), intArrayOf(if (client) TextFormatFlag.Underline.flag else 0), at)
        if (client) ic.panel.clientPreedit = preedit else ic.panel.preedit = preedit
    }

    private fun clearPanel(ic: InputContext) {
        ic.panel.reset()
        services.updatePreedit(ic)
        services.updatePanel(ic)
    }

    /** `commitPunctuation`: [text], then [after] (a pair's other half), the cursor between. */
    private fun commitPunctuation(ic: InputContext, text: String, after: String) {
        val length = text.codePointCount(0, text.length)
        if (after.isNotEmpty() && ic.has(Capability.COMMIT_STRING_WITH_CURSOR) && length != 0) {
            services.commit(ic, text + after, length)
        } else {
            services.commit(ic, text + after)
            repeat(after.codePointCount(0, after.length)) { services.forwardKey(ic, Key(KeySyms.LEFT)) }
        }
    }

    /** `AndroidEngine::pushPunctuation`: a key with several marks offers them all, the first going in unless another is picked. */
    private fun pushPunctuation(ic: InputContext, key: Key): Boolean {
        val punctuation = punctuation ?: return false
        if (key.isKeyPad) return false
        val unicode = KeySyms.toUnicode(key.sym)
        val marks = punctuation.candidates(unicode)
        val (text, after) = punctuation.push(ic, unicode)
        if (text.isEmpty()) return false
        if (marks.size < 2) {
            commitPunctuation(ic, text, after)
            return true
        }
        val typed = String(Character.toChars(unicode))
        val pair = punctuation.get(unicode).second.isNotEmpty()
        // a half typed alone opened or closed the pair; each push turns it over, so a second turns it back
        val turns = pair && after.isEmpty()
        if (turns) punctuation.push(ic, unicode)
        val words = ArrayList<TempCandidate>()
        words += punctuationWord(text, after, text + after == typed, if (turns) unicode else 0)
        // that entry is the whole first card: the marks list a pair's two halves apart
        for (i in (if (pair) 2 else 1) until marks.size) words += punctuationWord(marks[i], "", marks[i] == typed)
        val list = TempCandidateList(words, options.pageSize, emptyList())
        list.setGlobalCursorIndex(0)
        ic.panel.reset()
        showPreedit(ic, text + after)
        ic.panel.candidateList = PunctuationCandidateList(list)
        services.updatePreedit(ic)
        services.updatePanel(ic)
        return true
    }

    /** `endPunctuation`: [take] commits the first mark on offer, else none goes in; whether there were. */
    private fun endPunctuation(ic: InputContext, take: Boolean): Boolean {
        val marks = ic.panel.candidateList as? PunctuationCandidateList ?: return false
        if (take) marks.candidateFromAll(0)?.select(ic) else clearPanel(ic)
        return true
    }

    private class PunctuationCandidateList(list: TempCandidateList) : TempModeCandidates(list)

    /** [key]: pushed again as it goes in, on a pair typed a half at a time; else 0. */
    private fun punctuationWord(mark: String, after: String, half: Boolean, key: Int = 0) =
        TempCandidate(mark + after, if (half) strings.half else "") { ic ->
            if (key != 0) punctuation?.push(ic, key)
            commitPunctuation(ic, mark, after)
            // last: the list that holds this word goes with the panel
            clearPanel(ic)
        }

    /** `EngineCandidateWord`: picking sends its index among all candidates. */
    internal inner class EngineWord(
        private val im: String,
        val index: Int,
        override val text: String,
        override val comment: String,
        override val readings: List<String> = emptyList(),
    ) :
        PanelCandidate {
        override fun select(ic: InputContext) {
            send(ic, im, EngineEvent.PICK, index)
        }
    }

    /**
     * `EngineCandidateList`: a snapshot's candidates as one scrolling list, the rest fetched in chunks as asked;
     * candidate(i) is the (first + i)th of all. A long press's offers are asked on the press and checked again on choice.
     */
    internal inner class EngineCandidateList(private val ic: InputContext, private val im: String, s: Snapshot) : CandidateList {
        private var total = s.total
        private val actionable = s.actionable
        override val predicting = s.predicting

        private val composing = s.preedit.isNotEmpty()

        private val offset = maxOf(s.first, 0)
        private val words = s.candidates.mapIndexedTo(ArrayList()) { i, text -> EngineWord(im, offset + i, text, s.hints.getOrElse(i) { "" }, s.readings.getOrElse(i) { emptyList() }) }
        override val size = words.size

        private val labels = List(size) { i -> s.labels.getOrNull(i)?.toString() ?: ((i + 1) % LABEL_KEYS).toString() }
        private var before: List<EngineWord>? = null

        private var asked: EngineWord? = null

        override fun label(index: Int) = labels.getOrElse(index) { "" }
        override fun candidate(index: Int): PanelCandidate? = if (index in 0 until size) words[index] else null

        override val cursorIndex get() = if (composing) 0 else if (spaceMarks) spacePicks else -1

        private val spaceMarks = s.spaceMarks
        private val spacePicks = s.spacePicks

        override val lead get() = if (spaceMarks) spacePicks else 0

        override val bulk get() = true
        override val totalSize get() = total

        override fun candidateFromAll(index: Int): PanelCandidate? {
            // a total of -1: the session has not counted them, so ask until it runs out
            if (index < 0 || (total >= 0 && index >= total)) return null
            if (index < offset) {
                // asked once: a failed fetch is not retried for each index
                val earlier = before ?: fetch(im, 0, offset).mapIndexed { i, c -> EngineWord(im, i, c.text, c.hint, c.readings) }.also { before = it }
                return earlier.getOrNull(index)
            }
            while (offset + words.size <= index) {
                val from = offset + words.size
                val count = maxOf(CHUNK, index + 1 - from)
                val more = fetch(im, from, count)
                if (more.size < count) total = from + more.size
                more.forEach { words += EngineWord(im, offset + words.size, it.text, it.hint, it.readings) }
                if (total >= 0 && index >= total) return null
            }
            return words[index - offset]
        }

        // pages turn only while composing, as PageUp/PageDown do
        override val pageable get() = composing
        override val hasPrev get() = offset > 0
        override val hasNext: Boolean
            get() {
                val after = offset + size
                return if (total >= 0) after < total else candidateFromAll(after) != null
            }

        override fun prev() {
            send(ic, im, EngineEvent.PAGE_UP)
        }

        override fun next() {
            send(ic, im, EngineEvent.PAGE_DOWN)
        }

        override fun actions(word: PanelCandidate): List<CandidateAction> {
            if (!actionable || word !is EngineWord) return emptyList()
            asked = word
            val offered = offers(im, word.index)
            return OFFER_ACTIONS.filter { it.first in offered }.map { (offer, event) -> CandidateAction(event, strings.offer(offer)) }
        }

        @Suppress("ComplexCondition")
        override fun triggerAction(word: PanelCandidate, id: Int) {
            val offer = OFFER_ACTIONS.filter { it.second == id }.map { it.first }.toSet()
            // only what the session still offers, on the candidate this list offered it for
            if (!actionable || word !is EngineWord || word !== asked || offer.isEmpty() || offers(im, word.index).none { it in offer }) return
            // the snapshot that comes back replaces this list: nothing of it is touched after
            send(ic, im, id, word.index)
        }
    }

    companion object {
        /** The 拼 key's own label, shown before a table's pinyin lookup. */
        const val LOOKING_UP = "拼"

        /** `AndroidEngineConfig::pageSize`'s default. */
        const val DEFAULT_PAGE_SIZE = 7

        // what these type does not hang on the candidates shown
        private val RETYPED = setOf(
            EngineEvent.CHAR, EngineEvent.HARD_CHAR, EngineEvent.KEYPAD, EngineEvent.BACKSPACE,
            EngineEvent.CURSOR, EngineEvent.PAGE_UP, EngineEvent.PAGE_DOWN,
        )

        // `EngineCandidateList::Chunk`
        internal const val CHUNK = 32

        private const val LABEL_KEYS = 10
        private const val SPACE = 0x20
        private const val DEL = 0x7f
        private const val LETTERS = 26

        // the char Session reads as Backspace for TYPED (`'\b'`)
        private const val ERASED = 8

        private const val SHORTCUT = KeyStates.CTRL or KeyStates.ALT or KeyStates.SUPER or KeyStates.HYPER

        /** The character the keyboard sends after a glide (`Keyboard.GLIDE`): never text. */
        const val GLIDE_CHAR = 0xF0101

        // what 九键英文 sends where English spells words, and the letters a long press pins
        private const val NINE_KEYS_FIRST = 0xF0040
        private const val NINE_LETTERS_FIRST = 0xF0060
        private const val NINE_KEYS_END = NINE_LETTERS_FIRST + 2 * LETTERS

        /** `offerActions`: a long press's offers, in the menu's order. */
        internal val OFFER_ACTIONS = listOf(
            Offer.FORGET to EngineEvent.FORGET,
            Offer.PIN to EngineEvent.PIN,
            Offer.UNPIN to EngineEvent.UNPIN,
            Offer.BLOCK to EngineEvent.BLOCK,
            // a custom phrase (a built-in date too) goes by the unpin's event
            Offer.DELETE_PHRASE to EngineEvent.UNPIN,
            Offer.DELETE_COMMON_WORD to EngineEvent.UNPIN,
        )

        // what a session that cannot load, or failed, answers: the key is not taken, nothing shown
        private val UNHANDLED = Snapshot("", "", emptyList(), 0, false, false, handled = false, predicting = false)

        /** `composesText`: what [im] types is the text itself, shown in the app. */
        fun composesText(im: String) = InputMethodCatalog.listed(im)?.composesText == true
    }
}
