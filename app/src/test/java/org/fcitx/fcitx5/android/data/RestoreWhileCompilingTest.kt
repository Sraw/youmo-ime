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

/**
 * A restore beside the dictionaries' compile thread (MergedDictionary), which may be converting an
 * older build's texts as the copy writes them: the copy holds the sources lock (ImportedDictionaries).
 */
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

    // [dirs] exported, its metadata named as the app's (the package Robolectric runs it as may differ)
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

    // the lock the compile thread waits to take, if it waits on one
    private fun compileWaitsOn(): String? = Thread.getAllStackTraces().keys.firstOrNull { it.name == "dictionary-compile" }
        ?.let { ManagementFactory.getThreadMXBean().getThreadInfo(it.id)?.takeIf { info -> info.threadState == Thread.State.BLOCKED }?.lockName }

    // an older build's text the copy has made and not yet written to as a conversion lists it: the conversion
    // waits for the copy and finds the text whole. Without the lock it was committed empty and deleted under the
    // copy's write, its words gone. The copy's stand-in stops there till the compile is done or waits on the lock
    // Times one interleaving: the compile queued behind the copy's lock. A conversion already reading the text
    // as the copy writes it is kept by the commit's length and time check (ImportedDictionariesTest's
    // aConversionIsCommittedAsTheTextIsThen)
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
                        compile.isDone || compileWaitsOn()?.startsWith(ImportedDictionaries::class.java.name + "@") == true
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
