/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.table

import org.fcitx.fcitx5.android.data.table.TableBasedInputMethod.Companion.fixedTableFileName
import org.fcitx.fcitx5.android.data.table.TableBasedInputMethod.Companion.replacedTableFileName
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where an input method's table is kept, imported or replaced. */
class TableFileNameTest {

    @Test
    fun anImportedTableIsNamedLowerCaseWithoutSpaces() {
        assertEquals("my-table.txt", fixedTableFileName("My Table"))
        assertEquals("db.main.txt", fixedTableFileName("db.main"))
    }

    @Test
    fun aTextTableIsReplacedWhereItIs() {
        assertEquals("db.main.txt", replacedTableFileName("db.main.txt"))
    }

    /** As an import of the same file would name it, not after its first dot (`db.txt`). */
    @Test
    fun libimesTableIsReplacedByTextOfTheNameAnImportGives() {
        assertEquals(fixedTableFileName("db.main"), replacedTableFileName("db.main.dict"))
    }
}
