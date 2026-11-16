/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.PINYIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.SHUANGPIN
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.WUBI
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.TestCharLevels
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.phrase.Chaizi
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.stroke.Strokes
import org.fcitx.fcitx5.android.engine.user.CharGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生僻字屏蔽's contract (dev/design/061-rare-char-block.md §8): a character held back is in no
 * candidate of any way to type Chinese, nor a prediction, nor what a code commits alone; let
 * through, back on the same engines, as a block is (the user model and the glide live over
 * invalidate()). The gate made directly holds back 的 and 是, which no choice can; the default
 * choice (扩展 B alone) holds 𤝰 (U+24770), two chars of a string: what goes by `Char` fails there.
 */
class RareCharContractTest : EnginesFixture() {

    private val ext = String(Character.toChars(TestCharLevels.EXT)) // 𤝰
    private val out = String(Character.toChars(TestCharLevels.OUT)) // 龘
    private val biang = "𰻞" // 𰻞, U+30EDE: biang has no other character

    init {
        pinyin = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("大", -2f, 0f).entry("大", syl("da"))
            .unigram("打", -2.5f, 0f).entry("打", syl("da"))
            .unigram(out, -3f, 0f).entry(out, syl("da"))
            .unigram(ext, -3.5f, 0f).entry(ext, syl("da"))
            .unigram("大$out", -2.5f, 0f).entry("大$out", syl("da", "da"))
            .unigram("的", -1f, 0f).entry("的", syl("de"))
            .unigram("得", -2f, 0f).entry("得", syl("de"))
            .unigram("是", -1.5f, 0f).entry("是", syl("shi"))
            .unigram("事", -2f, 0f).entry("事", syl("shi"))
            .unigram("你", -2f, 0f).entry("你", syl("ni"))
            .unigram("好", -2.5f, 0f).entry("好", syl("hao"))
            .unigram(biang, -5f, 0f).entry(biang, syl("biang"))
            // niao likelier than ni'hao: a path over n-i-h-a-o reads 鸟 first, 你𤝰 another reading's word
            .unigram("鸟", -1f, 0f).entry("鸟", syl("niao"))
            .unigram("你$ext", -3f, 0f).entry("你$ext", syl("ni", "hao"))
            // after 打, 龘 and 𤝰 the likeliest: what a prediction offers first
            .bigram("打", out, -0.1f, 0f)
            .bigram("打", ext, -0.1f, 0f)
            .build().toByteArray()
        wubiData = CodeTable.Builder()
            .header("键码", "abcdefghijklmnopqrstuvwxy")
            .header("码长", "4")
            .rule("e2", "p11+p12+p21+p22")
            // 龘 alone under its code; 大 and 𤝰 重码 under theirs
            .entry("dddd", out)
            .entry("ddda", "大")
            .entry("ddda", ext)
            .entry("dddb", "的")
            .entry("dddb", "得")
            // a glide's: 龘 alone under one code, 打 under the code a key away
            .entry("dfgh", out)
            .entry("dfgj", "打")
            .build().toByteArray()
        strokes = Strokes.read("...\n大\thpn\n$out\thpnhpn\n".reader().buffered(), "stroke").build().toByteArray()
        chaizi = Chaizi.format(mapOf("dalong" to listOf(out, ext, "大"))).toByteArray()
        charLevels = TestCharLevels.bytes
    }

    // the user's file, as the app hands it over at each reload
    private var file = ""

    private fun engines() = Engines(
        ::load, MemoryFiles(), warmUps = inPlace,
        additions = { Engines.Additions("", "", dictionary = { null }, rareChars = file) },
    ).also {
        // what is not filtered by design (dates, phrases, emoji) kept out of what is looked at
        it.settings = EngineSettings(builtInPhrases = false, emoji = false, tables = mapOf("Wubi" to TableSettings(autoSelect = true)))
    }

    private fun Engines.texts(im: String) = candidates(im, 0, 30).map { it.text }

    private fun Engines.typed(im: String, keys: String): List<String> {
        onEvent(im, EngineEvent.RESET, 0)
        type(im, keys)
        return texts(im)
    }

    private fun Engines.glided(keys: String): List<String> {
        onEvent(PINYIN, EngineEvent.RESET, 0)
        val path = Glide(keys.flatMap { GlideLayout.QWERTY.centre(it).toList() }.toFloatArray(), GlideLayout.QWERTY)
        onEvent(PINYIN, EngineEvent.CHAR, Keyboard.GLIDE, payload = checkNotNull(glide(PINYIN, path)))
        return texts(PINYIN)
    }

    // a glide over a table's keys ([im]'s codes, TableGlide): what it commits alone, then its candidates
    private fun Engines.tableGlided(im: String, keys: String): List<String> {
        onEvent(im, EngineEvent.RESET, 0)
        val path = Glide(keys.flatMap { GlideLayout.QWERTY.centre(it).toList() }.toFloatArray(), GlideLayout.QWERTY)
        val commit = onEvent(im, EngineEvent.CHAR, Keyboard.GLIDE, payload = checkNotNull(glide(im, path))).commit
        return listOf(commit).filter { it.isNotEmpty() } + texts(im)
    }

    // the pad's [positions] written, a reading list each: what it shows to put in (the sentence) and the candidates
    private var preedit = ""

    // the pad numbers its pieces on: one written before a reset is ended, never read again
    private var piece = 0

    private fun Engines.written(vararg positions: List<String>): List<String> {
        onEvent(PINYIN, EngineEvent.RESET, 0)
        for (texts in positions) {
            val ink = Action.Ink(piece++, InkReading(texts, DoubleArray(texts.size) { -it.toDouble() }))
            preedit = onEvent(PINYIN, EngineEvent.CHAR, Keyboard.INK, payload = ink).preedit
        }
        return texts(PINYIN)
    }

    // the candidates after 打 is put in: the predictions
    private fun Engines.afterDa(): List<String> {
        context(PINYIN, "", cleared = true)
        typed(PINYIN, "da")
        onEvent(PINYIN, EngineEvent.PICK, texts(PINYIN).indexOf("打"))
        return texts(PINYIN)
    }

    // the nine keys' codes, handed to pinyin as the keyboard hands them
    private fun Engines.nineKeys(digits: String): List<String> {
        onEvent(PINYIN, EngineEvent.RESET, 0)
        digits.forEach { onEvent(PINYIN, EngineEvent.CHAR, Keyboard.pinyinNineKey(it)) }
        return texts(PINYIN)
    }

    private fun none(held: String, of: List<String>) = assertFalse("$held in $of", of.any { held in it })

    private fun some(held: String, of: List<String>) = assertTrue("$held not in $of", of.any { held in it })

    // every way to type Chinese the fixture has, none offering [held]
    private fun Engines.offersNone(held: String, pinyin: String, strokes: String, parts: String, code: String) {
        none(held, typed(PINYIN, pinyin))
        none(held, typed(SHUANGPIN, pinyin))
        none(held, nineKeys("32"))
        none(held, glided(pinyin))
        none(held, typed(PINYIN, "u$strokes"))
        none(held, typed(PINYIN, "u$parts"))
        none(held, typed(WUBI, code))
    }

    @Test
    @Uat("WD-53")
    fun aGateMadeDirectlyHoldsBackEvenTheCommonest() = engines().use { e ->
        // 龘 too, for the strokes: their table has a character of the basic plane alone
        e.rareGate = CharGate.of { it == '的'.code || it == '是'.code || it == TestCharLevels.EXT || it == TestCharLevels.OUT }
        e.reload()
        assertRows(
            "的 nowhere in de's candidates" to { none("的", e.typed(PINYIN, "de")) },
            "得, its homophone, first instead" to { assertEquals("得", e.typed(PINYIN, "de").first()) },
            "是 nowhere in shi's" to { none("是", e.typed(PINYIN, "shi")) },
            "a sentence of it has its homophone in its place, not none: dashi 大事" to { assertEquals("大事", e.typed(PINYIN, "dashi").first()) },
            "𤝰, beyond the basic plane, nowhere in da's" to { none(ext, e.typed(PINYIN, "da")) },
            "nor in 双拼's" to { none(ext, e.typed(SHUANGPIN, "da")) },
            "nor in the nine keys'" to { none(ext, e.nineKeys("32")) },
            "龘 not among the strokes', 大 there" to {
                none(out, e.typed(PINYIN, "uhpn"))
                some("大", e.typed(PINYIN, "uhpn"))
            },
            "nor the parts'" to { none(ext, e.typed(PINYIN, "udalong")) },
            "nor a glide's" to { none(ext, e.glided("da")) },
            "nor 五笔's, its 重码 大 left, which commits itself" to {
                e.onEvent(WUBI, EngineEvent.RESET, 0)
                assertEquals("大", e.type(WUBI, "ddda").commit)
            },
            "的 held in 五笔 too: 得 alone commits itself" to {
                e.onEvent(WUBI, EngineEvent.RESET, 0)
                assertEquals("得", e.type(WUBI, "dddb").commit)
            },
            "nor a prediction" to { none(ext, e.afterDa()) },
            "nor the pad's reading" to { none(ext, e.written(listOf(ext, "大"))) },
        )
    }

    @Test
    fun theDefaultChoiceHoldsBackExtensionBAndTheOutOfTableAsChosen() = engines().use { e ->
        assertRows(
            "off: 𤝰 and 龘 offered, 你𤝰 as a glide's other reading" to {
                some(ext, e.typed(PINYIN, "da"))
                some(out, e.typed(PINYIN, "da"))
                some(ext, e.glided("nihao"))
            },
            "turned on, 扩展 B alone: 𤝰 nowhere, 龘 and 大 still there" to {
                file = "# youmo-rare-chars 1\non\t1\ngroups\tEXT\n"
                e.reload()
                e.offersNone(ext, "da", "hpn", "dalong", "ddda")
                none(ext, e.glided("nihao"))
                some(out, e.typed(PINYIN, "da"))
                some("大", e.typed(PINYIN, "da"))
            },
            "OUT too: 龘 and the word with it nowhere, as an 打's prediction" to {
                file = "# youmo-rare-chars 1\non\t1\ngroups\tOUT EXT\n"
                e.reload()
                e.offersNone(out, "da", "hpn", "dalong", "dddd")
                none(out, e.typed(PINYIN, "dada"))
                none(out, e.afterDa())
            },
            "a code whose one character is held commits nothing, offers nothing" to {
                e.onEvent(WUBI, EngineEvent.RESET, 0)
                val s = e.type(WUBI, "dddd")
                assertEquals("", s.commit)
                assertEquals(emptyList<String>(), e.texts(WUBI))
            },
            "龘 let through: back, the same engines" to {
                file = "# youmo-rare-chars 1\non\t1\ngroups\tOUT EXT\nallow\t$out\n"
                e.reload()
                some(out, e.typed(PINYIN, "da"))
                some(out, e.glided("da"))
                some(out, e.afterDa())
                e.onEvent(WUBI, EngineEvent.RESET, 0)
                assertEquals(out, e.type(WUBI, "dddd").commit)
            },
            "held again: gone again, keys and glide both" to {
                file = "# youmo-rare-chars 1\non\t1\ngroups\tOUT EXT\n"
                e.reload()
                none(out, e.typed(PINYIN, "da"))
                none(out, e.glided("da"))
            },
            "off: both back" to {
                file = "# youmo-rare-chars 1\non\t0\ngroups\tOUT EXT\n"
                e.reload()
                some(out, e.typed(PINYIN, "da"))
                some(ext, e.glided("da"))
            },
        )
    }

    @Test
    fun aTablesGlideAndTheUsersOwnWordLeaveOutWhatIsHeld() = engines().use { e ->
        e.addWord("大$ext", "da da")
        assertRows(
            "off: 𤝰 a glide over 五笔's ddda offers, 龘 one over dfgh, and the user's 大𤝰 typed" to {
                some(ext, e.tableGlided(WUBI, "ddda"))
                some(out, e.tableGlided(WUBI, "dfgh"))
                some("大$ext", e.typed(PINYIN, "dada"))
            },
            "on: neither, 大 still the glide's (its 重码 gone, committed alone)" to {
                file = "# youmo-rare-chars 1\non\t1\n"
                e.reload()
                val glided = e.tableGlided(WUBI, "ddda")
                none(ext, glided)
                some("大", glided)
                none(ext, e.typed(PINYIN, "dada"))
            },
            "the user's word kept, not offered" to { assertTrue(e.userWords().any { it.text == "大$ext" }) },
            "OUT too: a glide over dfgh, 龘's code alone, goes on to the next code's 打, not to nothing" to {
                file = "# youmo-rare-chars 1\non\t1\ngroups\tOUT EXT\n"
                e.reload()
                val glided = e.tableGlided(WUBI, "dfgh")
                none(out, glided)
                some("打", glided)
            },
        )
    }

    @Test
    fun aSyllableAllOfWhoseCharactersAreHeldBreaksNothing() = engines().use { e ->
        file = "# youmo-rare-chars 1\non\t1\n"
        e.reload()
        val got = e.typed(PINYIN, "nibianghao")
        none(biang, got)
        assertTrue(got.toString(), got.isNotEmpty())
        none(biang, e.typed(PINYIN, "biang"))
    }

    @Test
    @Uat("WD-54")
    fun theHandwritingPadReadsAroundWhatIsHeld() = engines().use { e ->
        file = "# youmo-rare-chars 1\non\t1\n"
        e.reload()
        assertRows(
            "a reading of no word id held, the next first" to { assertEquals("大", e.written(listOf(ext, "大")).first()) },
            "the middle of three all held: the two either side" to {
                val got = e.written(listOf("你"), listOf(ext), listOf("好"))
                assertEquals("你好", preedit)
                none(ext, got)
            },
            "one position all held: nothing" to {
                assertEquals(emptyList<String>(), e.written(listOf(ext)))
                assertEquals("", preedit)
            },
        )
    }

    @Test
    fun theWordsHeldAreFoundOnceForTheSameDataAndChoice() = engines().use { e ->
        file = "# youmo-rare-chars 1\non\t1\n"
        e.reload()
        e.typed(PINYIN, "da")
        val made = e.rareHeldMade
        assertRows(
            "found once" to { assertEquals(1, made) },
            "settings changed twice (each makes the sessions anew): not again" to {
                e.settings = e.settings.copy(pageSize = 7)
                e.typed(PINYIN, "da")
                e.settings = e.settings.copy(pageSize = 8)
                e.typed(PINYIN, "da")
                assertEquals(1, e.rareHeldMade)
            },
            "reloaded, the same file: not again" to {
                e.reload()
                e.typed(PINYIN, "da")
                assertEquals(1, e.rareHeldMade)
            },
            "the choice changed: again" to {
                file = "# youmo-rare-chars 1\non\t1\ngroups\tOUT EXT\n"
                e.reload()
                e.typed(PINYIN, "da")
                assertEquals(2, e.rareHeldMade)
            },
        )
    }

    // pinyin's 18 keys' codes for [letters], as the keyboard hands them
    private fun Engines.keys18(letters: String): List<String> {
        onEvent(PINYIN, EngineEvent.RESET, 0)
        letters.forEach { onEvent(PINYIN, EngineEvent.CHAR, Keyboard.pinyin18Key(NineKeyLetters.G18.key(it))) }
        return texts(PINYIN)
    }

    /**
     * 0.6.1 测试版 1's report (dev/design/061-rare-switch-research.md): one choice for every keyboard, so
     * going from the nine keys to the full one and the 18 keys, each a session of its own
     * ([KeyboardPair]), with no reload between, holds back the same once a reload made the sessions anew,
     * and it is found once.
     */
    @Test
    @Uat("WD-61")
    fun theChoiceOutlivesAKeyboardSwitch() = engines().use { e ->
        assertRows(
            "off: 龘 on each keyboard (the sessions made now, before it is on)" to {
                some(out, e.nineKeys("32"))
                some(out, e.typed(PINYIN, "da"))
                some(out, e.keys18("da"))
            },
            "turned on while on the nine keys: 龘 and 𤝰 not there" to {
                file = "# youmo-rare-chars 1\non\t1\ngroups\tOUT EXT\n"
                e.reload()
                none(out, e.nineKeys("32"))
                none(ext, e.nineKeys("32"))
            },
            "the full keyboard next, no reload: not there either" to {
                none(out, e.typed(PINYIN, "da"))
                none(ext, e.typed(PINYIN, "da"))
                some("大", e.typed(PINYIN, "da"))
            },
            "the 18 keys, then the nine keys again, then the full one: none" to {
                none(out, e.keys18("da"))
                none(out, e.nineKeys("32"))
                none(out, e.typed(PINYIN, "da"))
            },
            "the words held found once for all the keyboards" to { assertEquals(1, e.rareHeldMade) },
        )
    }

    @Test
    fun voiceAsksWhichOfItsTextsAreHeld() = engines().use { e ->
        assertEquals(emptySet<String>(), e.rareHeld(listOf(ext, "大")))
        file = "# youmo-rare-chars 1\non\t1\n"
        e.reload()
        assertEquals(setOf(ext, "大$ext"), e.rareHeld(listOf(ext, "大", "大$ext")))
        // the user's word with it is still the user's: only voice leaves it out (VoiceHotwords.ofUser)
        e.addWord("大$ext", "da da")
        assertTrue(e.userWords().any { it.text == "大$ext" && it.kind == UserWord.Kind.ADDED })
    }

    @Test
    fun theLevelsUnreadIsOffToldOnce() {
        charLevels = ByteArray(16)
        file = "# youmo-rare-chars 1\non\t1\n"
        val errors = ArrayList<java.io.IOException>()
        Engines(
            ::load, MemoryFiles(), warmUps = inPlace, onError = { errors += it },
            additions = { Engines.Additions("", "", dictionary = { null }, rareChars = file) },
        ).use { e ->
            some(ext, e.typed(PINYIN, "da"))
            e.reload()
            some(ext, e.typed(PINYIN, "da"))
            assertEquals(errors.toString(), 1, errors.count { InputMethodCatalog.CHAR_LEVELS_DATA in it.message.orEmpty() })
        }
    }
}
