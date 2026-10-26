/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.fcitx.fcitx5.android.engine.host.EngineEvent
import org.fcitx.fcitx5.android.engine.session.Choice
import org.fcitx.fcitx5.android.engine.session.Offer
import org.fcitx.fcitx5.android.engine.session.Snapshot
import org.fcitx.fcitx5.android.host.EngineHost
import java.io.File
import java.util.Collections

/** Pinyin as far as a case needs: the letters typed are the preedit, two candidates, a pick commits one. */
class ScriptedEngine(private val home: File) : KotlinFcitx.EngineSide, EngineHost {
    val events: MutableList<Int> = Collections.synchronizedList(ArrayList())
    val settings: MutableList<String> = Collections.synchronizedList(ArrayList())
    private var typed = ""

    override val host: EngineHost get() = this
    override fun home() = home
    override fun start() = Unit
    override fun close() = Unit

    override fun event(im: String, event: Int, arg: Int, learning: Boolean): Snapshot {
        events += event
        var commit = ""
        when (event) {
            EngineEvent.CHAR -> if (arg in 'a'.code..'z'.code) typed += arg.toChar() else return idle()
            EngineEvent.PICK -> if (typed.isNotEmpty()) {
                commit = WORDS[arg]
                typed = ""
            }
            EngineEvent.RESET -> typed = ""
            else -> return idle()
        }
        val shown = if (typed.isEmpty()) emptyList() else WORDS
        return Snapshot(commit, typed, shown, 0, false, false, handled = true, predicting = false)
    }

    private fun idle() = Snapshot("", typed, emptyList(), 0, false, false, handled = false, predicting = false)

    override fun candidates(im: String, from: Int, count: Int) =
        (if (typed.isEmpty()) emptyList() else WORDS).drop(from).take(count).map { Choice(it) }

    override fun offers(im: String, index: Int) = emptySet<Offer>()

    override fun settings(flat: String) {
        settings += flat
    }

    companion object {
        val WORDS = listOf("你好", "你")
    }
}
