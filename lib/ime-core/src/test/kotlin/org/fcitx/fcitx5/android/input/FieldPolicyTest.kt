/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.input.editing.ContextMemory
import org.fcitx.fcitx5.android.input.editing.EditorTraits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the service may read of each kind of field, keep of it and tell the engine, as PRIVACY.md promises. */
class FieldPolicyTest {

    private val memory = ContextMemory()
    private val policy = FieldPolicy(memory)
    private val reads = mutableListOf<String>()

    private fun context(now: Long, before: String?, after: String? = "") = policy.context(
        now,
        before = { reads += "before"; before },
        after = { reads += "after"; after },
    )

    @Test
    fun aSensitiveFieldIsNotReadAndNothingOfItIsKept() {
        policy.focusAnother("chat", password = false, sensitive = false)
        memory.committed("你好", now = 0)
        policy.focusAnother("incognito", password = false, sensitive = true)
        // not read, only told that what is there is not known
        assertEquals("", context(1, "密码"))
        assertTrue(reads.isEmpty())
        // what is typed there is not kept as the field's, nor as the chat's
        memory.committed("秘密", now = 2)
        policy.focusAnother("incognito", password = false, sensitive = false)
        assertEquals("", context(3, ""))
        policy.focusAnother("chat", password = false, sensitive = false)
        assertEquals("你好", context(4, ""))
    }

    // U-91: addresses and numbers learn no pairs, a search no 联想; told with the context, gone with the field
    @Test
    fun aFieldsKindSaysWhatLearningLeavesOut() {
        assertEquals(Commit.NO_PAIRS, FieldPolicy.learnFlags(EditorTraits(isUri = true)))
        assertEquals(Commit.NO_PAIRS, FieldPolicy.learnFlags(EditorTraits(isEmail = true)))
        assertEquals(Commit.NO_PAIRS, FieldPolicy.learnFlags(EditorTraits(isNumber = true)))
        assertEquals(Commit.NO_PREDICT, FieldPolicy.learnFlags(EditorTraits(isSearch = true)))
        assertEquals(0, FieldPolicy.learnFlags(EditorTraits()))
        policy.focusAnother("url", password = false, sensitive = false, learnFlags = Commit.NO_PAIRS)
        assertEquals(Commit.NO_PAIRS, policy.told(0, { "abc" }, { "" })?.learnFlags)
        policy.finish()
        policy.focusAnother("chat", password = false, sensitive = false)
        assertEquals(0, policy.told(0, { "abc" }, { "" })?.learnFlags)
    }

    @Test
    fun anOrdinaryFieldIsReadAndItsTextTheContext() {
        policy.focusAnother("chat", password = false, sensitive = false)
        assertEquals("今天", context(0, "今天"))
        // something before the cursor: what follows it is not asked
        assertEquals(listOf("before"), reads)
    }

    @Test
    fun anEmptyFieldIsAskedWhatFollowsOnlyWhenSomethingFreshIsRemembered() {
        policy.focusAnother("chat", password = false, sensitive = false)
        assertEquals("", context(0, ""))
        assertEquals(listOf("before"), reads)
        memory.committed("明天见", now = 1)
        reads.clear()
        assertEquals("明天见", context(2, "", after = ""))
        assertEquals(listOf("before", "after"), reads)
        // text after the cursor: the start of a field, not an empty one
        assertEquals("", context(4, "", after = "好"))
    }

    @Test
    fun whatWasWrittenInAFieldNowEmptyIsToldClearedWhatIsThereIsNot() {
        policy.focusAnother("chat", password = false, sensitive = false)
        memory.committed("ok!", now = 0)
        assertEquals(Action.Context("ok!", cleared = true, learnFlags = 0, after = "", said = true), policy.told(1, { "" }, { "" }))
        assertEquals(Action.Context("ok! hi", learnFlags = 0, after = "", said = true), policy.told(2, { "ok! hi" }, { "" }))
        // not said: not known, nothing cleared
        assertEquals(Action.Context("", learnFlags = 0, after = ""), policy.told(3, { null }, { "" }))
        policy.focusAnother("incognito", password = false, sensitive = true)
        assertEquals(Action.Context("", learnFlags = 0), policy.told(4, { "x" }, { "" }))
        policy.finish()
        assertNull(policy.told(5, { "" }, { "" }))
    }

    // HW-22/23: an editor's own empty answer is said, a null (it would not say) is not, nor a sensitive field's
    @Test
    fun anEmptyFieldIsSaidOnlyWhereTheEditorAnswered() {
        policy.focusAnother("chat", password = false, sensitive = false)
        assertTrue(policy.told(1, { "" }, { "" })!!.said)
        assertFalse(policy.told(2, { null }, { "" })!!.said)
        policy.focusAnother("incognito", password = false, sensitive = true)
        assertFalse(policy.told(3, { "" }, { "" })!!.said)
    }

    // U-03: the session of an input method switched to is told the field as started, once, in a field (UAT HW-22/23)
    @Test
    fun anInputMethodSwitchedToInAFieldIsToldItStarted() {
        assertFalse(policy.inputMethodChanged("pinyin"))
        policy.focusAnother("chat", password = false, sensitive = false)
        assertTrue(policy.inputMethodChanged("handwriting"))
        assertFalse(policy.inputMethodChanged("handwriting"))
        assertTrue(policy.inputMethodChanged("pinyin"))
        policy.finish()
        assertFalse(policy.inputMethodChanged("handwriting"))
    }

    // U-03: what the engine checks its commits by. Another field is started; the same again (a WebView's restart,
    // back from another app) is not (review-1c S3); a selection is told as one (M2); nothing after the cursor of a sensitive field
    @Test
    fun theFieldStartedTheTextAfterAndASelectionAreTold() {
        assertTrue(policy.focusAnother("chat", password = false, sensitive = false))
        assertFalse(policy.focusAnother("chat", password = false, sensitive = false))
        // gone to another app and back: the input finished between, still the same field
        policy.finish()
        assertFalse(policy.focusAnother("chat", password = false, sensitive = false))
        assertTrue(policy.focusAnother("note", password = false, sensitive = false))
        assertEquals(
            Action.Context("给打了", learnFlags = 0, started = true, after = "吗", selected = true, said = true),
            policy.told(0, { "给打了" }, { "吗" }, started = true, selected = true),
        )
        policy.focusAnother("incognito", password = false, sensitive = true)
        assertNull(policy.told(1, { "给" }, { "吗" })?.after)
        // what follows asked once a report, even where the context asked it too
        policy.focusAnother("chat", password = false, sensitive = false)
        memory.committed("明天见", now = 2)
        var asked = 0
        policy.told(3, { "" }, { asked++; "吗" })
        assertEquals(1, asked)
    }

    @Test
    fun anEditorThatWillNotSayTellsItIsNotKnownAndKeepsWhatWasWritten() {
        policy.focusAnother("chat", password = false, sensitive = false)
        memory.committed("在吗", now = 0)
        assertEquals("", context(1, null))
        // nor will it say what follows the cursor: what was written stays for the next time
        assertEquals("", context(2, "", after = null))
        assertEquals("在吗", context(3, ""))
    }

    @Test
    fun aPasswordPairsNothingAndAShownOneIsStillOne() {
        policy.focusAnother("login", password = true, sensitive = true)
        assertFalse(policy.pairs(autoPairs = true, rawKeys = false))
        assertTrue(policy.password)
        policy.focusAnother("note", password = false, sensitive = false)
        assertTrue(policy.pairs(autoPairs = true, rawKeys = false))
        assertFalse(policy.pairs(autoPairs = false, rawKeys = false))
        assertFalse(policy.pairs(autoPairs = true, rawKeys = true))
        // an incognito field is no password: pairs and voice as anywhere
        policy.focusAnother("incognito", password = false, sensitive = true)
        assertTrue(policy.pairs(autoPairs = true, rawKeys = false))
        assertFalse(policy.password)
    }

    @Test
    fun aFinishedInputLeavesNoPasswordBehind() {
        policy.focusAnother("login", password = true, sensitive = true)
        policy.finish()
        assertFalse(policy.password)
        assertFalse(policy.sensitive)
    }

    @Test
    fun aReadQueuedInASensitiveFieldReadsNothingOnceItsInputFinished() {
        policy.focusAnother("incognito", password = false, sensitive = true)
        policy.finish()
        // no field until the next focus: not even read
        assertNull(context(0, "秘密"))
        assertTrue(reads.isEmpty())
        policy.focusAnother("chat", password = false, sensitive = false)
        assertEquals("你好", context(1, "你好"))
    }

    @Test
    fun aPasswordIsNotReadNorKeptWhateverElseTheServiceSays() {
        policy.focusAnother("login", password = true, sensitive = false)
        assertTrue(policy.sensitive)
        assertEquals("", context(0, "hunter2"))
        assertTrue(reads.isEmpty())
        // what is typed there is not kept as the field's
        memory.committed("hunter2", now = 1)
        policy.focusAnother("login", password = false, sensitive = false)
        assertEquals("", context(2, ""))
    }

    @Test
    fun aFieldOfThisAppItselfIsTakenAsOneThatAsksNotToBeLearnedFrom() {
        assertTrue(FieldPolicy.ownApp("io.github.sraw.youmo", "io.github.sraw.youmo"))
        assertTrue(FieldPolicy.ownApp("io.github.sraw.youmo.debug", "io.github.sraw.youmo.debug"))
        assertFalse(FieldPolicy.ownApp("com.tencent.mm", "io.github.sraw.youmo"))
        assertFalse(FieldPolicy.ownApp(null, "io.github.sraw.youmo"))
    }
}
