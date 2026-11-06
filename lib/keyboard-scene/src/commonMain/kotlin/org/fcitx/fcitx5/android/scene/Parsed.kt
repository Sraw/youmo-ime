/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

/** Something wrong with a layout, a theme or what they make together; [line] 0 where no line is to blame. */
data class Problem(val line: Int, val message: String) {
    override fun toString(): String = if (line > 0) "line $line: $message" else message
}

/**
 * What reading or building gave: the value, or why there is none. Never thrown: a user's theme or
 * layout is data, and the theme maker shows every problem at once, with its line.
 */
sealed interface Parsed<out T> {
    data class Ok<out T>(val value: T) : Parsed<T>

    data class Failed(val problems: List<Problem>) : Parsed<Nothing> {
        constructor(line: Int, message: String) : this(listOf(Problem(line, message)))
    }

    /** The value, or null where it failed. */
    fun orNull(): T? = (this as? Ok)?.value

    /** The problems, none where it did not fail. */
    fun problems(): List<Problem> = (this as? Failed)?.problems.orEmpty()
}

/** The value of this, or what [onFailure] makes of the problems (a test's `error`, a caller's fallback). */
inline fun <T> Parsed<T>.getOr(onFailure: (List<Problem>) -> T): T = when (this) {
    is Parsed.Ok -> value
    is Parsed.Failed -> onFailure(problems)
}

/** Collects problems while reading, so one pass reports them all. */
class Problems {
    private val list = ArrayList<Problem>()

    val all: List<Problem> get() = list

    fun isEmpty(): Boolean = list.isEmpty()

    fun add(line: Int, message: String) {
        list += Problem(line, message)
    }

    fun addAll(problems: List<Problem>) {
        list += problems
    }

    /** [value] when nothing went wrong, else the problems. */
    fun <T> result(value: () -> T): Parsed<T> = if (list.isEmpty()) Parsed.Ok(value()) else Parsed.Failed(list.toList())
}
