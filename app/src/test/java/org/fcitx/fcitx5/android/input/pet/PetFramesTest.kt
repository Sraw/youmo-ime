/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.pet

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.theme.ThemeAssets
import org.fcitx.fcitx5.android.scene.pet.Pet
import org.fcitx.fcitx5.android.scene.pet.PetState
import org.fcitx.fcitx5.android.scene.pet.PetState.Name
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Decoded once about as large as drawn; over the budget the states go in their order, then idle shrinks; too small to see move, idle stands still. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class)
class PetFramesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val runner = ThemeAssets.runner
    private val poster = ThemeAssets.poster

    @Before
    fun atOnce() {
        ThemeAssets.runner = { it.run() }
        ThemeAssets.poster = { it.run() }
    }

    @After
    fun putBack() {
        ThemeAssets.runner = runner
        ThemeAssets.poster = poster
    }

    private fun pet(vararg states: Pair<Name, PetState>) = Pet(Pet.Place.KEYBOARD, Pet.Align.TOP_END, 40.0, 0.0, 0.0, mapOf(*states))

    private fun s(frames: Int, columns: Int = frames) = PetState("x.png", frames, columns, 8, false)

    private val frame = 256 * 256 * 4L

    @Test
    fun framesShrinkToTheBoxAndNeverGrow() {
        val p = pet(Name.IDLE to s(4))
        assertEquals(PetFrames.Target(80, 20), PetFrames.plan(p, mapOf(Name.IDLE to intArrayOf(160, 40)), 20, PetFrames.BUDGET_BYTES)!!.targets[Name.IDLE])
        assertEquals(PetFrames.Target(160, 40), PetFrames.plan(p, mapOf(Name.IDLE to intArrayOf(160, 40)), 100, PetFrames.BUDGET_BYTES)!!.targets[Name.IDLE])
        val tall = pet(Name.IDLE to s(4, 2))
        assertEquals(PetFrames.Target(60, 120), PetFrames.plan(tall, mapOf(Name.IDLE to intArrayOf(100, 200)), 60, PetFrames.BUDGET_BYTES)!!.targets[Name.IDLE])
    }

    @Test
    fun overTheBudgetStatesGoInTheirOrder() {
        val names = listOf(Name.IDLE, Name.TYPING, Name.DELETE, Name.ENTER, Name.SPACE, Name.SLEEP, Name.WAKE)
        val p = pet(*names.map { it to s(1) }.toTypedArray())
        val sizes = names.associateWith { intArrayOf(256, 256) }
        val plan = PetFrames.plan(p, sizes, 256, 3 * frame)!!
        assertEquals(listOf(Name.WAKE, Name.SLEEP, Name.SPACE, Name.ENTER), plan.dropped)
        assertEquals(setOf(Name.IDLE, Name.TYPING, Name.DELETE), plan.targets.keys)
        assertEquals(names.toSet(), PetFrames.plan(p, sizes, 256, 7 * frame)!!.targets.keys)
        // the phone short of memory has half
        assertEquals(PetFrames.BUDGET_BYTES / 2, PetFrames.LOW_MEMORY_BUDGET_BYTES)
    }

    @Test
    fun idleAloneOverItShrinks() {
        val p = pet(Name.IDLE to s(4), Name.TYPING to s(1))
        val plan = PetFrames.plan(p, mapOf(Name.IDLE to intArrayOf(1024, 256), Name.TYPING to intArrayOf(256, 256)), 256, frame)!!
        assertEquals(listOf(Name.TYPING), plan.dropped)
        val t = plan.targets.getValue(Name.IDLE)
        assertEquals(PetFrames.Target(512, 128), t)
        assertTrue(t.bytes <= frame)
        assertEquals(false, plan.still)
    }

    @Test
    fun tooSmallToSeeMoveIdleStandsStill() {
        val p = pet(Name.IDLE to s(4), Name.TYPING to s(2))
        val plan = PetFrames.plan(p, mapOf(Name.IDLE to intArrayOf(256, 64), Name.TYPING to intArrayOf(128, 64)), 20, PetFrames.BUDGET_BYTES)!!
        assertTrue(plan.still)
        assertEquals(setOf(Name.IDLE), plan.targets.keys)
        assertEquals(listOf(Name.TYPING), plan.dropped)
    }

    @Test
    fun smallFramesInABigBoxStillMove() {
        // pixel art: 16 px frames drawn at the box's size, as the web's preview plays them
        val p = pet(Name.IDLE to s(4), Name.TYPING to s(2))
        val plan = PetFrames.plan(p, mapOf(Name.IDLE to intArrayOf(64, 16), Name.TYPING to intArrayOf(32, 16)), 100, PetFrames.BUDGET_BYTES)!!
        assertFalse(plan.still)
        assertEquals(setOf(Name.IDLE, Name.TYPING), plan.targets.keys)
        assertEquals(PetFrames.Target(64, 16), plan.targets[Name.IDLE])
        // shrunk for memory below what moves: still
        val starved = PetFrames.plan(p, mapOf(Name.IDLE to intArrayOf(1024, 256), Name.TYPING to intArrayOf(512, 256)), 256, 4L * 20 * 20 * 4)!!
        assertTrue(starved.still)
    }

    @Test
    fun noIdleNoPet() {
        assertNull(PetFrames.plan(pet(Name.IDLE to s(1), Name.TYPING to s(1)), mapOf(Name.TYPING to intArrayOf(8, 8)), 40, PetFrames.BUDGET_BYTES))
    }

    private fun load(p: Pet, box: Int, budget: Long = PetFrames.BUDGET_BYTES): PetFrames? {
        var got: PetFrames? = null
        PetFrames.load(p, box, budget) { got = it }
        return got
    }

    @Test
    fun loadedOnceAtTheirSizeWithinTheBudget() {
        val p = PetSheets.pet(folder.root, Name.IDLE to (4 to 4), Name.TYPING to (6 to 12), Name.SLEEP to (2 to 2), framePx = 200)
        val before = PetFrames.decodes
        val f = load(p, 100)!!
        assertEquals(3, PetFrames.decodes - before)
        val idle = f.sheet(Name.IDLE)!!
        assertEquals(Triple(100, 100, 4), Triple(idle.frameWidth, idle.frameHeight, idle.columns))
        assertEquals(400 to 100, idle.bitmap.width to idle.bitmap.height)
        val small = load(p, 100, 4 * 100 * 100 * 4L + 6 * 100 * 100 * 4L)!!
        assertEquals(setOf(Name.IDLE, Name.TYPING), small.pet.states.keys)
        assertNull(small.sheet(Name.SLEEP))
        f.release()
        assertNull(f.sheet(Name.IDLE))
        assertTrue(idle.bitmap.isRecycled)
    }

    @Test
    fun tooSmallItHasOneFrame() {
        val p = PetSheets.pet(folder.root, Name.IDLE to (4 to 4), Name.TYPING to (2 to 8))
        val f = load(p, 20)!!
        assertEquals(1, f.pet.idle.frames)
        assertEquals(setOf(Name.IDLE), f.pet.states.keys)
    }

    @Test
    fun aSheetGoneOrChangedSinceIsLeftOut() {
        val p = PetSheets.pet(folder.root, Name.IDLE to (4 to 4), Name.TYPING to (2 to 8))
        File(p.states.getValue(Name.TYPING).sheet).writeBytes(PetSheets.png(1, 1, 63))
        assertEquals(setOf(Name.IDLE), load(p, 40)!!.pet.states.keys)
        File(p.idle.sheet).delete()
        assertNull(load(p, 40))
    }
}
