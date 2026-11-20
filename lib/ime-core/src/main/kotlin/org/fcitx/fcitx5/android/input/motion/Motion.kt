/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.motion

/** The keyboard's one motion scale: every animation takes length and curve from here; nothing typed is animated; at most 150 ms. */
enum class Motion(private val enterMs: Long) {
    /** A long press's characters, a candidate's menu. */
    Short(100),

    /** A notice, one panel for another, the idle bar and the candidates. */
    Medium(120),

    /** One-handed to a side, floating and docked, the candidates expanded. */
    Long(150),
    ;

    /** 0 when animation is off. */
    fun enter(settings: Settings): Long = settings.scaled(enterMs)

    /** Three quarters of coming in: what leaves needs no reading. */
    fun exit(settings: Settings): Long = settings.scaled(enterMs * 3 / 4)

    /** In slows to a stop, out speeds away, a move does both. */
    enum class Curve { Enter, Exit, Move }

    /** [systemScale]: the animator duration scale (0 with 移除动画); [disabled]: 关闭动画. */
    data class Settings(val systemScale: Float, val disabled: Boolean) {
        val animates: Boolean get() = !disabled && systemScale > 0f

        internal fun scaled(ms: Long): Long = if (!animates) 0 else ms

        companion object {
            val DEFAULT = Settings(systemScale = 1f, disabled = false)
        }
    }

    companion object {
        const val NOTICE_SLIDE_DP = 8
    }
}
