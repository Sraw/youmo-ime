/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input

import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.input.editing.ContextMemory
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
        policy.focus("chat", password = false, sensitive = false)
        memory.committed("你好", now = 0)
        policy.focus("incognito", password = false, sensitive = true)
        // not read, only told that what is there is not known
        assertEquals("", context(1, "密码"))
        assertTrue(reads.isEmpty())
        // what is typed there is not kept as the field's, nor as the chat's
        memory.committed("秘密", now = 2)
        policy.focus("incognito", password = false, sensitive = false)
        assertEquals("", context(3, ""))
        policy.focus("chat", password = false, sensitive = false)
        assertEquals("你好", context(4, ""))
    }

    @Test
    fun anOrdinaryFieldIsReadAndItsTextTheContext() {
        policy.focus("chat", password = false, sensitive = false)
        assertEquals("今天", context(0, "今天"))
        // something before the cursor: what follows it is not asked
        assertEquals(listOf("before"), reads)
    }

    @Test
    fun anEmptyFieldIsAskedWhatFollowsOnlyWhenSomethingFreshIsRemembered() {
        policy.focus("chat", password = false, sensitive = false)
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
        policy.focus("chat", password = false, sensitive = false)
        memory.committed("ok!", now = 0)
        assertEquals(Action.Context("ok!", cleared = true), policy.told(1, { "" }, { "" }))
        assertEquals(Action.Context("ok! hi"), policy.told(2, { "ok! hi" }, { "" }))
        // not said: not known, nothing cleared
        assertEquals(Action.Context(""), policy.told(3, { null }, { "" }))
        policy.focus("incognito", password = false, sensitive = true)
        assertEquals(Action.Context(""), policy.told(4, { "x" }, { "" }))
        policy.finish()
        assertNull(policy.told(5, { "" }, { "" }))
    }

    @Test
    fun anEditorThatWillNotSayTellsItIsNotKnownAndKeepsWhatWasWritten() {
        policy.focus("chat", password = false, sensitive = false)
        memory.committed("在吗", now = 0)
        assertEquals("", context(1, null))
        // nor will it say what follows the cursor: what was written stays for the next time
        assertEquals("", context(2, "", after = null))
        assertEquals("在吗", context(3, ""))
    }

    @Test
    fun aPasswordPairsNothingAndAShownOneIsStillOne() {
        policy.focus("login", password = true, sensitive = true)
        assertFalse(policy.pairs(autoPairs = true, rawKeys = false))
        assertTrue(policy.password)
        policy.focus("note", password = false, sensitive = false)
        assertTrue(policy.pairs(autoPairs = true, rawKeys = false))
        assertFalse(policy.pairs(autoPairs = false, rawKeys = false))
        assertFalse(policy.pairs(autoPairs = true, rawKeys = true))
        // an incognito field is no password: pairs and voice as anywhere
        policy.focus("incognito", password = false, sensitive = true)
        assertTrue(policy.pairs(autoPairs = true, rawKeys = false))
        assertFalse(policy.password)
    }

    @Test
    fun aFinishedInputLeavesNoPasswordBehind() {
        policy.focus("login", password = true, sensitive = true)
        policy.finish()
        assertFalse(policy.password)
        assertFalse(policy.sensitive)
    }

    @Test
    fun aReadQueuedInASensitiveFieldReadsNothingOnceItsInputFinished() {
        policy.focus("incognito", password = false, sensitive = true)
        policy.finish()
        // no field until the next focus: not even read
        assertNull(context(0, "秘密"))
        assertTrue(reads.isEmpty())
        policy.focus("chat", password = false, sensitive = false)
        assertEquals("你好", context(1, "你好"))
    }

    @Test
    fun aPasswordIsNotReadNorKeptWhateverElseTheServiceSays() {
        policy.focus("login", password = true, sensitive = false)
        assertTrue(policy.sensitive)
        assertEquals("", context(0, "hunter2"))
        assertTrue(reads.isEmpty())
        // what is typed there is not kept as the field's
        memory.committed("hunter2", now = 1)
        policy.focus("login", password = false, sensitive = false)
        assertEquals("", context(2, ""))
    }
}
