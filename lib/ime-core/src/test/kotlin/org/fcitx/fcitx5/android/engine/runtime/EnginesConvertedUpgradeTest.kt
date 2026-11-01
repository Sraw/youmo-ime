/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.InputMethodCatalog.WUBI
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFile
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.host.VirtualHostClock
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A 0.4.0 install that converted a 0.3.0 one (src/test/resources/upgrade/v0.4.0-converted, made by
 * 2184786dc from 0.3.0's logs: 五笔's saved, picked and forgotten phrases, a pin that was an order-1
 * custom phrase, English's words), opened by this build: the table's phrases, the pin, a forget of a
 * saved phrase and English's words as 2184786dc had them (F2 review 2). Recorded with 2184786dc;
 * a change that is meant is recorded again with `GOLDEN_RECORD=1`. A pick 0.3.0 logged of what the
 * table has not (vbvn 好女), which 0.4's table log kept offering, is read from that log once
 * (EnginesOlderTableLogTest).
 *
 * contract: a 0.4.0 install's files and the golden recorded at 2184786dc, which mutation cannot see.
 */
class EnginesConvertedUpgradeTest : EnginesFixture() {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aV040InstallThatConvertedAnOlderOneOpensAsItDid() {
        val got = opened()
        assertGolden(File(GOLDEN), got)
    }

    private fun opened(): String {
        val fixture = File(FIXTURE)
        val dir = folder.newFolder("engine")
        File(fixture, "engine").copyRecursively(dir)
        val install = File(folder.root, "learn.install").also { File(fixture, "learn.install").copyTo(it) }
        val phrases = File(fixture, "pinyin/customphrase").readText()
        // an hour after the fixture was made, the same typing day
        val clock = VirtualHostClock(epochMillis = MADE_AT + 3_600_000L)
        val errors = ArrayList<String>()
        val out = StringBuilder()
        fun open() = Engines(
            ::load, JvmFiles(dir), onError = { errors += it.toString() },
            additions = { Engines.Additions(phrases, "", dictionary = { null }) },
            warmUps = inPlace, installFile = JvmFile(install), appVersion = "0.4.0+test",
            clock = clock,
        ).also { it.settings = wubiShown }
        fun Engines.wubi(keys: String): List<String> = type(WUBI, keys).candidates.also { onEvent(WUBI, EngineEvent.RESET, 0) }
        open().use { engines ->
            engines.context(InputMethodCatalog.PINYIN, "", started = true)
            out.append("nh: ").append(engines.type(InputMethodCatalog.PINYIN, "nh").candidates.take(3)).append('\n')
            engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
            // two saved under one code, 好妮 first in the log: as 0.4 ranked them (F2 review 3)
            out.append("vbwq: ").append(engines.wubi("vbwq")).append('\n')
            // 0.3.0's pick of what the table has not: 0.4 offered it from its table's log
            out.append("vbvn: ").append(engines.wubi("vbvn")).append('\n')
            out.append("abcd: ").append(engines.wubi("abcd").take(3)).append('\n')
            out.append("kkkk: ").append(engines.wubi("kkkk").take(3)).append('\n')
            val shown = engines.type(WUBI, "vbwq").candidates
            engines.onEvent(WUBI, EngineEvent.FORGET, shown.indexOf("好妮"))
            engines.onEvent(WUBI, EngineEvent.RESET, 0)
            out.append("vbwq, 好妮 forgotten: ").append(engines.wubi("vbwq")).append('\n')
            clock.advance(1_000)
            for (kind in UserWord.Kind.entries) out.append("$kind: ").append(engines.userWords(kind).map { it.text to it.pinyin }.sortedBy { it.first }).append('\n')
            out.append("english: ").append(engines.englishWords().map { it.text }.sorted()).append('\n')
        }
        clock.advance(60_000)
        open().use { engines ->
            out.append("again vbwq: ").append(engines.wubi("vbwq")).append('\n')
            out.append("again vbvn: ").append(engines.wubi("vbvn")).append('\n')
            out.append("again nh: ").append(engines.type(InputMethodCatalog.PINYIN, "nh").candidates.take(3)).append('\n')
        }
        out.append("pins: ").append(LearnLog.replay(File(dir, LearnLog.FILE).readBytes()).pins.map { (code, words) -> "$code=${words.text}" }.sorted()).append('\n')
        out.append("errors: ").append(errors).append('\n')
        return out.toString()
    }

    private companion object {
        const val FIXTURE = "src/test/resources/upgrade/v0.4.0-converted"
        const val GOLDEN = "$FIXTURE/opened.txt"
        const val MADE_AT = 1_793_335_172_220L
    }
}
