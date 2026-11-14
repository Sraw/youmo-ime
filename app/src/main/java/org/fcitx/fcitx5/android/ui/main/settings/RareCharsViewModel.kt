/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.graphics.Paint
import android.os.Parcelable
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.HostConnection
import org.fcitx.fcitx5.android.data.pinyin.RareCharsManager
import org.fcitx.fcitx5.android.engine.data.CharLevels
import org.fcitx.fcitx5.android.engine.data.CharLevels.Level
import org.fcitx.fcitx5.android.engine.user.RareChoice
import org.fcitx.fcitx5.android.engine.user.RareGrid
import org.fcitx.fcitx5.android.ui.rare.RareEdits
import timber.log.Timber
import java.io.IOException
import java.text.NumberFormat

/**
 * 生僻字屏蔽's page data (design §6.4), kept over a turn: the grid copied off the engine in one
 * `runOnReady`, sorted and checked for glyphs here off the main thread, the edits as the page made
 * them. The page holds no table the engine maps: a text pack changed lets that go.
 */
class RareCharsViewModel : ViewModel() {

    sealed interface State {
        data object Loading : State

        /** No text pack: no readings to lay the grid out by. */
        data object NoPack : State

        /** The APK's levels' table not read: the feature is off. */
        data object Unavailable : State

        class Ready(val page: Page) : State
    }

    /**
     * The grid as the page shows it: [sorted], each group's cells; [shown], those the phone can draw;
     * [unshown], how many of each group it cannot; [edits], the choice as read with the page's
     * changes, saved as the page is left ([unreadable]: the file not read, nothing changed or saved).
     */
    class Page(
        val grid: RareGrid,
        val levels: CharLevels,
        val sorted: Map<Level, IntArray>,
        val shown: Map<Level, IntArray>,
        val unshown: Map<Level, Int>,
        val edits: RareEdits,
        val unreadable: Boolean,
    ) {
        val readOnly: Boolean get() = unreadable || edits.readOnly

        /** The letter [cell]'s section is under: its first reading's first. */
        fun letterOf(cell: Int): Char = grid.spelling(cell).first()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    /** The groups shown open: 扩展区 shut at first (design §6.2). */
    val open: MutableSet<Level> = mutableSetOf(Level.L3, Level.TRAD, Level.OUT)

    private var loading: Job? = null

    fun load(fcitx: HostConnection) {
        if (loading != null) return
        loading = viewModelScope.launch {
            _state.value = try {
                read(fcitx)
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // the text pack away (PackUnavailableException), or its data not read
                Timber.w(e, "rare characters: no grid")
                State.NoPack
            }
        }
    }

    private suspend fun read(fcitx: HostConnection): State {
        val levels = withContext(Dispatchers.IO) { RareCharsManager.levels } ?: return State.Unavailable
        var unreadable = false
        val choice = withContext(Dispatchers.IO) {
            try {
                load()
            } catch (e: IOException) {
                Timber.w(e, "rare characters: not read, off this time")
                unreadable = true
                RareChoice.NONE
            }
        }
        val grid = fcitx.runOnReady { rareGrid() } ?: return State.Unavailable
        return State.Ready(withContext(Dispatchers.Default) { page(grid, levels, choice, unreadable, hasGlyph) })
    }

    /** The search typed, kept over a turn. */
    var query: String = ""

    /** The grid's scroll, kept over a turn. */
    var scroll: Parcelable? = null

    companion object {
        // the file's read; a test holds it to see the page as a file it cannot read leaves it
        @VisibleForTesting
        internal var load: () -> RareChoice = { RareCharsManager.load() }

        /** The page's last save, under way or done: the words page's count read after it (review N6). */
        @Volatile
        internal var saving: Deferred<Boolean>? = null

        // what the phone can draw, a paint of its own (design §6.4; EngineBridge's textShows)
        @VisibleForTesting
        internal var hasGlyph: (String) -> Boolean = Paint().let { paint -> { paint.hasGlyph(it) } }

        /** The page over [grid]: sorted, the cells the phone cannot draw left out and counted. */
        fun page(grid: RareGrid, levels: CharLevels, choice: RareChoice, unreadable: Boolean, hasGlyph: (String) -> Boolean): Page {
            val sorted = grid.sorted()
            val shown = sorted.mapValues { (_, cells) -> cells.filter { hasGlyph(String(Character.toChars(grid.chars[it]))) }.toIntArray() }
            val unshown = sorted.mapValues { (level, cells) -> cells.size - shown.getValue(level).size }
            return Page(grid, levels, sorted, shown, unshown, RareEdits(grid, levels, choice), unreadable)
        }
    }
}

/**
 * 生僻字屏蔽's row on 词库与短语 (§6.1): 「关」, or 「已屏蔽 N 个字」 counted over [grid] as the page
 * counts; null, the row left without, where the file or the grid cannot be read.
 */
internal suspend fun rareSummary(ctx: Context, grid: suspend () -> RareGrid?): String? {
    val levels = withContext(Dispatchers.IO) { RareCharsManager.levels } ?: return null
    // the page just left may be writing still: what it saved counted, not what was there before
    RareCharsViewModel.saving?.await()
    val choice = try {
        withContext(Dispatchers.IO) { RareCharsViewModel.load() }
    } catch (e: IOException) {
        Timber.w(e, "rare characters: not read")
        return null
    }
    // a later version's file is off here, as the engine has it
    if (!choice.on || choice.newer) return ctx.getString(R.string.rare_chars_off)
    val read = try {
        grid()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Timber.w(e, "rare characters: no grid")
        null
    } ?: return null
    val held = withContext(Dispatchers.Default) { RareEdits.heldCount(read, levels, choice) }
    return ctx.getString(R.string.rare_chars_held, NumberFormat.getIntegerInstance().format(held))
}
