/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.view.View
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineNotices
import org.fcitx.fcitx5.android.data.pack.PackStore
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.pack.PackKind

/**
 * Kept, and worked out again as one of its inputs changes, not at each frame drawn: the recognizer is asked
 * last, so none is made where nothing would be written. Not while TalkBack explores by touch.
 */
internal class KeyWriting(
    private val view: View,
    private val ink: KeyInk,
    private val area: LetterArea,
    private val open: ManagedPreference.PBool,
    private val canvas: () -> KeyboardCanvasView?,
    private val im: () -> String,
    private val password: () -> Boolean,
    private val tempMode: () -> Boolean,
) {

    var now = false
        private set

    private val accessibility = view.context.getSystemService(AccessibilityManager::class.java)
    private var exploring = false
    private var attached = false

    private val onSwitch = ManagedPreference.OnChangeListener<Boolean> { _, on ->
        // turned off: the character being written is done as it is, what it read left typed
        if (on) ink.warmUp() else ink.hidden()
        update()
        // the recognizer given up before: off again at once, so told again why (not a switch that does nothing)
        if (on && !open.getValue()) EngineNotices.failed(R.string.engine_unloadable)
    }

    // PackStore.state is told on any thread
    private var packs: CoroutineScope? = null

    private val onExploring = AccessibilityManager.TouchExplorationStateChangeListener {
        exploring = it
        update()
    }

    /** Once, however often told. */
    fun attached() {
        if (attached) return
        attached = true
        open.registerOnChangeListener(onSwitch)
        accessibility?.addTouchExplorationStateChangeListener(onExploring)
        exploring = accessibility?.isTouchExplorationEnabled == true
        ink.attached()
        packs = CoroutineScope(Dispatchers.Main.immediate + Job()).also { s ->
            s.launch { PackStore.state.drop(1).collect { update() } }
        }
        update()
    }

    fun detached() {
        if (!attached) return
        attached = false
        open.unregisterOnChangeListener(onSwitch)
        accessibility?.removeTouchExplorationStateChangeListener(onExploring)
        ink.detached()
        packs?.cancel()
        packs = null
    }

    fun update() {
        val would = open.getValue() &&
            HandwritingMode.writes(true, im(), password(), tempMode(), recognizerLoadable = true, touchExploring = exploring)
        now = would && PackStore.ready(PackKind.TEXT) && ink.loadable
        // the recognizer given up: the switch off, what failed told where it did
        if (would && !now && PackStore.ready(PackKind.TEXT)) open.setValue(false)
        area.fade(now, canvas())
    }
}
