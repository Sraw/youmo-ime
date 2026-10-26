/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

/**
 * The host the daemon runs, [KotlinFcitx]; it keeps [FcitxAPI] and the event flow as callers knew
 * them from the native one (fcitx-replacement §4).
 */
interface FcitxInstance : FcitxAPI, FcitxLifecycleOwner {
    fun start()
    fun stop()
}
