/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFile
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.host.VirtualHostClock
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The engine's files 0.4.0 wrote (uat/fixtures/upgrade/v0.4.0, its `files/engine/` and the custom
 * phrase, under src/test/resources/upgrade/v0.4.0) opened by this build, typed in and closed: what
 * it reads of them and what it leaves, as 2184786dc did (F2 S0, review B 1.4). Deleting the older
 * builds' conversion must not change how a 0.4.0 install opens. A change that is meant is recorded
 * again with `GOLDEN_RECORD=1`.
 *
 * contract: a 0.4.0 install's files and the golden recorded at 2184786dc, which mutation cannot see.
 */
class EnginesUpgradeTest : EnginesFixture() {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aV040InstallOpensAsItDid() {
        val got = opened()
        assertGolden(File(GOLDEN), got)
    }

    private fun opened(): String {
        val fixture = File(FIXTURE)
        val dir = folder.newFolder("engine")
        File(fixture, "engine").copyRecursively(dir)
        val install = File(folder.root, "learn.install").also { File(fixture, "learn.install").copyTo(it) }
        val phrases = File(fixture, "pinyin/customphrase").readText()
        // ten minutes after the fixture's last backup, the same typing day
        val clock = VirtualHostClock(epochMillis = 1_793_335_172_220L + 600_000L)
        val errors = ArrayList<String>()
        val out = StringBuilder()
        val engines = Engines(
            ::load, JvmFiles(dir), onError = { errors += it.toString() },
            additions = { Engines.Additions(phrases, "", dictionary = { null }) },
            warmUps = inPlace, installFile = JvmFile(install), appVersion = "0.4.0+test",
            clock = clock,
        )
        engines.context(InputMethodCatalog.PINYIN, "", started = true)
        out.append("dz: ").append(engines.type(InputMethodCatalog.PINYIN, "dz").candidates.take(3)).append('\n')
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
        engines.type(InputMethodCatalog.PINYIN, "ni")
        out.append("ni: ").append(engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code).commit).append('\n')
        clock.advance(1_000)
        for (kind in UserWord.Kind.entries) out.append("$kind: ").append(engines.userWords(kind).map { it.text to it.pinyin }).append('\n')
        out.append("english: ").append(engines.englishWords().map { it.text }).append('\n')
        engines.close()
        out.append("files: ").append(dir.walk().filter { it.isFile }.map { it.relativeTo(dir).path }.sorted().toList()).append('\n')
        out.append("errors: ").append(errors).append('\n')
        out.append("state: ").append(LearnLog.replay(File(dir, LearnLog.FILE).readBytes()).dump()).append('\n')
        return out.toString()
    }

    private companion object {
        const val FIXTURE = "src/test/resources/upgrade/v0.4.0"
        const val GOLDEN = "$FIXTURE/opened.txt"
    }
}
