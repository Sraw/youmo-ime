/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.data.PinyinData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer

class MainTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String, text: String) = tmp.newFile(name).apply { writeText(text.trimIndent()) }.path

    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = StringBuilder()
        val err = StringBuilder()
        return Triple(runCli(arrayOf(*args), out, err), out.toString(), err.toString())
    }

    @Test
    fun pinyinDataIsCompiledAndChecksOutAgainstItsModel() {
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA)
        val dict = file("dict.txt", "你\tni\t0\n好 hao\n你好 ni'hao\n嗯 xyz\n")
        val output = tmp.root.resolve("pinyin.data").path
        val (code, out, err) = run("pinyin", "-o", output, "--lm", lm, dict)
        assertEquals(err, 0, code)
        assertTrue(out, out.contains("model: 3 / 1 / 1 n-grams"))
        assertTrue(out, out.contains("dictionary: 3 readings"))
        assertTrue(out, out.contains("skipped 1 readings with unknown syllables: xyz×1"))
        assertTrue(out, out.contains("vocabulary: 4 words, 3 of them in the model"))
        val data = PinyinData.load(ByteBuffer.wrap(tmp.root.resolve("pinyin.data").readBytes()))
        assertEquals("lm.arpa,dict.txt", data.meta["source"])

        val (checkCode, checkOut, _) = run("check", output, lm)
        assertEquals(0, checkCode)
        assertTrue(checkOut, checkOut.contains("1-grams: 3, probability max error 0.0000, mean 0.00000, backoff max error 0.0000"))
        assertTrue(checkOut, checkOut.contains("2-grams: 1, probability max error 0.0000, mean 0.00000, backoff max error 0.0000"))
        assertTrue(checkOut, checkOut.contains("3-grams: 1, probability max error 0.0000, mean 0.00000\n"))
    }

    @Test
    fun aCodeTableIsCompiled() {
        val table = file("t.txt", ReadersTest.TINY_TABLE)
        val output = tmp.root.resolve("t.data").path
        val (code, out, _) = run("table", "-o", output, table)
        assertEquals(0, code)
        assertTrue(out, out.contains("table: 6 entries"))
        assertTrue(out, out.contains("1 entries use characters outside 键码, e.g. a;b 怪"))
        assertEquals(6, CodeTable.load(ByteBuffer.wrap(tmp.root.resolve("t.data").readBytes())).size)
    }

    @Test
    fun badSourcesExitWithTheirLocation() {
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA)
        val dict = file("dict.txt", "你\n")
        val (code, _, err) = run("pinyin", "-o", tmp.root.resolve("x").path, "--lm", lm, dict)
        assertEquals(1, code)
        assertTrue(err, err.startsWith("$dict:1: "))
    }

    @Test
    fun inconsistentModelsAndMissingFilesExitWithAMessage() {
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA.replace("-5.0\t<unk>", "-5.0\t<s>"))
        val dict = file("dict.txt", "你 ni\n")
        val (code, _, err) = run("pinyin", "-o", tmp.root.resolve("x").path, "--lm", lm, dict)
        assertEquals(1, code)
        assertEquals("the model has no <unk>\n", err)
        val (missing, _, missingErr) = run("table", "-o", tmp.root.resolve("y").path, tmp.root.resolve("nope.txt").path)
        assertEquals(1, missing)
        assertTrue(missingErr, missingErr.contains("nope.txt"))
    }

    @Test
    fun checkingSomethingOtherThanPinyinDataExitsWithAMessage() {
        val lm = file("lm.arpa", ReadersTest.TINY_ARPA)
        val table = tmp.root.resolve("t.data").path
        assertEquals(0, run("table", "-o", table, file("t.txt", ReadersTest.TINY_TABLE)).first)
        val (code, _, err) = run("check", table, lm)
        assertEquals(1, code)
        assertEquals("file kind 2, expected 1\n", err)
    }

    @Test
    fun badArgumentsPrintUsage() {
        listOf(
            emptyArray<String>(),
            arrayOf("pinyin", "-o", "x", "d.txt"),
            arrayOf("table", "-o", "x"),
            arrayOf("table", "t.txt"),
            arrayOf("check", "x"),
            arrayOf("frobnicate", "x"),
        ).forEach { args ->
            val (code, _, err) = run(*args)
            assertEquals(args.toList().toString(), 2, code)
            assertEquals(USAGE + "\n", err)
        }
    }
}
