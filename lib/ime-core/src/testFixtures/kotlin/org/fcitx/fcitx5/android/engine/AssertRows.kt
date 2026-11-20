/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine

import kotlin.coroutines.cancellation.CancellationException

/** Every row is checked and the failing ones listed together; a row that crashes fails like an assertion. */
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
