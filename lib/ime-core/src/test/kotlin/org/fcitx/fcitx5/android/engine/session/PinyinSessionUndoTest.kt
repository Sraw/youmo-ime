/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.learn.backup.BackupControl
import org.fcitx.fcitx5.android.engine.learn.backup.BackupHead
import org.fcitx.fcitx5.android.engine.learn.backup.Backups
import org.fcitx.fcitx5.android.engine.learn.backup.LearnBackup
import org.fcitx.fcitx5.android.engine.learn.backup.ListedWord
import org.fcitx.fcitx5.android.engine.learn.Code
import org.fcitx.fcitx5.android.engine.learn.control.LearningControl
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.Learner
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.LearnState
import org.fcitx.fcitx5.android.engine.learn.LearnTo
import org.fcitx.fcitx5.android.engine.learn.row
import org.fcitx.fcitx5.android.engine.learn.Words
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.PinyinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter
import org.fcitx.fcitx5.android.engine.session.Action.Backspace
import org.fcitx.fcitx5.android.engine.session.Action.Context
import org.fcitx.fcitx5.android.engine.session.Action.Key
import org.fcitx.fcitx5.android.engine.session.Action.Select
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * A commit deleted as soon as written is taken back, and one typed again for it is a correction
 * there (U-03, impl/design-1c.md §2): learn-ux's undo.txt, and what keeps a deletion from counting.
 */
class PinyinSessionUndoTest {

    @get:Rule
    val folder = TemporaryFolder()

    // dl reads 带来 first; 打了 and 点了 are picked over it
    private val data = dataOf(
        "给" to "gei", "带来" to "dai lai", "打了" to "da le", "点了" to "dian le", "吗" to "ma", "吧" to "ba",
        "点" to "dian", "了" to "le", "打" to "da", "大乐" to "da le",
    )
    private val learner = Learner(data)
    private val session = PinyinSession(data, PinyinSegmenter(), pageSize = 20, prediction = false, user = learner.model, learnTo = learner.learnTo())

    private fun Session.type(keys: String): Snapshot = keys.map { apply(Key(it)) }.last()

    // [text] picked of what [keys] show, committed where the cursor is
    private fun Session.pick(keys: String, text: String): String {
        val shown = type(keys).candidates
        assertTrue("$text in $shown", text in shown)
        return apply(Select(shown.indexOf(text))).commit
    }

    // [n] Backspaces the app takes, then another action: what they deleted whole is taken back as it comes
    private fun Session.delete(n: Int) {
        repeat(n) { assertFalse(apply(Backspace).handled) }
        apply(Action.Refine)
    }

    private fun uses(keys: String, text: String, reading: String) = learner.habits.uses(Code(Kinds.FULL, keys), Words.of(text, reading))
    private fun corrections(keys: String, text: String, reading: String) = learner.habits.corrections(Code(Kinds.FULL, keys), Words.of(text, reading))

    // undo.txt: 给 | dl 打了 | ma 吗, three Backspaces, dianlema 点了吗: 打了 and 吗 not used, 点了 corrected to under dl
    @Test
    fun commitsDeletedAreTakenBackAndTheRetypeIsACorrection() {
        session.apply(Context("给", learnFlags = 0))
        session.pick("dl", "打了")
        session.pick("ma", "吗")
        assertEquals(1, uses("dl", "打了", "da le"))
        session.delete(3)
        assertEquals(0, uses("dl", "打了", "da le"))
        assertEquals(0, uses("ma", "吗", "ma"))
        assertEquals("点了吗", session.pick("dianlema", "点了吗"))
        assertEquals(1, corrections("dl", "点了", "dian le"))
        session.apply(Context("给", learnFlags = 0))
        val shown = session.type("dl").candidates
        assertTrue(shown.toString(), shown.indexOf("点了") in 0 until shown.indexOf("打了"))
        session.apply(Select(shown.indexOf("点了")))
        session.apply(Context("给", learnFlags = 0))
        assertEquals("点了", session.type("dl").candidates.first())
    }

    // typed again with the same keys: only this commit's own pick, no second row
    @Test
    fun theSameKeysAgainAreThatCommitAlone() {
        session.apply(Context("给", learnFlags = 0))
        session.pick("dl", "打了")
        session.delete(2)
        session.pick("dl", "点了")
        assertEquals(1, uses("dl", "点了", "dian le"))
        assertEquals(1, corrections("dl", "点了", "dian le"))
        assertEquals(0, uses("dl", "打了", "da le"))
    }

    // the control for the guards below: 打了 deleted and 点了 typed again under other keys is a correction under dl
    @Test
    fun aRetypeUnderOtherKeysIsACorrection() {
        session.apply(Context("给", learnFlags = 0))
        session.pick("dl", "打了")
        session.delete(2)
        session.pick("dianle", "点了")
        assertEquals(1, corrections("dl", "点了", "dian le"))
    }

    @Test
    fun aCommitDeletedInPartIsLearnedAsItWas() {
        session.apply(Context("给", learnFlags = 0))
        session.pick("dl", "打了")
        session.delete(1)
        assertEquals(1, uses("dl", "打了", "da le"))
        session.pick("dian", "点")
        assertEquals(0, uses("dl", "点", "dian"))
    }

    // the field started again between: what it shows is no longer this session's commits
    @Test
    fun aFieldStartedAgainTakesNothingBack() {
        session.apply(Context("给", learnFlags = 0))
        session.pick("dl", "打了")
        session.apply(Context("给打了", started = true, learnFlags = 0))
        session.delete(2)
        assertEquals(1, uses("dl", "打了", "da le"))
    }

    // the cursor put back after 打了, 吗 still after it: 打了 deleted there is taken back (the text after
    // the commits read as the field started: else no edit among them is known)
    @Test
    fun aCommitDeletedAmongOthersIsTakenBack() {
        session.apply(Context("给", after = "", learnFlags = 0))
        session.pick("dl", "打了")
        session.pick("ma", "吗")
        session.apply(Context("给打了", after = "吗", learnFlags = 0))
        session.delete(2)
        assertEquals(0, uses("dl", "打了", "da le"))
        assertEquals(1, uses("ma", "吗", "ma"))
    }

    // a commit past the tail, written yesterday and over an hour ago, stays learned
    @Test
    fun aCommitOfAnotherDayIsNotTakenBack() {
        session.apply(Context("给", learnFlags = 0))
        session.pick("dl", "打了")
        learner.clock.minutes(15 * 60)
        session.delete(2)
        assertEquals(1, uses("dl", "打了", "da le"))
    }

    // m picked 吗 after 打了; dianlema typed again for both: 吗 now follows 点了, so it is no retype of that 吗
    @Test
    fun aRetypeAfterAnotherWordIsNoCorrectionThere() {
        session.apply(Context("给", learnFlags = 0))
        session.pick("dl", "打了")
        session.pick("m", "吗")
        session.delete(3)
        session.pick("dianlema", "点了吗")
        assertEquals(1, corrections("dl", "点了", "dian le"))
        assertEquals(0, uses("m", "吗", "ma"))
        assertEquals(0, corrections("m", "吗", "ma"))
    }

    // 繁体 output: the editor shows 打了 as the app converted it, and the commits are compared so (review-design-1c S9)
    @Test
    fun traditionalTextIsComparedSimplified() {
        val learner = Learner(data)
        val traditional = PinyinSession(
            data, PinyinSegmenter(), pageSize = 20, prediction = false, user = learner.model,
            learnTo = LearnTo(learner.habits, Kinds.FULL) { it.replace("點", "点") },
        )
        traditional.apply(Context("给", after = "", learnFlags = 0))
        traditional.pick("dianle", "点了")
        // an editor that reports each Backspace, in what it shows: 點了
        traditional.apply(Backspace)
        traditional.apply(Context("给點", after = "", learnFlags = 0))
        traditional.apply(Backspace)
        traditional.apply(Context("给", after = "", learnFlags = 0))
        // taken back at the next action
        traditional.apply(Action.Refine)
        assertEquals(0, learner.habits.uses(Code(Kinds.FULL, "dianle"), Words.of("点了", "dian le")))
    }

    // review-design-1c M1: punctuation passed on to the app after the commit, a panel or another input method
    // writing: a Backspace then may delete what this session did not write, and nothing is taken back
    @Test
    fun aBackspaceAfterTextNotWrittenHereTakesNothingBack() {
        session.apply(Context("给", learnFlags = 0))
        session.pick("dl", "打了")
        assertFalse(session.apply(Key(',')).handled)
        session.delete(3)
        assertEquals(1, uses("dl", "打了", "da le"))
        session.apply(Context("给", learnFlags = 0))
        session.pick("dl", "打了")
        // the input method left or a panel wrote: the field is not known till a context comes, so nothing is taken back
        session.apply(Action.Reset)
        session.delete(2)
        assertEquals(2, uses("dl", "打了", "da le"))
    }

    // a Backspace whose context says otherwise (a selection deleted elsewhere: 吗 still there) takes
    // nothing back; an editor that reports each Backspace agrees with it, and checks what went
    @Test
    fun aContextAfterEachBackspaceChecksIt() {
        session.apply(Context("给", after = "", learnFlags = 0))
        session.pick("dl", "打了")
        session.pick("ma", "吗")
        session.apply(Backspace)
        session.apply(Context("给打了吗", after = "", learnFlags = 0))
        assertEquals(1, uses("ma", "吗", "ma"))
        session.pick("ba", "吧")
        session.apply(Backspace)
        session.apply(Context("给打了吗", after = "", learnFlags = 0))
        session.apply(Action.Refine)
        assertEquals(0, uses("ba", "吧", "ba"))
    }

    // the record took sixteen commits since, elsewhere: the commit is past taking back, and learned as it was
    @Test
    fun aCommitTheRecordNoLongerTakesBackStaysLearned() {
        session.apply(Context("给", learnFlags = 0))
        session.pick("dl", "打了")
        repeat(LearnLog.UNDOABLE) { learner.habits.record(listOf(row("ni", "你", "ni"))) }
        session.delete(2)
        assertEquals(1, uses("dl", "打了", "da le"))
        session.pick("dianle", "点了")
        assertEquals(0, corrections("dl", "点了", "dian le"))
    }

    // review-1c S4: every rewrite of the record between the delete and the retype (清掉最近, 清空学到的,
    // 全部清空, a restore, 退回恢复前) leaves the journal empty: the retype learns nothing of the deleted
    @Test
    fun everyRewriteOfTheRecordEmptiesTheJournal() {
        val other = LearnBackup.bytes(BackupHead(0, 1, 7L, BackupHead.SAVED), LearnState(), emptyList(), emptyList())
        // what each does before the commit ([before]), and the rewrite between ([run])
        class Rewrite(val name: String, val before: (BackupControl) -> Unit = {}, val run: (LearningControl, BackupControl) -> Unit)
        val rewrites = listOf(
            Rewrite("clear today") { c, _ -> assertNotNull(c.clearRecent(LearningOp.Span.TODAY)) },
            Rewrite("clear learned") { c, _ -> assertTrue(c.clearLearned(all = false)) },
            Rewrite("clear all") { c, _ -> assertTrue(c.clearLearned(all = true)) },
            Rewrite("restore") { _, b -> assertNotNull(b.restore(other)) },
            Rewrite("revert", { b -> b.restore(other) }) { _, b -> assertTrue(b.revert()) },
        )
        for (rewrite in rewrites) {
            val dir = folder.newFolder()
            val learner = Learner(data, file = File(dir, LearnLog.FILE))
            val log = learner.log
            try {
                val backups = BackupControl(
                    Backups(File(dir, Backups.DIR), 1L), learner.clock, "test", state = { log.state }, lists = { emptyList<ListedWord>() to emptyList() },
                    rewrite = { t -> log.rewrite(t) }, addLists = { _, _ -> }, removeLists = { _, _ -> }, importLines = {},
                )
                rewrite.before(backups)
                val session = PinyinSession(data, PinyinSegmenter(), pageSize = 20, prediction = false, user = learner.model, learnTo = learner.learnTo())
                session.apply(Context("给", learnFlags = 0))
                session.pick("dl", "打了")
                session.delete(2)
                rewrite.run(LearningControl({ log }, learner.clock), backups)
                session.pick("dianle", "点了")
                assertEquals(rewrite.name, 0, learner.habits.corrections(Code(Kinds.FULL, "dl"), Words.of("点了", "dian le")))
            } finally {
                log.close()
            }
        }
    }

    // review-1c S7: a character looked up and committed after 打了 was deleted: 打了 taken back, and what follows
    // is typed after that character, no retype of 打了
    @Test
    fun aCharacterLookedUpEndsTheRun() {
        session.apply(Context("给", after = "", learnFlags = 0))
        session.pick("dl", "打了")
        repeat(2) { assertFalse(session.apply(Backspace).handled) }
        session.follow("字")
        assertEquals(0, uses("dl", "打了", "da le"))
        session.pick("dianle", "点了")
        assertEquals(0, corrections("dl", "点了", "dian le"))
    }

    // U-03 on the nine keys: 3253 打了 deleted, 3253 typed again for 大乐, a correction under the nine keys' code (review-1c S6)
    @Test
    fun aRetypeOnTheNineKeysIsACorrectionThere() {
        val t9 = PinyinSession(
            data, T9Segmenter(abbreviations = false), spell = true, pageSize = 20, prediction = false, user = learner.model, learnTo = learner.learnTo(),
        )
        val nineKeys = Kinds.nineKeys(NineKeyLetters.STANDARD.tag())
        t9.apply(Context("给", after = "", learnFlags = 0))
        t9.pick("3253", "打了")
        assertEquals(1, learner.habits.uses(Code(nineKeys, "3253"), Words.of("打了", "da le")))
        t9.delete(2)
        assertEquals(0, learner.habits.uses(Code(nineKeys, "3253"), Words.of("打了", "da le")))
        t9.pick("3253", "大乐")
        assertEquals(1, learner.habits.corrections(Code(nineKeys, "3253"), Words.of("大乐", "da le")))
    }

    // review-1c M1: every keyboard is told the host's context; text another keyboard committed after this one's is
    // no deletion of this one's commits
    @Test
    fun anotherKeyboardsCommitsAreNoDeletion() {
        val t9 = PinyinSession(data, T9Segmenter(abbreviations = false), spell = true, pageSize = 20, prediction = false, user = learner.model, learnTo = learner.learnTo())
        for (s in listOf(session, t9)) s.apply(Context("", started = true, after = "", learnFlags = 0))
        session.pick("dl", "打了")
        session.pick("ma", "吗")
        t9.pick("22", "吧")
        for (s in listOf(session, t9)) s.apply(Context("打了吗吧", after = "", learnFlags = 0))
        session.apply(Action.Refine)
        assertEquals(1, uses("dl", "打了", "da le"))
        assertEquals(1, uses("ma", "吗", "ma"))
    }

    private fun dataOf(vararg words: Pair<String, String>) = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder().unigram("<unk>", -7f, 0f)
                .apply {
                    words.forEachIndexed { i, (w, reading) -> unigram(w, if (w == "带来") -1f else -2f - i * 0.1f, 0f).entry(w, reading.split(' ').map(Syllables::id).toIntArray()) }
                }
                .build().toByteArray(),
        ),
    )
}
