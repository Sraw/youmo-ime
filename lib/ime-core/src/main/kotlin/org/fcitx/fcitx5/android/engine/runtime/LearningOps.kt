/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnRecord
import org.fcitx.fcitx5.android.engine.learn.control.BackupOp
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.LearningControl
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.learn.control.LexiconView
import org.fcitx.fcitx5.android.engine.learn.control.ListedItem
import org.fcitx.fcitx5.android.engine.learn.control.MenuItem
import org.fcitx.fcitx5.android.engine.learn.control.WordKind
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import java.io.IOException

/**
 * [Engines.learning]: the user's controls over learning (PLAN §2.8, impl/streams/E-design.md),
 * on the sessions of [session] and the words of [lexicon], the record of [log], acts made
 * undoable by [control]. An act on a candidate goes the way a long press's always went, as the
 * host's event ([act]: the addon's, which shows the list it leaves), recorded so the chip can take
 * it back.
 */
internal class LearningOps(
    // the session an input method types with now: a pair's keyed one while it types (KeyboardPair.sessionOf),
    // so a long press forgets, blocks or pins what that session showed (design M4)
    private val session: (String) -> Session?,
    private val lexicon: UserLexicon,
    private val log: () -> LearnLog,
    private val control: LearningControl,
    private val habits: () -> Boolean,
    private val useHabits: (Boolean) -> Unit,
    private val dropSessions: () -> Unit,
    private val first: (String) -> Int,
    private val tableForgets: () -> Int,
    private val backups: Backups = Backups.NONE,
    private val backup: (BackupOp) -> LearningResult = { LearningResult.Unavailable },
    private val restoreCorrection: (List<String>) -> Unit = {},
    private val resetTableOrder: (String) -> Unit = {},
) {
    /**
     * What the backups (stream D) do around a clear (review-DE-design DE-M1, C25): [invalidate] those
     * saved at or after a minute ([LearningResult.ALL]: every one of this install's) before the
     * rewrite, [saveIfOn] a fresh one after it.
     */
    interface Backups {
        fun invalidate(fromMinute: Int)

        fun saveIfOn()

        companion object {
            /** No backups kept: an engine of a test's, or of the eval tools. */
            val NONE = object : Backups {
                override fun invalidate(fromMinute: Int) {}

                override fun saveIfOn() {}
            }
        }
    }

    /**
     * Does [op] in [im], the input method in use (null where none is: the settings); [act] has the
     * host send the long press's event (an [EngineEvent]) for the candidate at an index.
     */
    // [shown]: a candidate's text as the host shows it (繁體: chttrans's output filter), the menu's text that
    fun apply(im: String?, op: LearningOp, shown: (String) -> String = { it }, act: (event: Int, index: Int) -> Boolean): LearningResult = when (op) {
        // the backups' own (stream D): listed, restored, made
        is BackupOp -> backup(op)
        is LearningOp.OnCandidate -> im?.let { onCandidate(it, op, shown, act) } ?: LearningResult.Unavailable
        is LearningOp.OnPage -> im?.let { onCandidate(it, op.op.at(first(it) + op.op.index), shown, act) } ?: LearningResult.Unavailable
        is LearningOp.Undo -> undo(im, op.token)
        is LearningOp.UseHabits -> {
            useHabits(op.on)
            LearningResult.Done()
        }
        is LearningOp.ClearRecent -> clearRecent(op)
        is LearningOp.ClearLearned -> clearLearned(op)
        LearningOp.Overview -> overview()
        is LearningOp.ListItems -> LearningResult.Listed(listed(op.what))
        is LearningOp.RemoveItems -> {
            remove(op.items)
            LearningResult.Done()
        }
        is LearningOp.ResetTableOrder -> {
            resetTableOrder(op.im)
            dropSessions()
            LearningResult.Done()
        }
        is LearningOp.RemoveWords -> removeWords(op.words)
        is LearningOp.RestoreCorrection -> LearningResult.Done().also { restoreCorrection(op.texts) }
    }

    // U-88, U-86: the backups that hold what goes forgotten before the rewrite, a fresh one after
    // (C25); failed, a fresh one all the same, as those were gone already (review-E-engine S1)
    // what the tables learn is in the record (stream C), their order kept but by 全部清空, as their
    // per-code blocks; the phrases an older build saved for them, the record's too, go with 全部清空
    private fun clearRecent(op: LearningOp.ClearRecent): LearningResult = cleared {
        control.clearRecent(op.span, backups::invalidate)?.let { LearningResult.Cleared(it) }
    }

    private fun clearLearned(op: LearningOp.ClearLearned): LearningResult = cleared {
        if (control.clearLearned(op.all, backups::invalidate)) {
            // the lists only once the record is cleared: a clear that failed forgets nothing (verify-int-E)
            if (op.all) {
                lexicon.clearLists()
            }
            LearningResult.Cleared(LearningResult.ALL)
        } else {
            null
        }
    }

    private fun cleared(clear: () -> LearningResult.Cleared?): LearningResult {
        val result = try {
            clear() ?: return LearningResult.Unavailable
        } catch (e: IOException) {
            control.onError(e)
            LearningResult.Failed
        }
        dropSessions()
        backups.saveIfOn()
        return result
    }

    private fun removeWords(words: List<UserWord>): LearningResult {
        fun entries(kind: UserWord.Kind) = words.filter { it.kind == kind }.mapNotNull(lexicon::entryOf)
        val added = lexicon.addedPlaces(entries(UserWord.Kind.ADDED))
        val blocked = entries(UserWord.Kind.BLOCKED)
        // English's, no reading to them (review M1): those blocked now blocked again on an undo
        val english = lexicon.englishBlocks
        val englishBlocked = words.filter { it.kind == UserWord.Kind.BLOCKED && it.pinyin.isEmpty() && english.has(it.text) }.map { it.text }
        val forgets = tableForgets()
        val token = control.undoable(inverse = {
            lexicon.reAdd(added)
            lexicon.reBlock(blocked)
            english.block(englishBlocked)
            dropSessions()
        }) { lexicon.removeWords(words) }
        // what the tables' logs forgot of them stays forgotten: no undo rather than half of one. A table not
        // loaded this run has each row of the word forgotten (TableRegistry.drop), its counts 0.4 made too,
        // so a word an unopened table had gives none either: accepted, as the table not loaded cannot tell
        return LearningResult.Done(if (tableForgets() != forgets) LearningResult.NO_UNDO else token)
    }

    // a session reads the record as it is now when told to show anew: not made again, what is typed
    // or written kept (DE-S4, review-int S1); one that cannot (Session.rereads false: the strokes'
    // lookup) is (review-E-engine S5)
    private fun undo(im: String?, token: Long): LearningResult {
        if (!control.undo(token)) return LearningResult.Unavailable
        if (im != null && session(im)?.rereads == false) dropSessions()
        return LearningResult.Done()
    }

    // [act] made undoable: its token, or null where the host made no act of it (the list it was
    // asked of gone by then; review-E-engine S4)
    private fun acted(inverse: () -> Unit = {}, act: () -> Boolean): Long? {
        var did = false
        val token = control.undoable(inverse) { did = act() }
        return token.takeIf { did }
    }

    private fun onCandidate(im: String, asShown: LearningOp.OnCandidate, shown: (String) -> String, act: (Int, Int) -> Boolean): LearningResult {
        val s = session(im) ?: return LearningResult.Unavailable
        val op = engines(s, asShown, shown) ?: return LearningResult.Unavailable
        val offers = s.offers(op.index)
        val event = { offer: Offer, e: Int ->
            acted { offer in offers && act(e, op.index) }?.let { LearningResult.Done(it) } ?: LearningResult.Unavailable
        }
        return when (op) {
            // the engine's text, which the menu's acts come back with (engines)
            is LearningOp.Menu -> menu(im, s, op)
            is LearningOp.Delete -> if (Offer.FORGET in offers) {
                val also = alike(s, op.text, shown)
                delete(op.text, also, s.why(op.index)?.whole.orEmpty()) { act(EngineEvent.FORGET, op.index).also { if (it) actOn(im, also, Offer.FORGET, EngineEvent.FORGET, act) } }
            } else {
                LearningResult.Unavailable
            }
            is LearningOp.Block -> if (Offer.BLOCK in offers) {
                val also = alike(s, op.text, shown)
                block(listOf(op.text) + also) { act(EngineEvent.BLOCK, op.index).also { if (it) actOn(im, also, Offer.BLOCK, EngineEvent.BLOCK, act) } }
            } else {
                LearningResult.Unavailable
            }
            is LearningOp.Pin -> event(Offer.PIN, EngineEvent.PIN)
            // a phrase's file holds it, not the learning record: no undo
            is LearningOp.Unpin -> if (Offer.DELETE_PHRASE in offers) {
                if (act(EngineEvent.UNPIN, op.index)) LearningResult.Done(LearningResult.NO_UNDO) else LearningResult.Unavailable
            } else {
                event(Offer.UNPIN, EngineEvent.UNPIN)
            }
            is LearningOp.NoPredict -> {
                val prev = prediction(s, op.index) ?: return LearningResult.Unavailable
                // a whole phrase off everywhere, filed after no word (U-73)
                val whole = s.why(op.index)?.whole.orEmpty()
                LearningResult.Done(control.undoable { if (whole.isEmpty()) log().noPredict(prev, op.text, on = true) else log().noPredict("", whole, on = true) })
            }
            is LearningOp.Report -> LearningResult.Text(report(im, s))
        }
    }

    // [asShown] as the engine has it, or null where the list changed since the menu showed (another
    // candidate at the index now). The menu and the report are asked with the text as shown (國家 for
    // 国家); an act comes back with the menu's text, the engine's, and must be it: another candidate
    // shown alike (a dictionary's own 國家) is not the one the menu was for
    private fun engines(s: Session, asShown: LearningOp.OnCandidate, shown: (String) -> String): LearningOp.OnCandidate? {
        val text = s.candidates(asShown.index, 1).firstOrNull()?.text ?: return null
        val asked = asShown is LearningOp.Menu || asShown is LearningOp.Report
        return asShown.at(asShown.index, text).takeIf { text == asShown.text || asked && shown(text) == asShown.text }
    }

    // the other candidates of the list shown as [text] is where the host converts it (國家: 国家 and a
    // dictionary's own 國家): what is blocked or deleted is gone as shown, not left in its other
    // form (review R2). Their texts, the engine's
    private fun alike(s: Session, text: String, shown: (String) -> String): List<String> {
        val asShown = shown(text)
        if (asShown == text) return emptyList()
        return s.candidates(0, ALIKE_SCAN).map { it.text }.filter { it != text && shown(it) == asShown }.distinct()
    }

    // [event] on each of [texts] where it is in the list now, the list as the last act left it
    private fun actOn(im: String, texts: List<String>, offer: Offer, event: Int, act: (Int, Int) -> Boolean) {
        for (t in texts) {
            val s = session(im) ?: return
            val at = s.candidates(0, ALIKE_SCAN).indexOfFirst { it.text == t }
            if (at >= 0 && offer in s.offers(at)) act(event, at)
        }
    }

    // [whole]: the whole phrase (U-73) [text] is the rest of, "" for a word; [also]: the words shown alike, deleted with it
    private fun delete(text: String, also: List<String>, whole: String, act: () -> Boolean): LearningResult {
        // a word added by hand comes off the list with it (Engines.changed): put back if taken back.
        // Not for a whole phrase, which takes no word with it, the rest's nor its own (U-73)
        val added = if (whole.isEmpty()) lexicon.addedPlaces((listOf(text) + also).flatMap { lexicon.addedOf(it) }) else emptyList()
        val forgets = tableForgets()
        val token = acted(inverse = { lexicon.reAdd(added) }, act) ?: return LearningResult.Unavailable
        // what the tables' own logs forgot (a phrase they saved, theirs or shared with pinyin) no
        // rewrite of the learning record brings back: no chip, rather than one that half undoes;
        // what a table learned is in the record (stream C), and comes back. A table not loaded this run
        // forgets every row of the word (TableRegistry.drop): no chip for a word it had, as above
        return LearningResult.Done(if (tableForgets() != forgets) LearningResult.NO_UNDO else token)
    }

    private fun block(texts: List<String>, act: () -> Boolean): LearningResult {
        val before = texts.flatMap { lexicon.blockedOf(it) }.toSet()
        // English's words have a list of their own (no reading)
        val english = lexicon.englishBlocks
        // as written, which the shown text need not be (The for the; EnglishBlocks)
        val englishBefore = english.blocked.toSet()
        var blocked = emptyList<Entry>()
        var englishBlocked = emptyList<String>()
        val token = acted(inverse = {
            lexicon.unblock(blocked)
            english.unblock(englishBlocked)
        }) {
            act().also {
                blocked = texts.flatMap { lexicon.blockedOf(it) }.filter { it !in before }
                englishBlocked = english.blocked.filter { it !in englishBefore }
            }
        } ?: return LearningResult.Unavailable
        return LearningResult.Done(token)
    }

    // the word before a prediction at [index], where it is one and turning it off is kept
    private fun prediction(s: Session, index: Int): String? =
        s.why(index)?.code?.takeIf { it.kind == Kinds.PREDICTION && it.keys.isNotEmpty() && habits() }?.keys

    private fun menu(im: String, s: Session, op: LearningOp.Menu): LearningResult.Menu {
        val offers = s.offers(op.index)
        val why = s.why(op.index)
        val items = buildList {
            if (Offer.FORGET in offers) add(MenuItem.DELETE)
            if (Offer.BLOCK in offers) add(MenuItem.BLOCK)
            // handwriting has no code a pin stands under (U-82, C18)
            if (Offer.PIN in offers && im != InputMethodCatalog.HANDWRITING) add(MenuItem.PIN)
            if (Offer.UNPIN in offers) add(MenuItem.UNPIN)
            if (Offer.DELETE_PHRASE in offers) add(MenuItem.DELETE_PHRASE)
            if (prediction(s, op.index) != null) add(MenuItem.NO_PREDICT)
            // English ranks by its own rules, not the habits' tiers; where nothing may be done
            // (a field that learns nothing) there is no menu, nor for a phrase alone (UAT PY-49); nor where the
            // session cannot say (review-int-runtime M2)
            if (im != InputMethodCatalog.ENGLISH && any { it != MenuItem.DELETE_PHRASE } && why != null) add(MenuItem.WHY)
        }
        // a table blocks its candidate under the code typed alone (U-28, stream C; review-E-ui Q1);
        // what may follow, as pinyin's, everywhere
        // the session's where it says (review H3): a character by its strokes under them alone as a
        // table's (U-28), one a table looked up by pinyin everywhere, as pinyin blocks it
        val underCode = im in InputMethodCatalog.TABLES && why?.code?.kind != Kinds.PREDICTION
        val scope = s.blockScope(op.index) ?: if (underCode) BlockScope.UNDER_CODE else BlockScope.EVERYWHERE
        return LearningResult.Menu(op.text, kindOf(op.text), items, why, scope)
    }

    private fun kindOf(text: String) = when {
        lexicon.addedOf(text).isNotEmpty() -> WordKind.ADDED
        text in log().state.mine -> WordKind.MINE
        log().state.imports.placesOf(text).isNotEmpty() -> WordKind.IMPORTED
        else -> WordKind.DICTIONARY
    }

    /**
     * 复制给开发者 (U-81, D26): the candidates the user sees, each with its tier and uses, and the
     * keys typed; never the text before them, nor a prediction's word before (that is the text).
     */
    private fun report(im: String, s: Session): String = buildString {
        append("youmo learning report 1\n")
        append("im ").append(im).append('\n')
        val shown = s.candidates(0, REPORTED)
        val code = shown.indices.firstNotNullOfOrNull { s.why(it)?.code }
        if (code != null) append("code ").append(code.kind).append(if (code.kind == Kinds.PREDICTION) "" else " " + reported(code)).append('\n')
        shown.forEachIndexed { i, c ->
            val why = s.why(i)
            append(i + 1).append(' ').append(c.text).append(' ').append(why?.tier ?: "?").append(' ').append(why?.uses ?: 0).append('\n')
        }
    }

    // a grouped keyboard's codes as their keys' letters (bn ui): the report is pasted where they would read as nothing
    private fun reported(code: Code): String =
        Kinds.keysTag(code.kind)?.let(NineKeyLetters::grouped)?.shown(code.keys) ?: code.keys

    private fun overview(): LearningResult.Overview {
        val state = log().state
        val counts = lexicon.userWordCounts()
        return LearningResult.Overview(
            mine = state.mine.size,
            added = counts[UserWord.Kind.ADDED] ?: 0,
            pinned = state.pins.size,
            blocked = counts[UserWord.Kind.BLOCKED] ?: 0,
            noPredict = state.noPredict.size,
            habits = habits(),
        )
    }

    private fun listed(what: LearningOp.Listed): List<ListedItem> {
        val state = log().state
        return when (what) {
            LearningOp.Listed.PINS -> state.pins.entries.sortedWith(compareBy({ it.key.kind }, { it.key.keys })).map { (code, w) -> ListedItem(code, "", w.text, w.reading) }
            LearningOp.Listed.NO_PREDICT -> state.noPredict.sortedWith(compareBy({ it.first }, { it.second })).map { (prev, text) -> ListedItem(null, prev, text) }
            LearningOp.Listed.TABLE_BLOCKS -> state.tableBlocks.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
                .flatMap { (key, texts) -> texts.sorted().map { ListedItem(Code(Kinds.table(key.first), key.second), "", it, blocked = true) } }
        }
    }

    private fun remove(items: List<ListedItem>) {
        val log = log()
        for (item in items) {
            val code = item.code
            when {
                code == null -> log.noPredict(item.prev, item.text, on = false)
                item.blocked -> log.event { minute, _ -> LearnRecord.TableBlock(minute, Kinds.tableOf(code.kind), code.keys, item.text, on = false) }
                else -> log.pin(code, item.text, item.reading, on = false)
            }
        }
    }

    /**
     * [words] as the lexicon page lists them (U-80): each with the calendar day it was last used
     * ([UserWord.lastUsed]), the typing days since ([UserWord.idle]), and how recently it became
     * the user's ([UserWord.order]: a learned word's rank by the typing day it became the user's, then its seq, an added one's place in its list, the
     * latest last).
     */
    fun dated(words: List<UserWord>): List<UserWord> = LexiconView.dated(log().state, words)

    private companion object {
        // as many as a page or two show: enough to see where the word went
        const val REPORTED = 20

        // how far the list is looked through for a word shown alike: past any page the menu is on
        const val ALIKE_SCAN = 200
    }
}
