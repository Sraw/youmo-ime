/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 060-keys-unify §4.3–4.5 and §6.4, the app's half: `key_lists` made once at a start from 0.5.2's and the betas'
 * long presses, those kept for a downgrade, a backup's migrated at the start after its import, and the keyboard
 * before the first unlock reading the device-protected copy's old ones migrated in memory.
 */
@RunWith(RobolectricTestRunner::class)
class KeyListsUpgradeTest {

    private val app = RuntimeEnvironment.getApplication()
    private val sp = app.getSharedPreferences("key-lists-test", Context.MODE_PRIVATE)

    @Before
    fun empty() = sp.edit { clear() }

    private fun started(prefs: AppPrefs = AppPrefs(sp)) = prefs.apply { dropRetired() }

    private fun cache(prefs: AppPrefs) = KeyListsCache {
        KeyListsCache.Stored(prefs.internal.keyLists.getValue()) {
            Triple(prefs.internal.popupOverrides.getValue(), prefs.internal.keyPopups.getValue(), prefs.keyboard.t9Symbols.getValue())
        }
    }

    // 0.5.2 (and beta1): 全键盘's by the letter and 九键's symbols
    private fun SharedPreferences.Editor.v052() {
        putString(AppPrefs.POPUP_OVERRIDES, "q ! 1 Q\n, 。 ？")
        putString(AppPrefs.T9_SYMBOLS, "1 . ,")
    }

    // beta2: the other keyboards' by the key's name too
    private fun SharedPreferences.Editor.beta2() {
        v052()
        putString(AppPrefs.KEY_POPUPS, "t9/2 2 A B C a b c # @\ncomma ！")
    }

    private fun stored() = sp.getString(AppPrefs.KEY_LISTS, null)

    @Test
    fun aNewInstallHasTheEmptyLists() {
        started()
        assertEquals("@1", stored())
    }

    @Test
    fun v052sLongPressesMoveOverAndTheOldOnesStay() {
        sp.edit { v052() }
        val prefs = started()
        val lists = KeyLists.parse(stored())!!
        assertEquals(UserList(false, listOf("!", "1", "Q")), lists[KeyId("full/q")])
        assertEquals(listOf("。", "？"), lists[KeyId.function("comma")]?.symbols)
        assertEquals(listOf(".", ","), lists[KeyId("t9/1")]?.symbols)
        assertEquals(lists, cache(prefs).current)
        // kept as they were, for a downgrade (§4.4)
        assertEquals("q ! 1 Q\n, 。 ？", sp.getString(AppPrefs.POPUP_OVERRIDES, null))
        assertEquals("1 . ,", sp.getString(AppPrefs.T9_SYMBOLS, null))
    }

    @Test
    fun beta2sNamedListsMoveOverToo() {
        sp.edit { beta2() }
        started()
        val lists = KeyLists.parse(stored())!!
        assertEquals(listOf("#", "@"), lists[KeyId("t9/2")]?.symbols)
        // the named one, the newer, over the character's
        assertEquals(listOf("！"), lists[KeyId.function("comma")]?.symbols)
    }

    @Test
    fun onceOnlyAndAResetStaysReset() {
        sp.edit { beta2() }
        started()
        val first = stored()
        started()
        assertEquals(first, stored())
        // 全部恢复默认 writes the empty lists: the old ones, still stored, never come back
        sp.edit { putString(AppPrefs.KEY_LISTS, KeyLists.HEADER) }
        started()
        assertEquals("@1", stored())
    }

    @Test
    fun aDowngradesChangesAreNotBroughtBack() {
        sp.edit { v052() }
        started()
        val first = stored()
        // the old version changed its own setting meanwhile: 0.6's is the user's (§4.4, L9)
        sp.edit { putString(AppPrefs.POPUP_OVERRIDES, "w 2") }
        started()
        assertEquals(first, stored())
    }

    @Test
    fun aLaterFormatOrAWrongTypeIsMigratedNeverACrash() {
        sp.edit { putInt(AppPrefs.KEY_LISTS, 3); putInt(AppPrefs.POPUP_OVERRIDES, 1) }
        started()
        assertEquals("@1", stored())
        sp.edit { putString(AppPrefs.KEY_LISTS, "@2\nfull/q\toff"); v052() }
        started()
        assertTrue(KeyLists.parse(stored())!![KeyId("full/q")] != null)
    }

    // a 0.5.2 backup over a 0.6 install: the start after the import resets the hidden settings and then drops the
    // retired ones, in FcitxApplication.onCreate's order. key_lists is the backup's (none): its old ones migrate (m2)
    @Test
    @Config(application = FcitxApplication::class)
    fun a052BackupImportedIsMigratedAtTheStartAfter() {
        sp.edit { putString(AppPrefs.KEY_LISTS, "@1\nfull/w\toff") }
        // the import replaced the whole file
        sp.edit { clear(); v052() }
        val marker = File.createTempFile("imported", null)
        val prefs = AppPrefs(sp)
        FcitxApplication.resetHiddenSettings(prefs, directBoot = false) { marker }
        started(prefs)
        val lists = KeyLists.parse(stored())!!
        assertEquals(null, lists[KeyId("full/w")])
        assertEquals(UserList(false, listOf("!", "1", "Q")), lists[KeyId("full/q")])
        // and a 0.6 backup's own is kept through the reset after its import
        sp.edit { putString(AppPrefs.KEY_LISTS, "@1\nfull/w\toff") }
        FcitxApplication.resetHiddenSettings(prefs, directBoot = false) { File.createTempFile("imported", null) }
        started(prefs)
        assertEquals("@1\nfull/w\toff", stored())
    }

    // before the first unlock after an upgrade: the device-protected copy is 0.5.2's, never migrated (dropRetired
    // runs unlocked only); the keyboard reads the user's lists all the same, and nothing is written there
    @Test
    fun directBootReadsTheOldOnesMigratedInMemory() {
        sp.edit { beta2() }
        val locked = AppPrefs(sp)
        val c = cache(locked)
        val expected = KeyLists.parse(KeyListsMigration.migrate(sp.getString(AppPrefs.POPUP_OVERRIDES, null), sp.getString(AppPrefs.KEY_POPUPS, null), sp.getString(AppPrefs.T9_SYMBOLS, null)).text)!!
        assertEquals(expected, c.current)
        assertFalse(sp.contains(AppPrefs.KEY_LISTS))
        // the same lists after the unlock's migration: the caches' readers see no change
        val v = c.version
        started(locked)
        assertEquals(expected, c.current)
        assertEquals(v, c.version)
        // a change is a new version
        locked.internal.keyLists.setValue("@1\nfull/q\toff")
        assertEquals(UserList.Off, c.current[KeyId("full/q")])
        assertTrue(c.version > v)
    }

    // unlocked, the lists go to the device-protected copy; the old ones and 九键's symbols no longer do
    @Test
    @Config(application = FcitxApplication::class)
    fun theListsAreCopiedForBeforeTheFirstUnlock() {
        sp.edit { beta2() }
        val prefs = started()
        val de = PreferenceManager.getDefaultSharedPreferences(app.createDeviceProtectedStorageContext())
        de.edit { clear() }
        prefs.syncToDeviceEncryptedStorage()
        assertEquals(stored(), de.getString(AppPrefs.KEY_LISTS, null))
        assertFalse(de.contains(AppPrefs.POPUP_OVERRIDES) || de.contains(AppPrefs.KEY_POPUPS) || de.contains(AppPrefs.T9_SYMBOLS))
        // and read there before the next unlock, as the user's
        assertEquals(KeyLists.parse(stored()), cache(AppPrefs(de)).current)
    }
}
