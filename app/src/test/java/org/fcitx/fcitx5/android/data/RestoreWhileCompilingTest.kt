/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.dirsUnder
import org.fcitx.fcitx5.android.data.pinyin.ImportedDictionaries
import org.fcitx.fcitx5.android.data.pinyin.MergedDictionary
import org.fcitx.fcitx5.android.until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A restore beside the compile thread, which may convert old texts as the copy writes them: the copy holds the sources lock. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class RestoreWhileCompilingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val copy = UserDataManager.copyMoved

    @Before
    fun quiet() {
        MergedDictionary.reset()
        MergedDictionary.made = {}
        MergedDictionary.base = { null }
    }

    @After
    fun putBack() {
        UserDataManager.copyMoved = copy
        MergedDictionary.reset()
    }

    // its metadata named as the app's (Robolectric's package may differ)
    private fun exported(dirs: UserDataManager.Dirs) = ByteArrayOutputStream().also { bytes ->
        val export = ByteArrayOutputStream().also { UserDataManager.export(it, 0L, dirs).getOrThrow() }
        ZipInputStream(export.toByteArray().inputStream()).use { from ->
            ZipOutputStream(bytes).use { zip ->
                generateSequence { from.nextEntry }.forEach {
                    zip.putNextEntry(ZipEntry(it.name))
                    if (it.name == "metadata.json") {
                        zip.write("""{"packageName":"${BuildConfig.APPLICATION_ID}","versionCode":${BuildConfig.VERSION_CODE},"versionName":"1","exportTime":0}""".encodeToByteArray())
                    } else {
                        from.copyTo(zip)
                    }
                }
            }
        }
    }.toByteArray().inputStream()

    // each Robolectric sandbox has its own compile thread of the same name: told apart by the lock
    private fun compileWaitsOn(): List<String> = Thread.getAllStackTraces().keys.filter { it.name == "dictionary-compile" }.map {
        ManagementFactory.getThreadMXBean().getThreadInfo(it.id)?.takeIf { info -> info.threadState == Thread.State.BLOCKED }?.lockInfo.toString()
    }

    private fun compileWaitsOnTheSourcesLock(): Boolean {
        val lock = System.identityHashCode(ImportedDictionaries)
        return Thread.getAllStackTraces().keys.filter { it.name == "dictionary-compile" }.any {
            ManagementFactory.getThreadMXBean().getThreadInfo(it.id)
                ?.let { info -> info.threadState == Thread.State.BLOCKED && info.lockInfo?.identityHashCode == lock } == true
        }
    }

    // a text the copy made but has not written yet: the conversion waits for the lock (unlocked, it committed it empty).
    // A conversion already reading is covered by ImportedDictionariesTest's length and time check
    @Test
    fun aRestoredTextIsNotConvertedHalfWritten() {
        val old = dirsUnder(File(folder.root, "old"))
        File(old.data, "pinyin/dictionaries/big.txt").apply { parentFile!!.mkdirs() }.writeText("搭子 da'zi 0.0\n妮 ni 0.0\n你 ni 0.0\n")
        val fresh = dirsUnder(File(folder.root, "fresh"))
        val dictionaries = File(fresh.data, "pinyin/dictionaries")
        UserDataManager.copyMoved = { source, target ->
            val text = File(source, "pinyin/dictionaries/big.txt")
            val bytes = text.takeIf { it.isFile }?.readBytes()?.also { text.delete() }
            copy(source, target)
            if (bytes != null) {
                File(target, "pinyin/dictionaries/big.txt").outputStream().use { out ->
                    val compile = checkNotNull(MergedDictionary.refresh(dictionaries))
                    until("the compile done or waiting on the sources lock", { "done ${compile.isDone}, waits on ${compileWaitsOn()}" }) {
                        compile.isDone || compileWaitsOnTheSourcesLock()
                    }
                    out.write(bytes)
                }
            }
        }
        UserDataManager.import(exported(old), fresh).getOrThrow()
        MergedDictionary.refreshNow(dictionaries)
        assertEquals(mapOf("big" to 3), MergedDictionary.sourceWords(dictionaries))
    }
}
