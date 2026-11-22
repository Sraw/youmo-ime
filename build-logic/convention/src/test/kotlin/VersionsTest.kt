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

    // the base must stay above the published version, or install -r fails with INSTALL_FAILED_VERSION_DOWNGRADE
    @Test
    fun theBaseIsAboveTheLastPublishedRelease() {
        assertTrue(Versions.baseVersionName, Versions.baseVersionCode > Versions.versionCodeOf("0.6.2"))
    }

    @Test
    fun aNameTheCodeCannotFollowIsRefused() {
        for (name in listOf("0.1", "0.1.0.1", "0.1.100", "0.100.0", "0.0.9", "0.1.0-untagged")) {
            assertThrows(name, IllegalArgumentException::class.java) { Versions.versionCodeOf(name) }
        }
    }

    // what the app's splits and lib/handwriting's abiFilters are given (B15: the released ABI alone by default)
    @Test
    fun theBuildMakesTheReleasedAbiAloneUnlessToldOthers() {
        val rows = listOf(
            Triple("no -PbuildABI: arm64 alone, the ABI released", null, listOf("arm64-v8a")),
            Triple("CI's emulator job: the x86_64 named, not arm64 besides", "x86_64", listOf("x86_64")),
            Triple("32-bit still built when named, with others", "armeabi-v7a,arm64-v8a", listOf("armeabi-v7a", "arm64-v8a")),
        )
        val wrong = rows.filter { (_, override, abis) -> Versions.buildABIs(override).toList() != abis }.map { it.first }
        assertEquals(emptyList<String>(), wrong)
    }
}
