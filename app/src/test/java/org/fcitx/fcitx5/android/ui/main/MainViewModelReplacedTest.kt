/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.fcitx.fcitx5.android.FcitxApplication
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The settings started anew over itself (`am start -f 0x10008000`): the new activity's model is
 * made before the old one's is cleared. The old one clearing must leave the new one connected,
 * or the new activity's onStop (its save) throws "MainViewModel is disconnected".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class MainViewModelReplacedTest {

    private fun model(store: ViewModelStore) = ViewModelProvider.create(store)[MainViewModel::class.java]

    @Test
    fun theOldModelClearedLeavesTheNewOneConnected() {
        val old = ViewModelStore()
        val new = ViewModelStore()
        model(old)
        val current = model(new)
        old.clear()
        // what MainActivity.onStop does
        current.fcitx.runIfReady { save() }
        new.clear()
    }
}
