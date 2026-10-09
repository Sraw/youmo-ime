/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.host

import org.fcitx.fcitx5.android.engine.session.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** The tables the user adds ([Engines.UserTable]): built, kept and read again, and what they forget. */
class EnginesTablesTest : EnginesFixture() {

    @get:Rule
    val folder = TemporaryFolder()

    private fun userTable(
        conf: String = "",
        stamp: String = "1",
        reads: () -> Unit = {},
        text: String = MY_TABLE,
        settings: String = "",
    ) = Engines.UserTable("[Table]\nFile=table/my.main.dict\n$conf", { "$it $stamp" }, {
        assertEquals("table/my.main.dict", it)
        reads()
        text.reader().buffered()
    }, settings)

    @Test
    fun whatTheUserSetOfAnAddedTableIsReadAgainWithoutBuildingIt() {
        var reads = 0
        var settings = ""
        Engines(::load, folder.newFolder("engine"), userTables = { userTable(reads = { reads++ }, settings = settings) }).use { engines ->
            engines.type("my", "ab")
            engines.onEvent("my", EngineEvent.PICK, 1)
            assertEquals("工", engines.type("my", "ab").candidates.first())
            settings = "[Table]\nOrderPolicy=Freq\n"
            engines.reload()
            // the pick kept all along, now what orders them
            assertEquals("式", engines.type("my", "ab").candidates.first())
        }
        assertEquals(1, reads)
    }

    @Test
    fun anAddedTableThatCannotBeKeptIsTypedAllTheSame() {
        val dir = folder.newFolder("engine")
        // where the tables would go, a file
        dir.resolve(Engines.USER_TABLES).writeText("")
        val errors = ArrayList<IOException>()
        Engines(::load, dir, { errors += it }, userTables = { userTable() }).use { engines ->
            assertEquals(listOf("工", "式", "工作"), engines.type("my", "ab").candidates)
        }
        // the table not kept, nor what it learned
        assertEquals(2, errors.size)
    }

    @Test
    fun aTableTheUserAddedIsBuiltOnceAndAgainOnlyWhenItChanges() {
        val dir = folder.newFolder("engine")
        var reads = 0
        var stamp = "1"
        val tables = { im: String -> if (im == "my") userTable(stamp = stamp, reads = { reads++ }) else null }
        repeat(2) {
            Engines(::load, dir, userTables = tables).use { engines ->
                assertEquals(listOf("工", "式", "工作"), engines.type("my", "ab").candidates)
                engines.onEvent("my", EngineEvent.RESET, 0)
                // coded by the rules from its 词组
                assertEquals(listOf("工作"), engines.type("my", "abcd").candidates)
            }
        }
        assertEquals(1, reads)
        stamp = "2"
        Engines(::load, dir, userTables = tables).use { engines ->
            engines.reload()
            assertEquals(listOf("工", "式", "工作"), engines.type("my", "ab").candidates)
        }
        assertEquals(2, reads)
        assertThrows(IllegalArgumentException::class.java) { Engines(::load, dir, userTables = tables).type("nope", "a") }
    }

    @Test
    fun aTableTheUserAddedLearnsAsItsConfSays() {
        val dir = folder.newFolder("engine")
        for ((conf, first) in listOf("OrderPolicy=Freq\nLearning=False" to "工", "OrderPolicy=Freq" to "式")) {
            val tables = { _: String -> userTable(conf) }
            Engines(::load, dir, userTables = tables).use { engines ->
                engines.type("my", "ab")
                // forgetting is the user's asking, not learning: offered either way
                assertEquals(setOf(Offer.FORGET), engines.offers("my", 0))
                assertEquals("式", engines.onEvent("my", EngineEvent.PICK, 1).commit)
                // not even for now, if it does not learn
                assertEquals(first, engines.type("my", "ab").candidates.first())
                engines.onEvent("my", EngineEvent.RESET, 0)
            }
            Engines(::load, dir, userTables = tables).use { engines ->
                assertEquals(first, engines.type("my", "ab").candidates.first())
            }
        }
        // the conf's own options, not the built-in tables'
        Engines(::load, null, userTables = { userTable() }).use { engines ->
            engines.type("my", "ab")
            engines.onEvent("my", EngineEvent.PICK, 1)
            assertEquals("工", engines.type("my", "ab").candidates.first())
        }
    }

    @Test
    fun aTableTheUserAddedThatCannotBeReadIsReportedOnceUntilReload() {
        val errors = ArrayList<IOException>()
        var asked = 0
        var text = "键码=ab\n"
        val engines = Engines(::load, folder.newFolder("engine"), { errors += it }, userTables = {
            asked++
            userTable(text = text)
        })
        repeat(2) { assertThrows(IllegalArgumentException::class.java) { engines.type("my", "a") } }
        assertEquals(1, asked)
        assertEquals(1, errors.size)
        text = MY_TABLE
        engines.reload()
        assertEquals(listOf("工", "式", "工作"), engines.type("my", "ab").candidates)
        assertEquals(2, asked)
        // nor a .conf naming no table
        val noFile = Engines(::load, null, { errors += it }, userTables = { Engines.UserTable("", { "1" }, { MY_TABLE.reader().buffered() }) })
        assertThrows(IllegalArgumentException::class.java) { noFile.type("my", "a") }
        // nor one the app cannot read
        val gone = Engines(::load, null, { errors += it }, userTables = { throw IOException("gone") })
        assertThrows(IllegalArgumentException::class.java) { gone.type("my", "a") }
        assertEquals(listOf("gone"), errors.drop(2).map { it.message })
        assertEquals(3, errors.size)
    }

    @Test
    fun aBuiltTableCutShortIsBuiltAgain() {
        val dir = folder.newFolder("engine")
        val errors = ArrayList<IOException>()
        var reads = 0
        val tables = { _: String -> userTable(reads = { reads++ }) }
        Engines(::load, dir, userTables = tables).use { it.type("my", "ab") }
        val built = dir.resolve("${Engines.USER_TABLES}/my.table")
        built.writeBytes(built.readBytes().copyOf(built.length().toInt() / 2))
        Engines(::load, dir, { errors += it }, userTables = tables).use { engines ->
            assertEquals(listOf("工", "式", "工作"), engines.type("my", "ab").candidates)
        }
        assertEquals(2, reads)
        assertEquals(1, errors.size)
        Engines(::load, dir, userTables = tables).use { it.type("my", "ab") }
        assertEquals(2, reads)
    }

    @Test
    fun aTableTypedByDigitsLeavesEscapeAndCharactersItDoesNotReadToTheApp() {
        // as 电报码: its codes are digits, as the nine keys' are
        Engines(::load, null, userTables = { userTable(text = "键码=0123456789\n码长=4\n[数据]\n0001 一\n") }).use { engines ->
            assertFalse(engines.onEvent("db", EngineEvent.ESCAPE, 0).handled)
            assertFalse(engines.onEvent("db", EngineEvent.CHAR, Keyboard.syllableKey(0, 0).code).handled)
            assertEquals(listOf("一"), engines.type("db", "0001").candidates)
        }
        // the nine keys' 重输 with nothing typed: not the app's
        assertTrue(Engines(::load, null).onEvent(Engines.T9, EngineEvent.ESCAPE, 0).handled)
    }

    /** 你 then 好 typed in [NIHAO_TABLE], then the phrase they make picked: the table's, and pinyin's word. */
    private fun Engines.saveNihao() {
        type("my", "ab ")
        type("my", "cd ")
        assertEquals(listOf("你好"), type("my", "abcd").candidates)
        assertEquals("你好", onEvent("my", EngineEvent.PICK, 0).commit)
        onEvent("my", EngineEvent.RESET, 0)
        assertTrue(userWords().any { it.text == "你好" && it.kind == Engines.UserWord.Kind.LEARNED })
    }

    /** 你好 forgotten in pinyin by a long press, no added table loaded for it. */
    private fun Engines.forgetNihaoInPinyin() {
        type(Engines.PINYIN, "nihao")
        val at = candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == "你好" }
        assertTrue(Offer.FORGET in offers(Engines.PINYIN, at))
        onEvent(Engines.PINYIN, EngineEvent.FORGET, at)
    }

    @Test
    fun aPhraseForgottenElsewhereIsForgottenByAnAddedTableNotTypedWithSinceTheStart() {
        val dir = folder.newFolder("engine")
        val tables = { _: String -> userTable(text = NIHAO_TABLE) }
        Engines(::load, dir, userTables = tables).use { it.saveNihao() }
        Engines(::load, dir, userTables = tables).use { engines ->
            val learned = engines.userWords().filter { it.text == "你好" }
            assertEquals(1, learned.size)
            engines.removeWords(learned)
        }
        Engines(::load, dir, userTables = tables).use { engines ->
            assertFalse("你好" in engines.type("my", "abcd").candidates)
            assertTrue(engines.userWords().none { it.text == "你好" })
        }
    }

    @Test
    fun aPhraseATableSavedForgottenInPinyinIsForgottenByTheTableToo() {
        val dir = folder.newFolder("engine")
        val tables = { _: String -> userTable(text = NIHAO_TABLE) }
        Engines(::load, dir, userTables = tables).use { engines ->
            engines.saveNihao()
            engines.type(Engines.PINYIN, "nihao")
            val at = engines.candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == "你好" }
            assertTrue(Offer.FORGET in engines.offers(Engines.PINYIN, at))
            engines.onEvent(Engines.PINYIN, EngineEvent.FORGET, at)
        }
        // pinyin's words gone, the table offers only what it saved itself
        assertTrue(dir.resolve(Engines.USER_PINYIN).delete())
        Engines(::load, dir, userTables = tables).use { engines ->
            assertFalse("你好" in engines.type("my", "abcd").candidates)
        }
    }

    @Test
    fun anAddedTableNotLoadedForgetsWhatPinyinForgotAsItLoadsNotBuiltForIt() {
        val dir = folder.newFolder("engine")
        var reads = 0
        var stamp = "1"
        val tables = { im: String -> if (im == "my") userTable(stamp = stamp, reads = { reads++ }, text = NIHAO_TABLE) else null }
        Engines(::load, dir, userTables = tables).use { it.saveNihao() }
        val added = dir.resolve(Engines.USER_TABLES)
        // no added table's log: a build cut short, a log moved aside, a built-in's name; and the
        // log of a table since removed, which cannot load
        for (name in listOf("my.table.new", "my.user.unreadable", "engine-wubi.user", "gone.user")) added.resolve(name).writeText("")
        // changed since: it would be built again
        stamp = "2"
        val asked = HashSet<String>()
        val errors = ArrayList<IOException>()
        Engines(::load, dir, { errors += it }, userTables = { asked += it; tables(it) }).use { engines ->
            engines.type(Engines.PINYIN, "nihao")
            val at = engines.candidates(Engines.PINYIN, 0, 20).indexOfFirst { it.text == "你好" }
            assertTrue(Offer.FORGET in engines.offers(Engines.PINYIN, at))
            engines.onEvent(Engines.PINYIN, EngineEvent.FORGET, at)
            // only the confs of those with a log read, none loaded, let alone built, for it: nor
            // the one removed reported as unreadable
            assertEquals(setOf("my", "gone"), asked)
            assertEquals(1, reads)
            assertEquals(emptyList<IOException>(), errors)
        }
        // none for the log of the table removed, which would never forget it
        assertEquals(setOf("my.user${Engines.FORGOTTEN}"), added.list().orEmpty().filter { it.endsWith(Engines.FORGOTTEN) }.toSet())
        assertTrue(dir.resolve(Engines.USER_PINYIN).delete())
        Engines(::load, dir, userTables = tables).use { engines ->
            assertFalse("你好" in engines.type("my", "abcd").candidates)
        }
        assertEquals(2, reads)
        assertFalse(added.resolve("my.user${Engines.FORGOTTEN}").exists())
    }

    @Test
    fun nothingIsKeptToForgetForAnAddedTableThatWillNotReadItsLog() {
        val dir = folder.newFolder("engine")
        Engines(::load, dir, userTables = { userTable(text = NIHAO_TABLE) }).use { it.saveNihao() }
        // its .conf naming no table now
        Engines(::load, dir, userTables = { Engines.UserTable("", { "1" }, { NIHAO_TABLE.reader().buffered() }) }).use { it.forgetNihaoInPinyin() }
        assertTrue(dir.resolve("${Engines.USER_TABLES}/my.user").isFile)
        assertFalse(dir.resolve("${Engines.USER_TABLES}/my.user${Engines.FORGOTTEN}").exists())
    }

    @Test
    fun aPhraseForgottenWhileAnAddedTableLearnsNothingStaysForgottenOnceItLearnsAgain() {
        for (typed in listOf(false, true)) {
            val dir = folder.newFolder("engine$typed")
            var conf = ""
            val tables = { _: String -> userTable(conf, text = NIHAO_TABLE) }
            Engines(::load, dir, userTables = tables).use { it.saveNihao() }
            conf = "Learning=False"
            Engines(::load, dir, userTables = tables).use { engines ->
                // loaded as it learns nothing, its log not read, or not loaded at all
                if (typed) engines.onEvent("my", EngineEvent.RESET, 0)
                engines.forgetNihaoInPinyin()
            }
            conf = ""
            // pinyin's words gone, the table offers only what it saved itself
            assertTrue(dir.resolve(Engines.USER_PINYIN).delete())
            Engines(::load, dir, userTables = tables).use { engines ->
                assertFalse("你好" in engines.type("my", "abcd").candidates)
            }
        }
    }

    @Test
    fun anAddedTableWhoseConfCannotBeReadNowIsKeptWhatIsForgotten() {
        val dir = folder.newFolder("engine")
        Engines(::load, dir, userTables = { userTable(text = NIHAO_TABLE) }).use { it.saveNihao() }
        Engines(::load, dir, userTables = { throw IOException("unreadable") }).use { it.forgetNihaoInPinyin() }
        assertTrue(dir.resolve("${Engines.USER_TABLES}/my.user${Engines.FORGOTTEN}").isFile)
    }

    @Test
    fun allThatIsKeptOfAnAddedTableIsInTheFilesGoneWithIt() {
        val dir = folder.newFolder("engine")
        val tables = { _: String -> userTable(text = NIHAO_TABLE) }
        // built, learned, told to pinyin, then kept something to forget
        Engines(::load, dir, userTables = tables).use { it.saveNihao() }
        Engines(::load, dir, userTables = tables).use { it.forgetNihaoInPinyin() }
        // what the app deletes with the table (TableBasedInputMethod.delete)
        assertEquals(
            Engines.addedTableFiles("my").map { File(it).name }.toSet(),
            dir.resolve(Engines.USER_TABLES).list().orEmpty().toSet(),
        )
    }

    @Test
    fun aPhraseKeptToForgetAfterALineCutShortIsKeptOnALineOfItsOwn() {
        val dir = folder.newFolder("engine")
        val tables = { _: String -> userTable(text = NIHAO_TABLE) }
        Engines(::load, dir, userTables = tables).use { it.saveNihao() }
        val forgotten = dir.resolve("${Engines.USER_TABLES}/my.user${Engines.FORGOTTEN}")
        // a kill cut its last line short
        forgotten.writeText("再见")
        Engines(::load, dir, userTables = tables).use { it.forgetNihaoInPinyin() }
        assertEquals("再见\n你好\n", forgotten.readText())
    }

    @Test
    fun aWordAddedPickedInATableAndRemovedIsNotBackThereAtTheNextStart() {
        val dir = folder.newFolder("engine")
        val tables = { _: String -> userTable(text = NIHAO_TABLE) }
        Engines(::load, dir, userTables = tables).use { engines ->
            assertTrue(engines.addWord("你好", "nihao"))
            // the user's word, coded by the table's rule: its pick is the table's, read back as a phrase saved
            assertEquals(listOf("你好"), engines.type("my", "abcd").candidates)
            assertEquals("你好", engines.onEvent("my", EngineEvent.PICK, 0).commit)
            engines.onEvent("my", EngineEvent.RESET, 0)
            engines.removeWord(Engines.UserWord("你好", "ni hao", Engines.UserWord.Kind.ADDED))
        }
        Engines(::load, dir, userTables = tables).use { engines ->
            assertFalse("你好" in engines.type("my", "abcd").candidates)
            assertEquals(emptyList<Engines.UserWord>(), engines.userWords())
        }
    }

    private companion object {
        // 你 and 好, which pinyin reads too, and the rule that codes a phrase of them
        val NIHAO_TABLE = """
            键码=abcd
            码长=4
            [组词规则]
            e2=p11+p12+p21+p22
            [数据]
            ab 你
            cd 好
        """.trimIndent()

        val MY_TABLE = """
            键码=abcd
            码长=4
            [组词规则]
            e2=p11+p12+p21+p22
            [数据]
            ab 工
            ab 式
            cd 作
            [词组]
            工作
        """.trimIndent()
    }
}
