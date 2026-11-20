/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.engine.platform.LogFile
import org.fcitx.fcitx5.android.engine.platform.MemoryFiles
import org.fcitx.fcitx5.android.engine.platform.UserFiles
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.engine.platform.write
import org.fcitx.fcitx5.android.engine.store.Unsaved
import org.fcitx.fcitx5.android.engine.user.UserFixtures.entry
import org.fcitx.fcitx5.android.engine.user.UserFixtures.inTrie
import org.fcitx.fcitx5.android.engine.user.UserFixtures.model
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

class WordListsTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun pinyinIsReadAsTypedApartOrRunTogether() {
        val nihao = entry("你好", "ni", "hao")
        assertEquals(nihao, WordLists.entry("你好", "ni hao"))
        assertEquals(nihao, WordLists.entry(" 你好 ", "Ni'Hao"))
        assertEquals(nihao, WordLists.entry("你好", "nihao"))
        assertEquals(entry("西安", "xi", "an"), WordLists.entry("西安", "xi'an"))
        assertEquals(entry("绿", "lv"), WordLists.entry("绿", "lü"))
        assertEquals(entry("略", "lve"), WordLists.entry("略", "lue"))
        assertEquals(entry("忽略", "hu", "lve"), WordLists.entry("忽略", "hulue"))
    }

    @Test
    fun whatDoesNotReadIsNull() {
        assertNull(WordLists.entry("你好", "ni"))
        assertNull(WordLists.entry("你好", "nizz"))
        assertNull(WordLists.entry("", "ni"))
        assertNull(WordLists.entry("你", ""))
        assertNull(WordLists.entry("方案", "fangan"))
        assertNull(WordLists.entry("西安", "xian"))
    }

    // a delete taken back puts each word where it was, in order, past the end at the end
    @Test
    fun wordsPutBackGoWhereTheyWereAndAreKept() {
        val dir = MemoryFiles()
        val lists = WordLists(dir)
        val a = entry("你", "ni")
        val b = entry("好", "hao")
        val c = entry("吗", "ma")
        val d = entry("拟", "ni")
        lists.addAll(listOf(a, b, c, d))
        lists.removeAll(listOf(a, c, d))
        val back = lists.putBack(listOf(IndexedValue(9, d), IndexedValue(2, c), IndexedValue(1, b), IndexedValue(0, a)))
        assertEquals(listOf(a, c, d), back)
        assertEquals(listOf(a, b, c, d), lists.addedWords)
        assertEquals(listOf(a, b, c, d), WordLists(dir).addedWords)
        assertEquals(emptyList<UserModel.Entry>(), lists.putBack(listOf(IndexedValue(0, b))))
    }

    @Test
    @Uat("VO-59")
    fun voiceAlwaysIsKeptBesideTheAddedList() {
        val dir = MemoryFiles()
        val lists = WordLists(dir)
        val gongshi = entry("攻势", "gong", "shi")
        lists.add(gongshi)
        val addedLine = "攻势 gong'shi\n"
        assertRows(
            "only an added word is marked" to { assertFalse(lists.setVoiceAlways("徐婧", true)) },
            "marked, and read back so" to {
                assertTrue(lists.setVoiceAlways("攻势", true))
                assertEquals(setOf("攻势"), WordLists(dir).voiceAlways)
            },
            "the added list's own file as an older build reads it" to {
                assertEquals(addedLine, String(dir.file(WordLists.ADDED).input().readBytes()))
            },
            "unmarked" to {
                assertTrue(lists.setVoiceAlways("攻势", false))
                assertEquals(emptySet<String>(), WordLists(dir).voiceAlways)
            },
            "deleted with the word: added again it is not marked" to {
                lists.setVoiceAlways("攻势", true)
                lists.remove(gongshi)
                lists.add(gongshi)
                assertEquals(emptySet<String>(), WordLists(dir).voiceAlways)
            },
            "全部清空 clears it" to {
                lists.setVoiceAlways("攻势", true)
                lists.clear()
                lists.add(gongshi)
                assertEquals(emptySet<String>(), WordLists(dir).voiceAlways)
            },
            "deleted by an older build, which keeps the mark's file: dropped at the next start, not back when added again" to {
                lists.setVoiceAlways("攻势", true)
                dir.file(WordLists.ADDED).write(ByteArray(0))
                WordLists(dir).add(gongshi)
                assertEquals(emptySet<String>(), WordLists(dir).voiceAlways)
            },
            "put beside those marked (a restore), only added ones" to {
                val other = WordLists(MemoryFiles()).apply {
                    add(gongshi)
                    add(entry("徐婧", "xu", "jing"))
                    setVoiceAlways("攻势", true)
                    putVoiceAlways(listOf("徐婧", "不在"), merge = true)
                }
                assertEquals(setOf("攻势", "徐婧"), other.voiceAlways)
                other.putVoiceAlways(listOf("徐婧"), merge = false)
                assertEquals(setOf("徐婧"), other.voiceAlways)
            },
            "the added list not read at a start: the marks left as they are for the next" to {
                val files = MemoryFiles()
                WordLists(files).apply {
                    add(gongshi)
                    setVoiceAlways("攻势", true)
                }
                val failing = object : UserFiles by files {
                    override fun file(name: String): LogFile = files.file(name).let { f ->
                        if (name != WordLists.ADDED) f else object : LogFile by f {
                            override fun input(): InputStream = throw IOException("EIO")
                        }
                    }
                }
                WordLists(failing) {}
                assertEquals(setOf("攻势"), WordLists(files).voiceAlways)
            },
            "no file (older data): none marked" to { assertEquals(emptySet<String>(), WordLists(MemoryFiles()).voiceAlways) },
        )
    }

    @Test
    fun theListsAreKeptInTheirFiles() {
        val dir = MemoryFiles()
        val lists = WordLists(dir)
        assertTrue(lists.add(entry("你好", "ni", "hao")))
        assertFalse(lists.add(entry("你好", "ni", "hao")))
        assertTrue(lists.block(entry("拟", "ni")))
        assertEquals("你好 ni'hao\n", dir.file(WordLists.ADDED).read().decodeToString())
        val again = WordLists(dir)
        assertEquals(listOf(entry("你好", "ni", "hao")), again.addedWords)
        assertEquals(listOf(entry("拟", "ni")), again.blockedWords)
        assertTrue(again.unblock(entry("拟", "ni")))
        assertTrue(again.remove(entry("你好", "ni", "hao")))
        assertEquals("", dir.file(WordLists.BLOCKED).read().decodeToString())
        assertEquals(emptyList<UserModel.Entry>(), WordLists(dir).addedWords)
    }

    @Test
    fun theListsReadBackAreAppliedToAModel() {
        val dir = MemoryFiles()
        WordLists(dir).apply {
            add(entry("拟好", "ni", "hao"))
            block(entry("你好", "ni", "hao"))
        }
        val m = model()
        WordLists(dir).applyTo(m)
        // a word the dictionary lacks is listed, to type; the dictionary's word blocked is never offered
        assertEquals(listOf("拟好"), m.listedTexts())
        assertTrue(inTrie(m, "拟好", "ni", "hao"))
        assertTrue(m.blockedAnyhow(m.id(entry("你好", "ni", "hao"))))
        assertFalse(m.blockedAnyhow(m.id(entry("你", "ni"))))
    }

    @Uat("WD-48")
    @Test
    fun aListThatCannotBeWrittenIsKeptInMemory() {
        val errors = ArrayList<IOException>()
        val root = MemoryFiles().apply { file("lists").createNew() }
        val lists = WordLists(root.dir("lists"), errors::add)
        assertTrue(lists.add(entry("你好", "ni", "hao")))
        assertEquals(listOf(entry("你好", "ni", "hao")), lists.addedWords)
        // decode-2: told as not kept, which the app says on the keyboard, not only logged
        assertTrue(errors.single() is Unsaved)
    }

    // decode-9: a line this build does not read (a later build's, say) is written back as it was, not lost to the next change
    @Test
    fun aLineThatDoesNotReadIsKeptThroughAChange() {
        val dir = MemoryFiles()
        dir.file(WordLists.BLOCKED).write("拟 ni\n你好 ni'hao 1.5 later\n".toByteArray())
        val lists = WordLists(dir)
        assertEquals(listOf(entry("拟", "ni")), lists.blockedWords)
        assertTrue(lists.block(entry("好", "hao")))
        assertEquals("拟 ni\n好 hao\n你好 ni'hao 1.5 later\n", dir.file(WordLists.BLOCKED).read().decodeToString())
        assertEquals(listOf(entry("拟", "ni"), entry("好", "hao")), WordLists(dir).blockedWords)
    }

    // the lines this build does not read go too, or a later build's block comes back
    @Test
    @Uat("WD-25")
    fun aClearEmptiesTheListsAndTheLinesThatDoNotRead() {
        val dir = MemoryFiles()
        dir.file(WordLists.ADDED).write("你好 ni'hao\n幽默 you'mo 2 later\n".toByteArray())
        dir.file(WordLists.BLOCKED).write("你好 ni'hao 1.5 later\n".toByteArray())
        val lists = WordLists(dir)
        assertEquals(listOf(entry("你好", "ni", "hao")), lists.addedWords)
        assertEquals(emptyList<UserModel.Entry>(), lists.blockedWords)
        lists.clear()
        assertEquals(emptyList<UserModel.Entry>(), lists.addedWords)
        assertEquals(mapOf(WordLists.ADDED to "", WordLists.BLOCKED to ""), dir.all.mapValues { it.value.decodeToString() })
        assertTrue(lists.block(entry("拟", "ni")))
        assertEquals("拟 ni\n", dir.file(WordLists.BLOCKED).read().decodeToString())
    }

    // a list whose file did not read is deleted too, or a later start brings its words back
    @Test
    @Uat("WD-25")
    fun aClearDeletesAListThatDidNotRead() = assertRows(
        "its file reads again later: deleted unread, so not back at the next start, and the list is written again" to {
            val files = MemoryFiles()
            files.file(WordLists.ADDED).write("你好 ni'hao\n".toByteArray())
            var failing = true
            val dir = object : UserFiles by files {
                override fun file(name: String): LogFile = files.file(name).let { f ->
                    if (!failing) f else object : LogFile by f {
                        override fun input(): InputStream = throw IOException("EIO")
                    }
                }
            }
            val errors = ArrayList<IOException>()
            val lists = WordLists(dir, errors::add)
            assertEquals(1, errors.size)
            failing = false
            lists.clear()
            assertFalse(files.file(WordLists.ADDED).taken())
            assertEquals(emptyList<UserModel.Entry>(), WordLists(files).addedWords)
            assertTrue(lists.add(entry("拟", "ni")))
            assertEquals("拟 ni\n", files.file(WordLists.ADDED).read().decodeToString())
            assertEquals(1, errors.size)
        },
        "it cannot be deleted (a directory with something in it): told as not kept, and still not written over" to {
            val files = MemoryFiles()
            files.dir(WordLists.ADDED).dir("x").mkdirs()
            val errors = ArrayList<IOException>()
            val lists = WordLists(files, errors::add)
            assertEquals(1, errors.size)
            lists.clear()
            assertEquals(2, errors.size)
            assertTrue(errors.last() is Unsaved)
            assertTrue(lists.add(entry("拟", "ni")))
            assertEquals(3, errors.size)
            assertTrue(files.dir(WordLists.ADDED).dir("x").exists())
        },
    )

    @Test
    fun aListThatDidNotReadIsNotWrittenOver() {
        val disk = folder.newFolder()
        val dir = JvmFiles(disk)
        val link = File(disk, WordLists.ADDED)
        // a list there that cannot be read as one, yet could be renamed over
        assumeTrue(runCatching { Files.createSymbolicLink(link.toPath(), folder.newFolder().toPath()) }.isSuccess)
        val errors = ArrayList<IOException>()
        val lists = WordLists(dir, errors::add)
        assertEquals(1, errors.size)
        assertTrue(lists.add(entry("你好", "ni", "hao")))
        assertEquals(listOf(entry("你好", "ni", "hao")), lists.addedWords)
        assertEquals(2, errors.size)
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertFalse(dir.file("${WordLists.ADDED}.new").exists())
        assertTrue(lists.block(entry("拟", "ni")))
        assertEquals("拟 ni\n", dir.file(WordLists.BLOCKED).read().decodeToString())
        assertEquals(2, errors.size)
    }

    @Test
    fun aListThatCannotBeRenamedInLeavesNoTempFile() = listThatCannotBeRenamedIn(MemoryFiles())

    @Test
    fun aListThatCannotBeRenamedInLeavesNoTempFileOnDisk() = listThatCannotBeRenamedIn(JvmFiles(folder.newFolder()))

    private fun listThatCannotBeRenamedIn(dir: UserFiles) {
        val errors = ArrayList<IOException>()
        val lists = WordLists(dir, errors::add)
        dir.dir(WordLists.ADDED).mkdirs()
        assertTrue(lists.add(entry("你好", "ni", "hao")))
        assertEquals(1, errors.size)
        assertFalse(dir.file("${WordLists.ADDED}.new").exists())
    }

    @Test
    fun pinyinRunTogetherIsSplitForAWordNotASentence() {
        fun nis(chars: Int) = UserModel.Entry("你".repeat(chars), IntArray(chars) { Syllables.id("ni") })
        assertEquals(nis(WORD), WordLists.entry("你".repeat(WORD), "ni".repeat(WORD)))
        assertNull(WordLists.entry("你".repeat(WORD + 1), "ni".repeat(WORD + 1)))
        assertEquals(nis(WORD + 1), WordLists.entry("你".repeat(WORD + 1), "ni ".repeat(WORD + 1)))
        assertNull(WordLists.entry("你好", "a".repeat(100_000)))
    }

    @Test
    fun aLineToImportIsAWordAndItsPinyinEitherWayRound() {
        assertEquals(WordLists.Line("幽默", "you'mo", false), WordLists.line("幽默 you'mo"))
        assertEquals(WordLists.Line("幽默", "you mo", false), WordLists.line("you mo\t幽默\t3"))
        // a word pack's line, its score after the pinyin: imported into the user dictionary as well
        assertEquals(WordLists.Line("搭子", "da'zi", false), WordLists.line("搭子\tda'zi\t-5.6"))
        assertEquals(WordLists.Line("3D", "san'di", false), WordLists.line("3D san'di"))
        assertEquals(WordLists.Line("幽默", null, true), WordLists.line("!幽默"))
        assertEquals(WordLists.Line("幽默", "youmo", false), WordLists.line("\uFEFF幽默 youmo"))
        assertNull(WordLists.line("  "))
        assertNull(WordLists.line("# 幽默 you'mo"))
        assertEquals(WordLists.Line.UNREAD, WordLists.line("you mo"))
        assertEquals(WordLists.Line.UNREAD, WordLists.line("幽默 风趣"))
    }

    @Test
    fun aListIsReadInTheEncodingOtherInputMethodsWriteIn() {
        val text = "幽默 you'mo\n"
        assertEquals(text, WordLists.decode(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, WordLists.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, WordLists.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE)))
        assertEquals(text, WordLists.decode(text.toByteArray(charset("GB18030"))))
    }

    @Test
    fun aLetterIsASyllableAsSpelledAndReadsBackSo() {
        val stock = WordLists.entry("A股", "A'gu")!!
        assertEquals("A股 A'gu", "${stock.text} ${stock.apostrophed()}")
        assertEquals(stock, WordLists.entry("A股", "A gu"))
        assertEquals(entry("你好", "ni", "hao"), WordLists.entry("你好", "Ni Hao"))
        assertEquals(entry("啊", "a"), WordLists.entry("啊", "A"))
    }

    private companion object {
        const val WORD = 32
    }

    @Test
    fun aBlockedEmojiReadsAsItsWordWhateverItsCodePoints() {
        assertEquals("hao", WordLists.blockedEntry("👍🏻", "hao")?.spelled())
        assertEquals("ni hao", WordLists.blockedEntry("😃", "ni'hao")?.spelled())
        assertEquals("xin", WordLists.blockedEntry("ℹ️", "xin")?.spelled())
        // a keycap (1️⃣, #️⃣) is no word though its digit is: read as an emoji, no table hides it
        assertFalse(WordLists.isWordly("1️⃣"))
        assertFalse(WordLists.isWordly("#️⃣"))
        assertEquals("yi", WordLists.blockedEntry("1️⃣", "yi")?.spelled())
        // a text of letters or digits as any word: a syllable a character, or none
        assertNull(WordLists.blockedEntry("abc", "a'o"))
        val dir = MemoryFiles()
        dir.file(WordLists.BLOCKED).write("👍🏻 hao\n😃 ni'hao 0\n👌 hao x\nℹ️ xin\n".toByteArray())
        assertEquals(listOf("👍🏻", "😃", "ℹ️"), WordLists(dir).blockedWords.map { it.text })
    }

    // a Latin candidate blocked in pinyin is written as its upper-case letter syllables: it reads back after a restart
    @Test
    @Uat("PY-117")
    fun aBlockedLatinWordReadsBackAsItsLetters() {
        val dir = MemoryFiles()
        dir.file(WordLists.BLOCKED).write("hello H'E'L'L'O\niPhone I'P'H'O'N'E\n".toByteArray())
        val read = WordLists(dir).blockedWords
        assertEquals(listOf("hello", "iPhone"), read.map { it.text })
        assertEquals(listOf(WordLists.blockedEntry("hello", "H'E'L'L'O"), WordLists.blockedEntry("iPhone", "I'P'H'O'N'E")), read)
        assertEquals(5, read[0].syllables.size)
    }
}
