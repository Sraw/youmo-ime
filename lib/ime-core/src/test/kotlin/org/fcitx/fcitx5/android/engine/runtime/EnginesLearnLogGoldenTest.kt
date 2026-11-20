/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.runtime

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.learn.LearnLog
import org.fcitx.fcitx5.android.engine.learn.control.LearningOp
import org.fcitx.fcitx5.android.engine.learn.control.LearningResult
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFile
import org.fcitx.fcitx5.android.engine.platform.jvm.JvmFiles
import org.fcitx.fcitx5.android.host.VirtualHostClock
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The learning record a scripted session writes, byte for byte as golden. Record a meant change with `GOLDEN_RECORD=1`.
 *
 * contract: the record's bytes as the golden file has them, which mutation cannot see.
 */
class EnginesLearnLogGoldenTest : EnginesFixture() {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aScriptedSessionWritesTheRecordedLog() {
        val bytes = script()
        val golden = File(GOLDEN)
        if (System.getenv("GOLDEN_RECORD") == "1") {
            golden.parentFile.mkdirs()
            golden.writeBytes(bytes)
        }
        val expected = golden.readBytes()
        if (!expected.contentEquals(bytes)) {
            val actual = File("build/golden-learn.actual.log").apply { parentFile.mkdirs(); writeBytes(bytes) }
            throw AssertionError(
                "learn.log differs from the golden (${expected.size} bytes, now ${bytes.size}): " +
                    "${actual.absolutePath}; replayed now: ${LearnLog.replay(bytes).dump()}",
            )
        }
    }

    // the keys and acts, as a user's morning: what the record holds after each kind of thing it records
    private fun script(): ByteArray {
        val dir = folder.newFolder("user")
        val install = folder.newFile("install-id").apply { writeText("4242424242") }
        // 2026-10-29 09:00 UTC
        val clock = VirtualHostClock(epochMillis = 1_793_264_400_000L)
        val engines = Engines(
            ::load, JvmFiles(dir), warmUps = inPlace, installFile = JvmFile(install), appVersion = "golden",
            clock = clock,
        )
        fun step() = clock.advance(STEP)
        engines.settings = wubiShown
        engines.context(InputMethodCatalog.PINYIN, "", started = true)
        engines.type(InputMethodCatalog.PINYIN, "ni")
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code)
        step()
        engines.type(InputMethodCatalog.PINYIN, "ni")
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.PICK, engines.index("拟"))
        step()
        engines.type(InputMethodCatalog.PINYIN, "haoni")
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.CHAR, ' '.code)
        step()
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
        engines.context(InputMethodCatalog.PINYIN, "")
        engines.type(InputMethodCatalog.PINYIN, "ni")
        engines.learning(InputMethodCatalog.PINYIN, LearningOp.Pin(engines.index("拟"), "拟"))
        step()
        val forgot = engines.learning(InputMethodCatalog.PINYIN, LearningOp.Delete(engines.index("你"), "你"))
        step()
        if (forgot is LearningResult.Done) engines.learning(InputMethodCatalog.PINYIN, LearningOp.Undo(forgot.undo))
        engines.onEvent(InputMethodCatalog.PINYIN, EngineEvent.RESET, 0)
        step()
        engines.context(InputMethodCatalog.WUBI, "", started = true)
        engines.type(InputMethodCatalog.WUBI, "wqiy")
        engines.onEvent(InputMethodCatalog.WUBI, EngineEvent.CHAR, ' '.code)
        step()
        engines.context(InputMethodCatalog.ENGLISH, "", started = true)
        engines.type(InputMethodCatalog.ENGLISH, "the")
        engines.onEvent(InputMethodCatalog.ENGLISH, EngineEvent.CHAR, ' '.code)
        engines.addEnglishWord("youmo")
        step()
        // the lists: a word added and one blocked by hand, a list imported
        engines.addWord("你好", "ni hao")
        engines.blockWord("拟好", "ni hao")
        step()
        engines.importWords(sequenceOf("好你 hao'ni 3"), "words.txt")
        step()
        engines.close()
        return File(dir, LearnLog.FILE).readBytes().also { assertTrue(it.isNotEmpty()) }
    }

    private fun Engines.index(text: String): Int = candidates(InputMethodCatalog.PINYIN, 0, 10).indexOfFirst { it.text == text }.also {
        assertTrue("$text among the candidates", it >= 0)
    }

    private companion object {
        const val STEP = 2_000L
        const val GOLDEN = "src/test/resources/learn/golden-learn.log"
    }
}
