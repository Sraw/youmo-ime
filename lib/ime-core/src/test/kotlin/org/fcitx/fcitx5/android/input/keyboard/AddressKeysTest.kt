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
}
