/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.view.View
import org.fcitx.fcitx5.android.input.keyboard.KeyboardArea
import org.fcitx.fcitx5.android.input.keyboard.KeyboardStyles
import org.fcitx.fcitx5.android.input.keyboard.inInputMethod
import org.fcitx.fcitx5.android.input.keyboard.keyboardCompact
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.keys.ScrollItem
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard

/**
 * One-handed and at a side InputView places the keyboard already (its side buttons are views, the popups
 * placed in the window): the keys fill what the view was given, so both FULL.
 */
object SceneEnvs {
    /** The cells, the panels, the rows that show no shortcuts. */
    val NO_MARKS = MarkSettings(shortcuts = emptyMap())

    /**
     * Main thread. Null before KeyboardWindow tells: then, and outside the input method (a settings page),
     * what each keyboard's Input says. Each keyboard shown is told ([watch]), not only KeyboardWindow's.
     */
    var typing: Set<String>? = null
        set(value) {
            if (field == value) return
            field = value
            // a copy: one told may come or go
            watching.toList().forEach { it() }
        }

    private val watching = LinkedHashSet<() -> Unit>()

    fun watch(onTyping: () -> Unit) {
        watching += onTyping
    }

    fun unwatch(onTyping: () -> Unit) {
        watching -= onTyping
    }

    /** Floating's text scale only in the input method, not a settings preview. Not [resized]: a bar's, which only gets wider. */
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
        resized: Boolean = true,
        /** Settings' cards: the lists as being edited; the keyboard's own shows none, the column draws them. */
        scrollItems: Map<String, List<ScrollItem>>? = null,
    ): Env {
        val context = view.context
        val floating = if (context.inInputMethod() && KeyboardStyles.floating(context)) KeyboardArea.floatingSize(KeyboardArea.screen(context)) else null
        // a dp of the scene's that many px, so the scene in dp is the default size's and the keys keep their shape
        val resize = if (resized) floating?.resize ?: 1f else 1f
        return Env(
            width.toDouble(), height.toDouble(), view.resources.displayMetrics.density.toDouble() * resize,
            landscape = KeyboardArea.landscape(context),
            compact = keyboardCompact(),
            floating = floating?.textScale?.toDouble(),
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
            scrollItems = scrollItems,
        )
    }
}
