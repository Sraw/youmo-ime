/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * [ClipboardScale] on a device, in the app's cache. Runs only when given sizes:
 * `adb shell am instrument -w -e class org.fcitx.fcitx5.android.data.clipboard.ClipboardScaleDeviceBenchmark
 * -e sizes 10000,100000 <test package>/androidx.test.runner.AndroidJUnitRunner`.
 */
class ClipboardScaleDeviceBenchmark {

    @Test
    fun timesAtEachSize() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val asked = InstrumentationRegistry.getArguments().getString("sizes")
        assumeTrue("no -e sizes", asked != null)
        val sizes = asked!!.split(',').map { it.trim().toInt() }
        for (size in sizes) {
            val root = File(context.cacheDir, "clipboard-scale-$size").apply { deleteRecursively() }
            val times = ClipboardScale.measure(context, root, size)
            root.deleteRecursively()
            Log.i("ClipboardScale", times.toString())
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$times\n") })
        }
    }
}
