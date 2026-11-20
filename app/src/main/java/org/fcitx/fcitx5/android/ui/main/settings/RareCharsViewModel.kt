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
import org.fcitx.fcitx5.android.ui.rare.RareSamples
import timber.log.Timber
import java.io.IOException
import java.text.NumberFormat

/**
 * Kept over a turn: the grid copied off the engine in one `runOnReady`, sorted and glyph-checked off the main thread,
 * and the page's edits. No engine-mapped table is held, so a text pack change can let it go.
 */
class RareCharsViewModel : ViewModel() {

    sealed interface State {
        data object Loading : State

        /** No readings to lay the grid out by. */
        data object NoPack : State

        /** The APK's levels table not read: the feature is off. */
        data object Unavailable : State

        class Ready(val page: Page) : State
    }

    /**
     * [shown]: cells the phone can draw; [unshown]: per group, those it cannot; [samples]: a card's few; [edits]: saved as
     * the page is left ([unreadable]: nothing changed or saved).
     */
    class Page(
        val grid: RareGrid,
        val levels: CharLevels,
        val sorted: Map<Level, IntArray>,
        val shown: Map<Level, IntArray>,
        val unshown: Map<Level, Int>,
        val samples: Map<Level, List<String>>,
        val edits: RareEdits,
        val unreadable: Boolean,
    ) {
        val readOnly: Boolean get() = unreadable || edits.readOnly

        /** [sorted]'s count, not RareGrid.count's full pass, asked twice a card a step. */
        fun total(level: Level): Int = sorted[level]?.size ?: grid.count(level)

        /** Its first reading's first letter. */
        fun letterOf(cell: Int): Char = grid.spelling(cell).first()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    /** Null shows the cards. */
    var opened: Level? = null

    /** Each grid's first row shown and its offset, kept as it is left. */
    val scrolls: MutableMap<Level, Pair<Int, Int>> = mutableMapOf()

    /** For the title bar's ↶; none after a save. */
    var undo: RareEdits.Undo? = null

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

    var query: String = ""

    var scroll: Parcelable? = null

    companion object {
        // a test holds it to see the page as an unreadable file leaves it
        @VisibleForTesting
        internal var load: () -> RareChoice = { RareCharsManager.load() }

        /** Under way or done: the words page counts after it. */
        @Volatile
        internal var saving: Deferred<Boolean>? = null

        // a paint of its own (as EngineBridge's textShows)
        @VisibleForTesting
        internal var hasGlyph: (String) -> Boolean = Paint().let { paint -> { paint.hasGlyph(it) } }

        /** Cells the phone cannot draw are left out and counted. */
        fun page(grid: RareGrid, levels: CharLevels, choice: RareChoice, unreadable: Boolean, hasGlyph: (String) -> Boolean): Page {
            val sorted = grid.sorted()
            val shown = sorted.mapValues { (_, cells) -> cells.filter { hasGlyph(String(Character.toChars(grid.chars[it]))) }.toIntArray() }
            val unshown = sorted.mapValues { (level, cells) -> cells.size - shown.getValue(level).size }
            val samples = shown.mapValues { (level, cells) ->
                RareSamples.of(level, cells.map { String(Character.toChars(grid.chars[it])) }, hasGlyph)
            }
            return Page(grid, levels, sorted, shown, unshown, samples, RareEdits(grid, levels, choice), unreadable)
        }
    }
}

/** 「关」 or 「已屏蔽 N 个字」, counted as the page counts; null where the file or grid cannot be read. */
internal suspend fun rareSummary(ctx: Context, grid: suspend () -> RareGrid?): String? {
    val levels = withContext(Dispatchers.IO) { RareCharsManager.levels } ?: return null
    // the page just left may still be writing: count what it saved
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
