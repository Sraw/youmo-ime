/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.fcitx.fcitx5.android.input.FcitxInputMethodService

/** fcitx is one per process: a test's engine would share events and data with this app's bound input method, which `Fcitx`'s guard does not check. */
fun checkThisInputMethodIsNotBound() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val device = UiDevice.getInstance(instrumentation)
    val pkg = instrumentation.targetContext.packageName
    val service = FcitxInputMethodService::class.java.name
    val keyboard = device.executeShellCommand("settings get secure default_input_method").trim()
    check(ComponentName.unflattenFromString(keyboard) != ComponentName(pkg, service)) {
        "the device's keyboard is this app, whose input method runs in this process: select another first (adb shell ime set <id>)"
    }
    check(service !in device.executeShellCommand("dumpsys activity services $pkg")) {
        "this app's input method is still bound in this process"
    }
}
