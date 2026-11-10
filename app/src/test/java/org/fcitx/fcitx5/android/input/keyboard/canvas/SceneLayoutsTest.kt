/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.scene.layout.Include
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Every layout the app is built with reads (060-render-p2 S7, U8): a keyboard whose layout does not
 * read draws nothing (no fall back to its views), so one that does not is a packaging error, caught
 * here. Each raw `scene_layout_*` resource is one [BuiltInLayouts.RAW] names, and each reads, the
 * fragments it includes with it; a fragment reads as the layouts including it read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class SceneLayoutsTest {

    // contract: every layout file shipped in keyboard-scene reads with no problem
    @Test
    fun everyBuiltInLayoutReads() {
        val context = RuntimeEnvironment.getApplication()
        // the resources as built, by their names: one copied in and not named in RAW is never read
        val built = R.raw::class.java.fields.filter { it.name.startsWith(PREFIX) }
            .associate { it.name.removePrefix(PREFIX).replace('_', '-') to it.getInt(null) }
        assertTrue("no scene layouts built", built.isNotEmpty())
        assertEquals(built, BuiltInLayouts.RAW)
        SceneLayouts.source = ::BuiltInLayouts
        SceneLayouts.fallback = ::BuiltInLayouts
        val read = BuiltInLayouts.RAW.keys.mapNotNull { id -> SceneLayouts.of(context, id)?.let { id to it } }.toMap()
        val included = read.values.flatMap { l -> l.layout.parts.filterIsInstance<Include>().map { it.src } }.toSet()
        for (id in BuiltInLayouts.RAW.keys) {
            if (id in included) {
                assertTrue("fragment $id read by no layout", read.values.any { id in it.fragments })
            } else {
                assertNotNull("layout $id does not read", read[id])
            }
        }
    }

    private companion object {
        const val PREFIX = "scene_layout_"
    }
}
