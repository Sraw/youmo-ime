/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.core.CoreLog
import org.fcitx.fcitx5.android.core.FormattedText
import org.fcitx.fcitx5.android.core.TextFormatFlag
import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.host.Engines
import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.host.KeyboardPair
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot

/**
 * The engine in the key pipeline, the native `androidengine` addon ported
 * (`app/src/main/cpp/androidengine/androidengine.cpp` at the tag `native-last`): which key becomes which [EngineEvent] for
 * the input method's session ([keyEvent], `:550-687`), what the snapshot back shows ([send],
 * `:689-739`), the punctuation marks of a Chinese input method ([EnginePunctuation], `:515-548`),
 * the refine after a pause ([Refiner], `:706-721`), and what activating, resetting and leaving an
 * input method sends (`:492-513,741-772`).
 *
 * The sessions answer through [host]; one that throws is handled as `native-lib`'s
 * `engineEventCallback` did (`native-lib.cpp:722-745`): the key goes to the app, the panel is
 * cleared, the session reset unless it never answered, and one whose reset fails too is not reset
 * again till it answers.
 */
class KeyRouter(
    private val host: EngineHost,
    private val services: HostServices,
    private val clock: HostClock,
    private val strings: HostStrings = HostStrings.ENGLISH,
    /** The punctuation module, when it is plugged in. */
    var punctuation: EnginePunctuation? = null,
) : KeyEngine {

    /** The engine options the router reads (`AndroidEngineConfig`): set when they are read or changed. */
    data class Options(val pageSize: Int = DEFAULT_PAGE_SIZE, val englishSuggest: Boolean = true)

    var options = Options()

    internal val refiner = Refiner(clock)

    // English where it suggests nothing: the last key was a glide the session took, its word held;
    // cleared by any other key, a reset (the cursor moved, focus lost) and activate (`androidengine.h:302-304`)
    private var glidedAsIs = false

    // input methods whose session has answered: one that never did has nothing to drop (`native-lib.cpp:728`)
    private val answered = HashSet<String>()

    // failures by call, input method, event and class, their trace at the first, a count at the 2nd, 4th, 8th…
    private val failures = HashMap<String, Long>()

    /** `AndroidEngine::activate` (`:492-513`): where what is typed shows, then the session afresh (its data loaded now). */
    fun activate(ic: InputContext, im: String) {
        ic.preeditEnabled = composesText(im)
        glidedAsIs = false
        send(ic, im, EngineEvent.RESET)
    }

    /** `AndroidEngine::reset` (`:758-762`). */
    fun reset(ic: InputContext, im: String) {
        // a glide's word held belongs to the field and cursor it was glided at
        glidedAsIs = false
        send(ic, im, EngineEvent.RESET)
    }

    /**
     * `AndroidEngine::deactivate` (`:764-772`): switching to another input method keeps what was
     * typed, as libime's pinyin does ([switching]); losing focus drops it.
     */
    fun deactivate(ic: InputContext, im: String, switching: Boolean) {
        if (switching) {
            endPunctuation(ic, take = true)
            send(ic, im, EngineEvent.ENTER)
        }
        reset(ic, im)
    }

    // androidengine's branches one for one, in its order: kept so, to be read against it
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
    override fun keyEvent(ic: InputContext, e: KeyEvent) {
        if (e.release) return
        val key = e.key
        if (key.isModifier) return
        // any key ends a pause, shortcuts too: a refine landing after one would redraw a panel
        // someone else may have put up since
        refiner.cancel()
        // shortcuts are the app's: a mark on offer goes in first, as before any other key, so that
        // what the app does (a paste) comes after it
        if (key.states and SHORTCUT != 0) {
            endPunctuation(ic, take = true)
            // one may start quick phrase's or Unicode's (Ctrl+Shift+U) code, shown above the
            // candidates in English too; the next event sent sets the flag back. Not with English's
            // word in the app: the flag off, it would stay there as composing text, never updated
            if (ic.panel.clientPreedit.isEmpty()) ic.preeditEnabled = false
            return
        }
        // a punctuation key's marks on offer: Backspace takes the key back, any other key takes the
        // first and goes on as typed, but for Space and Return below
        if (key.sym == KeySyms.BACKSPACE && endPunctuation(ic, take = false)) {
            e.filterAndAccept()
            return
        }
        // Space and Return pick the mark shown, as libime's did, and type nothing more
        val picks = key.sym == KeySyms.SPACE || key.sym == KeySyms.RETURN || key.sym == KeySyms.KP_ENTER
        if (picks && endPunctuation(ic, take = true)) {
            e.filterAndAccept()
            return
        }
        endPunctuation(ic, take = true)
        val im = services.inputMethod(ic)
        // a grouped keyboard's code (NineKeyLetters.ofCode, and the rest of their blocks), a tap's with its place
        // (Keyboard.TAPS, the 14 keys' alone yet) too: never let through, the app would type it. The input method
        // whose keyboard it is reads it; any other (a switch the keyboard shown has not caught up with) has it
        // swallowed, never read as one of its own groups
        val cp = KeySyms.toUnicode(key.sym)
        val code = cp in NineKeyLetters.CODES || cp in Keyboard.TAPS
        val reads = if (cp in Keyboard.TAPS) im == Engines.PINYIN else Engines.readsCode(im, cp)
        if (code && !reads) {
            e.filterAndAccept()
            return
        }
        val chinese = Engines.listed(im)?.chinese == true
        if (!chinese && !suggestsEnglish(ic, e.virtual)) {
            asIs(ic, im, e)
            return
        }
        // a hardware keyboard's - and = turn the pages while something is typed, as on a PC; with
        // nothing typed, and from the screen's keys (f's and g's swipe), they are the marks they are
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
        val sent = if (what == EngineEvent.CHAR && !e.virtual && im == Engines.SHUANGPIN && !KeyboardPair.isLetter(arg)) EngineEvent.HARD_CHAR else what
        // English's punctuation is the key's own, not a Chinese mark
        // pinyin failing to take a code (its session not made): swallowed all the same
        if (send(ic, im, sent, arg) || code || (what == EngineEvent.CHAR && chinese && pushPunctuation(ic, key))) {
            e.filterAndAccept()
        }
    }

    // `:652-673`: the session's event for a key, and its argument
    private fun eventOf(key: Key): Pair<Int, Int> = when (key.sym) {
        KeySyms.BACKSPACE -> EngineEvent.BACKSPACE to 0
        KeySyms.RETURN, KeySyms.KP_ENTER -> EngineEvent.ENTER to 0
        KeySyms.ESCAPE -> EngineEvent.ESCAPE to 0
        KeySyms.PAGE_UP -> EngineEvent.PAGE_UP to 0
        KeySyms.PAGE_DOWN -> EngineEvent.PAGE_DOWN to 0
        else -> {
            val unicode = KeySyms.toUnicode(key.sym)
            // a control character (Tab, a line feed, Delete) is a key like an arrow: the session
            // keeps it while composing, the app gets it otherwise; a keypad's is told apart, the
            // nine keys read 2 as abc, which KP_2 is not
            if (unicode >= SPACE && unicode != DEL) {
                (if (key.isKeyPad) EngineEvent.KEYPAD else EngineEvent.CHAR) to unicode
            } else {
                EngineEvent.OTHER to key.sym
            }
        }
    }

    /**
     * English where it suggests nothing (`:583-638`): the keys type themselves, what was typed
     * going in first, as typed (Enter commits it, and has the session forget the space after a word).
     */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
    private fun asIs(ic: InputContext, im: String, e: KeyEvent) {
        val key = e.key
        val unicode = KeySyms.toUnicode(key.sym)
        // a glide's key goes to the session as where it suggests, which commits the word glided;
        // swallowed whatever it does, as let through the app would type its private-use character.
        // No Enter first: it would have the session forget the char before the cursor
        if (unicode == GLIDE_CHAR) {
            glidedAsIs = send(ic, im, EngineEvent.CHAR, GLIDE_CHAR)
            e.filterAndAccept()
            return
        }
        // a nine key's: never let through, the app would type its private-use character. A letter
        // a long press pinned is the one the user chose: typed, in its case; a key is no letter yet
        if (unicode in NINE_KEYS_FIRST until NINE_KEYS_END) {
            if (unicode >= NINE_LETTERS_FIRST) {
                val n = unicode - NINE_LETTERS_FIRST
                services.commit(ic, ((if (n >= LETTERS) 'A' else 'a') + n % LETTERS).toString())
            }
            e.filterAndAccept()
            return
        }
        // the key right after a glide: Backspace and any other char are the session's, as where it
        // suggests (the word taken back, the space moved behind the mark); an ASCII letter types itself
        val afterGlide = glidedAsIs.also { glidedAsIs = false }
        val letter = unicode in 'a'.code..'z'.code || unicode in 'A'.code..'Z'.code
        val mark = unicode >= SPACE && unicode != DEL && !letter
        val erases = key.sym == KeySyms.BACKSPACE
        if (!afterGlide || (!erases && !mark)) {
            // the char the screen's key has the app type, or take back (Backspace, as '\b'), for the
            // session to know what is before the cursor; none from a password or sensitive field,
            // nor from a hardware key
            val told = (mark || letter || erases) && e.virtual && !ic.has(Capability.PASSWORD_OR_SENSITIVE)
            if (told) {
                send(ic, im, EngineEvent.TYPED, if (erases) ERASED else unicode)
            } else {
                send(ic, im, EngineEvent.ENTER)
            }
            return
        }
        if (send(ic, im, if (erases) EngineEvent.BACKSPACE else EngineEvent.CHAR, unicode)) e.filterAndAccept()
        // the words that may follow are not offered here: put away through the session, so that its
        // keyboard knows the panel empty and takes the app's next read of the text
        if (ic.panel.candidateList is EngineCandidateList) send(ic, im, EngineEvent.TYPED, 0)
    }

    /** `AndroidEngine::suggestsEnglish` (`:681-687`). */
    private fun suggestsEnglish(ic: InputContext, virtual: Boolean) =
        options.englishSuggest && virtual && !ic.has(Capability.PASSWORD or Capability.NO_SPELL_CHECK or Capability.URL or Capability.EMAIL)

    /**
     * `AndroidEngine::invokeActionImpl` (`:741-756`): a tap in the composed text puts the session's
     * cursor there; one it does not take, on the code above the candidates, does nothing (fcitx's
     * own would commit the app's composing text, there none, then reset, the code lost). In the app
     * (handwriting's) it is left unfiltered for fcitx's default, which [HostCore.moveCursor] runs:
     * the composing text committed as shown, then reset.
     */
    override fun invokeAction(ic: InputContext, e: InvokeActionEvent) {
        if (e.action != InvokeActionEvent.Action.LeftClick || e.cursor < 0) return
        val taken = send(ic, services.inputMethod(ic), EngineEvent.CURSOR, e.cursor)
        // read after send, which sets it
        if (taken || !ic.has(Capability.PREEDIT)) e.filter()
    }

    /** `AndroidEngine::send` (`:689-704`): [event] to [im]'s session and what comes back shown; whether it took it. */
    fun send(ic: InputContext, im: String, event: Int, arg: Int = 0): Boolean {
        refiner.cancel()
        // where what is typed shows is the input method's, whatever turned it since activate
        ic.preeditEnabled = composesText(im)
        val learning = !ic.has(Capability.PASSWORD_OR_SENSITIVE)
        val s = call(im, event, arg, learning)
        // a key may be on its way: the pause starts a little after it, the next slice at once
        if (s.refines) refineLater(ic, im, if (event == EngineEvent.REFINE) 0 else Refiner.PAUSE_MILLIS)
        // a slice of refining that changed nothing leaves the panel as it is, the list scrolled;
        // as does a tap no session takes
        if ((event != EngineEvent.REFINE && event != EngineEvent.CURSOR) || s.handled) show(ic, im, s)
        return s.handled
    }

    @Suppress("TooGenericExceptionCaught")
    private fun call(im: String, event: Int, arg: Int, learning: Boolean): Snapshot {
        val s = try {
            host.event(im, event, arg, learning)
        } catch (e: Throwable) {
            failed("onEvent", im, event, e)
            // the panel is cleared for it (but for a slice of refining, not shown): so must the
            // session be, or what it had comes back with the next key; one that fails to reset too
            // is not sent another till it answers
            if (event != EngineEvent.RESET && event != EngineEvent.REFINE && im in answered) {
                try {
                    host.event(im, EngineEvent.RESET, 0, learning)
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

    /** New engine options for the sessions, flattened (`AndroidEngine::pushSettings`, `:472-490`). */
    @Suppress("TooGenericExceptionCaught")
    fun settings(flat: String) = try {
        host.settings(flat)
    } catch (e: Throwable) {
        failed("configure", "", -1, e)
    }

    // `engineFailed` (`native-lib.cpp:506-528`), which took any pending throwable, out of memory
    // too: the class, never a message or an event's argument
    // (the log is exported, and an argument is what the user typed)
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

    private fun refineLater(ic: InputContext, im: String, delay: Long) = refiner.later(delay) {
        // still ours to show: the same input method, and our list on the panel, not a quick
        // phrase one put up meanwhile
        if (ic.hasFocus && services.inputMethod(ic) == im && ic.panel.candidateList is EngineCandidateList) {
            send(ic, im, EngineEvent.REFINE)
        }
    }

    /** Stops what refining is left, till the next event: a candidate pressed is acted on by its index (`androidengine.h:242-246`). */
    fun stopRefining() = refiner.cancel()

    /** `AndroidEngine::show` (`:723-739`): what [s], [im]'s, commits, its preedit and candidates. */
    @Suppress("ComplexCondition")
    private fun show(ic: InputContext, im: String, s: Snapshot) {
        val panel = ic.panel
        // a key passed on with nothing on show before or after it: no panel to reset
        if (!s.handled && s.deleteBefore <= 0 && s.commit.isEmpty() && s.preedit.isEmpty() &&
            s.candidates.isEmpty() && panel.empty
        ) {
            return
        }
        // English's space after a word, put behind the punctuation typed next
        if (s.deleteBefore > 0) services.deleteSurrounding(ic, s.deleteBefore, 0)
        if (s.commit.isNotEmpty()) services.commit(ic, s.commit)
        panel.reset()
        if (s.preedit.isNotEmpty()) showPreedit(ic, s.preedit, s.cursor)
        if (s.candidates.isNotEmpty()) panel.candidateList = EngineCandidateList(ic, im, s)
        services.updatePreedit(ic)
        services.updatePanel(ic)
    }

    /**
     * `showPreedit` (`:241-258`): [text] as what is typed, the cursor [cursor] into it (UTF-16 units,
     * as the session counts; -1 or past its end, at its end): in the app, underlined, where it may
     * go ([composesText]); else above the candidates.
     */
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

    /** `commitPunctuation` (`:266-280`): a key's [text], and [after] it the other of a pair, the cursor between. */
    private fun commitPunctuation(ic: InputContext, text: String, after: String) {
        val length = text.codePointCount(0, text.length)
        if (after.isNotEmpty() && ic.has(Capability.COMMIT_STRING_WITH_CURSOR) && length != 0) {
            services.commit(ic, text + after, length)
        } else {
            services.commit(ic, text + after)
            repeat(after.codePointCount(0, after.length)) { services.forwardKey(ic, Key(KeySyms.LEFT)) }
        }
    }

    /**
     * `AndroidEngine::pushPunctuation` (`:515-548`): the mark a Chinese input method types for a
     * key its session passed on; a key with several offers them all, the first going in unless
     * another is picked ([endPunctuation]). Whether it typed one.
     */
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
        // a half typed alone has opened or closed the pair, which another card picked or Backspace
        // must not do: each push turns it over, so a second turns it back and the first entry
        // pushes again as it goes in
        val turns = pair && after.isEmpty()
        if (turns) punctuation.push(ic, unicode)
        val list = PunctuationCandidateList()
        list.pageSize = options.pageSize
        list.append(PunctuationWord(text, after, text + after == typed, if (turns) unicode else 0))
        // that entry is the whole first card: the marks list a pair's two halves apart
        for (i in (if (pair) 2 else 1) until marks.size) list.append(PunctuationWord(marks[i], "", marks[i] == typed))
        list.globalCursorIndex = 0
        ic.panel.reset()
        showPreedit(ic, text + after)
        ic.panel.candidateList = list
        services.updatePreedit(ic)
        services.updatePanel(ic)
        return true
    }

    /**
     * `endPunctuation` (`:313-328`): ends the marks on offer, if the panel shows them: [take]
     * commits the first, what the key typed alone, else none goes in. Whether there were.
     */
    private fun endPunctuation(ic: InputContext, take: Boolean): Boolean {
        val marks = ic.panel.candidateList as? PunctuationCandidateList ?: return false
        if (take) marks.candidateFromAll(0)?.select(ic) else clearPanel(ic)
        return true
    }

    // a punctuation key's marks on the panel, till one goes in (`:310-311`)
    private class PunctuationCandidateList : CommonCandidateList()

    /** One of the marks a punctuation key with several offers (`:282-308`): picked, it goes in as the key types it. */
    private inner class PunctuationWord(
        private val mark: String,
        private val after: String,
        half: Boolean,
        // the key pushed again as it goes in, on the first entry of a pair typed a half at a time; else 0
        private val key: Int = 0,
    ) : CandidateWord {
        override val text = mark + after
        override val comment = if (half) strings.half else ""

        override fun select(ic: InputContext) {
            if (key != 0) punctuation?.push(ic, key)
            commitPunctuation(ic, mark, after)
            // last: the list that holds this word goes with the panel
            clearPanel(ic)
        }
    }

    /** A session's candidate: picking it sends its index among all of them (`EngineCandidateWord`, `:39-56`). */
    internal inner class EngineWord(private val im: String, val index: Int, override val text: String, override val comment: String) :
        CandidateWord {
        override fun select(ic: InputContext) {
            send(ic, im, EngineEvent.PICK, index)
        }
    }

    /**
     * `EngineCandidateList` (`:75-239`): all the candidates of a snapshot, as a list that scrolls:
     * those it came with, from the page's first on, then the rest fetched from the session as the
     * keyboard asks for them, those after a chunk at a time and those before the page at once. A new
     * snapshot replaces the list, so what is fetched is always of the input shown. As a page it is
     * the one the session shows: candidate(i) is the (first + i)th of all.
     *
     * A long press offers what the session says it may do with the candidate; asked on the press,
     * not sent with each snapshot. The menu's choice is checked again when it is made: only what the
     * session still offers, and only on the candidate this list offered it for.
     */
    internal inner class EngineCandidateList(private val ic: InputContext, private val im: String, s: Snapshot) : CandidateList {
        private var total = s.total
        private val actionable = s.actionable

        // what was typed, not a prediction (whose preedit is empty)
        private val composing = s.preedit.isNotEmpty()

        // the index among all of words[0], the page's first
        private val offset = maxOf(s.first, 0)
        private val words = s.candidates.mapIndexedTo(ArrayList()) { i, text -> EngineWord(im, offset + i, text, s.hints.getOrElse(i) { "" }) }
        override val size = words.size

        // as the keys that pick them: 1 to 9, then 0
        private val labels = List(size) { i -> s.labels.getOrNull(i)?.toString() ?: ((i + 1) % LABEL_KEYS).toString() }
        private var before: List<EngineWord>? = null

        // the candidate whose actions were last asked of this list
        private var asked: EngineWord? = null

        override fun label(index: Int) = labels.getOrElse(index) { "" }
        override fun candidate(index: Int): CandidateWord? = if (index in 0 until size) words[index] else null

        // what space commits while composing: the first of the page
        override val cursorIndex get() = if (composing) 0 else -1

        override val bulk get() = true
        override val totalSize get() = total

        override fun candidateFromAll(index: Int): CandidateWord? {
            // a total of -1: the session has not counted them, so ask until it runs out
            if (index < 0 || (total >= 0 && index >= total)) return null
            if (index < offset) {
                // asked once, whatever came back: a fetch that failed is not made again for each index
                val earlier = before ?: fetch(im, 0, offset).mapIndexed { i, c -> EngineWord(im, i, c.text, c.hint) }.also { before = it }
                return earlier.getOrNull(index)
            }
            while (offset + words.size <= index) {
                val from = offset + words.size
                val count = maxOf(CHUNK, index + 1 - from)
                val more = fetch(im, from, count)
                if (more.size < count) total = from + more.size
                more.forEach { words += EngineWord(im, offset + words.size, it.text, it.hint) }
                // a short chunk was the last: none past it to ask for
                if (total >= 0 && index >= total) return null
            }
            return words[index - offset]
        }

        // pages turn only while composing, as PageUp and PageDown do: a prediction they drop
        override val pageable get() = composing
        override val hasPrev get() = offset > 0
        override val hasNext: Boolean
            get() {
                val after = offset + size
                return if (total >= 0) after < total else candidateFromAll(after) != null
            }

        // as the keys turn it; the snapshot that comes back replaces this list
        override fun prev() {
            send(ic, im, EngineEvent.PAGE_UP)
        }

        override fun next() {
            send(ic, im, EngineEvent.PAGE_DOWN)
        }

        override fun actions(word: CandidateWord): List<CandidateAction> {
            if (!actionable || word !is EngineWord) return emptyList()
            asked = word
            val offered = offers(im, word.index)
            val actions = OFFER_ACTIONS.filter { it.first in offered }.map { (offer, event) -> CandidateAction(event, strings.offer(offer)) }
            // a menu shown holds the list still, its choice acted on by index; none, refining goes on
            if (actions.isNotEmpty()) stopRefining()
            return actions
        }

        @Suppress("ComplexCondition")
        override fun triggerAction(word: CandidateWord, id: Int) {
            val offer = OFFER_ACTIONS.filter { it.second == id }.map { it.first }.toSet()
            // asked again: only what the session still offers for the candidate at that index, and
            // only on the one this list offered it for: a menu of a list since replaced names another
            if (!actionable || word !is EngineWord || word !== asked || offer.isEmpty() || offers(im, word.index).none { it in offer }) return
            // the snapshot that comes back replaces this list: nothing of it is touched after
            send(ic, im, id, word.index)
        }
    }

    companion object {
        /** `AndroidEngineConfig::pageSize`'s default (`androidengine.h:96-97`). */
        const val DEFAULT_PAGE_SIZE = 7

        // `EngineCandidateList::Chunk` (`:214`)
        internal const val CHUNK = 32

        private const val LABEL_KEYS = 10
        private const val SPACE = 0x20
        private const val DEL = 0x7f
        private const val LETTERS = 26

        // the char Session reads as Backspace for TYPED (`'\b'`)
        private const val ERASED = 8

        // a shortcut is the app's: Ctrl, Alt, Super or Hyper (`:559`)
        private const val SHORTCUT = KeyStates.CTRL or KeyStates.ALT or KeyStates.SUPER or KeyStates.HYPER

        /** The character the keyboard sends after a glide (`Keyboard.GLIDE`, `:30-31`): never text. */
        const val GLIDE_CHAR = 0xF0101

        // what 九键英文 sends where English spells words, its keys and the letters a long press pins (`:33-37`)
        private const val NINE_KEYS_FIRST = 0xF0040
        private const val NINE_LETTERS_FIRST = 0xF0060
        private const val NINE_KEYS_END = NINE_LETTERS_FIRST + 2 * LETTERS

        /** What a long press may offer, in the menu's order, with the event it sends (`offerActions`, `:65-73`). */
        internal val OFFER_ACTIONS = listOf(
            Offer.FORGET to EngineEvent.FORGET,
            Offer.PIN to EngineEvent.PIN,
            Offer.UNPIN to EngineEvent.UNPIN,
            Offer.BLOCK to EngineEvent.BLOCK,
            // a custom phrase (a built-in date too) goes by the unpin's event, as in v0.3.0 (UAT PY-49)
            Offer.DELETE_PHRASE to EngineEvent.UNPIN,
        )

        // what a session that cannot load, or failed, answers: the key is not taken, nothing shown
        private val UNHANDLED = Snapshot("", "", emptyList(), 0, false, false, handled = false, predicting = false)

        /** `composesText` (`:375-383`): what [im] has typed is the text itself, shown in the app. */
        fun composesText(im: String) = Engines.listed(im)?.composesText == true
    }
}
