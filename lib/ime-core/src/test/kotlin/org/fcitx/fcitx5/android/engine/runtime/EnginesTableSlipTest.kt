/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.data.CodeTable
import org.fcitx.fcitx5.android.engine.pinyin.Near
import org.fcitx.fcitx5.android.engine.pinyin.Touch
import org.fcitx.fcitx5.android.engine.session.Action
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

/** A table's tap with its place, as the host's key carries it (061-neighbour-table): read through the engine, as the setting says. */
class EnginesTableSlipTest : EnginesFixture() {

    private val cangjie = CodeTable.Builder()
        .header("键码", "abcdefghijklmnopqrstuvwxyz")
        .header("码长", "5")
        .entry("ab", "丙")
        .entry("abr", "甲")
        .entry("abrr", "甲甲")
        .entry("abw", "乙")
        .entry("x", "难")
        .entry("xa", "难难")
        .build()
        .toByteArray()

    private fun engines() = Engines({ if (it == "${InputMethodCatalog.TABLE_DIR}/cj.data") ByteBuffer.wrap(cangjie) else load(it) }, null, warmUps = inPlace)

    // e tapped at its right edge, toward r: abe leads nowhere, abr does
    private val tap = Action.Key('e', touch = Touch(0.45f, 0f, 36f, 50f, listOf(Near('r', 1f, 0f))))

    @Test
    @Uat("TBL-56")
    fun aSlipIntoAnEmptyCodeIsReadAsTheKeyMeantUnlessSlipsAreOff() {
        val engines = engines()
        engines.type(InputMethodCatalog.CANGJIE, "ab")
        assertEquals(listOf("甲", "甲甲"), engines.onEvent(InputMethodCatalog.CANGJIE, EngineEvent.CHAR, 'e'.code, payload = tap).candidates)
        engines.onEvent(InputMethodCatalog.CANGJIE, EngineEvent.RESET, 0)
        engines.settings = EngineSettings(typos = false)
        engines.type(InputMethodCatalog.CANGJIE, "ab")
        assertEquals(emptyList<String>(), engines.onEvent(InputMethodCatalog.CANGJIE, EngineEvent.CHAR, 'e'.code, payload = tap).candidates)
    }

    // e tapped at its left edge, toward w: abw's lone 乙
    private val towardW = Action.Key('e', touch = Touch(-0.45f, 0f, 36f, 50f, listOf(Near('w', -1f, 0f))))

    @Test
    @Uat("TBL-54")
    fun aCodeReadFromASlipWaitsThenTheNextKeyThatCannotGoOnCommitsIt() {
        val engines = engines()
        engines.type(InputMethodCatalog.CANGJIE, "ab")
        val read = engines.onEvent(InputMethodCatalog.CANGJIE, EngineEvent.CHAR, 'e'.code, payload = towardW)
        assertEquals("", read.commit)
        assertEquals(listOf("乙"), read.candidates)
        // x cannot go on abw: 乙 committed, x the next code's
        val next = engines.onEvent(InputMethodCatalog.CANGJIE, EngineEvent.CHAR, 'x'.code)
        assertEquals("乙", next.commit)
        assertEquals(listOf("难", "难难"), next.candidates)
        // slips off: abe is an empty code, x goes into it, nothing committed
        engines.onEvent(InputMethodCatalog.CANGJIE, EngineEvent.RESET, 0)
        engines.settings = EngineSettings(typos = false)
        engines.type(InputMethodCatalog.CANGJIE, "ab")
        engines.onEvent(InputMethodCatalog.CANGJIE, EngineEvent.CHAR, 'e'.code, payload = towardW)
        assertEquals("", engines.onEvent(InputMethodCatalog.CANGJIE, EngineEvent.CHAR, 'x'.code).commit)
    }
}
