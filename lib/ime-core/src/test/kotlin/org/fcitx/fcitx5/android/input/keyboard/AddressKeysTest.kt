/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.input.editing.EditorTraits
import org.junit.Assert.assertEquals
import org.junit.Test

class AddressKeysTest {

    @Test
    fun anAddressFieldOffersWhatAddressesAreMadeOf() {
        assertEquals(AddressKeys.URL, AddressKeys.of(EditorTraits(isUri = true)))
        assertEquals(AddressKeys.EMAIL, AddressKeys.of(EditorTraits(isEmail = true)))
        assertEquals(AddressKeys.NONE, AddressKeys.of(EditorTraits()))
        assertEquals(".com", AddressKeys.DOMAINS.first())
    }

    @Test
    fun theNineKeysColumnOffersAnAddresssMarksTheCommonestFirst() {
        assertEquals(listOf("/", ".", ".com", ":"), AddressKeys.column(AddressKeys.URL).take(4))
        assertEquals(listOf("@", ".", ".com", "-"), AddressKeys.column(AddressKeys.EMAIL).take(4))
        assertEquals(emptyList<String>(), AddressKeys.column(AddressKeys.NONE))
        // every domain, each once
        for (keys in listOf(AddressKeys.URL, AddressKeys.EMAIL)) {
            val column = AddressKeys.column(keys)
            assertEquals(column.distinct(), column)
            assertEquals(AddressKeys.DOMAINS.toSet(), column.filter { it.length > 1 }.toSet())
        }
    }
}
