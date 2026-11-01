/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.uat

import android.os.Looper
import android.widget.TextView
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.EngineBackedHostApi
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.InputMethodNames
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.runtime.Engines
import org.fcitx.fcitx5.android.engine.runtime.UserWord
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.until
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.time.Duration
import java.util.concurrent.Executor

/**
 * uat/10-tables.md's page half: the user lexicon's row of a word a table cannot take, over the
 * real engine with the app's tables (the engine's half, typing, is TablesUatTest's in :lib:ime-eval).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class TablesSettingsUatTest {

    // the app's data as the build makes it into its assets. Not there (the data task not run) is a
    // failure that says so, not a skip: a skipped case would pass the UAT gate unrun
    private val roots = File("build/generated").listFiles().orEmpty().toList()

    private fun blob(path: String) = roots.map { File(it, path) }.firstOrNull { it.isFile }
        ?.let { f -> RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) } }
        ?: throw FileNotFoundException("$path not in build/generated: run ./gradlew :app:compileEngineData first")

    private val engines = Engines(blobs = ::blob, userDir = null, warmUps = Executor(Runnable::run))
    private val fake = FakeHostApi()
    private val st = SettingsHarness(fake, host = EngineBackedHostApi(engines, fake))

    @After
    fun close() {
        st.close()
        engines.close()
    }

    private fun shown() = KeyboardHarness.all(st.activity.window.decorView)
        .filter { it.isShown && it is TextView }.map { (it as TextView).text.toString() }.toList()

    @Test
    @Uat("TBL-12")
    fun aPinyinWordOnATablesOnlyCodeSaysWhichWord() {
        val wubi = InputMethodEntry(InputMethodCatalog.WUBI, "五笔", "", "", "五", "zh_CN", "androidengine", false)
        fake.availableIme = arrayOf(KeyboardHarness.PINYIN, wubi)
        fake.enabledIme = fake.availableIme
        assertTrue(runBlocking { engines.addWord("鲸园", "jing yuan") })
        st.activity
        st.open(SettingsRoute.UserWordList(UserWord.Kind.ADDED.name))
        val row = st.s(R.string.same_code_as, InputMethodNames.of(st.activity, wubi), "昏天黑地")
        // the row waits for 五笔's table to load: tens of seconds on a slow runner
        until("「$row」", { "in ${shown()}" }, timeoutMs = 60_000, step = { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }) {
            shown().any { row in it }
        }
        assertTrue(shown().any { it == "鲸园" })
    }
}
