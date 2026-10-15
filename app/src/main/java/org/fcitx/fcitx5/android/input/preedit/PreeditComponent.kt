/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.context
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

    val ui by lazy {
        PreeditUi(context, theme, oneLine = true, setupTextView = {
            // in dp: the row's height is fixed, and a large font scale would cut it off
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            horizontalPadding = dp(8)
            isSingleLine = true
            includeFontPadding = false
            // a long code keeps its end, where the typing is, in sight
            ellipsize = TextUtils.TruncateAt.START
        }).apply {
            // on InputView's barBackground, with the bar: one taller bar
            (root as LinearLayout).gravity = Gravity.CENTER_VERTICAL
        }
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        ui.update(data)
    }
}
