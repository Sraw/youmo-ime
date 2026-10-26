/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A release tagged without raising the base version would ship the last one's version code, which updaters miss. */
class VersionsTest {

    @Test
    fun aReleaseBuiltAtItsTagIsTheBaseVersion() {
        assertEquals(Versions.baseVersionName, Versions.checkedVersionName(Versions.baseVersionName))
        val e = assertThrows(IllegalStateException::class.java) { Versions.checkedVersionName("999.0.0") }
        assertTrue(e.message, e.message!!.contains("baseVersionCode"))
    }

    @Test
    fun aBuildPastATagOrWithoutOneIsNoRelease() {
        assertEquals("999.0.0-5-gabc1234", Versions.checkedVersionName("999.0.0-5-gabc1234"))
        assertEquals("999.0.0-untagged", Versions.checkedVersionName("999.0.0-untagged"))
    }

    @Test
    fun theCodeRisesWithTheName() {
        assertEquals("0.1.0, released, keeps its code", 12, Versions.versionCodeOf("0.1.0"))
        assertEquals(Versions.versionCodeOf(Versions.baseVersionName), Versions.baseVersionCode)
        val names = listOf("0.1.0", "0.1.1", "0.1.99", "0.2.0", "0.99.99", "1.0.0", "1.0.1", "2.0.0")
        val codes = names.map { Versions.versionCodeOf(it) }
        assertEquals(names.zip(codes).toString(), codes.sorted().distinct(), codes)
    }

    // F3785: a merge once took an older branch's base back below the published 0.3.3: install -r
    // over it then fails with INSTALL_FAILED_VERSION_DOWNGRADE. Raise this with each release (a
    // step of the youmo-release skill)
    @Test
    fun theBaseIsAboveTheLastPublishedRelease() {
        assertTrue(Versions.baseVersionName, Versions.baseVersionCode > Versions.versionCodeOf("0.3.3"))
    }

    @Test
    fun aNameTheCodeCannotFollowIsRefused() {
        for (name in listOf("0.1", "0.1.0.1", "0.1.100", "0.100.0", "0.0.9", "0.1.0-untagged")) {
            assertThrows(name, IllegalArgumentException::class.java) { Versions.versionCodeOf(name) }
        }
    }
}
