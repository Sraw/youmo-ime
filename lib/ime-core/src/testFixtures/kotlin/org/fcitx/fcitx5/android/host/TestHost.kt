/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.InputMethodCatalog

/** A first-run profile with every listed input method, [defaultName] its group. */
fun testProfile(defaultName: String = "Default"): Profile =
    Profile.load(null, InputMethodCatalog.LISTED.map { it.name }.toSet(), defaultName)

/** Everything [this] tells the app from now on, in order. */
fun HostCore.log(): MutableList<HostEvent> = mutableListOf<HostEvent>().also { l -> events += HostEvents { l += it } }
