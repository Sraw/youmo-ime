/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.motion

/**
 * The keyboard's one motion scale (specs/app.md, "Motion"): every animation takes its
 * length and curve from here, none writes its own. Nothing typed is animated, a key's preview, the
 * candidates as they change, a layout switched: each key would wait on it. At most 150 ms (user decision
 * 2026-10-23, F3460), the keyboard following the finger mattering more than how it looks.
 */
enum class Motion(private val enterMs: Long) {
    /** Small things that come and go: a long press's characters, a candidate's menu. */
    Short(100),

    /** A notice coming in, one panel for another, the idle bar and the candidates. */
    Medium(120),

    /** The whole keyboard moving: one-handed to a side, floating and docked, the candidates expanded. */
    Long(150),
    ;

    /** How long coming in takes, [Settings] considered: 0 when animation is off. */
    fun enter(settings: Settings): Long = settings.scaled(enterMs)

    /** How long going takes: three quarters of coming in, what leaves needs no reading. */
    fun exit(settings: Settings): Long = settings.scaled(enterMs * 3 / 4)

    /** How the curve runs: in slows to a stop, out speeds away, a move does both. */
    enum class Curve { Enter, Exit, Move }

    /**
     * What the system and the user said: [systemScale] Android's animator duration scale (0 with
     * 移除动画), [disabled] the keyboard's own 关闭动画.
     */
    data class Settings(val systemScale: Float, val disabled: Boolean) {
        val animates: Boolean get() = !disabled && systemScale > 0f

        internal fun scaled(ms: Long): Long = if (!animates) 0 else ms

        companion object {
            val DEFAULT = Settings(systemScale = 1f, disabled = false)
        }
    }

    companion object {
        /** How far a notice slides out of the keyboard as it comes, in dp. */
        const val NOTICE_SLIDE_DP = 8
    }
}
