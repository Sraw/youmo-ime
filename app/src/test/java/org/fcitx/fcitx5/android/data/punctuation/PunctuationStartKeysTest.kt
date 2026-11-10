/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.punctuation

import android.content.Context
import android.content.ContextWrapper
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

// the file kept from the last run: what it wrote read back; an empty, torn or other-version file none kept (the
// keys held back as on a first install), never punctuation off (S7a round 4)
@RunWith(RobolectricTestRunner::class)
class PunctuationStartKeysTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val file get() = File(ctx.createDeviceProtectedStorageContext().filesDir, "punctuation-start-keys")

    // where 0.6's betas kept it: the credential store
    private val old get() = File(ctx.filesDir, "punctuation-start-keys")

    @After
    fun forget() {
        PunctuationStartKeys.forget(ctx)
        old.delete()
    }

    private fun readBack(): Map<String, String>? {
        PunctuationStartKeys.loadNow(ctx)
        return PunctuationStartKeys.known
    }

    @Test
    fun writtenReadsBack() {
        val keys = mapOf("," to "、", "." to "。")
        PunctuationStartKeys.write(ctx, keys)
        assertEquals(keys, readBack())
        PunctuationStartKeys.write(ctx, emptyMap())
        assertEquals(emptyMap<String, String>(), readBack())
    }

    @Test
    fun anEmptyTornOrOtherFileIsNoneKept() {
        PunctuationStartKeys.write(ctx, mapOf("," to "、", "." to "。"))
        val whole = file.readText()
        assertRows(
            listOf(
                "empty" to "",
                "torn short of its last line" to whole.substring(0, whole.lastIndexOf(',')),
                "another version's" to whole.replaceFirst("v1", "v9"),
                "no header" to "，\n",
                "a line without its tab" to "v1\t2\n,\t、\n.",
                "a line's value empty" to "v1\t2\n,\t、\n.\t",
            ),
            { (why, bad) -> "$why <$bad>" },
        ) { (_, bad) ->
            file.writeText(bad)
            assertNull(readBack())
        }
    }

    @Test
    fun theCopyKeptWhereBetasKeptItMovesOnceToTheDeviceProtectedStore() {
        PunctuationStartKeys.write(ctx, mapOf("," to "、", "." to "。"))
        val text = file.readText()
        PunctuationStartKeys.forget(ctx)
        // the two stores one directory: nothing to move, the test skipped (shown so) rather than passing unchecked
        assumeFalse("one store", old.absolutePath == file.absolutePath)
        old.writeText(text)
        // the user unlocked, no device-protected copy: moved over and read, the old one gone
        assertEquals(mapOf("," to "、", "." to "。"), readBack())
        assertEquals(text, file.readText())
        assertEquals(false, old.exists())
        // a device-protected copy there (the host wrote it before the first unlock): an old one left is not
        // read over it, and is deleted
        old.writeText("v1\t1\n,\t，\n")
        assertEquals(mapOf("," to "、", "." to "。"), readBack())
        assertEquals(false, old.exists())
    }

    // before the first unlock (direct boot) the credential store's copy is left where it is, not read
    // nor deleted: moved once the user has unlocked
    @Test
    fun beforeTheUserUnlocksTheOldCopyStaysWhereItIs() {
        PunctuationStartKeys.write(ctx, mapOf("," to "、", "." to "。"))
        val text = file.readText()
        PunctuationStartKeys.forget(ctx)
        assumeFalse("one store", old.absolutePath == file.absolutePath)
        old.writeText(text)
        shadowOf(ctx.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        assertNull(readBack())
        assertEquals(text, old.readText())
        assertEquals(false, file.exists())
    }

    // as on disk once preloaded: the host's first write of the same keys is no rewrite (F5868)
    @Test
    fun theSameKeysAsPreloadedAreNotWrittenAgain() {
        val keys = mapOf("," to "、", "." to "。")
        PunctuationStartKeys.write(ctx, keys)
        asAfterARestart()
        PunctuationStartKeys.preload(ctx)
        val deadline = System.currentTimeMillis() + 5_000
        while (PunctuationStartKeys.known == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(keys, PunctuationStartKeys.known)
        file.delete()
        PunctuationStartKeys.write(ctx, keys)
        assertEquals(false, file.exists())
    }

    // marked written only once on disk: a write that failed is tried again at the next (F5982)
    @Test
    fun aWriteThatFailedIsTriedAgain() {
        val keys = mapOf("," to "、")
        file.parentFile!!.mkdirs()
        file.delete()
        // a directory with a file in it where the file goes: the rename fails
        file.mkdirs()
        File(file, "x").writeText("x")
        PunctuationStartKeys.write(ctx, keys)
        file.deleteRecursively()
        PunctuationStartKeys.write(ctx, keys)
        assertEquals(true, file.isFile)
        assertEquals(keys, readBack())
    }

    // one store (no device-protected part of its own: the two paths one file): its only copy kept, not
    // deleted as the old one
    @Test
    fun oneStoreKeepsItsOnlyCopy() {
        val one = object : ContextWrapper(ctx) {
            override fun createDeviceProtectedStorageContext(): Context = this
        }
        val only = File(one.filesDir, "punctuation-start-keys")
        only.writeText("v1\t1\n,\t，\n")
        try {
            PunctuationStartKeys.migrate(one)
            assertEquals(true, only.isFile)
        } finally {
            only.delete()
        }
    }

    // the in-memory state as at a process start, the file kept
    private fun asAfterARestart() {
        val text = file.readText()
        PunctuationStartKeys.forget(ctx)
        file.writeText(text)
    }
}
