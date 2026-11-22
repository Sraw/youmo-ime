/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import android.app.ActivityManager
import android.content.Context

/** A phone whose image caches are kept smaller: one the system calls low on RAM, or whose app heap is small. */
object LowMemory {

    const val MAX_HEAP_MB = 192

    fun of(lowRamDevice: Boolean, memoryClassMb: Int): Boolean = lowRamDevice || memoryClassMb <= MAX_HEAP_MB

    fun on(context: Context): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return true
        return of(am.isLowRamDevice, am.memoryClass)
    }
}
