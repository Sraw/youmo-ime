/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

/**
 * A table input method's behaviour beyond its data: fcitx's table `.conf` options, meaning what they mean to
 * libime. A length limit of -1 holds at any length, 0 turns its rule off.
 *
 * @property autoSelect commit unasked: a lone candidate, or the first once the code is full or the next key leads nowhere
 * @property autoSelectLength a lone candidate whose code is all that was typed commits once the code is this long
 * @property noMatchAutoSelectLength a key leading nowhere commits the first candidate once the code is this long, and starts a new code
 * @property noSortInputLength codes this long or shorter (and no longer than typed) come first, in table order
 * @property sortByCodeLength the other codes, shorter first
 * @property orderByUse 调频 (fcitx's OrderPolicy): 重码 by the models and the user's picks; off, table order
 * @property matchingKey stands for any one key (五笔 z, 仓颉 *)
 * @property pinyinKey typed first, looks a character up by pinyin, showing its code
 * @property hint show what is left of each candidate's code
 * @property endKeys keys that end a code (EndKey): the next key commits the first candidate
 * @property selectionKeys keys that pick while something is typed (Selection), for tables whose codes are digits
 * @property roots the table's roots on its letter keys (仓颉, 五笔 86)
 * @property keyLabels what of [roots] the keys show; any but none also shows [TableRoots.inPreedit]'s code as roots
 */
data class TableOptions(
    val autoSelect: Boolean = true,
    val autoSelectLength: Int = -1,
    val noMatchAutoSelectLength: Int = -1,
    val noSortInputLength: Int = 0,
    val sortByCodeLength: Boolean = true,
    val orderByUse: Boolean = false,
    val matchingKey: Char? = null,
    val pinyinKey: Char? = null,
    val hint: Boolean = true,
    val pageSize: Int = 5,
    val endKeys: String = "",
    val selectionKeys: String = "",
    val roots: TableRoots? = null,
    val keyLabels: KeyLabels = KeyLabels.NONE,
) {
    init {
        require(pageSize >= 1) { "page size $pageSize" }
    }

    fun shown(code: String): String = if (keyLabels == KeyLabels.NONE) code else roots?.spell(code) ?: code

    companion object {
        val WUBI = TableOptions(
            noSortInputLength = 2,
            // as fcitx's .conf has it, for older builds' users; a new install's 调频 is off via the addon's setting, which
            // TableSettings.applyTo leaves true where the config has no OrderPolicy
            orderByUse = true,
            matchingKey = 'z',
            pinyinKey = 'z',
            roots = TableRoots.WUBI86,
        )

        /** fcitx's wbpy.conf: 五笔 with its pinyin entries matched as codes (no lookup key). */
        val WUBI_PINYIN = WUBI.copy(pinyinKey = null)

        /** fcitx's cangjie.conf; roots on the keys and in the preedit (日月, not ab). */
        val CANGJIE = TableOptions(
            noMatchAutoSelectLength = 0,
            matchingKey = '*',
            hint = false,
            roots = TableRoots.CANGJIE,
            keyLabels = KeyLabels.NAMES,
        )

        val ZIRANMA = TableOptions(orderByUse = true)

        /** fcitx's erbi.conf; its AutoSelectRegex is not needed, AutoSelectLength=-1 already holds at any length. */
        val ERBI = TableOptions(
            noMatchAutoSelectLength = 0,
            hint = false,
            orderByUse = true,
            matchingKey = '*',
            pinyinKey = '[',
        )
    }
}
