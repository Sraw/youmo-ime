/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.WeakHashMap

/** Names a file directly inside a directory, not a path. */
internal fun isPlainFileName(name: String) =
    name.isNotEmpty() && name != "." && name != ".." && '/' !in name

/** File managers may name one `WORDS.SCEL`; each reader checks a lowercase extension. */
internal fun withLowercaseExtension(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot < 0) name else name.substring(0, dot) + name.substring(dot).lowercase()
}

inline fun <T> withTempDir(block: (File) -> T): T {
    // named by the millisecond, two imports at once shared one and the first to end deleted it
    val dir = appContext.cacheDir.resolve(UUID.randomUUID().toString())
    if (!dir.mkdirs()) throw IOException("Cannot create '${dir.path}'")
    try {
        return block(dir)
    } finally {
        dir.deleteRecursively()
    }
}

fun formatDateTime(timeMillis: Long? = null): String {
    return SimpleDateFormat.getDateTimeInstance().format(timeMillis?.let { Date(it) } ?: Date())
}

private val ISO8601DateFormat by lazy {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
}

fun iso8601UTCDateTime(timeMillis: Long? = null): String {
    return ISO8601DateFormat.format(timeMillis?.let { Date(it) } ?: Date())
}

/** Each once: a chain may loop back (initCause refuses only itself). */
fun Throwable.causes(): Sequence<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    return generateSequence(this) { it.cause }.takeWhile(seen::add)
}

@Suppress("FunctionName")
fun <T> WeakHashSet(): MutableSet<T> = Collections.newSetFromMap(WeakHashMap<T, Boolean>())

/** `toList()` reads size then `first()`, which throws if the GC clears a weak key in between; the iterator holds it. */
fun <T> Set<T>.snapshot(): List<T> {
    val out = ArrayList<T>()
    val it = iterator()
    while (it.hasNext()) out.add(it.next())
    return out
}
