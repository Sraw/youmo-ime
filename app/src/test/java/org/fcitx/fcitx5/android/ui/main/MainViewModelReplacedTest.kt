/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.daemon.HostDaemon
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Started anew over itself (`am start -f 0x10008000`): the old model clearing must leave the new one connected. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class MainViewModelReplacedTest {

    private fun model(store: ViewModelStore) = ViewModelProvider.create(store)[MainViewModel::class.java]

    @Test
    fun theOldModelClearedLeavesTheNewOneConnected() {
        // the real daemon's names, not a fake engine's
        assertNull(HostDaemon.testConnection)
        val old = ViewModelStore()
        val new = ViewModelStore()
        try {
            model(old)
            val current = model(new)
            old.clear()
            // what MainActivity.onStop does
            current.fcitx.runIfReady { save() }
        } finally {
            new.clear()
        }
        // both gone: the host free to stop
        assertNull(HostDaemon.getFirstConnectionOrNull())
    }
}
