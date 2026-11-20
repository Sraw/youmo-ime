/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.punctuation

import androidx.test.core.app.ApplicationProvider
import org.fcitx.fcitx5.android.engine.assertRows
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// an empty, torn or other-version file keeps none (the keys held back as on a first install), never punctuation off
@RunWith(RobolectricTestRunner::class)
class PunctuationStartKeysTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val file get() = File(ctx.createDeviceProtectedStorageContext().filesDir, "punctuation-start-keys")

    @After
    fun forget() {
        PunctuationStartKeys.forget(ctx)
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

    // as on disk once preloaded: the host's first write of the same keys is no rewrite
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

    // marked written only once on disk: a write that failed is tried again at the next
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

    private fun asAfterARestart() {
        val text = file.readText()
        PunctuationStartKeys.forget(ctx)
        file.writeText(text)
    }
}
