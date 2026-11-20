/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [EngineOptions] and [IniFile] against goldens `tools/fcitx-ini/gen.py` made from fcitx-config over
 * `options.inc`: a found file (`in.ini`), the pages saved on top (`steps.tsv`), fcitx's result
 * (`fcitx.ini` with comments, `saved.ini` without, which gen.py checked fcitx reads back alike).
 * contract: goldens generated from fcitx-config, which mutation cannot see.
 */
class EngineOptionsGoldenTest {

    private fun text(case: String, file: String): String? =
        javaClass.getResourceAsStream("/host/ini/$case/$file")?.use { it.readBytes().decodeToString() }

    private val cases = text("", "cases.txt")!!.lines().filter { it.isNotEmpty() }

    private fun options(case: String) = EngineOptions().apply {
        load(text(case, "in.ini")!!)
        text(case, "steps.tsv")?.lines()?.filter { it.isNotEmpty() }?.forEach { line ->
            val (im, page) = line.split('\t', limit = 2)
            setPage(im, IniFile.read(unescapeGolden(page)))
        }
    }

    @Test
    fun `saves what fcitx saves`() {
        for (case in cases) assertEquals(case, text(case, "saved.ini"), options(case).text())
    }

    @Test
    fun `reads a file fcitx wrote, descriptions and all, as fcitx does`() {
        // fcitx's file → Kotlin read → write = fcitx's own rewrite
        for (case in cases) {
            val options = EngineOptions().apply { load(text(case, "fcitx.ini")!!) }
            assertEquals(case, text(case, "saved.ini"), options.text())
        }
    }

    @Test
    fun `shows each input method's page as fcitx does`() {
        for (case in cases) {
            val options = options(case)
            val expected = text(case, "pages.ini")!!.split("### ").filter { it.isNotEmpty() }
            for (section in expected) {
                val im = section.substringBefore('\n')
                assertEquals("$case $im", section.substringAfter('\n'), IniFile.write(options.page(im)!!))
            }
        }
    }

    @Test
    fun `writing what was read changes nothing`() {
        for (case in cases) {
            val once = options(case).text()
            assertEquals(case, once, EngineOptions().apply { load(once) }.text())
        }
    }
}
