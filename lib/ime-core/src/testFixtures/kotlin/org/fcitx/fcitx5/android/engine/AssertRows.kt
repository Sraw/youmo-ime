/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine

import kotlin.coroutines.cancellation.CancellationException

/**
 * Every row of a table test checked, the failing ones listed together at the end, each under the
 * name [why] gives it: a regression in one row hides none of the others (review F3407). A row that
 * crashes (a `!!`, an ExecutionException) is a failure like an assertion, not the table's end (F3452).
 */
@Suppress("TooGenericExceptionCaught")
fun <T> assertRows(rows: Iterable<T>, why: (T) -> String, check: (T) -> Unit) {
    var first: Throwable? = null
    val failed = rows.mapNotNull { row ->
        try {
            check(row)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (first == null) first = e
            "${why(row)}: ${e::class.simpleName}: ${e.message}"
        }
    }
    if (failed.isNotEmpty()) throw AssertionError("${failed.size} row(s) failed:\n" + failed.joinToString("\n"), first)
}
