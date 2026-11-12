/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyListsCacheTest {

    // `@1` read upgraded by the user's 按键字母: those changed alone (key_lists still `@1`), the lists are made again
    @Test
    fun aV1ListIsMadeAgainWhenTheNineLettersAloneChange() {
        var letters = "abc,def,ghi,jkl,mno,pqrs,tuv,wxyz"
        val cache = KeyListsCache { KeyListsCache.Stored("@1\nt9/2\tset\t*\t#", { letters }) { KeyListsCache.Old(null, null, null) } }
        assertEquals(listOf("A", "B", "C", "2", "a", "b", "c", "#"), cache.current[KeyId("t9/2")]?.items)
        val version = cache.version
        letters = "abd,def,ghi,jkl,mno,pqrs,tuv,wxyz"
        assertEquals(listOf("A", "B", "D", "2", "a", "b", "d", "#"), cache.current[KeyId("t9/2")]?.items)
        assertEquals(version + 1, cache.version)
    }
}
