/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

// listeners added and removed on one thread while another tells them of entries: no
// ConcurrentModificationException (the telling thread's scope has no handler: one would crash the app)
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ClipboardListenersTest {
    @Test
    fun toldWhileAddedAndRemovedThrowsNothing() {
        val kept = List(8) { ClipboardManager.OnClipboardUpdateListener { } }
        kept.forEach(ClipboardManager::addOnUpdateListener)
        val failed = AtomicReference<Throwable?>()
        val go = CountDownLatch(1)
        val teller = thread {
            go.await()
            runCatching { repeat(ROUNDS) { ClipboardManager.updateLastEntry(ClipboardEntry(text = "e$it")) } }
                .onFailure(failed::set)
        }
        go.countDown()
        runCatching {
            repeat(ROUNDS) {
                val l = ClipboardManager.OnClipboardUpdateListener { }
                ClipboardManager.addOnUpdateListener(l)
                ClipboardManager.removeOnUpdateListener(l)
            }
        }.onFailure(failed::set)
        teller.join()
        kept.forEach(ClipboardManager::removeOnUpdateListener)
        ClipboardManager.lastEntry = null
        assertNull(failed.get())
    }

    private companion object {
        const val ROUNDS = 20_000
    }
}
