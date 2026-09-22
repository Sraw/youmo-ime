/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RawConfig] is the tree the JNI layer exchanges with fcitx: every settings screen, the
 * punctuation table and the INI helpers are built on it.
 */
class RawConfigTest {

    // region construction

    @Test
    fun nameValueConstructorHasNoSubItems() {
        val c = RawConfig("k", "v")
        assertEquals("k", c.name)
        assertEquals("v", c.value)
        assertEquals("", c.comment)
        assertNull(c.subItems)
    }

    @Test
    fun booleanConstructorUsesFcitxSpelling() {
        assertEquals("True", RawConfig("k", true).value)
        assertEquals("False", RawConfig("k", false).value)
    }

    @Test
    fun subItemsConstructorLeavesTheNameEmpty() {
        val c = RawConfig(arrayOf(RawConfig("a", "1")))
        assertEquals("", c.name)
        assertEquals(1, c.subItems?.size)
    }

    @Test
    fun defaultConstructorIsAnEmptyNamedNode() {
        val c = RawConfig()
        assertEquals("", c.name)
        assertEquals("", c.value)
        assertEquals(0, c.subItems?.size)
    }

    // endregion

    // region lookup

    private fun tree() = RawConfig(
        arrayOf(
            RawConfig("a", "1"),
            RawConfig("b", "2"),
            RawConfig("nested", arrayOf(RawConfig("c", "3"))),
        )
    )

    @Test
    fun findByNameReturnsTheMatchingChild() {
        assertEquals("1", tree().findByName("a")?.value)
    }

    @Test
    fun findByNameReturnsNullForAMissingChild() {
        assertNull(tree().findByName("nope"))
    }

    @Test
    fun findByNameDoesNotRecurse() {
        assertNull("only direct children are searched", tree().findByName("c"))
    }

    @Test
    fun findByNameOnALeafReturnsNull() {
        assertNull(RawConfig("k", "v").findByName("anything"))
    }

    @Test
    fun getIsFindByNameThatInsistsOnAMatch() {
        assertEquals("2", tree()["b"].value)
        assertThrows(NullPointerException::class.java) { tree()["nope"] }
    }

    @Test
    fun nestedLookupChains() {
        assertEquals("3", tree()["nested"]["c"].value)
    }

    @Test
    fun findByNameReturnsTheFirstOfDuplicateNames() {
        val c = RawConfig(arrayOf(RawConfig("dup", "first"), RawConfig("dup", "second")))
        assertEquals("first", c.findByName("dup")?.value)
    }

    // endregion

    // region getOrCreate

    @Test
    fun getOrCreateReturnsTheExistingChild() {
        val root = tree()
        val existing = root.findByName("a")
        assertSame(existing, root.getOrCreate("a"))
        assertEquals("children were not duplicated", 3, root.subItems?.size)
    }

    @Test
    fun getOrCreateAppendsAMissingChild() {
        val root = tree()
        val created = root.getOrCreate("new")
        assertEquals("new", created.name)
        assertEquals("", created.value)
        assertEquals(4, root.subItems?.size)
        assertSame("the new child is reachable", created, root.findByName("new"))
    }

    /** A leaf has `subItems == null`; getOrCreate turns it into a parent. */
    @Test
    fun getOrCreateOnALeafStartsAChildList() {
        val leaf = RawConfig("k", "v")
        val child = leaf.getOrCreate("c")
        assertEquals("c", child.name)
        assertEquals(1, leaf.subItems?.size)
        assertSame(child, leaf.findByName("c"))
    }

    @Test
    fun getOrCreateIsIdempotent() {
        val root = RawConfig()
        val first = root.getOrCreate("x")
        val second = root.getOrCreate("x")
        assertSame(first, second)
        assertEquals(1, root.subItems?.size)
    }

    @Test
    fun valuesWrittenThroughGetOrCreateArePersisted() {
        val root = RawConfig()
        root.getOrCreate("x").value = "42"
        assertEquals("42", root["x"].value)
    }

    // endregion

    // region equality

    @Test
    fun equalityIsStructural() {
        assertEquals(RawConfig("k", "v"), RawConfig("k", "v"))
        assertEquals(tree(), tree())
    }

    @Test
    fun equalityComparesSubItemsByContent() {
        val a = RawConfig(arrayOf(RawConfig("x", "1")))
        val b = RawConfig(arrayOf(RawConfig("x", "1")))
        val c = RawConfig(arrayOf(RawConfig("x", "2")))
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    /** An absent child list and an empty one are different things. */
    @Test
    fun aLeafIsNotEqualToAnEmptyParent() {
        assertNotEquals(RawConfig("k", "v"), RawConfig("k", "", "v", arrayOf()))
    }

    @Test
    fun equalStructuresShareAHashCode() {
        assertEquals(tree().hashCode(), tree().hashCode())
    }

    @Test
    fun differingNameBreaksEquality() {
        assertNotEquals(RawConfig("a", "v"), RawConfig("b", "v"))
    }

    // endregion

    // region mutability

    @Test
    fun valueIsMutableInPlace() {
        val c = RawConfig("k", "v")
        c.value = "changed"
        assertEquals("changed", c.value)
    }

    @Test
    fun mutatingAChildIsVisibleThroughTheParent() {
        val root = tree()
        root["a"].value = "changed"
        assertEquals("changed", root.findByName("a")?.value)
    }

    @Test
    fun subItemsCanBeReplacedWholesale() {
        val root = RawConfig("k", "v")
        root.subItems = arrayOf(RawConfig("x", "1"))
        assertTrue(root.findByName("x") != null)
    }

    // endregion
}
