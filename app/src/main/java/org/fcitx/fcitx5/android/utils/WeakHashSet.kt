/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import java.util.Collections
import java.util.WeakHashMap

@Suppress("FunctionName")
fun <T> WeakHashSet(): MutableSet<T> = Collections.newSetFromMap(WeakHashMap<T, Boolean>())

/**
 * A copy to go through: by the iterator alone. `toList()` asks the size first and, at one, takes
 * `first()`; a weak key cleared by the GC in between makes that throw NoSuchElementException (seen
 * in a JVM test). The iterator's `hasNext()` holds the next key strongly, so `next()` cannot miss it.
 */
fun <T> Set<T>.snapshot(): List<T> {
    val out = ArrayList<T>()
    val it = iterator()
    while (it.hasNext()) out.add(it.next())
    return out
}
