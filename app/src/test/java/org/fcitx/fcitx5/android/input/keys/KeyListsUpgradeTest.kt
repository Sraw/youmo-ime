/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.dirsUnder
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.data.importedUserDataMarker
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** `key_lists` made once from 0.5.2's and the betas' long presses, those kept for a downgrade, a backup's migrated after import, direct boot's migrated in memory. */
@RunWith(RobolectricTestRunner::class)
class KeyListsUpgradeTest {

    private val app = RuntimeEnvironment.getApplication()
    private val sp = app.getSharedPreferences("key-lists-test", Context.MODE_PRIVATE)

    @Before
    fun empty() = sp.edit { clear() }

    private fun started(prefs: AppPrefs = AppPrefs(sp)) = prefs.apply { dropRetired() }

    private fun cache(prefs: AppPrefs) = KeyListsCache { KeyListsCache.of(prefs) }

    private fun SharedPreferences.Editor.v052() {
        putString(AppPrefs.POPUP_OVERRIDES, "q ! 1 Q\n, 。 ？")
        putString(AppPrefs.T9_SYMBOLS, "1 . ,")
    }

    private fun stored() = AppPrefs(sp).storedKeyLists()

    @Test
    fun aNewInstallHasTheEmptyLists() {
        started()
        assertEquals("@2", stored())
    }

    @Test
    @Uat("ST-108")
    fun v052sLongPressesMoveOverAndTheOldOnesStay() {
        sp.edit { v052() }
        val prefs = started()
        val lists = KeyLists.parse(stored())!!
        assertEquals(UserList(false, listOf("!", "1", "Q")), lists[KeyId("full/q")])
        assertEquals(listOf("。", "？"), lists[KeyId.function("comma")]?.items)
        assertEquals(listOf("1", ".", ","), lists[KeyId("t9/1")]?.items)
        assertEquals(lists, cache(prefs).current)
        // kept as they were, for a downgrade
        assertEquals("q ! 1 Q\n, 。 ？", sp.getString(AppPrefs.POPUP_OVERRIDES, null))
        assertEquals("1 . ,", sp.getString(AppPrefs.T9_SYMBOLS, null))
    }

    @Test
    fun onceOnlyAndAResetStaysReset() {
        sp.edit { v052() }
        started()
        val first = stored()
        started()
        assertEquals(first, stored())
        // 全部恢复默认 writes the empty lists: the old ones, still stored, never come back
        sp.edit { putString(AppPrefs.KEY_LISTS, KeyLists.HEADER) }
        started()
        assertEquals("@2", stored())
    }

    @Test
    fun aDowngradesChangesAreNotBroughtBack() {
        sp.edit { v052() }
        started()
        val first = stored()
        // the old version changed its own setting meanwhile: 0.6's is the user's
        sp.edit { putString(AppPrefs.POPUP_OVERRIDES, "w 2") }
        started()
        assertEquals(first, stored())
    }

    @Test
    fun aWrongTypeIsMigratedNeverACrash() {
        sp.edit { putInt(AppPrefs.KEY_LISTS, 3); putInt(AppPrefs.POPUP_OVERRIDES, 1) }
        started()
        assertEquals("@2", stored())
        sp.edit { putString(AppPrefs.KEY_LISTS, " "); v052() }
        started()
        assertEquals(UserList(false, listOf("!", "1", "Q")), KeyLists.parse(stored())!![KeyId("full/q")])
    }

    // a downgrade from a later version: left as it is for that version, read as none set
    @Test
    fun aLaterFormatIsLeftAloneAndReadAsEmpty() {
        sp.edit { putString(AppPrefs.KEY_LISTS, "@4\nfull/q\toff"); v052() }
        val prefs = started()
        assertEquals("@4\nfull/q\toff", stored())
        assertEquals(KeyLists.Empty, cache(prefs).current)
        sp.edit { putString(AppPrefs.KEY_LISTS, "\uFEFF @2\nfull/q\toff") }
        started()
        assertEquals(UserList.Off, cache(prefs).current[KeyId("full/q")])
    }

    // key_lists gone with an old one changed (as 0.5's page left them): migrated again
    @Test
    fun anOldOneChangedWithNoKeyListsIsMigratedAgain() {
        sp.edit { v052() }
        val prefs = started()
        val c = cache(prefs)
        assertEquals(UserList(false, listOf("!", "1", "Q")), c.current[KeyId("full/q")])
        sp.edit { putString(AppPrefs.POPUP_OVERRIDES, "q ?"); remove(AppPrefs.KEY_LISTS) }
        assertEquals(null, prefs.storedKeyLists())
        assertEquals(listOf("?"), c.current[KeyId("full/q")]?.items)
        started(prefs)
        assertEquals(listOf("?"), KeyLists.parse(stored())!![KeyId("full/q")]?.items)
    }

    // a backup restored through UserDataManager, then the start after it in FcitxApplication.onCreate's order: 0.5.2's
    // has no key_lists, its old ones migrate; 0.6's is kept
    private fun imported(name: String, strings: Map<String, String>): AppPrefs {
        val xml = strings.entries.joinToString("") { (k, v) -> "    <string name=\"$k\">$v</string>\n" }
        val zip = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z ->
                z.putNextEntry(ZipEntry("metadata.json"))
                z.write("""{"packageName":"${BuildConfig.APPLICATION_ID}","versionCode":${BuildConfig.VERSION_CODE},"versionName":"1","exportTime":0}""".encodeToByteArray())
                z.putNextEntry(ZipEntry("shared_prefs/$name.xml"))
                z.write("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n$xml</map>\n".encodeToByteArray())
            }
        }.toByteArray()
        val dirs = dirsUnder(File(app.applicationInfo.dataDir))
        UserDataManager.import(zip.inputStream(), dirs).getOrThrow()
        val prefs = AppPrefs(app.getSharedPreferences(name, Context.MODE_PRIVATE))
        FcitxApplication.afterImport(prefs, directBoot = false) { importedUserDataMarker(app) }
        return started(prefs)
    }

    @Test
    @Config(application = FcitxApplication::class)
    @Uat("ST-108")
    fun aBackupImportedIsMigratedAtTheStartAfterOr0_6sKept() {
        val old = imported("key-lists-052", mapOf(AppPrefs.POPUP_OVERRIDES to "q ! 1 Q\nw", AppPrefs.T9_SYMBOLS to "1 . ,"))
        val lists = KeyLists.parse(old.storedKeyLists())!!
        assertEquals(UserList(false, listOf("!", "1", "Q")), lists[KeyId("full/q")])
        assertEquals(UserList.Off, lists[KeyId("full/w")])
        assertEquals(listOf("1", ".", ","), lists[KeyId("t9/1")]?.items)
        val new = imported("key-lists-060", mapOf(AppPrefs.KEY_LISTS to "@2\nfull/w\toff", AppPrefs.POPUP_OVERRIDES to "w 2"))
        assertEquals("@2\nfull/w\toff", new.storedKeyLists())
        assertEquals(UserList.Off, cache(new).current[KeyId("full/w")])
    }

    // the device-protected copy is 0.5.2's, never migrated (dropRetired runs unlocked only): read migrated, nothing written
    @Test
    fun directBootReadsTheOldOnesMigratedInMemory() {
        sp.edit { v052() }
        val locked = AppPrefs(sp)
        val c = cache(locked)
        val migrated = KeyListsMigration.migrate(sp.getString(AppPrefs.POPUP_OVERRIDES, null), sp.getString(AppPrefs.T9_SYMBOLS, null)).text
        val expected = KeyLists.parse(KeyListsMigration.upgrade(migrated, KeyboardVariant.STANDARD_NINE))!!
        assertEquals(expected, c.current)
        assertFalse(sp.contains(AppPrefs.KEY_LISTS))
        // the caches' readers see no change
        val v = c.version
        started(locked)
        assertEquals(expected, c.current)
        assertEquals(v, c.version)
        locked.internal.keyLists.setValue("@2\nfull/q\toff")
        assertEquals(UserList.Off, c.current[KeyId("full/q")])
        assertTrue(c.version > v)
    }

    // unlocked, the lists go to the device-protected copy and the old ones are removed there
    @Test
    @Config(application = FcitxApplication::class)
    fun theListsAreCopiedForBeforeTheFirstUnlock() {
        sp.edit { v052() }
        val prefs = started()
        val de = PreferenceManager.getDefaultSharedPreferences(app.createDeviceProtectedStorageContext())
        de.edit { clear(); putString(AppPrefs.POPUP_OVERRIDES, "q ?") }
        val locked = AppPrefs(de)
        assertEquals(listOf("?"), cache(locked).current[KeyId("full/q")]?.items)
        de.edit { putString(AppPrefs.T9_SYMBOLS, "2 ⒉"); putString(AppPrefs.KEY_POPUPS, "t9/3 3 D E F d e f +") }
        prefs.syncToDeviceEncryptedStorage()
        assertEquals(stored(), de.getString(AppPrefs.KEY_LISTS, null))
        assertFalse(de.contains(AppPrefs.POPUP_OVERRIDES))
        assertFalse(de.contains(AppPrefs.T9_SYMBOLS))
        assertFalse(de.contains(AppPrefs.KEY_POPUPS))
        assertEquals(KeyLists.parse(stored()), cache(locked).current)
    }

    // not migrated yet: the copy's own old ones migrated in memory, never a `@1` that would hide them for good, and kept
    @Test
    @Config(application = FcitxApplication::class)
    fun noListsStoredNoneCopied() {
        val prefs = AppPrefs(sp)
        val de = PreferenceManager.getDefaultSharedPreferences(app.createDeviceProtectedStorageContext())
        de.edit { clear(); putString(AppPrefs.KEY_LISTS, "@1\nfull/w\toff"); putString(AppPrefs.POPUP_OVERRIDES, "q ?") }
        prefs.syncToDeviceEncryptedStorage()
        assertFalse(de.contains(AppPrefs.KEY_LISTS))
        assertEquals(listOf("?"), cache(AppPrefs(de)).current[KeyId("full/q")]?.items)
    }

    // key_lists not stored after a sync that dropped the old ones there: copied again, direct boot never left with neither
    @Test
    @Config(application = FcitxApplication::class)
    fun noListsStoredTheOldOnesCopied() {
        sp.edit { clear(); putString(AppPrefs.POPUP_OVERRIDES, "q ?") }
        val prefs = AppPrefs(sp)
        val de = PreferenceManager.getDefaultSharedPreferences(app.createDeviceProtectedStorageContext())
        de.edit { clear(); putString(AppPrefs.KEY_LISTS, "@1\nfull/w\toff") }
        prefs.syncToDeviceEncryptedStorage()
        assertFalse(de.contains(AppPrefs.KEY_LISTS))
        assertEquals("q ?", de.getString(AppPrefs.POPUP_OVERRIDES, null))
        assertEquals(listOf("?"), cache(AppPrefs(de)).current[KeyId("full/q")]?.items)
    }
}
