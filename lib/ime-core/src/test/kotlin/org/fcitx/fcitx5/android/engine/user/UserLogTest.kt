/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.engine.user.UserFixtures.entry
import org.fcitx.fcitx5.android.engine.user.UserFixtures.inTrie
import org.fcitx.fcitx5.android.engine.user.UserFixtures.model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UserLog] as an older build's `pinyin.user` is read to be converted (LegacyConversion,
 * UserLexicon.legacyModel): what does not read is passed over, the rest still learned
 * (review R11 F2664, the reading tests of the UserStoreTest gone with UserStore).
 */
class UserLogTest {

    private fun words(m: UserModel): Map<String, Float> {
        val out = HashMap<String, Float>()
        m.forEachCount({ e, c -> out[e.toString()] = c }, { _, _, _ -> })
        return out
    }

    private val learned = UserLog.sentence(null, listOf(entry("你", "ni"), entry("拟好", "ni", "hao")))
    private val after = UserLog.sentence(null, listOf(entry("吗", "ma")))

    @Test
    fun recordsThatDoNotReadArePassedOverAndTheRestIsLearned() {
        val bytes = UserLog.header() + learned +
            // a syllable this build does not have
            UserLog.FORMAT.record(1) { writeBoolean(false); writeShort(1); writeUTF("哈"); writeUTF("xq") } +
            // an old key habit's count (type 6), and a later version's type
            UserLog.FORMAT.record(6) { writeUTF("nh"); writeUTF("你好"); writeInt(3) } +
            UserLog.FORMAT.record(9) { writeUTF("吗") } +
            // a sentence of -1 words, as a writer's bug would leave
            UserLog.FORMAT.record(1) { writeBoolean(false); writeShort(-1) } +
            after
        val m = model()
        assertEquals(bytes.size, UserLog.read(bytes, m))
        assertEquals(mapOf("你(ni)" to 1f, "拟好(ni hao)" to 1f, "吗(ma)" to 1f), words(m))
        assertTrue(inTrie(m, "拟好", "ni", "hao"))
    }

    @Test
    fun aRecordCutShortOrGarbledEndsWhatIsRead() {
        val whole = UserLog.header() + learned
        val garbled = after.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val m = model()
        assertEquals(whole.size, UserLog.read(whole + garbled + after, m))
        assertEquals(mapOf("你(ni)" to 1f, "拟好(ni hao)" to 1f), words(m))
        val cut = model()
        assertEquals(whole.size, UserLog.read(whole + after.copyOf(after.size - 3), cut))
        assertEquals(words(m), words(cut))
    }

    @Test
    fun aHeaderCutShortIsNoLogOfThis() {
        assertFalse(UserLog.hasHeader(UserLog.header().copyOf(5)))
        assertTrue(UserLog.hasHeader(UserLog.header()))
    }

    @Test
    fun whatIsForgottenIsWhatWasForgottenAndNotLearnedAgain() {
        val niHao = entry("拟好", "ni", "hao")
        val niZai = entry("拟再", "ni", "zai")
        val bytes = UserLog.header() + learned + UserLog.forgot(listOf(niHao)) +
            UserLog.sentence(null, listOf(niZai)) + UserLog.forgot(listOf(niZai)) + UserLog.sentence(null, listOf(niZai)) +
            // a forget of a syllable this build does not have: nothing of it typeable here
            UserLog.FORMAT.record(4) { writeShort(1); writeUTF("哈"); writeUTF("xq") }
        assertEquals(setOf("拟好"), UserLog.forgotten(bytes))
        val m = model()
        UserLog.read(bytes, m)
        assertFalse(inTrie(m, "拟好", "ni", "hao"))
        assertTrue(inTrie(m, "拟再", "ni", "zai"))
    }
}
