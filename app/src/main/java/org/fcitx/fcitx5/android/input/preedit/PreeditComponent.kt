/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.preedit

import android.util.TypedValue
import android.view.Gravity
import org.fcitx.fcitx5.android.host.HostEvent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.TypedComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.horizontalPadding

/** [floats]: in the strip over the app, not embedded in the keyboard. */
class PreeditComponent(private val floats: Boolean = false) : TypedComponent<PreeditComponent>(), Dependent, InputBroadcastReceiver,
    ManagedHandler by managedHandler() {

    private val context by manager.context()
    private val theme by manager.theme()
    private val service by manager.inputMethodService()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()

    val ui by lazy {
        CodeRowUi(context, theme, wrap = floats, setupTextView = {
            // in dp: the row's height is fixed, and a large font scale would cut it off
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (floats) 15f else 14f)
            horizontalPadding = dp(8)
            isSingleLine = true
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }).apply {
            // queued after the keys typed before it, which go in where the cursor was: a key still queued that changed the
            // code leaves the place nowhere meant
            onTap = { at ->
                val tapped = shown
                service.postFcitxJob { if (inputPanelCached.preedit.toString() == tapped) moveCursor(at) }
            }
            // a syllable taken goes as 九键's column's does, through the keys' listener
            onNotice = { service.writeKept() }
            syllables.onPick = { commonKeyActionListener.listener.onKeyAction(it, KeyActionListener.Source.Keyboard) }
            syllables.onShownChanged = { onShown?.invoke() }
        }
    }

    private var shown = ""

    // drawn again as the code moves into the field or out of it
    private var last: HostEvent.InputPanel? = null

    /** With the code in the app's field the syllables alone may be there. */
    var onShown: (() -> Unit)? = null

    override fun onInputPanelUpdate(data: HostEvent.InputPanel) {
        last = data
        // the code in the app's field is not drawn here too: aux and the syllables are
        val drawn = service.forStrip(data)
        shown = drawn.preedit.toString()
        ui.notice = service.keptLine?.length ?: 0
        ui.update(drawn)
        onShown?.invoke()
    }

    fun codePlaceChanged() {
        // with nothing typed since the view was made the kept line alone may be to draw
        (last ?: HostEvent.InputPanel.EMPTY.takeIf { service.keptLine != null })?.let(::onInputPanelUpdate)
    }
}
