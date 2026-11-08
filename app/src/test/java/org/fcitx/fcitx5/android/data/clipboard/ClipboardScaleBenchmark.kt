/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.FcitxApplication
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [ClipboardScale] on the JVM: 100 entries by default, a smoke run beside the other tests; the sizes to
 * measure in `CLIPBOARD_BENCH`, comma separated (`CLIPBOARD_BENCH=10000,100000 ./gradlew
 * :app:testDebugUnitTest --tests '*ClipboardScaleBenchmark'`), the times in the test's standard
 * output (its XML report). The device's: ClipboardScaleDeviceBenchmark.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ClipboardScaleBenchmark {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sizes = System.getenv("CLIPBOARD_BENCH")?.split(',')?.map { it.trim().toInt() } ?: listOf(100)

    @Test
    fun timesAtEachSize() = runBlocking {
        for (size in sizes) println(ClipboardScale.measure(RuntimeEnvironment.getApplication(), tmp.newFolder("n$size"), size))
    }
}
