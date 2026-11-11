/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.fail

/**
 * A table test: [check] run on every row (a failing or throwing row does not hide the ones after it); one failure naming each
 * failed row by its [why], the first failure as its cause. The skin tests' own: ime-core's testFixtures are not
 * on this module's path.
 */
internal fun <R> assertRows(rows: List<R>, why: (R) -> String, check: (R) -> Unit) {
    require(rows.isNotEmpty()) { "a table of no rows checks nothing" }
    val failed = rows.mapNotNull { row ->
        try {
            check(row)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            why(row) to e
        }
    }
    if (failed.isEmpty()) return
    fail(failed.joinToString("\n") { (w, e) -> "$w: ${e::class.simpleName}: ${e.message}" }, failed.first().second)
}
