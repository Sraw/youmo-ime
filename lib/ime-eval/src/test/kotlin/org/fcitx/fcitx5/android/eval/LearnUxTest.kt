/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.eval

import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.fcitx.fcitx5.android.engine.data.PinyinDataBuilder
import org.fcitx.fcitx5.android.engine.pinyin.Syllables
import org.fcitx.fcitx5.android.eval.LearnUx.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

class LearnUxTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun syl(vararg s: String) = s.map { Syllables.id(it) }.toIntArray()

    // 你 far likelier than 拟 at ni; 我 before 再 more than 在 at zai
    private val bytes = PinyinDataBuilder()
        .unigram("<unk>", -7f, 0f)
        .unigram("你", -2f, 0f)
        .unigram("拟", -5f, 0f)
        .unigram("我", -2f, 0f)
        .unigram("在", -2f, 0f)
        .unigram("再", -2.5f, 0f)
        .bigram("我", "再", -0.5f, 0f)
        .entry("你", syl("ni"))
        .entry("拟", syl("ni"))
        .entry("我", syl("wo"))
        .entry("在", syl("zai"))
        .entry("再", syl("zai"))
        .build().toByteArray()

    private val data = PinyinData.load(ByteBuffer.wrap(bytes))

    private fun run(script: String) = LearnUx(data).run(LearnUxScript.parse("t.txt", script.trimIndent().lines()))

    private fun statuses(script: String) = run(script).map { it.status }

    @Test
    fun aScriptIsScenariosOfStepsRepeatsExpanded() {
        val scenarios = LearnUxScript.parse(
            "t.txt",
            """
            # a comment
            scenario one
            ctx 我
            type zai kb=t9
            repeat 2 {
              pick 在
              repeat 3 {
                space
              }
            }
            expect top2 在,再
            scenario two
            pending later
            clear
            expect before 你 拟
            """.trimIndent().lines(),
        )
        assertEquals(listOf("one", "two"), scenarios.map { it.name })
        val one = scenarios[0].steps
        assertEquals(Step.Type(4, "zai", "t9"), one[1])
        val repeat = one[2] as Step.Repeat
        assertEquals(2, repeat.times)
        assertEquals(3, (repeat.steps[1] as Step.Repeat).times)
        assertEquals(listOf("在", "再"), (one[3] as Step.Expect).words)
        assertEquals("later", scenarios[1].pending)
        assertEquals(null, scenarios[0].pending)
    }

    @Test
    fun aLineTheFormatDoesNotHaveSaysWhere() {
        fun parse(vararg lines: String) = LearnUxScript.parse("t.txt", lines.toList())
        for (bad in listOf(
            arrayOf("type ni"),
            arrayOf("scenario s", "jump"),
            arrayOf("scenario s", "repeat 2 {", "space"),
            arrayOf("scenario s", "}"),
            arrayOf("scenario s", "expect top2 你"),
            arrayOf("scenario s", "expect last 你"),
            arrayOf("scenario s", "expect within0 你"),
            arrayOf("scenario s", "expect lacks0 你"),
            arrayOf("scenario s", "type ni qp"),
            arrayOf("scenario s", "space", "pending why"),
            arrayOf("scenario s", "del 0"),
        )) {
            val e = assertThrows(IllegalArgumentException::class.java) { parse(*bad) }
            assertTrue(e.message, e.message!!.startsWith("t.txt:"))
        }
    }

    @Test
    fun theShippedScriptsParse() {
        val files = File("data/learn-ux").listFiles { f -> f.name.endsWith(".txt") }!!
        assertTrue(files.size >= 10)
        for (f in files) assertTrue(f.name, LearnUxScript.parse(f.name, f.readLines()).isNotEmpty())
    }

    // §9: after a commit, nothing typed, a pick, a space and an expectation are of the 联想 offered
    @Test
    fun afterACommitThePredictionsAreWhatIsShown() {
        val script = """
            scenario s
            type wo
            space
            expect first 再
            expect has 再
            expect lacks 你
            pick 再
            type ni
            expect first 你
            """
        assertEquals(List(4) { Status.PASS }, statuses(script))
        // nothing follows 再: no 联想, nothing typed to expect of
        assertEquals(listOf(Status.FAIL), statuses("scenario s\ntype zai\npick 再\nexpect first 我"))
    }

    @Test
    fun anExpectationPassesOrFailsShowingTheCandidates() {
        val results = run(
            """
            scenario s
            type ni
            expect first 你
            expect first 拟
            expect notfirst 拟
            expect before 你 拟
            expect top2 拟,你
            expect within1 你
            expect within1 拟
            expect lacks1 拟
            expect lacks1 你
            expect lacks 拟
            """,
        )
        // within<n>/lacks<n>: in the first n or not (R14 F3737); a bare lacks, not in the first 200
        assertEquals(
            listOf(Status.PASS, Status.FAIL, Status.PASS, Status.PASS, Status.FAIL, Status.PASS, Status.FAIL, Status.PASS, Status.FAIL, Status.FAIL),
            results.map { it.status },
        )
        assertEquals("shown: 你 拟", results[1].detail)
    }

    @Test
    fun whatWasPickedIsLearnedAndEachScenarioStartsAfresh() {
        val picked = """
            scenario picked
            repeat 3 {
            clear
            type ni
            pick 拟
            }
            clear
            type ni
            expect first 拟
            scenario fresh
            type ni
            expect first 你
            """
        assertEquals(listOf(Status.PASS, Status.PASS), statuses(picked))
    }

    @Test
    fun theAppsTextIsTheContextAndWhatWasCommittedToo() {
        val results = statuses(
            """
            scenario s
            ctx 我
            type zai
            expect first 再
            clear
            type zai
            expect first 在
            pick 在
            type wo
            space
            type zai
            expect first 再
            """,
        )
        assertEquals(listOf(Status.PASS, Status.PASS, Status.PASS), results)
    }

    @Test
    fun whatTheEngineCannotDoYetIsPending() {
        val results = run(
            """
            scenario marked
            pending step 9
            type ni
            expect first 拟
            pick 没有
            scenario a day
            type ni
            expect first 你
            day
            clear
            type ni
            expect first 你
            scenario a table
            type fghy kb=wubi
            pick 寸
            expect first 雨
            scenario todo
            todo import
            type ni
            expect first 你
            """,
        )
        // a day passes on the learning record's clock: what comes after it is checked (impl/design-1b.md §5)
        assertEquals(listOf(Status.PENDING, Status.PENDING, Status.PASS, Status.PASS, Status.PENDING, Status.PENDING), results.map { it.status })
    }

    // U-101, U-102: a word imported comes after the dictionary's of its keys; the user's once picked twice, undone or not
    @Test
    fun aWordImportedAndItsListUndone() {
        val results = run(
            """
            scenario imported
            import 妮 ni
            type ni
            expect before 拟 妮
            pick 妮
            clear
            type ni
            pick 妮
            clear
            type ni
            expect first 妮
            unimport
            clear
            type ni
            expect first 妮
            scenario undone
            import 妮 ni
            unimport
            type ni
            expect notfirst 妮
            unimport
            expect first 你
            """,
        )
        assertEquals(listOf(Status.PASS, Status.PASS, Status.PASS, Status.PASS, Status.FAIL), results.map { it.status })
    }

    @Test
    fun aStepThatCannotBeDoneFailsTheRestOfItsScenario() {
        val results = run(
            """
            scenario s
            type ni
            pick 没有
            expect first 你
            scenario t
            type ni
            type ni
            scenario u
            type ni
            space
            type wo
            space
            del 1
            type ni
            show
            space
            del 3
            scenario v
            type ni
            pick 没有
            repeat 2 {
            expect first 你
            }
            expect first 我
            """,
        )
        assertEquals(listOf(Status.FAIL, Status.FAIL, Status.NOTE, Status.FAIL, Status.FAIL, Status.FAIL, Status.FAIL), results.map { it.status })
        // an expectation not come to is a FAIL of its own, the step that stopped the run in its detail
        assertEquals("expect first 你", results[0].text)
        assertTrue(results[0].detail, results[0].detail.startsWith("not reached: stopped at line 3 (pick): 没有 not offered"))
        assertEquals("del 3: only 2 committed", results[3].detail)
        // as many as the script has, a repeat's as often as it repeats
        assertEquals(listOf("expect first 你", "expect first 你", "expect first 我"), results.drop(4).map { it.text })
    }

    @Test
    fun theCommandReportsAndGatesOnFailures() {
        val pinyin = tmp.newFile("pinyin.data").apply { writeBytes(bytes) }
        val scripts = tmp.newFolder("learn-ux")
        File(scripts, "a.txt").writeText("scenario s\ntype ni\nexpect first 你\nexpect first 拟\n")
        val out = StringBuilder()
        assertEquals(0, runCli(arrayOf("learn-ux", pinyin.path, scripts.path), out, StringBuilder()))
        assertTrue(out.toString(), out.lines().last { it.isNotBlank() }.matches(Regex("all +PASS 1  FAIL 1  PENDING 0")))
        assertEquals(1, runCli(arrayOf("learn-ux", pinyin.path, scripts.path, "--gate"), StringBuilder(), StringBuilder()))
        assertEquals(1, runCli(arrayOf("learn-ux", pinyin.path, File(scripts, "a.txt").path, "--gate", "--user-counts", "multi"), StringBuilder(), StringBuilder()))
        assertEquals(2, runCli(arrayOf("learn-ux", pinyin.path, scripts.path, "--user-counts", "some"), StringBuilder(), StringBuilder()))
    }

    // an expectation that stops the run is a row of its own, those after it rows too: the count is the script's
    @Test
    fun anExpectationThatStopsTheRunKeepsItsRow() {
        val results = run("scenario s\ntype zai\npick 再\nexpect first 我\nexpect first 在")
        assertEquals(listOf("expect first 我", "expect first 在"), results.map { it.text })
        assertEquals(listOf(Status.FAIL, Status.FAIL), results.map { it.status })
        assertTrue(results[0].detail, results[0].detail.endsWith("nothing typed"))
        assertTrue(results[1].detail, results[1].detail.startsWith("not reached: stopped at line 4"))
    }

    // Backspace drops the 联想 shown: what follows is of nothing typed, not of the 联想 before
    @Test
    fun delDropsThePredictionsShown() {
        val results = run("scenario s\ntype wo\nspace\ndel 1\nexpect lacks 再")
        assertEquals(listOf(Status.FAIL), results.map { it.status })
        assertTrue(results[0].detail, results[0].detail.endsWith("nothing typed"))
    }

    // the app's space selects only while composing: with only 联想 shown it is no pick of one
    @Test
    fun spaceWithOnlyPredictionsShownStops() {
        val results = run("scenario s\ntype wo\nspace\nspace\nexpect first 我")
        assertEquals(listOf(Status.FAIL), results.map { it.status })
        assertTrue(results[0].detail, "space with only 联想 shown" in results[0].detail)
    }

    // a field that cleared itself (a message sent): no 前文 learned after it (USER-DECISIONS 2026-10-20)
    @Test
    fun aClearedContextAndAQuotedOne() {
        val steps = LearnUxScript.parse("t.txt", listOf("scenario s", "ctx! 我", "ctx \" 我 \"")).single().steps
        assertEquals(listOf(Step.Ctx(2, "我", cleared = true), Step.Ctx(3, " 我 ")), steps)
        // still the text before for scoring (PinyinSession.takeText): what it ends is the pair a pick learns with it
        assertEquals(listOf(Status.PASS), statuses("scenario s\nctx! 我\ntype zai\nexpect first 再"))
    }

    @Test
    fun theNineKeysAndShuangpinAreDriven() {
        assertEquals(List(2) { Status.PASS }, statuses("scenario t9\ntype 924 kb=t9\nexpect has 在\nscenario sp\ntype zl kb=sp:ziranma\nexpect has 在"))
        val e = assertThrows(IllegalArgumentException::class.java) { run("scenario s\ntype zl kb=sp:none") }
        assertTrue(e.message, "no 双拼 scheme none" in e.message!!)
    }

    // the seed is the user's past: typed once a run, into every scenario's record; no scenarios, no seed typed
    @Test
    fun theSeedIsEveryScenariosPast() {
        val ux = LearnUx(data, seed = List(3) { Sample("ni", "拟", "daily") })
        val scenarios = LearnUxScript.parse("t.txt", "scenario a\ntype ni\nexpect first 拟\nscenario b\ntype ni\nexpect first 拟".lines())
        assertEquals(List(2) { Status.PASS }, ux.run(scenarios).map { it.status })
        // again on the same runner, and with nothing to run
        assertEquals(List(2) { Status.PASS }, ux.run(scenarios).map { it.status })
        // F2761: nothing to run types no seed. A seed that throws once read for typing: its record is
        // deleted after a run either way, so only its typing tells (F3213)
        val untypable = object : AbstractList<Sample>() {
            override val size = 1
            override fun get(index: Int): Sample = throw AssertionError("the seed was typed")
        }
        assertEquals(emptyList<LearnUx.Result>(), LearnUx(data, seed = untypable).run(emptyList()))
    }

    // F2810: a word already imported is not taken again, as ImportLayer.import has it
    @Test
    fun aWordImportedTwiceIsTakenOnce() {
        val results = run("scenario s\nimport 妮 ni\nimport 妮 ni\nexpect first 你")
        assertEquals(listOf(Status.FAIL), results.map { it.status })
        assertTrue(results[0].detail, "not imported: a word already" in results[0].detail)
        // the second import refused, not the first (F3214)
        assertTrue(results[0].detail, "stopped at line 3 " in results[0].detail)
    }

    @Test
    fun theCommandSeedsFromTheSetsBesideTheScripts() {
        val pinyin = tmp.newFile("pinyin.data").apply { writeBytes(bytes) }
        val sets = tmp.newFolder("sets")
        for (name in listOf("pinyin-web", "pinyin-chat", "pinyin-dialog", "pinyin-mixed", "pinyin-collide", "pinyin-slips")) {
            File(sets, "$name.tsv").writeText("ni\t拟\tdaily\n")
        }
        val scripts = File(sets, "learn-ux").apply { mkdirs() }
        File(scripts, "a.txt").writeText("scenario s\ntype ni\nexpect first 拟\n")
        assertEquals(1, runCli(arrayOf("learn-ux", pinyin.path, scripts.path, "--gate"), StringBuilder(), StringBuilder()))
        assertEquals(0, runCli(arrayOf("learn-ux", pinyin.path, scripts.path, "--gate", "--seed", "6"), StringBuilder(), StringBuilder()))
        assertEquals(0, runCli(arrayOf("learn-ux", pinyin.path, File(scripts, "a.txt").path, "--gate", "--seed", "6"), StringBuilder(), StringBuilder()))
    }

    // a gate that checked nothing fails
    @Test
    fun theGateFailsWhereNothingWasChecked() {
        val pinyin = tmp.newFile("pinyin.data").apply { writeBytes(bytes) }
        val scripts = tmp.newFolder("empty")
        val out = StringBuilder()
        assertEquals(0, runCli(arrayOf("learn-ux", pinyin.path, scripts.path), out, StringBuilder()))
        assertTrue(out.toString(), "no expectation checked" in out)
        assertEquals(1, runCli(arrayOf("learn-ux", pinyin.path, scripts.path, "--gate"), StringBuilder(), StringBuilder()))
    }
}
