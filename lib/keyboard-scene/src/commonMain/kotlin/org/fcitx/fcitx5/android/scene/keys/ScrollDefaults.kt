/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.keys

/** One item of a scroll list. */
sealed interface ScrollItem {
    /** [asIs]: 不转中文标点. */
    data class Text(val text: String, val asIs: Boolean = false) : ScrollItem

    /** By KeyCommand's key (`copy`, `switch_input_method`). */
    data class Command(val key: String) : ScrollItem
}

/** The scroll lists' categories, fixed by the app (a layout names one, never adds one), and each one's built-in items, top to bottom. */
object ScrollDefaults {
    const val NINE = "nine"
    const val NINE_ENGLISH = "nine-english"
    const val NUMBER = "number"
    const val PHONE = "phone"
    const val DATETIME = "datetime"
    const val URL = "url"
    const val EMAIL = "email"

    private fun texts(vararg t: String): List<ScrollItem> = t.map { ScrollItem.Text(it) }

    // the commonest domain first, as the nine keys' address column had them
    private val LISTS: Map<String, List<ScrollItem>> = linkedMapOf(
        NINE to texts(",", ".", "?", "!", "…", "、", ":", "~", "@"),
        NINE_ENGLISH to texts(",", ".", "?", "!", "'", "-", ":", ";", "@"),
        NUMBER to texts("+", "-", "*", "/", "%", ",", ":", "(", ")", "#"),
        PHONE to texts("+", "*", "#", "-", "(", ")", ","),
        DATETIME to texts("-", "/", ":"),
        URL to texts("/", ".", ".com", ":", "-", "_", "?", "=", "&", "#", ".cn", ".net", ".org"),
        EMAIL to texts("@", ".", ".com", "-", "_", "+", ".cn", ".net", ".org"),
    )

    /** In the order settings list them. */
    val IDS: List<String> = LISTS.keys.toList()

    /** Null for an id not among [IDS]. */
    fun of(id: String): List<ScrollItem>? = LISTS[id]
}
