/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.daemon.HostDaemon
import org.fcitx.fcitx5.android.utils.appContext

class MainViewModel : ViewModel() {

    enum class ButtonMode { NONE, EDIT, DELETE }

    val toolbarTitle = MutableLiveData(appContext.getString(R.string.app_name))

    val toolbarButton = MutableLiveData(ButtonMode.NONE)

    // a name of this model's own: an activity made anew (a CLEAR_TASK start) gets its model before
    // the old one is cleared, and a shared name would disconnect the new one with the old
    private val clientName = "${javaClass.name}@${System.identityHashCode(this)}"

    val fcitx: HostConnection = HostDaemon.connect(clientName)

    fun setToolbarTitle(title: String) {
        toolbarTitle.value = title
    }

    override fun onCleared() {
        HostDaemon.disconnect(clientName)
    }
}