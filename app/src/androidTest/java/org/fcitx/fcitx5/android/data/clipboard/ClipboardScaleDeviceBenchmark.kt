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
 * [ClipboardScale] on a device, in a folder of the app's cache (the app's own clipboard untouched).
 * Run only when given the sizes, in the `sizes` argument:
 * `adb shell am instrument -w -e class org.fcitx.fcitx5.android.data.clipboard.ClipboardScaleDeviceBenchmark
 * -e sizes 10000,100000 <test package>/androidx.test.runner.AndroidJUnitRunner`; each size's times
 * in the instrumentation's output and in logcat (`ClipboardScale`).
 */
class ClipboardScaleDeviceBenchmark {

    @Test
    fun timesAtEachSize() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // asked for only: the emulator's run of every instrumented test skips it, as EngineEvalRunner
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
