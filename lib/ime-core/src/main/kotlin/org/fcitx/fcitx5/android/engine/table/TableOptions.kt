/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.table

/**
 * How a table input method behaves beyond what its data says: the options of fcitx's table
 * `.conf` files, meaning what they mean to libime. A length limit of -1 holds at any length and
 * 0 turns its rule off.
 *
 * @property autoSelect commit without being asked: a lone candidate, or the first one once the
 *   code is full or the next key leads nowhere
 * @property autoSelectLength a lone candidate whose code is all that was typed is committed once
 *   the code is this long
 * @property noMatchAutoSelectLength a key that leads to no entry commits the first candidate of
 *   what was typed before it, once that is this long, and starts a new code
 * @property noSortInputLength codes this long or shorter (and no longer than what was typed) come
 *   first, in the table's order
 * @property sortByCodeLength the other codes, shorter first
 * @property orderByUse and among codes as long, what was picked most first (fcitx's OrderPolicy)
 * @property matchingKey stands for any one key (五笔 z, 仓颉 *)
 * @property pinyinKey typed first, looks a character up by its pinyin, showing its code
 * @property hint show what is left of each candidate's code
 * @property autoPhraseLength the last characters committed one by one, up to this many (-1: the
 *   longest code), become phrases typed by their 组词规则 code; 0 turns them off
 * @property saveAutoPhraseAfter an auto phrase typed this many times, character by character, or
 *   picked once, joins the table's own entries; 0 or less: only once picked
 * @property endKeys keys that end a code (晚风's `,./;`): the next key starts another, the first
 *   candidate committed (fcitx's EndKey)
 * @property selectionKeys keys that pick a candidate while something is typed, the first key the
 *   first on the page: for a table whose codes are digits (电报码's `qwertyuiop`)
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
    val autoPhraseLength: Int = 0,
    val saveAutoPhraseAfter: Int = 0,
    val pageSize: Int = 5,
    val endKeys: String = "",
    val selectionKeys: String = "",
) {
    init {
        require(pageSize >= 1) { "page size $pageSize" }
    }

    companion object {
        /** fcitx's wbx.conf: 五笔 86 */
        val WUBI = TableOptions(
            noSortInputLength = 2,
            orderByUse = true,
            matchingKey = 'z',
            pinyinKey = 'z',
            autoPhraseLength = 4,
            saveAutoPhraseAfter = 3,
        )

        /**
         * fcitx's wbpy.conf: 五笔 with pinyin, the table's pinyin entries matched as its codes are
         * (no key to start a lookup).
         */
        val WUBI_PINYIN = WUBI.copy(pinyinKey = null)

        /** fcitx's db.conf: codes of digits, so letters pick. */
        val DIANBAO = TableOptions(
            orderByUse = true,
            autoPhraseLength = -1,
            saveAutoPhraseAfter = -1,
            selectionKeys = "qwertyuiop",
        )

        /** fcitx's qxm.conf: OrderPolicy=Fast. */
        val BINGCHAN = TableOptions(orderByUse = true, matchingKey = '*', autoPhraseLength = 4, saveAutoPhraseAfter = 3)

        /** fcitx's wanfeng.conf: a code ends with one of its punctuation keys. */
        val WANFENG = TableOptions(
            noMatchAutoSelectLength = 1,
            autoPhraseLength = -1,
            saveAutoPhraseAfter = -1,
            endKeys = ",;/.",
        )

        /**
         * fcitx's cangjie.conf. Auto phrases as libime has them unless set, though its table has
         * no 组词规则 to code them by.
         */
        val CANGJIE = TableOptions(
            noMatchAutoSelectLength = 0,
            matchingKey = '*',
            hint = false,
            autoPhraseLength = -1,
            saveAutoPhraseAfter = -1,
        )

        /**
         * fcitx's zrm.conf: OrderPolicy=Fast, which orders by use as Freq does, and auto phrases
         * as libime has them unless set.
         */
        val ZIRANMA = TableOptions(orderByUse = true, autoPhraseLength = -1, saveAutoPhraseAfter = -1)

        /**
         * fcitx's erbi.conf. Its AutoSelectRegex is not needed: libime takes it or the length,
         * and AutoSelectLength=-1 already holds at any length.
         */
        val ERBI = TableOptions(
            noMatchAutoSelectLength = 0,
            hint = false,
            orderByUse = true,
            matchingKey = '*',
            pinyinKey = '[',
            autoPhraseLength = 4,
            saveAutoPhraseAfter = -1,
        )
    }
}
