/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyListsCacheTest {

    // the old ones changed alone (no key_lists still): the lists are made again
    @Test
    fun theOldOnesAreMadeAgainWhenTheNineLettersAloneChange() {
        var letters = "abc,def,ghi,jkl,mno,pqrs,tuv,wxyz"
        val cache = KeyListsCache { KeyListsCache.Stored(null, { letters }) { KeyListsCache.Old(null, "2 #") } }
        assertEquals(listOf("A", "B", "C", "2", "a", "b", "c", "#"), cache.current[KeyId("t9/2")]?.items)
        val version = cache.version
        letters = "abd,def,ghi,jkl,mno,pqrs,tuv,wxyz"
        assertEquals(listOf("A", "B", "D", "2", "a", "b", "d", "#"), cache.current[KeyId("t9/2")]?.items)
        assertEquals(version + 1, cache.version)
    }
}
