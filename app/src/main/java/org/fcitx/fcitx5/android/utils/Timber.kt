/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import android.util.Log
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.core.CoreLog
import timber.log.Timber

class VerboseTree : Timber.DebugTree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        super.log(priority, "[${Thread.currentThread().name}] $tag", message, t)
    }
}

class ConciseTree : Timber.Tree() {
    // "tag" is only available when calling with Timber.tag().log(), which we didn't
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.INFO) return
        Log.println(priority, "[${Thread.currentThread().name}]", message)
    }
}

fun Timber.Forest.setupForest(verbose: Boolean) {
    if (treeCount > 0) {
        uprootAll()
    }
    val debugLogging = BuildConfig.DEBUG || verbose
    plant(if (debugLogging) VerboseTree() else ConciseTree())
    // :lib:ime-core is a plain JVM module and cannot depend on Timber. Attaching the sink here
    // keeps it in step with the tree, and leaving it null when debug logging is off preserves
    // the old behaviour of not formatting messages that would be dropped anyway.
    CoreLog.sink = if (debugLogging) ({ Timber.tag("ime-core").d(it) }) else null
}
