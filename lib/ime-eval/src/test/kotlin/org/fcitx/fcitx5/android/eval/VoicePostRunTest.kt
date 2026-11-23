/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.data.ReadingSource
import org.fcitx.fcitx5.android.engine.data.syl
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.Blobs
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.eval.VoicePostRun.Mark
import org.fcitx.fcitx5.android.input.voice.VoiceChoice
import org.fcitx.fcitx5.android.input.voice.VoiceHomophones
import org.fcitx.fcitx5.android.input.voice.VoicePost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class VoicePostRunTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun wordsAreHanCharactersOrLatinRunsEachWithTheMarkAfterIt() {
        val t = VoicePostRun.tokens("我买了3.5斤 Apple，好吗？ok.")
        assertEquals(listOf("我", "买", "了", "3.5", "斤", "apple", "好", "吗", "ok"), t.words)
        assertEquals(listOf(null, null, null, null, null, Mark.COMMA, null, Mark.QUESTION, Mark.PERIOD), t.after)
    }

    // as the app's VoiceChoice.Talk: a stretch kept is the next one's before, one of spaces too (its context none)
    @Test
    fun theUsersWordsArePutInEachStretchAfterTheOneKeptBeforeItAsTheAppDoes() {
        val data = PinyinDataBuilder()
            .unigram("<unk>", -7f, 0f)
            .unigram("推导", -5f, 0f).entry("推导", syl("tui", "dao"))
            .unigram("这个", -3f, 0f).entry("这个", syl("zhe", "ge"))
            // 攻势 far less likely than 公式 alone, far likelier after 这个
            .unigram("公式", -4.7f, 0f).entry("公式", syl("gong", "shi"))
            .unigram("攻势", -9f, 0f).entry("攻势", syl("gong", "shi"))
            .bigram("这个", "攻势", -0.5f, 0f)
            .apply {
                for ((c, syllable, tone) in listOf(Triple('公', "gong", 1), Triple('攻', "gong", 1), Triple('式', "shi", 4), Triple('势', "shi", 4))) {
                    charTone(c.code, Syllables.id(syllable), tone)
                    charReading(ReadingSource.MANDARIN, c.code, Syllables.id(syllable), tone)
                }
            }
            .build()
            .toByteArray()
        val blobs = Blobs { path -> if (path == InputMethodCatalog.PINYIN_DATA) ByteBuffer.wrap(data) else throw FileNotFoundException(path) }
        Engines(blobs, null, warmUps = Executor { it.run() }).use { engines ->
            val gongshi = engines.voiceHomophones(listOf(UserWord("攻势", "gong shi", UserWord.Kind.ADDED)))
            val words = VoicePostRun.Words(engines, gongshi)
            fun picked(vararg raw: String) = words.picked(raw.map { VoicePost.Stretch(it) }).map { it.raw }
            // the same through the app's own Talk, each stretch chosen then kept: the copy of its rule cannot drift
            fun talked(vararg raw: String): List<String> {
                val language = object : VoiceChoice.Language {
                    override suspend fun boundaries(texts: List<String>) = emptyMap<String, IntArray>()
                    override suspend fun logProbs(texts: List<String>): FloatArray? = null
                    override suspend fun pick(homophones: VoiceHomophones, text: String, before: String, blocked: List<String>) =
                        engines.voicePick(homophones, text, before, blocked)
                }
                val talk = VoiceChoice.Talk(gongshi, emptyList(), language, launch = {}, show = {})
                return raw.map { r -> now { talk.chosen(r) }.also(talk::kept) }
            }
            assertRows(
                "after 推导这个: taken" to { assertEquals(listOf("推导这个", "攻势"), picked("推导这个", "公式")) },
                "after a stretch of a space: alone, left" to { assertEquals(listOf("推导这个", " ", "公式"), picked("推导这个", " ", "公式")) },
                "next to it, as the app's Talk" to { assertEquals(talked("推导这个", "公式"), picked("推导这个", "公式")) },
                "after a stretch of a space, as the app's Talk" to {
                    assertEquals(talked("推导这个", " ", "公式"), picked("推导这个", " ", "公式"))
                },
                "after an empty stretch, as the app's Talk" to { assertEquals(talked("推导这个", "", "公式"), picked("推导这个", "", "公式")) },
            )
        }
    }

    // a suspending call that never suspends, run on this thread
    private fun <T> now(block: suspend () -> T): T {
        var out: Result<T>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { out = it })
        return checkNotNull(out) { "suspended" }.getOrThrow()
    }

    @Test
    fun aSecondMarkInARowIsNotCountedAgain() {
        assertEquals(listOf(Mark.PERIOD), VoicePostRun.tokens("好。，").after)
    }

    @Test
    fun theMarksInsideAreScoredByKindTheEndApart() {
        val s = VoicePostRun.Score()
        // ref: 。 inside, ， inside, 。 at the end; hyp: ， where the 。 was, nothing where the ， was, no end mark
        VoicePostRun.score("今天下雨。我们不去，在家。", "今天下雨，我们不去在家", s)
        assertEquals(0, s.wordErrors)
        assertEquals(0, s.tp.sum())
        assertEquals(1, s.fp[Mark.COMMA.ordinal])
        assertEquals(1, s.fn[Mark.PERIOD.ordinal])
        assertEquals(1, s.fn[Mark.COMMA.ordinal])
        assertEquals(1, s.refEnds)
        assertEquals(0, s.hypEnds)
        assertEquals(0, s.endsSame)
        // written: 3 marks missed or wrong of 10 words and 3 marks
        assertEquals(3, s.writtenErrors)
        assertEquals(13, s.refWritten)
    }

    @Test
    fun theReferencesNumbersAreComparedAsWritten() {
        // numbers are the tagger's call: Han numerals against 2026 is wrong, the same form on both sides is not
        val s = VoicePostRun.Score()
        VoicePostRun.score("二零二六年", "2026年", s)
        assertEquals(4, s.wordErrors) // 2026 for one numeral, three numerals missing, 年 matched
        val same = VoicePostRun.Score()
        VoicePostRun.score("2026年", "2026年", same)
        VoicePostRun.score("二零二六年", "二零二六年", same)
        assertEquals(0, same.wordErrors)
    }

    @Test
    fun doubledMarksAreCounted() {
        val s = VoicePostRun.Score()
        VoicePostRun.score("好的。", "好的。。", s)
        assertEquals(1, s.doubled)
    }

    @Test
    fun alignmentPairsWordsAndMarksWhatIsMissing() {
        assertEquals(listOf(0 to 0, 1 to null, 2 to 1), VoicePostRun.align(listOf("a", "b", "c"), listOf("a", "c")))
        assertEquals(listOf(0 to 0, null to 1, 1 to 2), VoicePostRun.align(listOf("a", "c"), listOf("a", "b", "c")))
    }

    @Test
    fun aFileIsReadAndScoredStretchesJoinedAsTheAppJoinsThem() {
        val file = tmp.newFile("seg-x.jsonl")
        file.writeText(
            """{"wav": "1.wav", "ref": "你好，世界。", "stretches": [{"start": 0.5, "end": 1.2, "nbest": ["你好。"], "scores": [0], "app": "你好，"}, """ +
                """{"start": 2.0, "end": 2.6, "nbest": ["世界"], "scores": [0], "app": "世界。"}]}""" + "\n",
        )
        val clips = VoicePostRun.read(file, "app")
        assertEquals(listOf("你好，", "世界。"), clips.single().stretches.map { it.raw })
        val out = StringBuilder()
        VoicePostRun.run(listOf(file.path), "app", null, out)
        val row = out.lines()[1].split('\t')
        assertEquals(listOf("x", "1", "2", "0.00%", "0.00%"), row.take(5))
        val top = StringBuilder()
        val misses = tmp.newFile("misses.tsv")
        VoicePostRun.run(listOf(file.path), "top", misses.path, top)
        // the first hypotheses: 你好。世界 — the words right, a period where the comma was and no end mark: 2 of 6
        assertEquals(listOf("0.00%", "33.33%"), top.lines()[1].split('\t').subList(3, 5))
        assertEquals(listOf("set\twav\tref\thyp", "x\t1.wav\t你好，世界。\t你好。世界"), misses.readLines())
        // every clip typed, a miss or not
        val texts = tmp.newFile("texts.tsv")
        VoicePostRun.run(listOf(file.path), "app", null, StringBuilder(), texts = texts.path)
        assertEquals(listOf("set\twav\tref\thyp", "x\t1.wav\t你好，世界。\t你好，世界。"), texts.readLines())
    }

    @Test
    fun theWrittenReferenceIsScoredAgainstWhereThereIsOne() {
        val file = tmp.newFile("seg-w.jsonl")
        file.writeText(
            """{"wav": "1.wav", "ref": "嗯我我去", "written": "我去。", "stretches": [{"start": 0, "end": 1, "nbest": ["我去。"], "scores": [0], "app": "我去。"}]}""" + "\n",
        )
        val out = StringBuilder()
        VoicePostRun.run(listOf(file.path), "app", null, out)
        assertEquals(listOf("0.00%", "0.00%"), out.lines()[1].split('\t').subList(3, 5))
    }

    @Test
    fun aClipWithoutAWrittenReferenceInASetWithThemIsLeftOutAndCounted() = assertRows(
        "the key missing" to { leftOutAndCounted("seg-u.jsonl", "") },
        "\"written\": null: none, not the text \"null\"" to { leftOutAndCounted("seg-n.jsonl", "\"written\": null, ") },
    )

    // two clips, the second's written reference given as [written] (its JSON before "stretches")
    private fun leftOutAndCounted(name: String, written: String) {
        val file = tmp.newFile(name)
        file.writeText(
            """{"wav": "1.wav", "ref": "我去", "written": "我去。", "stretches": [{"start": 0, "end": 1, "nbest": ["我去。"], "scores": [0], "app": "我去。"}]}""" + "\n" +
                """{"wav": "2.wav", "ref": "他来", ${written}"stretches": [{"start": 0, "end": 1, "nbest": ["她来。"], "scores": [0], "app": "她来。"}]}""" + "\n",
        )
        val clips = VoicePostRun.read(file, "app")
        assertEquals(listOf(true, false), clips.map { it.written })
        assertEquals("他来", clips[1].ref)
        val out = StringBuilder()
        VoicePostRun.run(listOf(file.path), "app", null, out)
        val row = out.lines()[1].split('\t')
        assertEquals(listOf("1", "0.00%"), listOf(row[1], row[3]))
        assertEquals("1", row.last())
    }

    @Test
    fun anEmptyNBestReadsAsNothingHeardWithTheTopSource() {
        val file = tmp.newFile("seg-e.jsonl")
        file.writeText(
            """{"wav": "1.wav", "ref": "嗯", "stretches": [{"start": 0, "end": 1, "nbest": [], "scores": [], "app": ""}]}""" + "\n",
        )
        assertEquals(listOf(""), VoicePostRun.read(file, "top").single().stretches.map { it.raw })
    }

    @Test
    fun aSetWithoutMarksScoresNoMarks() {
        val s = VoicePostRun.Score()
        VoicePostRun.score("我去", "我去", s)
        assertEquals("-", VoicePostRun.row("x", s).split('\t')[8])
    }

    // --source tagged: the tagger's labels (voice-eval's) through the app's own polish and guards (voice-4)
    @Test
    fun taggedLabelsArePolishedAsTheAppPolishes() {
        val tagger = tmp.newFile("polish-tagger.json")
        tagger.writeText("""{"ops": ["keep", "del"], "after": ["", "，", "。"], "num": ["none"]}""")
        val file = tmp.newFile("seg-t.jsonl")
        // 嗯 deleted, 。 after 好; 三 deleted too, which the guard keeps (a number's character is never deleted)
        file.writeText(
            """{"wav": "1.wav", "ref": "今天三个人很好。", "stretches": [{"start": 0, "end": 1, "nbest": ["嗯今天三个人很好"], "scores": [0], "app": "嗯今天三个人很好"}], """ +
                """"tagged": {"text": "嗯今天三个人很好", "op": [1, 0, 0, 1, 0, 0, 0, 0], "after": [0, 0, 0, 0, 0, 0, 0, 2], "num": [0, 0, 0, 0, 0, 0, 0, 0]}}""" + "\n",
        )
        val tags = VoicePostRun.tags(tagger)
        fun texts(guard: Boolean): Pair<String, String> {
            val texts = tmp.newFile()
            val out = StringBuilder()
            VoicePostRun.run(listOf(file.path), "tagged", null, out, texts.path, VoicePostRun.Polish(tags, guard))
            return texts.readLines()[1].split('\t')[3] to out.lines().first { it.startsWith("tagged:") }
        }
        assertEquals("今天三个人很好。" to "tagged: guard=true untagged=0 unfound=0 long=0", texts(true))
        assertEquals("今天个人很好。" to "tagged: guard=false untagged=0 unfound=0 long=0", texts(false))
    }

    // a tagged run's trust: windows found where the stream is, and clips not replayable counted, not scored wrong
    @Test
    fun taggedWindowsAreFoundAlongTheClipAndTheOnesNotReplayedCounted() {
        val tagger = tmp.newFile("polish-tagger.json")
        tagger.writeText("""{"ops": ["keep", "del"], "after": ["", "，", "。"], "num": ["none"]}""")
        // 80 characters none of which repeats, so a window is found in one place only
        val han = (0 until 80).map { Char(0x5000 + it) }.joinToString("")
        fun labels(f: (Int) -> Int) = (0 until 80).joinToString(", ", "[", "]") { "${f(it)}" }
        val file = tmp.newFile("seg-w.jsonl")
        file.writeText(
            """{"wav": "long.wav", "ref": "x", "stretches": [{"app": "${han.take(40)}"}, {"app": "${han.drop(40)}"}], """ +
                """"tagged": {"text": "$han", "op": ${labels { if (it == 75) 1 else 0 }}, "after": ${labels { if (it == 79) 2 else 0 }}, """ +
                """"num": ${labels { 0 }}}}""" + "\n" +
                // voice-eval tagged another text than the app's: every window not found, the text as heard
                """{"wav": "other.wav", "ref": "x", "stretches": [{"app": "我去了"}], """ +
                """"tagged": {"text": "我来了", "op": [1, 0, 0], "after": [0, 0, 2], "num": [0, 0, 0]}}""" + "\n" +
                // no labels: as the app's with the tagger failing to load, the text unpolished
                """{"wav": "none.wav", "ref": "x", "stretches": [{"app": "嗯我去了"}]}""" + "\n",
        )
        val texts = tmp.newFile()
        val out = StringBuilder()
        VoicePostRun.run(listOf(file.path), "tagged", null, out, texts.path, VoicePostRun.Polish(VoicePostRun.tags(tagger)))
        assertEquals(
            listOf(han.removeRange(75, 76) + "。", "我去了", "嗯我去了"),
            texts.readLines().drop(1).map { it.split('\t')[3] },
        )
        // other.wav: its window asked about as the text grew and at the end, neither found
        assertEquals("tagged: guard=true untagged=1 unfound=2 long=1", out.lines().first { it.startsWith("tagged:") })
    }

    @Test
    fun taggedLabelsNotACharacterEachAreRefusedNamingTheClip() {
        val file = tmp.newFile("seg-b.jsonl")
        file.writeText(
            """{"wav": "short.wav", "ref": "x", "stretches": [{"app": "我去了"}], """ +
                """"tagged": {"text": "我去了", "op": [0, 0], "after": [0, 0, 0], "num": [0, 0, 0]}}""" + "\n",
        )
        val e = assertThrows(VoicePostRun.BadInput::class.java) { VoicePostRun.read(file, "tagged") }
        assertTrue(e.message!!, e.message!!.contains("short.wav"))
    }

    // a file not there (a path mistyped) is a failed run naming it, not a stack trace
    @Test
    fun aFileNotThereIsRefusedNamingIt() = assertRows(
        "a set's clips: read outside the parsing, an IOException not caught" to {
            val e = assertThrows(VoicePostRun.BadInput::class.java) { VoicePostRun.read(File(tmp.root, "seg-gone.jsonl"), "app") }
            assertTrue(e.message!!, e.message!!.startsWith("seg-gone.jsonl: "))
        },
        "a tagger's json: read inside the parsing, which caught no IOException" to {
            val e = assertThrows(VoicePostRun.BadInput::class.java) { VoicePostRun.tags(File(tmp.root, "gone-tagger.json")) }
            assertTrue(e.message!!, e.message!!.startsWith("gone-tagger.json: "))
        },
    )
}
