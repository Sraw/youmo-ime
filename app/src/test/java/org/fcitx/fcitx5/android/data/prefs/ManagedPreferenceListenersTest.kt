/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A listener called on a change may remove itself or add another, as the input view made anew on a
 * theme change does with its own: the settings crashed after a reset, a ConcurrentModificationException
 * of the set being gone through. In each test both listeners remove themselves, so whichever is
 * called first changes the set before the other is reached.
 */
@RunWith(RobolectricTestRunner::class)
class ManagedPreferenceListenersTest {

    private lateinit var sp: SharedPreferences

    private class Sample(sp: SharedPreferences) : ManagedPreferenceProvider() {
        val flag = ManagedPreference.PBool(sp, "flag", false).apply { register() }
    }

    @Before
    fun setUp() {
        sp = RuntimeEnvironment.getApplication().getSharedPreferences("listeners-test", Context.MODE_PRIVATE)
        sp.edit { clear() }
    }

    @Test
    fun aProvidersListenerMayRemoveItselfOrAddAnotherWhileCalled() {
        val sample = Sample(sp)
        val called = mutableListOf<String>()
        val added = ManagedPreferenceProvider.OnChangeListener { called += "added" }
        val first = object : ManagedPreferenceProvider.OnChangeListener {
            override fun onChange(key: String) {
                called += "first"
                sample.unregisterOnChangeListener(this)
            }
        }
        val second = object : ManagedPreferenceProvider.OnChangeListener {
            override fun onChange(key: String) {
                called += "second"
                sample.unregisterOnChangeListener(this)
                sample.registerOnChangeListener(added)
            }
        }
        sample.registerOnChangeListener(first)
        sample.registerOnChangeListener(second)
        sample.fireChange(sample.flag.key)
        assertEquals(listOf("first", "second"), called.sorted())
        // the one added is called from the next change on
        called.clear()
        sample.fireChange(sample.flag.key)
        assertEquals(listOf("added"), called)
        sample.unregisterOnChangeListener(added)
    }

    @Test
    fun aPreferencesListenerMayRemoveItselfOrAddAnotherWhileCalled() {
        val flag = Sample(sp).flag
        val called = mutableListOf<String>()
        val added = ManagedPreference.OnChangeListener<Boolean> { _, _ -> called += "added" }
        val first = object : ManagedPreference.OnChangeListener<Boolean> {
            override fun onChange(key: String, value: Boolean) {
                called += "first"
                flag.unregisterOnChangeListener(this)
            }
        }
        val second = object : ManagedPreference.OnChangeListener<Boolean> {
            override fun onChange(key: String, value: Boolean) {
                called += "second"
                flag.unregisterOnChangeListener(this)
                flag.registerOnChangeListener(added)
            }
        }
        flag.registerOnChangeListener(first)
        flag.registerOnChangeListener(second)
        flag.fireChange()
        assertEquals(listOf("first", "second"), called.sorted())
        called.clear()
        flag.fireChange()
        assertEquals(listOf("added"), called)
        flag.unregisterOnChangeListener(added)
    }
}
