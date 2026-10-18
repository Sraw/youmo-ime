/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.glide

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.lattice.UserWords
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class PinyinGlideTest {

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    private val data = PinyinData.load(
        ByteBuffer.wrap(
            PinyinDataBuilder()
                .unigram("<unk>", -7f, 0f)
                .unigram("你好", -3f, 0f)
                .unigram("你", -2f, 0f)
                .unigram("女", -3f, 0f)
                .unigram("我", -1.8f, 0f)
                .unigram("为", -2.2f, 0f)
                .entry("你好", syl("ni", "hao"))
                .entry("你", syl("ni"))
                .entry("女", syl("nv"))
                .entry("我", syl("wo"))
                .entry("为", syl("wei"))
                .build().toByteArray()
        )
    )

    private val glide = PinyinGlide(data)

    private fun path(keys: String) = keys.flatMap { GlideLayout.QWERTY.centre(it).toList() }.toFloatArray()

    @Test
    fun aWordsSyllablesApart() {
        val best = glide.decode(path("nihao"), GlideLayout.QWERTY, 3).first()
        assertEquals("ni'hao", best.keys)
        assertEquals("你好", best.word)
        // ü spelt v, as typed
        assertEquals("nv", glide.decode(path("nv"), GlideLayout.QWERTY, 3).first().keys)
    }

    @Test
    fun whereThePathLiftsDecidesALineAlongTheRow() {
        // w to o passes e and i: wo, not wei, which would lift a key short
        assertEquals("wo", glide.decode(path("wo"), GlideLayout.QWERTY, 3).first().keys)
        assertEquals("wei", glide.decode(path("wei"), GlideLayout.QWERTY, 3).first().keys)
    }

    @Test
    fun shuangpinGlidesItsSchemesKeysTheNineKeysTheLetters() {
        val scheme = ShuangpinScheme.ZIRANMA
        val keys = scheme.encode(Syllables.id("ni"))!! + scheme.encode(Syllables.id("hao"))!!
        val shuangpin = glide.decode(path(keys), GlideLayout.QWERTY, 3, PinyinGlide.Spelling.shuangpin(scheme)).first()
        // two keys a syllable, no boundary
        assertEquals(keys, shuangpin.keys)
        assertEquals("你好", shuangpin.word)
        val nine = GlideLayout.nineKeys()
        val t9 = glide.decode("nihao".flatMap { nine.centre(it).toList() }.toFloatArray(), nine, 3).first()
        assertEquals("ni'hao", t9.keys)
        assertEquals("你好", t9.word)
    }

    @Test
    fun eachReadingOnceAndNoneForNoPath() {
        val readings = glide.decode(path("nihao"), GlideLayout.QWERTY, 10).map { it.keys }
        assertEquals(readings.distinct(), readings)
        assertTrue(glide.decode(floatArrayOf(1f, 1f), GlideLayout.QWERTY, 3).isEmpty())
        assertTrue(glide.decode(path("nihao"), GlideLayout.QWERTY, 0).isEmpty())
    }

    @Test
    fun theUsersScoresCountAndABlockedWordIsNoReadingsAsTheVersionChanges() {
        val nihao = data.wordIndex.find("你好")
        var version = 0
        var blocked = false
        val user = PinyinGlide(data, user = { PinyinGlide.User(version, { w -> if (w == nihao) -1f else -2f }) { w, _ -> blocked && w == nihao } })
        // 你好's -1 the user's, over the model's -3
        val lifted = user.decode(path("nihao"), GlideLayout.QWERTY, 3).first { it.word == "你好" }.score
        assertTrue(lifted > glide.decode(path("nihao"), GlideLayout.QWERTY, 3).first { it.word == "你好" }.score)
        blocked = true
        version++
        assertTrue(user.decode(path("nihao"), GlideLayout.QWERTY, 10).none { it.word == "你好" })
    }

    @Test
    fun aLearnedWordScoredEachGlideTheRestOnceTillTheVersionChanges() {
        val nihao = data.wordIndex.find("你好")
        val asked = HashMap<Int, Int>()
        var score = -2f
        val user = PinyinGlide(
            data,
            user = {
                PinyinGlide.User(0, { w -> asked.merge(w, 1, Int::plus); if (w == nihao) score else -2f }, null, { w -> w == nihao }) { _, _ -> false }
            },
        )
        val first = user.decode(path("nihao"), GlideLayout.QWERTY, 3).first { it.word == "你好" }
        val once = HashMap(asked)
        score = -0.5f
        val second = user.decode(path("nihao"), GlideLayout.QWERTY, 3).first { it.word == "你好" }
        // 你好's count worked in again, the words the user has none of not
        assertEquals(2 * once.getValue(nihao), asked.getValue(nihao))
        assertEquals(once - nihao, asked - nihao)
        assertEquals(1.5f, second.score - first.score, 1e-4f)
    }

    @Test
    fun onlyANodeWithALearnedWordIsGoneThroughAgainEachGlide() {
        val nihao = data.wordIndex.find("你好")
        var learned = setOf<Int>()
        var asked = 0
        var lift = -3f
        val user = PinyinGlide(
            data,
            user = { PinyinGlide.User(learned, { w -> if (w == nihao) lift else -2f }, null, { w -> asked++; w in learned }) { _, _ -> false } },
        )
        user.decode(path("nihao"), GlideLayout.QWERTY, 3)
        val all = asked
        assertTrue(all > 0)
        asked = 0
        // nothing counted: no node's words asked about again
        user.decode(path("nihao"), GlideLayout.QWERTY, 3)
        assertEquals(0, asked)
        learned = setOf(nihao)
        user.decode(path("nihao"), GlideLayout.QWERTY, 3)
        asked = 0
        user.decode(path("nihao"), GlideLayout.QWERTY, 3)
        // ni'hao's node again, not every node the path reaches
        assertTrue(asked in 1 until all)
        // and what it offers moves with the learned word's count, the version as it was
        val before = user.decode(path("nihao"), GlideLayout.QWERTY, 3).first { it.word == "你好" }.score
        lift = -1f
        assertTrue(user.decode(path("nihao"), GlideLayout.QWERTY, 3).first { it.word == "你好" }.score > before)
    }

    @Test
    fun aWordOnlyTheUserHasGlides() {
        val woai = data.vocabulary.size
        // 我爱 at wo'ai: the dictionary has wo, not wo'ai
        val words = object : UserWords {
            override fun child(node: Int, syllable: Int) = when {
                node == 0 && syllable == Syllables.id("wo") -> 1
                node == 1 && syllable == Syllables.id("ai") -> 2
                else -> -1
            }
            override fun childCount(node: Int) = if (node < 2) 1 else 0
            override fun wordCount(node: Int) = if (node == 2) 1 else 0
            override fun word(node: Int, index: Int) = woai
            override fun text(word: Int) = "我爱"
        }
        var blocked = false
        val user = PinyinGlide(data, user = { PinyinGlide.User(blocked, { w -> if (w == woai) -1f else -2f }, words, null) { w, _ -> blocked && w == woai } })
        assertTrue(glide.decode(path("woai"), GlideLayout.QWERTY, 10).none { it.word == "我爱" })
        assertTrue(user.decode(path("woai"), GlideLayout.QWERTY, 10).any { it.keys == "wo'ai" && it.word == "我爱" })
        // the dictionary's wo is still the dictionary's
        assertEquals("我", user.decode(path("wo"), GlideLayout.QWERTY, 3).first().word)
        blocked = true
        assertTrue(user.decode(path("woai"), GlideLayout.QWERTY, 10).none { it.word == "我爱" })
    }
}
