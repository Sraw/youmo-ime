/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class VoiceSpareTest {

    @Test
    fun anErrorBuildingIsToldNotThrownAndLeavesNone() {
        val spare = VoiceSpare<String> {}
        var told: Throwable? = null
        assertFalse(spare.tryBuild({ throw UnsatisfiedLinkError("no sherpa") }) { told = it })
        assertTrue(told is UnsatisfiedLinkError)
        assertFalse(spare.ready)
        // and the next build is not blocked by the failed one
        assertTrue(spare.tryBuild({ "vad" }) { told = it })
        assertEquals("vad", spare.take())
        // a cancellation is the coroutine's, not a failure
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            spare.tryBuild({ throw java.util.concurrent.CancellationException() }) { error("told") }
        }
    }

    @Test
    fun aSecondBuildIsNotStartedWhileOneIsOnItsWay() {
        val spare = VoiceSpare<String> { }
        val inBuild = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var made = 0
        // a slow build (the VAD's model read in)
        val first = thread { spare.build { made++; inBuild.countDown(); finish.await(5, TimeUnit.SECONDS); "vad" } }
        assertTrue(inBuild.await(5, TimeUnit.SECONDS))
        // the keyboard shown again meanwhile, and taken from while it builds: nothing waits, nothing doubles
        assertFalse(spare.build { made++; "other" })
        assertEquals(null, spare.take())
        finish.countDown()
        first.join()
        assertEquals(1, made)
        assertEquals("vad", spare.take())
    }

    @Test
    fun oneNotWantedIsReleasedNotKept() {
        val released = mutableListOf<String>()
        val spare = VoiceSpare<String> { released += it }
        assertEquals(false, spare.offer("a", wanted = false))
        assertEquals(listOf("a"), released)
        assertEquals(null, spare.take())
    }

    @Test
    fun oneSpareAtATimeTheOtherReleased() {
        val released = mutableListOf<String>()
        val spare = VoiceSpare<String> { released += it }
        assertTrue(spare.offer("a"))
        assertFalse(spare.offer("b"))
        assertEquals(listOf("b"), released)
        assertEquals("a", spare.take())
        assertEquals(null, spare.take())
        spare.offer("c")
        spare.clear()
        assertEquals(listOf("b", "c"), released)
        assertFalse(spare.ready)
    }

    // a clear while a build is under way releases what the build made, rather than keep it as the
    // spare: kept, a VAD's native memory stayed held with voice not in use
    @Test
    fun aClearDuringABuildReleasesWhatItMade() {
        val released = mutableListOf<String>()
        val spare = VoiceSpare<String> { released += it }
        assertFalse(spare.build { spare.clear(); "a" })
        assertFalse(spare.ready)
        assertEquals(listOf("a"), released)
    }

    // a clear landing after the build's keep but before its undo lets go of what was made itself: the
    // undo then finds it gone and does not release it again (a VAD released twice is a native double free)
    @Test
    fun aClearBetweenTheKeepAndItsUndoReleasesOnce() {
        val released = mutableListOf<String>()
        val spare = VoiceSpare<String> { released += it }
        spare.afterOffer = { spare.clear() }
        spare.build { "a" }
        assertFalse(spare.ready)
        assertEquals(listOf("a"), released)
    }

    // review F6461: a clear between reading clears and the offer undoes the keep, released once; with none between, it is kept
    @Test
    fun anOfferAfterAClearItDidNotSeeIsUndone() {
        val released = mutableListOf<String>()
        val spare = VoiceSpare<String> { released += it }
        val before = spare.clears
        spare.clear()
        assertFalse(spare.offer("a", wanted = true, clearsBefore = before))
        assertFalse(spare.ready)
        assertEquals(listOf("a"), released)
        assertTrue(spare.offer("b", wanted = true, clearsBefore = spare.clears))
        assertEquals(listOf("a"), released)
        assertEquals("b", spare.take())
    }
}
