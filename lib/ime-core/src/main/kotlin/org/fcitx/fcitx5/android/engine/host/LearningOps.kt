/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.host.Engines.UserWord
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
    // 全部清空's of the tables' saved phrases, kept apart from the record
    private val clearTables: () -> Unit = {},
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
    fun apply(im: String?, op: LearningOp, act: (event: Int, index: Int) -> Boolean): LearningResult = when (op) {
        // the backups' own (stream D): listed, restored, made
        is BackupOp -> backup(op)
        is LearningOp.OnCandidate -> im?.let { onCandidate(it, op, act) } ?: LearningResult.Unavailable
        is LearningOp.OnPage -> im?.let { onCandidate(it, op.op.at(first(it) + op.op.index), act) } ?: LearningResult.Unavailable
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
    // per-code blocks; the phrases an older build saved for them, their own file's, go with 全部清空
    private fun clearRecent(op: LearningOp.ClearRecent): LearningResult = cleared {
        control.clearRecent(op.span, backups::invalidate)?.let { LearningResult.Cleared(it) }
    }

    private fun clearLearned(op: LearningOp.ClearLearned): LearningResult = cleared {
        if (control.clearLearned(op.all, backups::invalidate)) {
            // the tables' older phrases only once the record is cleared: a clear that failed forgets
            // nothing (verify-int-E; a kill between leaves them shown, review-int-records N4 accepted)
            if (op.all) {
                lexicon.clearLists()
                clearTables()
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
        val forgets = tableForgets()
        val token = control.undoable(inverse = {
            lexicon.reAdd(added)
            lexicon.reBlock(blocked)
            dropSessions()
        }) { lexicon.removeWords(words) }
        // what the tables' logs forgot of them stays forgotten: no undo rather than half of one
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

    private fun onCandidate(im: String, op: LearningOp.OnCandidate, act: (Int, Int) -> Boolean): LearningResult {
        val s = session(im) ?: return LearningResult.Unavailable
        // the list changed since the menu showed: another candidate is at the index now
        if (s.candidates(op.index, 1).firstOrNull()?.text != op.text) return LearningResult.Unavailable
        val offers = s.offers(op.index)
        val event = { offer: Offer, e: Int ->
            acted { offer in offers && act(e, op.index) }?.let { LearningResult.Done(it) } ?: LearningResult.Unavailable
        }
        return when (op) {
            is LearningOp.Menu -> menu(im, s, op)
            is LearningOp.Delete -> if (Offer.FORGET in offers) delete(op) { act(EngineEvent.FORGET, op.index) } else LearningResult.Unavailable
            is LearningOp.Block -> if (Offer.BLOCK in offers) block(op) { act(EngineEvent.BLOCK, op.index) } else LearningResult.Unavailable
            is LearningOp.Pin -> event(Offer.PIN, EngineEvent.PIN)
            // a phrase's file holds it, not the learning record: no undo
            is LearningOp.Unpin -> if (Offer.DELETE_PHRASE in offers) {
                if (act(EngineEvent.UNPIN, op.index)) LearningResult.Done(LearningResult.NO_UNDO) else LearningResult.Unavailable
            } else {
                event(Offer.UNPIN, EngineEvent.UNPIN)
            }
            is LearningOp.NoPredict -> {
                val prev = prediction(s, op.index) ?: return LearningResult.Unavailable
                LearningResult.Done(control.undoable { log().noPredict(prev, op.text, on = true) })
            }
            is LearningOp.Report -> LearningResult.Text(report(im, s))
        }
    }

    private fun delete(op: LearningOp.Delete, act: () -> Boolean): LearningResult {
        // a word added by hand comes off the list with it (Engines.changed): put back if taken back
        val added = lexicon.addedPlaces(lexicon.addedOf(op.text))
        val forgets = tableForgets()
        val token = acted(inverse = { lexicon.reAdd(added) }, act) ?: return LearningResult.Unavailable
        // what the tables' own logs forgot (a phrase they saved, theirs or shared with pinyin) no
        // rewrite of the learning record brings back: no chip, rather than one that half undoes;
        // what a table learned is in the record (stream C), and comes back
        return LearningResult.Done(if (tableForgets() != forgets) LearningResult.NO_UNDO else token)
    }

    private fun block(op: LearningOp.Block, act: () -> Boolean): LearningResult {
        val before = lexicon.blockedOf(op.text).toSet()
        var blocked = emptyList<Entry>()
        val token = acted(inverse = { lexicon.unblock(blocked) }) {
            act().also { blocked = lexicon.blockedOf(op.text).filter { it !in before } }
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
            if (Offer.PIN in offers && im != Engines.HANDWRITING) add(MenuItem.PIN)
            if (Offer.UNPIN in offers) add(MenuItem.UNPIN)
            if (Offer.DELETE_PHRASE in offers) add(MenuItem.DELETE_PHRASE)
            if (prediction(s, op.index) != null) add(MenuItem.NO_PREDICT)
            // English ranks by its own rules, not the habits' tiers; where nothing may be done
            // (a field that learns nothing) there is no menu, nor for a phrase alone (UAT PY-49); nor where the
            // session cannot say (review-int-runtime M2)
            if (im != Engines.ENGLISH && any { it != MenuItem.DELETE_PHRASE } && why != null) add(MenuItem.WHY)
        }
        // a table blocks its candidate under the code typed alone (U-28, stream C; review-E-ui Q1);
        // what may follow, as pinyin's, everywhere
        val scope = if (im in Engines.TABLES && why?.code?.kind != Kinds.PREDICTION) BlockScope.UNDER_CODE else BlockScope.EVERYWHERE
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
        if (code != null) append("code ").append(code.kind).append(if (code.kind == Kinds.PREDICTION) "" else " " + code.keys).append('\n')
        shown.forEachIndexed { i, c ->
            val why = s.why(i)
            append(i + 1).append(' ').append(c.text).append(' ').append(why?.tier ?: "?").append(' ').append(why?.uses ?: 0).append('\n')
        }
    }

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
    }
}
