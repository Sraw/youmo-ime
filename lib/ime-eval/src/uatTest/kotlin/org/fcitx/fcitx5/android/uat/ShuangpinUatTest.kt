/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.glide.GlideLayout
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.pinyin.GroupedShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinSegmenter
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.rerank.Reranker
import org.fcitx.fcitx5.android.engine.rerank.SentenceModel
import org.fcitx.fcitx5.android.engine.runtime.EngineSettings
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.session.Action
import org.fcitx.fcitx5.android.engine.session.PinyinSession
import org.fcitx.fcitx5.android.engine.user.UserModel
import org.fcitx.fcitx5.android.eval.EvalSet
import org.fcitx.fcitx5.android.eval.EvalUser
import org.fcitx.fcitx5.android.eval.PinyinRun
import org.fcitx.fcitx5.android.eval.ShuangpinSet
import org.fcitx.fcitx5.android.eval.TouchSim
import org.fcitx.fcitx5.android.eval.TypedHistory
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** uat/07-shuangpin.md: each case from B0-双拼 (自然码 unless the case sets another 方案). */
class ShuangpinUatTest {

    @Test
    @Uat("SP-02")
    fun xiaohe() = with(EngineReplay(settings(EngineSettings(shuangpin = "Xiaohe")), im = InputMethodCatalog.SHUANGPIN)) {
        type("nihc")
        space()
        expectText("你好")
    }

    @Test
    @Uat("SP-04")
    fun aSyllableWithNoInitial() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        type("aj")
        expectAmong("安")
    }

    @Test
    @Uat("SP-05")
    fun twoKeysNoSyllableAreInitials() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        type("fx")
        expectPreedit("f x")
        assertTrue("fx: $page", "发现" in page || "方向" in page)
        backspace(2)
        type("vg")
        expectPreedit("zheng")
    }

    // 编码行显示双拼按键: the keys on 26 keys, the scheme's letters read on the grouped ones; off, the pinyin
    @Test
    @Uat("SP-60", "SP-61")
    fun theCodeShowsTheKeysPressedOnEveryKeyboardWhenSet() {
        for (letters in listOf<NineKeyLetters?>(null) + GROUPED) {
            with(EngineReplay(settings(EngineSettings(shuangpinKeysShown = true)), im = InputMethodCatalog.SHUANGPIN)) {
                if (letters == null) type("nihk") else typeOn(letters, "nihk")
                val shown = preedit.filter { it != ' ' }
                // 14 keys and nine read another pair first (不好 bu hk): its letters, each on the key pressed
                if (letters == null || letters == NineKeyLetters.G18) assertEquals("$letters", "ni hk", preedit)
                assertEquals(preedit, 4, shown.length)
                if (letters != null) assertTrue(preedit, shown.indices.all { shown[it] in letters.letters(letters.key("nihk"[it])) })
            }
            if (letters == null || letters == NineKeyLetters.G18) {
                with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
                    if (letters == null) type("nihk") else typeOn(letters, "nihk")
                    assertEquals("$letters off", "ni hao", preedit)
                }
            }
        }
    }

    @Test
    @Uat("SP-07")
    fun aWordMadeInFullPinyinTypesInShuangpinItsOrderItsOwn() = with(EngineReplay()) {
        learnWangXiaoming()
        ime(InputMethodCatalog.SHUANGPIN)
        type("whxcmy")
        expectStart("王小明", "汪小明")
    }

    @Test
    @Uat("SP-09")
    fun everyKeyboardGlides() {
        with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
            glide("nihk")
            assertEquals("ni hao", preedit.replace("'", " "))
            expectAmong("你好")
        }
        with(EngineReplay(im = InputMethodCatalog.WUBI)) {
            glide("wqiy")
            assertTrue("「$preedit」 ${candidates.take(3)} 「$text」", (preedit == "wqiy" && candidates.firstOrNull() == "你") || text == "你")
        }
        with(EngineReplay(im = InputMethodCatalog.PINYIN)) {
            glide("nihao", GlideLayout.nineKeys())
            assertTrue("「$preedit」", preedit.isNotEmpty() && preedit.none(Char::isDigit))
            expectAmong("你好")
        }
    }

    // 简拼 in the middle (docs/pinyin.md「双拼」): a key read alone where it and the next make a syllable
    @Test
    @Uat("SP-30")
    fun aWordTypedWithItsFirstSyllableWholeAndTheRestByInitials() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        // 自然码 shen = uf, then m, sh, h: mu and uh are syllables, each key read alone only for a word typed before
        type("ufmuh")
        assertTrue("ufmuh, never typed: $page", "什么时候" !in candidates)
        focus("")
        type("ufmeuihb")
        pick("什么时候")
        focus("")
        // shen, then m, u and h each alone: the code reads no whole syllables to its end, so the word typed is first
        type("ufmuh")
        expectFirst("什么时候")
        focus("")
        // zh's key alone: v and z make zhei
        type("yivz")
        assertTrue("yivz, never typed: $page", "一直在" !in candidates)
        focus("")
        type("yivizl")
        pick("一直在")
        focus("")
        type("yivz")
        // 一直在 is no word of the dictionary: read as 一直 and 在, not as one word typed, at its place
        assertTrue("yivz: $page", "一直在" in page)
    }

    @Test
    @Uat("SP-31")
    fun twoKeysThatMakeASyllableStayThatSyllableFirst() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        // 自然码 xm is xian: 先 first, the code row xian, as before 简拼 in the middle
        type("xm")
        expectPreedit("xian")
        expectFirst("先")
        backspace(2)
        type("vg")
        expectPreedit("zheng")
    }

    @Test
    @Uat("SP-31")
    fun wholeSyllablesReadAsBeforeTheAbbreviations() {
        // pinyin-chat.tsv typed whole in each scheme and keyboard, with and without --sp-abbrev off: no right first made wrong,
        // at most one first changed (a grouped keyboard's sentence or so moves with no budget, which the user accepts)
        val chat = File("data/pinyin-chat.tsv").useLines { EvalSet.parse(it) }
        val model = SentenceModel.load(UatEngines.data(InputMethodCatalog.SENTENCE_MODEL))
        // a key alone reads the words typed before: the 3000 likeliest typed, both ways
        val typed = TypedHistory.likeliest(UatEngines.pinyinData, HISTORY)
        // each scheme and keyboard's, asserted once: a run takes minutes, and the first failure hid the rest
        val changedBy = LinkedHashMap<String, List<String>>()
        for ((name, scheme) in ShuangpinSet.SCHEMES) {
            val keyboards = listOf<NineKeyLetters?>(null) + ShuangpinSet.KEYS.values.filter { it != NineKeyLetters.G14 || ImKeyboards.has14(scheme) }
            for (letters in keyboards) {
                val samples = ShuangpinSet.convert(chat, scheme, letters)
                fun run(abbreviations: Boolean) = PinyinRun(
                    UatEngines.pinyinData,
                    if (letters == null) ShuangpinSegmenter(scheme, abbreviations = abbreviations) else GroupedShuangpinSegmenter(scheme, letters, abbreviations = abbreviations),
                    reranker = { Reranker(model) }, typed = typed,
                )
                val (off, on) = run(false) to run(true)
                assertTrue("$name $letters ${samples.size}", samples.size > 1500)
                val changed = samples.map { s -> Triple(s.expected, off.candidates(s.input, s.context).firstOrNull(), on.candidates(s.input, s.context).firstOrNull()) }
                    .filter { (_, was, now) -> was != now }
                // the grouped keys' inputs are private-use characters, so the texts say which
                if (changed.size > 1 || changed.any { (expected, was, _) -> was == expected }) {
                    changedBy["$name $letters"] = changed.map { (expected, was, now) -> "$expected: $was → $now" }
                }
            }
        }
        assertEquals(emptyMap<String, List<String>>(), changedBy.filterValues { it.isNotEmpty() })
        // each syllable's two keys alone in each scheme: the first never changes and page 1's first stays first but for a few
        val firstChangedBy = LinkedHashMap<String, List<String>>()
        val displacedBy = LinkedHashMap<String, String>()
        for (scheme in ShuangpinSet.SCHEMES.values + DROPPED) {
            fun twoKeys(abbreviations: Boolean) = PinyinRun(UatEngines.pinyinData, ShuangpinSegmenter(scheme, abbreviations = abbreviations), reranker = { Reranker(model) }, typed = typed)
            val (was0, now0) = twoKeys(false) to twoKeys(true)
            val codes = (0 until Syllables.count).mapNotNull { scheme.encode(it) }.distinct()
            val firstChanged = ArrayList<String>()
            val displaced = ArrayList<String>()
            for (code in codes) {
                val (was, now) = was0.candidates(code).take(EngineSettings.DEFAULT_PAGE_SIZE) to now0.candidates(code).take(EngineSettings.DEFAULT_PAGE_SIZE)
                if (was.firstOrNull() != now.firstOrNull()) firstChanged += "$code ${was.firstOrNull()} ${now.firstOrNull()}"
                if (now.take(was.size) != was) displaced += "$code $was $now"
            }
            firstChangedBy["$scheme"] = firstChanged
            if (displaced.size * 100 > codes.size) displacedBy["$scheme"] = "$displaced of ${codes.size}"
        }
        assertEquals(emptyMap<String, List<String>>(), firstChangedBy.filterValues { it.isNotEmpty() })
        assertEquals(emptyMap<String, String>(), displacedBy)
    }

    // a learning session for a user who typed the likeliest words, typed a key at a time, no sentence model
    @Test
    @Uat("SP-31")
    fun wholeSyllablesReadAsBeforeTheAbbreviationsInASessionThatLearns() {
        val chat = File("data/pinyin-chat.tsv").useLines { EvalSet.parse(it) }
        val data = UatEngines.pinyinData
        val user = EvalUser(data)
        assertTrue(TypedHistory.count(user.model, data, TypedHistory.likeliest(data, HISTORY)) >= HISTORY)
        // each scheme and keyboard's, asserted once
        val changedBy = LinkedHashMap<String, List<String>>()
        val pagedBy = LinkedHashMap<String, String>()
        for ((name, scheme) in ShuangpinSet.SCHEMES) {
            val keyboards = listOf<NineKeyLetters?>(null) + ShuangpinSet.KEYS.values.filter { it != NineKeyLetters.G14 || ImKeyboards.has14(scheme) }
            for (letters in keyboards) {
                fun session(abbreviations: Boolean) = PinyinSession(
                    data,
                    if (letters == null) ShuangpinSegmenter(scheme, abbreviations = abbreviations) else GroupedShuangpinSegmenter(scheme, letters, abbreviations = abbreviations),
                    user = user.model, prediction = false, learnTo = user.learnTo(Kinds.shuangpin(name)),
                )
                val (off, on) = session(false) to session(true)
                fun shown(s: PinyinSession, input: String, context: String): List<String> {
                    s.apply(if (context.isEmpty()) Action.Reset else Action.Context(context))
                    return input.map { s.apply(Action.Key(it)) }.last().candidates.take(EngineSettings.DEFAULT_PAGE_SIZE).also { s.apply(Action.Reset) }
                }
                val pairs = (0 until Syllables.count).mapNotNull { scheme.encode(it) }.distinct().map { code -> letters?.let { l -> code.map(l::key).joinToString("") } ?: code }
                val inputs = pairs.distinct().map { it to "" } + ShuangpinSet.convert(chat, scheme, letters).take(CHAT_IN_SESSION).map { it.input to it.context }
                val changed = ArrayList<String>()
                var paged = 0
                for ((input, context) in inputs) {
                    val (was, now) = shown(off, input, context) to shown(on, input, context)
                    if (was.firstOrNull() != now.firstOrNull()) changed += "$input $was $now"
                    if (now != was) paged++
                }
                changedBy["$name $letters"] = changed
                // the typed words read by keys alone are there: the order made over them was asked
                pagedBy["$name $letters"] = if (paged > 0) "" else "no page of ${inputs.size} changed"
            }
        }
        assertEquals(emptyMap<String, List<String>>(), changedBy.filterValues { it.isNotEmpty() })
        assertEquals(emptyMap<String, String>(), pagedBy.filterValues { it.isNotEmpty() })
    }

    @Test
    @Uat("SP-32")
    fun aWordTypedByItsInitialsOnly() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        // 自然码 sh m sh h: mu and uh are syllables, each key read alone too; a word typed before only
        type("umuh")
        assertTrue("umuh, never typed", "什么时候" !in candidates)
        focus("")
        type("ufmeuihb")
        pick("什么时候")
        focus("")
        // u alone at the start: the code reads no whole syllables to its end, so the word typed is first
        type("umuh")
        expectFirst("什么时候")
    }

    @Test
    @Uat("SP-33")
    fun aWordTypedPartlyByInitialsIsLearnedUnderThoseKeys() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        // what the syllables read first, nothing read by keys alone: the habits off
        engines.useHabits = false
        type("ufmuh")
        val syllables = page.first()
        focus("")
        engines.useHabits = true
        // typed in whole syllables once: first by keys alone from then on, what the syllables read next
        type("ufmeuihb")
        pick("什么时候")
        focus("")
        type("ufmuh")
        expectFirst("什么时候")
        assertEquals("ufmuh: $page", 1, page.indexOf(syllables))
        // picked twice under these keys it is first there again, learned under those keys only
        repeat(2) {
            focus("")
            type("ufmuh")
            pick(syllables)
        }
        focus("")
        type("ufmuh")
        expectFirst(syllables)
        backspace(5)
        assertTrue("删光: $preedit", preedit.isEmpty())
        type("umuh")
        expectFirst("什么时候")
    }

    // a code that reads as whole syllables keeps its first, the words typed by its keys alone right after it
    @Test
    @Uat("SP-34", "SP-55", "SP-59")
    fun aWordUsedInFullGoesSecondUnderAPairItsInitialsSpell() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        // 自然码 xm is xian, xwmm xia mian: 下面 typed is second under xm, first under x'm
        type("xm")
        val fresh = page
        repeat(2) {
            focus("")
            type("xwmm")
            pick("下面")
        }
        focus("")
        type("xm")
        assertEquals(fresh.first(), page.first())
        assertEquals("xm: $page", 1, page.indexOf("下面"))
        focus("")
        // x then m begun reads the whole dictionary, as before: the word typed first, one never typed still there
        type("x'm")
        expectFirst("下面")
        assertTrue("x'm: $candidates", "小米" in candidates)
    }

    @Test
    @Uat("SP-35")
    fun aWordAddedByHandGoesSecondUnderAPairItsInitialsSpell() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        type("xm")
        val fresh = page
        focus("")
        assertTrue(engines.addWord("小明", "xiao ming"))
        type("xm")
        assertEquals(fresh.first(), page.first())
        assertEquals("xm: $page", 1, page.indexOf("小明"))
        focus("")
        type("xcmy")
        expectFirst("小明")
        focus("")
        type("x'm")
        expectFirst("小明")
    }

    @Test
    @Uat("SP-36", "SP-55")
    fun whatIsPinnedOrPickedUnderACodeReadWholeIsRankedThere() {
        // 自然码 ky is kuai; 可以 by k and y alone, once typed (keyi): right after kuai's first
        with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
            type("ky")
            assertTrue("ky, never typed", "可以" !in candidates)
            focus("")
            type("keyi")
            pick("可以")
            focus("")
            type("ky")
            assertEquals("ky: ${candidates.take(10)}", 1, candidates.indexOf("可以"))
            assertTrue(learn(LearningOp.Pin(1, "可以")) is LearningResult.Done)
            focus("")
            type("ky")
            expectFirst("可以")
        }
        with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
            type("keyi")
            pick("可以")
            // picked once it stays second, twice it is first
            focus("")
            type("ky")
            pick("可以")
            focus("")
            type("ky")
            assertEquals("ky: ${candidates.take(10)}", 1, candidates.indexOf("可以"))
            pick("可以")
            focus("")
            type("ky")
            expectFirst("可以")
        }
    }

    // 自然码 什么时候 by its initials once typed, a key tapped by its edge read as its neighbour too; never typed, no such reading
    @Test
    @Uat("SP-41")
    fun initialsInTheMiddleReadTheWordsTypedWhereverTapped() {
        val data = UatEngines.pinyinData
        val board = TouchSim.board("qwerty").boxes
        fun shown(user: UserModel, dx: Float): List<String> {
            val s = PinyinSession(data, ShuangpinSegmenter(ShuangpinScheme.ZIRANMA, neighbours = true), pageSize = 40, prediction = false, user = user)
            return "ufmuh".map { c -> s.apply(Action.Key(c, touch = board.touch(c, 0f, 0f)!!.copy(dx = if (c == 'm') dx else 0f, dy = 0f))) }.last().candidates
        }
        val fresh = UserModel(data.dictionary, data.vocabulary, index = data.wordIndex)
        val user = UserModel(data.dictionary, data.vocabulary, index = data.wordIndex)
        assertEquals(1, TypedHistory.count(user, data, listOf("什么时候\tshen me shi hou")))
        for (dx in listOf(0f, -EDGE)) {
            val before = shown(fresh, dx)
            assertTrue("$dx: $before", "什么时候" !in before)
            val typed = shown(user, dx)
            // after the syllables the keys make (fm is fan, mu is mu), on the first page
            assertEquals("$dx", before.first(), typed.first())
            assertTrue("$dx: $typed", typed.indexOf("什么时候") in 1 until EngineSettings.DEFAULT_PAGE_SIZE)
        }
    }

    // the user's own words are typed ones for good: read by a key alone with no count
    @Test
    @Uat("SP-43")
    fun aWordAddedByHandIsReadByAKeyAloneAtOnce() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        // 自然码 x, then my (ming); xm is xian
        type("xmy")
        assertTrue("xmy, before", "小明" !in candidates)
        focus("")
        assertTrue(engines.addWord("小明", "xiao ming"))
        type("xmy")
        // xian, then y begun reads to the end: the word added right after the first
        assertEquals("xmy: $page", 1, page.indexOf("小明"))
    }

    // 借手机: the habits off, nothing is typed before, and no key is read alone
    @Test
    @Uat("SP-46")
    fun withTheHabitsOffNoKeyIsReadAlone() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        type("ufmeuihb")
        pick("什么时候")
        focus("")
        type("ufmuh")
        expectFirst("什么时候")
        focus("")
        engines.useHabits = false
        type("ufmuh")
        assertTrue("ufmuh, habits off", "什么时候" !in candidates)
        focus("")
        type("ufmeuihb")
        expectFirst("什么时候")
        focus("")
        engines.useHabits = true
        type("ufmuh")
        expectFirst("什么时候")
    }

    private fun EngineReplay.pickOn(letters: NineKeyLetters, keys: String, text: String) {
        focus("")
        typeOn(letters, keys)
        pick(text)
        focus("")
    }

    // the 18, 14 and nine keys read a key alone where it and the next make a syllable, for typed words only
    @Test
    @Uat("SP-52", "SP-53", "SP-54", "SP-46", "SP-58")
    fun theGroupedKeyboardsReadAWordTypedByItsInitials() {
        for (letters in GROUPED) with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
            typeOn(letters, "ufmuh")
            assertTrue("$letters ufmuh, never typed: $candidates", "什么时候" !in candidates)
            pickOn(letters, "ufmeuihb", "什么时候")
            typeOn(letters, "ufmuh")
            expectFirst("什么时候")
            focus("")
            engines.useHabits = false
            typeOn(letters, "ufmuh")
            assertTrue("$letters ufmuh, habits off: $candidates", "什么时候" !in candidates)
            focus("")
            engines.useHabits = true
            // a pair read whole (k y is kuai): the word typed right after its first
            pickOn(letters, "keyi", "可以")
            typeOn(letters, "ky")
            assertEquals("$letters ky: $page", 1, page.indexOf("可以"))
            focus("")
            // x then m begun, the nine keys' 1 between: the word typed first
            repeat(2) { pickOn(letters, "xwmm", "下面") }
            typeOn(letters, "x'm")
            expectFirst("下面")
        }
    }

    // on the nine keys most codes read whole: sh m sh h is tian tang there, the word typed second
    @Test
    @Uat("SP-54")
    fun theNineKeysPutAWordTypedSecondUnderACodeTheyReadWhole() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        val nine = NineKeyLetters.NINE_SP
        pickOn(nine, "ufmeuihb", "什么时候")
        typeOn(nine, "umuh")
        assertEquals("umuh: $page", 1, page.indexOf("什么时候"))
    }

    // typing a word in whole syllables, its last key begun, a word typed all by initials goes second
    @Test
    @Uat("SP-56")
    fun aWordTypedByInitialsGoesSecondOnTheWayToAWordTypedInFull() {
        for (letters in listOf<NineKeyLetters?>(null) + GROUPED) with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
            fun keys(k: String) = if (letters == null) type(k) else typeOn(letters, k)
            fun picked(k: String, text: String) {
                focus("")
                keys(k)
                pick(text)
                focus("")
            }
            // 一日游 typed: y r y by initials only, first
            picked("yiriyb", "一日游")
            keys("yry")
            assertEquals("$letters yry: $page", 0, page.indexOf("一日游"))
            focus("")
            // 原因 typed too: yr is yuan, y begun on the way to it, 一日游 second
            picked("yryn", "原因")
            keys("yry")
            assertEquals("$letters yry: $page", 1, page.indexOf("一日游"))
        }
    }

    // 微软's ; is ing: x, the separator, then m; reads 小明 as before, the whole dictionary
    @Test
    @Uat("SP-58")
    fun microsoftsSemicolonAfterAKeyAloneReadsAsBefore() = with(EngineReplay(settings(EngineSettings(shuangpin = "MS")), im = InputMethodCatalog.SHUANGPIN)) {
        type("x'm;")
        expectOnFirstPage("小明")
    }

    // in 自然码: fghw twice lifts 奉化 under the part fgh (fh is fang there); full pinyin's uses do not
    @Test
    @Uat("SP-13b", "SP-13c")
    fun aWordTypedTwiceInFullGoesThirdUnderAPartWithinItsKeyboard() {
        with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
            fun shown(keys: String, pick: String?): List<String> {
                focus("他")
                type(keys)
                return candidates.also { if (pick != null) pick(pick) else backspace(keys.length) }
            }
            assertTrue("fresh", "奉化" !in shown("fgh", null).take(5))
            repeat(2) { shown("fghw", "奉化") }
            val part = shown("fgh", null)
            assertTrue("fgh $part", "奉化" in part.take(3))
        }
        with(EngineReplay()) {
            repeat(2) {
                focus("他")
                type("fenghua")
                pick("奉化")
            }
            ime(InputMethodCatalog.SHUANGPIN)
            focus("他")
            type("fgh")
            assertTrue("full pinyin's: $candidates", "奉化" !in candidates.take(5))
        }
    }

    // the scheme's letters as the 18 keys or the nine keys send them: each its key's code (spaces apart for reading)
    private fun on(letters: NineKeyLetters, keys: String) = keys.filter { it != ' ' }.map { letters.key(it) }.joinToString("")

    @Test
    @Uat("SP-14")
    fun theEighteenKeysInXiaoheTypeASentenceAndOfferTheSyllables() =
        with(EngineReplay(settings(EngineSettings(shuangpin = "Xiaohe")), im = InputMethodCatalog.SHUANGPIN)) {
            type(on(NineKeyLetters.G18, "wo"))
            assertTrue("${panel.syllables}", "wo" in panel.syllables)
            type(on(NineKeyLetters.G18, "mf jb tm qu ii fj"))
            assertTrue("$page", SENTENCE in page)
            pick(SENTENCE)
            expectText(SENTENCE)
            assertTrue(EngineReplay.noPrivateUse(text))
        }

    @Test
    @Uat("SP-15")
    fun theNineKeysInZiranmaTypeASentenceAndOfferTheSyllables() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        type(on(NineKeyLetters.NINE_SP, "wo"))
        assertTrue("${panel.syllables}", "wo" in panel.syllables)
        type(on(NineKeyLetters.NINE_SP, "mf jn tm qu ii fj"))
        assertTrue("$page", SENTENCE in page)
        pick(SENTENCE)
        expectText(SENTENCE)
        assertTrue(EngineReplay.noPrivateUse(text))
    }

    @Test
    @Uat("SP-16")
    fun microsoftsIngIsTheLSemicolonKey() =
        with(EngineReplay(settings(EngineSettings(shuangpin = "MS")), im = InputMethodCatalog.SHUANGPIN)) {
            type(on(NineKeyLetters.G18, "m;"))
            assertTrue("$page", "明" in page || "名" in page)
            backspace(2)
            type(on(NineKeyLetters.G18, "le"))
            assertTrue("$page", "了" in page)
        }

    @Test
    @Uat("SP-17")
    fun theEighteenKeysLearnApartFromTheFullKeyboard() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        val shi = on(NineKeyLetters.G18, "ui")
        val keys = typed(shi)
        val full = typed("ui").take(5)
        assertEquals(full.first(), keys.first())
        val second = keys[1]
        repeat(2) {
            typed(shi)
            pick(second)
            commit("。")
        }
        assertEquals(second, typed(shi).first())
        // learned under the 18 keys' own kind, not the full keyboard's
        val record = String(UatEngines.learningRecord(), Charsets.UTF_8)
        assertTrue(Kinds.shuangpinKeys("Ziranma", NineKeyLetters.G18.tag()) in record)
        // the full keyboard's order (sp:) as it was: nothing of the 18 keys' (spk:) learning
        assertEquals(full, typed("ui").take(5))
    }

    @Test
    @Uat("SP-18")
    fun enterOrASwitchCommitsTheReadingShownInLetters() = with(HostReplay(im = InputMethodCatalog.SHUANGPIN)) {
        keys(on(NineKeyLetters.G18, "xi"))
        val xi = preedit
        enter()
        assertEquals(xi.filter { it != ' ' && it != '\'' }, text)
        focus("")
        keys(on(NineKeyLetters.G18, "nih"))
        val nih = preedit
        ime(InputMethodCatalog.ENGLISH)
        assertEquals(nih.filter { it != ' ' && it != '\'' }, text)
        assertTrue(text, text.isNotEmpty() && text.all { it in 'a'..'z' })
    }

    @Test
    @Uat("SP-22")
    fun onTheNineKeysOneSeparatesTheSyllablesWhileTyping() = with(EngineReplay(im = InputMethodCatalog.SHUANGPIN)) {
        // 6 4, 1 (the keyboard's ' while typing: T9Keyboard.resolveShuangpin), 4 3
        type(on(NineKeyLetters.NINE_SP, "ni") + "'" + on(NineKeyLetters.NINE_SP, "hd"))
        assertTrue(preedit, '\'' in preedit)
    }

    @Test
    @Uat("SP-23")
    fun microsoftsScreenSemicolonIsAMarkAndAnInitialTakenKeepsItsSyllables() {
        with(HostReplay(engines = settings(EngineSettings(shuangpin = "MS")), im = InputMethodCatalog.SHUANGPIN)) {
            keys(on(NineKeyLetters.G18, "ni"))
            val first = candidates.first()
            // the long press's ; is the screen's character, not the L; key's code
            keys(";")
            expectText("$first；")
        }
        with(EngineReplay(settings(EngineSettings(shuangpin = "MS")), im = InputMethodCatalog.SHUANGPIN)) {
            type(on(NineKeyLetters.G18, "xi"))
            syllable("c")
            assertFalse("$candidates", "西" in candidates)
            assertTrue("${panel.syllables}", panel.syllables.isNotEmpty() && panel.syllables.all { it.startsWith("c") })
            assertTrue("${panel.syllables}", "ci" in panel.syllables || "cuo" in panel.syllables)
            // and the words are c-'s: the first few read ci or cuo, as 词, 次, 错
            assertTrue("$candidates", candidates.take(5).any { it in setOf("词", "次", "此", "错", "刺", "瓷") })
        }
    }

    private companion object {
        // the words typed before where a run needs some
        const val HISTORY = 3000

        // the single characters compared: full pinyin's jing puts 金 15th
        const val FIRST = 30

        // the chat samples a session types a key at a time, on each keyboard of each scheme, with and without keys alone
        const val CHAT_IN_SESSION = 150
        val GROUPED = listOf(NineKeyLetters.G18, NineKeyLetters.G14, NineKeyLetters.NINE_SP)

        // a tap this far of a key's width from its middle: by its edge
        const val EDGE = 0.42f

        const val SENTENCE = "我们今天去吃饭"

        // 紫光, 智能ABC and 国标, no longer offered (SchemeMigration): libime's tables, as ShuangpinSegmenterTest has them
        val DROPPED = listOf(
            ShuangpinScheme(
                "zh=u ch=a sh=i ai=p an=r ang=s ao=q ei=k en=w eng=t er=j ia=x ian=f iang=g iao=b ie=d in=y ing=; " +
                    "iong=h iu=j ong=h ou=z ua=x uai=y uan=l uang=g ue=n ui=n un=m uo=o ve=n v=v",
                "o",
            ),
            ShuangpinScheme(
                "zh=a ch=e sh=v ai=l an=j ang=h ao=k ei=q en=f eng=g er=r ia=d ian=w iang=t iao=z ie=x in=c ing=y " +
                    "iong=s iu=r ong=s ou=b ua=d uai=c uan=p uang=t ue=m ui=m un=n uo=o ve=m v=v",
                "o",
            ),
            ShuangpinScheme(
                "zh=v ch=i sh=u ai=k an=f ang=g ao=c ei=b en=r eng=h er=l ia=q ian=d iang=n iao=m ie=t in=l ing=j " +
                    "iong=s iu=y ong=s ou=p ua=q uai=y uan=w uang=n ue=x ui=v un=z uo=o ve=x v=v",
                "a",
            ),
        )
    }
}

// a scheme's [keys] as the keyboard sends them on [letters] (the 14 keys as 双拼's own keys); ' the separator
internal fun EngineReplay.typeOn(letters: NineKeyLetters, keys: String) = keys.forEach { c ->
    when {
        c == '\'' -> type("'")
        letters == NineKeyLetters.G14 -> key(EngineReplay.symOf(Keyboard.shuangpin14Key(letters.key(c))))
        else -> type(letters.key(c).toString())
    }
}
