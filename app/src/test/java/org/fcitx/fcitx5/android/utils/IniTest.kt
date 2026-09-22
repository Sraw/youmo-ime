/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import org.fcitx.fcitx5.android.core.RawConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [Ini] is a thin path-addressed view over a [RawConfig] tree, used to read table input method
 * descriptors. Parsing an actual file goes through JNI and is out of reach here, but the
 * navigation and mutation on top of an existing tree is plain Kotlin.
 */
class IniTest {

    private fun ini() = Ini(
        RawConfig(
            arrayOf(
                RawConfig("InputMethod", arrayOf(RawConfig("Name", "Wubi"), RawConfig("Icon", "wubi"))),
                RawConfig("Table", arrayOf(RawConfig("File", "/usr/share/table/wubi.main.dict"))),
            )
        )
    )

    // region get

    @Test
    fun getWalksASinglePathSegment() {
        assertEquals("Wubi", ini().get("InputMethod", "Name")?.value)
    }

    @Test
    fun getWalksSeveralSegments() {
        assertEquals("/usr/share/table/wubi.main.dict", ini().get("Table", "File")?.value)
    }

    @Test
    fun getReturnsAnIntermediateNode() {
        val section = ini().get("InputMethod")
        assertEquals("", section?.value)
        assertEquals("Wubi", section?.get("Name")?.value)
    }

    @Test
    fun getReturnsNullWhenAnySegmentIsMissing() {
        assertNull(ini().get("Nope"))
        assertNull(ini().get("InputMethod", "Nope"))
        assertNull(ini().get("Nope", "Name"))
    }

    /** No keys means no path to walk, which is a lookup failure rather than "the root". */
    @Test
    fun getWithNoKeysReturnsNull() {
        assertNull(ini().get())
    }

    @Test
    fun valueExposesTheNodesOwnValue() {
        assertEquals("Wubi", Ini(RawConfig("Name", "Wubi")).value)
    }

    // endregion

    // region set(str)

    @Test
    fun setOverwritesAnExistingValue() {
        val ini = ini()
        ini.set("InputMethod", "Name", str = "Cangjie")
        assertEquals("Cangjie", ini.get("InputMethod", "Name")?.value)
    }

    @Test
    fun setCreatesMissingIntermediateNodes() {
        val ini = ini()
        ini.set("New", "Deep", "Key", str = "v")
        assertEquals("v", ini.get("New", "Deep", "Key")?.value)
    }

    @Test
    fun setWithNoKeysIsANoOp() {
        val ini = ini()
        ini.set(str = "ignored")
        assertEquals("the root value is untouched", "", ini.value)
    }

    @Test
    fun setDoesNotDisturbSiblings() {
        val ini = ini()
        ini.set("InputMethod", "Name", str = "Cangjie")
        assertEquals("wubi", ini.get("InputMethod", "Icon")?.value)
    }

    // endregion

    // region set(raw)

    @Test
    fun setRawGraftsANodeUnderThePath() {
        val ini = ini()
        ini.set("InputMethod", raw = RawConfig("Label", "wb"))
        assertEquals("wb", ini.get("InputMethod", "Label")?.value)
    }

    @Test
    fun setRawOverwritesValueAndChildrenOfAnExistingNode() {
        val ini = ini()
        ini.set("InputMethod", raw = RawConfig("Name", arrayOf(RawConfig("zh", "五笔"))))
        assertEquals("", ini.get("InputMethod", "Name")?.value)
        assertEquals("五笔", ini.get("InputMethod", "Name", "zh")?.value)
    }

    @Test
    fun setRawWithNoKeysGraftsOntoTheRoot() {
        val ini = ini()
        ini.set(raw = RawConfig("Top", "v"))
        assertEquals("v", ini.get("Top")?.value)
    }

    @Test
    fun setRawCreatesMissingIntermediateNodes() {
        val ini = ini()
        ini.set("A", "B", raw = RawConfig("C", "v"))
        assertEquals("v", ini.get("A", "B", "C")?.value)
    }

    // endregion

    @Test
    fun theUnderlyingTreeIsSharedNotCopied() {
        val core = RawConfig(arrayOf(RawConfig("k", "v")))
        val ini = Ini(core)
        ini.set("k", str = "changed")
        assertEquals("writes go straight to the wrapped tree", "changed", core["k"].value)
    }
}
