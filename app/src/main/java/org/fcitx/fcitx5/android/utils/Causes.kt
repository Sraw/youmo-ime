/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.utils

import java.util.Collections
import java.util.IdentityHashMap

/**
 * This throwable and its causes, each once: initCause refuses only a throwable itself, so a chain may loop back
 * (a cause whose cause is the first); followed to its end, not cut at a count that one deep chain outgrows.
 */
fun Throwable.causes(): Sequence<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    return generateSequence(this) { it.cause }.takeWhile(seen::add)
}
