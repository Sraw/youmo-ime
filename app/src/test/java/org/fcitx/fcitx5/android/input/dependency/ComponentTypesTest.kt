/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 youmo-ime Contributors
 */
package org.fcitx.fcitx5.android.input.dependency

import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mechdancer.dependency.DynamicScope

/** The scope's key of a component, found once per class, is the library's own. */
class ComponentTypesTest {

    @Test
    fun theTypeIsTheLibrarysAndKeptPerClass() {
        val a = PunctuationComponent()
        assertEquals(a.defaultType(), a.type)
        assertSame(a.type, PunctuationComponent().type)
        assertEquals(PreeditEmptyStateComponent().defaultType(), PreeditEmptyStateComponent().type)
        assertNotEquals(a.type, PreeditEmptyStateComponent().type)
    }

    @Test
    fun aSecondOfAClassIsStillRefusedByTheScope() {
        val scope = DynamicScope()
        assertEquals(true, scope.setup(PunctuationComponent()))
        assertEquals(false, scope.setup(PunctuationComponent()))
    }
}
