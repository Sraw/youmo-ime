/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.util.TypedValue
import android.view.Gravity
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import splitties.dimensions.dp
import splitties.views.horizontalPadding

class PreeditComponent : UniqueComponent<PreeditComponent>(), Dependent, InputBroadcastReceiver,
    ManagedHandler by managedHandler() {

    private val context by manager.context()
    private val theme by manager.theme()
    private val service by manager.inputMethodService()

    val ui by lazy {
        CodeRowUi(context, theme, setupTextView = {
            // in dp: the row's height is fixed, and a large font scale would cut it off
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            horizontalPadding = dp(8)
            isSingleLine = true
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }).apply {
            // on InputView's barBackground, with the bar: one taller bar. A tap puts the cursor in
            // the code, for a key missed in the middle of it to be typed or deleted there; queued
            // after the keys typed before it, which go in where the cursor was. The place is one in
            // the code tapped on: a key still queued that changed the code leaves it nowhere meant
            onTap = { at ->
                val tapped = shown
                service.postFcitxJob { if (inputPanelCached.preedit.toString() == tapped) moveCursor(at) }
            }
        }
    }

    // the code on the row, as a tap on it found it
    private var shown = ""

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        shown = data.preedit.toString()
        ui.update(data)
    }
}
