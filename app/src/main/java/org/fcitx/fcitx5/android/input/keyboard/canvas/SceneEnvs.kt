/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.content.res.Configuration
import android.view.View
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.keyboard.inInputMethod
import org.fcitx.fcitx5.android.input.keyboard.keyboardCompact
import org.fcitx.fcitx5.android.input.theme.ThemeLook
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard

/**
 * The scene engine's environment for any keyboard the canvas draws (060-render-p2 §4): what the
 * screen and the settings make of every keyboard alike, the keyboard's own [Input], [Shift], texts
 * and marks given. One-handed and at a side InputView places the keyboard already (its side buttons
 * are views, the popups placed in the window): the keys fill what the view was given, so both FULL.
 */
object SceneEnvs {

    /**
     * What is typed now as a theme's scenes (`im=wubi`, `field=password`; SceneTyping): KeyboardWindow's
     * as the input method and the field change (main thread). Null before it tells: then, and outside
     * the input method (a settings page), what each keyboard's Input says. Each keyboard shown is told
     * ([watch]), the number row's and the expanded candidates' too, not only KeyboardWindow's.
     */
    var typing: Set<String>? = null
        set(value) {
            if (field == value) return
            field = value
            // a copy: one told may come or go
            watching.toList().forEach { it() }
        }

    private val watching = LinkedHashSet<() -> Unit>()

    /** [onTyping] called as [typing] changes, till [unwatch]: a keyboard's while it is shown (BaseKeyboard). */
    fun watch(onTyping: () -> Unit) {
        watching += onTyping
    }

    fun unwatch(onTyping: () -> Unit) {
        watching -= onTyping
    }

    /**
     * [view]'s keys at [width] × [height] px in [look] ([dark] its theme's): landscape as the screen is,
     * compact as the height setting is (KeyboardHeightCap), floating's text scale only in the input
     * method (not a settings page's preview), [split] where the keyboard splits its letters.
     */
    fun of(
        view: View,
        look: ThemeLook,
        dark: Boolean,
        width: Int,
        height: Int,
        input: Input,
        split: Boolean = false,
        shift: Shift = Shift.None,
        texts: Texts = Texts(),
        marks: MarkSettings = MarkSettings(),
    ): Env {
        val context = view.context
        val config = view.resources.configuration
        val floating = if (ThemeManager.prefs.floatingKeyboard.getValue() && context.inInputMethod()) {
            val screen = config.screenWidthDp
            FloatingKeyboard.textScale(FloatingKeyboard.widthDp(screen), screen).toDouble()
        } else {
            null
        }
        return Env(
            width.toDouble(), height.toDouble(), view.resources.displayMetrics.density.toDouble(),
            landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE,
            compact = keyboardCompact(),
            floating = floating,
            split = split,
            oneHanded = SplitKeyboard.Dock.FULL,
            dock = SplitKeyboard.Dock.FULL,
            dark = dark,
            flat = !look.keys.blocks,
            input = input,
            shift = shift,
            texts = texts,
            marks = marks,
            typed = typing.takeIf { context.inInputMethod() },
        )
    }
}
