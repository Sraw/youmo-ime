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
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Session
import org.fcitx.fcitx5.android.engine.user.UserModel.Entry
import java.io.IOException

/** [Engines.learning]: the user's controls over learning on [session]s, [lexicon] and the record [log], made undoable by [control]. Candidate acts go through the host's long-press event ([act]) as always, recorded so the chip can undo them. */
internal class LearningOps(
    // the session an input method types with now: a pair's keyed one while it types (KeyboardPair.sessionOf), so a
    // long press acts on what that session showed
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
    /** The backups' part in a clear: [invalidate] those saved at or after a minute ([LearningResult.ALL]: all of this install's) before the rewrite, [saveIfOn] a fresh one after. */
    interface Backups {
        fun invalidate(fromMinute: Int)

        fun saveIfOn()

        companion object {
            /** For test and eval engines. */
            val NONE = object : Backups {
                override fun invalidate(fromMinute: Int) {}

                override fun saveIfOn() {}
            }
        }
    }

    /** Does [op] in [im] (null: the settings); [act] has the host send the long press's [EngineEvent] for a candidate index and the engine's text, false where the host does not show that. [shown] as the host shows a text (繁體). */
    fun apply(im: String?, op: LearningOp, shown: (String) -> String = { it }, act: (event: Int, index: Int, text: String) -> Boolean): LearningResult = when (op) {
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
        LearningOp.Saving -> saving()
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

    // whether learning is written now, and whether the record takes rewrites at all till restart
    private fun saving(): LearningResult = log().let { LearningResult.Saving(it.saving, it.takesRewrites) }

    // backups holding what is forgotten go before the rewrite and a fresh one after, even if the rewrite failed
    // (those were gone already). Tables' learning is in the record too: order kept except by 全部清空
    private fun clearRecent(op: LearningOp.ClearRecent): LearningResult = cleared {
        control.clearRecent(op.span, backups::invalidate)?.let { LearningResult.Cleared(it) }
    }

    private fun clearLearned(op: LearningOp.ClearLearned): LearningResult = cleared {
        if (control.clearLearned(op.all, backups::invalidate)) {
            // clear the lists only once the record is cleared: a failed clear forgets nothing
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
        // English's have no reading: those blocked now are blocked again on undo
        val english = lexicon.englishBlocks
        // as written: "The" is not listed where "the" is, though has() says it is blocked
        val englishNow = english.blocked.toSet()
        val englishBlocked = words.filter { it.kind == UserWord.Kind.BLOCKED && it.pinyin.isEmpty() && it.text in englishNow }.map { it.text }
        val forgets = tableForgets()
        val token = control.undoable(inverse = {
            lexicon.reAdd(added)
            lexicon.reBlock(blocked)
            english.block(englishBlocked)
            dropSessions()
        }) { lexicon.removeWords(words) }
        // what tables' logs forgot stays forgotten: no undo rather than half of one (an unloaded table cannot tell)
        return LearningResult.Done(if (tableForgets() != forgets) LearningResult.NO_UNDO else token)
    }

    // a session told to show anew rereads the record, keeping its input; one that cannot (Session.rereads false)
    // is remade. Failed, not Unavailable, where the record could not be written, so the app tells them apart
    private fun undo(im: String?, token: Long): LearningResult {
        when (control.takeBack(token)) {
            LearningControl.TakenBack.DONE -> Unit
            LearningControl.TakenBack.GONE -> return LearningResult.Unavailable
            LearningControl.TakenBack.FAILED -> return LearningResult.Failed
        }
        if (im != null && session(im)?.rereads == false) dropSessions()
        return LearningResult.Done()
    }

    // [act] made undoable: its token, or null where the host did nothing (its list gone). If not done but [wrote]
    // something (a look-alike candidate acted on), that is taken back at once; where that fails the look-alikes
    // stay deleted and the user is told it was not done, since undoing would drop what was learned since
    private fun acted(inverse: () -> Unit = {}, wrote: () -> Boolean = { false }, act: () -> Boolean): Long? {
        var did = false
        val token = control.undoable(inverse) { did = act() }
        if (!did && wrote()) control.undo(token)
        return token.takeIf { did }
    }

    private fun onCandidate(im: String, asShown: LearningOp.OnCandidate, shown: (String) -> String, act: (Int, Int, String) -> Boolean): LearningResult {
        val s = session(im) ?: return LearningResult.Unavailable
        val op = engines(s, asShown, shown) ?: return LearningResult.Unavailable
        val offers = s.offers(op.index)
        val event = { offer: Offer, e: Int ->
            acted { offer in offers && act(e, op.index, op.text) }?.let { LearningResult.Done(it) } ?: LearningResult.Unavailable
        }
        return when (op) {
            // the engine's text, which the menu's acts come back with (engines)
            is LearningOp.Menu -> menu(im, s, op)
            is LearningOp.Delete -> if (Offer.FORGET in offers) deleteAlike(im, s, op, shown, act) else LearningResult.Unavailable
            is LearningOp.Block -> if (Offer.BLOCK in offers) blockAlike(im, s, op, shown, act) else LearningResult.Unavailable
            is LearningOp.Pin -> event(Offer.PIN, EngineEvent.PIN)
            // a phrase file holds it, not the record: no undo
            is LearningOp.Unpin -> if (Offer.DELETE_PHRASE in offers || Offer.DELETE_COMMON_WORD in offers) {
                if (act(EngineEvent.UNPIN, op.index, op.text)) LearningResult.Done(LearningResult.NO_UNDO) else LearningResult.Unavailable
            } else {
                event(Offer.UNPIN, EngineEvent.UNPIN)
            }
            is LearningOp.NoPredict -> {
                val prev = prediction(s, op.index) ?: return LearningResult.Unavailable
                // a whole phrase off everywhere, filed after no word
                val whole = s.why(op.index)?.whole.orEmpty()
                LearningResult.Done(control.undoable { if (whole.isEmpty()) log().noPredict(prev, op.text, on = true) else log().noPredict("", whole, on = true) })
            }
            is LearningOp.Report -> LearningResult.Text(report(im, s))
        }
    }

    // [asShown] in the engine's text, or null if the list changed since the menu showed. Menus are asked with the
    // shown text (國家 for 国家); an act comes back with the engine's and must match, not a look-alike
    private fun engines(s: Session, asShown: LearningOp.OnCandidate, shown: (String) -> String): LearningOp.OnCandidate? {
        val text = s.candidates(asShown.index, 1).firstOrNull()?.text ?: return null
        val asked = asShown is LearningOp.Menu || asShown is LearningOp.Report
        return asShown.at(asShown.index, text).takeIf { text == asShown.text || asked && shown(text) == asShown.text }
    }

    // the shown list's candidates that look like [text] after conversion (国家 and a dictionary's own 國家), so a
    // block or delete removes it in every form; each at its index with the engine's text. One look at the list
    private fun alike(s: Session, op: LearningOp.OnCandidate, shown: (String) -> String): List<IndexedValue<String>> {
        val pressed = IndexedValue(op.index, op.text)
        val asShown = shown(op.text)
        val list = s.candidates(0, ALIKE_SCAN).map { it.text }
        if (asShown == op.text && list.all { shown(it) == it }) return listOf(pressed)
        val others = list.withIndex().filter { (i, t) -> i != op.index && t != op.text && shown(t) == asShown }
        return listOf(pressed) + others
    }

    private fun deleteAlike(im: String, s: Session, op: LearningOp.Delete, shown: (String) -> String, act: (Int, Int, String) -> Boolean): LearningResult {
        val all = alike(s, op, shown)
        return delete(all.map { it.value }, s.why(op.index)?.whole.orEmpty()) { actOn(im, all, Offer.FORGET, EngineEvent.FORGET, act) }
    }

    private fun blockAlike(im: String, s: Session, op: LearningOp.Block, shown: (String) -> String, act: (Int, Int, String) -> Boolean): LearningResult {
        val all = alike(s, op, shown)
        return block(all.map { it.value }) { actOn(im, all, Offer.BLOCK, EngineEvent.BLOCK, act) }
    }

    // [event] on each of [words] still where the list had it, last first (an act removes one, keeping earlier
    // positions). Returns the texts done; without the pressed one (first), the act is not done
    private fun actOn(im: String, words: List<IndexedValue<String>>, offer: Offer, event: Int, act: (Int, Int, String) -> Boolean): List<String> {
        val done = ArrayList<String>()
        for ((at, t) in words.sortedByDescending { it.index }) {
            val s = session(im) ?: break
            // an earlier act may have moved the list: only if the engine's [t] is still there
            if (s.candidates(at, 1).firstOrNull()?.text == t && offer in s.offers(at) && act(event, at, t)) done += t
        }
        return done
    }

    // [whole]: the whole phrase [texts]' first ends, "" for a word; [texts]: it and look-alikes; [act]: those
    // deleted, none where the first was not
    private fun delete(texts: List<String>, whole: String, act: () -> List<String>): LearningResult {
        // a deleted added word leaves its list (Engines.changed) and returns on undo, deleted ones only; a whole phrase
        // takes no word with it. Look-alikes are words even when the pressed one is a phrase's rest
        val added = lexicon.addedPlaces((if (whole.isEmpty()) texts else texts.drop(1)).flatMap { lexicon.addedOf(it) })
        val forgets = tableForgets()
        var done = emptyList<String>()
        val token = acted(inverse = { lexicon.reAdd(added.filter { it.value.text in done }) }, wrote = { done.isNotEmpty() }) {
            done = act()
            texts.first() in done
        } ?: return LearningResult.Unavailable
        // what tables' own logs forgot no record rewrite brings back: no chip rather than a half undo (an unloaded
        // table forgets every row of the word, so none either)
        return LearningResult.Done(if (tableForgets() != forgets) LearningResult.NO_UNDO else token)
    }

    // [texts]: the pressed one first, then look-alikes; [act] returns those blocked
    private fun block(texts: List<String>, act: () -> List<String>): LearningResult {
        val before = texts.flatMap { lexicon.blockedOf(it) }.toSet()
        // English's words have their own list (no reading)
        val english = lexicon.englishBlocks
        // as written, which the shown text need not be (The for the; EnglishBlocks)
        val englishBefore = english.blocked.toSet()
        var blocked = emptyList<Entry>()
        var englishBlocked = emptyList<String>()
        var done = emptyList<String>()
        val token = acted(inverse = {
            lexicon.unblock(blocked)
            english.unblock(englishBlocked)
        }, wrote = { done.isNotEmpty() }) {
            done = act()
            blocked = texts.flatMap { lexicon.blockedOf(it) }.filter { it !in before }
            englishBlocked = english.blocked.filter { it !in englishBefore }
            texts.first() in done
        } ?: return LearningResult.Unavailable
        return LearningResult.Done(token)
    }

    // the word before a prediction at [index], where turning it off is kept
    private fun prediction(s: Session, index: Int): String? =
        s.why(index)?.code?.takeIf { it.kind == Kinds.PREDICTION && it.keys.isNotEmpty() && habits() }?.keys

    private fun menu(im: String, s: Session, op: LearningOp.Menu): LearningResult.Menu {
        val offers = s.offers(op.index)
        val why = s.why(op.index)
        val items = buildList {
            if (Offer.FORGET in offers) add(MenuItem.DELETE)
            if (Offer.BLOCK in offers) add(MenuItem.BLOCK)
            if (Offer.PIN in offers) add(MenuItem.PIN)
            if (Offer.UNPIN in offers) add(MenuItem.UNPIN)
            if (Offer.DELETE_PHRASE in offers) add(MenuItem.DELETE_PHRASE)
            if (Offer.DELETE_COMMON_WORD in offers) add(MenuItem.DELETE_COMMON_WORD)
            if (prediction(s, op.index) != null) add(MenuItem.NO_PREDICT)
            // English ranks by its own rules, not the habits' tiers; no 为什么 where nothing may be done (a field that
            // learns nothing), for a phrase alone, or where the session cannot say
            if (im != InputMethodCatalog.ENGLISH && any { it !in TEXT_ONLY } && why != null) add(MenuItem.WHY)
        }
        // a table blocks its candidate under the code typed alone, predictions everywhere; otherwise as the session
        // says (a character by strokes under them alone, a table's pinyin lookup everywhere)
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

    /** 复制给开发者: the visible candidates with tier and uses, and the keys typed; never the text before them, nor a prediction's preceding word. */
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

    // grouped codes as their keys' letters (bn ui): pasted elsewhere they would read as nothing
    private fun reported(code: Code): String =
        Kinds.keysOf(code.kind)?.shown(code.keys) ?: code.keys

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
            saving = log().saving,
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

    /** [words] as the lexicon page lists them: last-used day ([UserWord.lastUsed]), typing days since ([UserWord.idle]) and recency of becoming the user's ([UserWord.order]). */
    fun dated(words: List<UserWord>): List<UserWord> = LexiconView.dated(log().state, words)

    private companion object {
        // a page or two: enough to see where the word went
        const val REPORTED = 20

        // how far to look for look-alikes: past any page the menu is on
        const val ALIKE_SCAN = 200

        // settings-owned text rows (custom phrase, common-word replacement): no 为什么 for them alone
        val TEXT_ONLY = setOf(MenuItem.DELETE_PHRASE, MenuItem.DELETE_COMMON_WORD)
    }
}
