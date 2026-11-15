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
 * Whether the letters of [view] are written on now (HandwritingMode.writes, 061-handwriting-toolbar §3.4): the
 * toolbar's 手写 ([open]) on, an input method that writes ([im]), not a [password] field nor a temporary mode
 * ([tempMode]), not while TalkBack explores by touch, the recognizer loadable; shown, the [area] faded. Kept,
 * and worked out again ([update]) as one of those changes, not at each frame drawn: the recognizer is asked
 * last, so none is made where nothing would be written. The switch and TalkBack are heard while [view] is on
 * the window ([attached]).
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

    /** Whether the letters are written on, as last worked out. */
    var now = false
        private set

    private val accessibility = view.context.getSystemService(AccessibilityManager::class.java)
    private var exploring = false
    private var attached = false

    private val onSwitch = ManagedPreference.OnChangeListener<Boolean> { _, on ->
        // turned off: the character being written is done as it is, what it read left typed (§3.4)
        if (on) ink.warmUp() else ink.hidden()
        update()
        // turned on with the recognizer given up before: off again at once, so told again why (not a switch that does nothing)
        if (on && !open.getValue()) EngineNotices.failed(R.string.engine_unloadable)
    }

    // the text pack imported or removed while shown: what is written on worked out again (PackStore.state is told on any thread)
    private var packs: CoroutineScope? = null

    private val onExploring = AccessibilityManager.TouchExplorationStateChangeListener {
        exploring = it
        update()
    }

    /** On the window: the switch, TalkBack, the packs and the engine's pieces ended heard; once, however often told. */
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

    /** Off the window (hidden, another layout shown): no longer heard, what was written done ([KeyInk.detached]). */
    fun detached() {
        if (!attached) return
        attached = false
        open.unregisterOnChangeListener(onSwitch)
        accessibility?.removeTouchExplorationStateChangeListener(onExploring)
        ink.detached()
        packs?.cancel()
        packs = null
    }

    /** Worked out again, the letters drawn again where that changed. */
    fun update() {
        val would = open.getValue() &&
            HandwritingMode.writes(true, im(), password(), tempMode(), recognizerLoadable = true, touchExploring = exploring)
        now = would && PackStore.ready(PackKind.TEXT) && ink.loadable
        // the recognizer given up (§3.4): the switch off, what failed told where it did (AssetRecognizer.told)
        if (would && !now && PackStore.ready(PackKind.TEXT)) open.setValue(false)
        area.fade(now, canvas())
    }
}
