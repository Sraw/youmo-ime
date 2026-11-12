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
import org.fcitx.fcitx5.android.core.dirsUnder
import org.fcitx.fcitx5.android.data.UserDataManager
import org.fcitx.fcitx5.android.data.importedUserDataMarker
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    private fun cache(prefs: AppPrefs) = KeyListsCache { KeyListsCache.of(prefs) }

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

    private fun stored() = AppPrefs(sp).storedKeyLists()

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
    fun aWrongTypeIsMigratedNeverACrash() {
        sp.edit { putInt(AppPrefs.KEY_LISTS, 3); putInt(AppPrefs.POPUP_OVERRIDES, 1) }
        started()
        assertEquals("@1", stored())
        // empty, or blanks: never of any version's
        sp.edit { putString(AppPrefs.KEY_LISTS, " "); v052() }
        started()
        assertEquals(UserList(false, listOf("!", "1", "Q")), KeyLists.parse(stored())!![KeyId("full/q")])
    }

    // a later version's, a downgrade from it: left as it is for that version, read as none set
    @Test
    fun aLaterFormatIsLeftAloneAndReadAsEmpty() {
        sp.edit { putString(AppPrefs.KEY_LISTS, "@2\nfull/q\toff"); v052() }
        val prefs = started()
        assertEquals("@2\nfull/q\toff", stored())
        assertEquals(KeyLists.Empty, cache(prefs).current)
        // a byte order mark and blanks around ours are ours
        sp.edit { putString(AppPrefs.KEY_LISTS, "\uFEFF @1\nfull/q\toff") }
        started()
        assertEquals(UserList.Off, cache(prefs).current[KeyId("full/q")])
    }

    // key_lists gone with an old one changed (as 0.5's page left them): migrated again, read so before that start too
    @Test
    fun anOldOneChangedWithNoKeyListsIsMigratedAgain() {
        sp.edit { v052() }
        val prefs = started()
        val c = cache(prefs)
        assertEquals(UserList(false, listOf("!", "1", "Q")), c.current[KeyId("full/q")])
        sp.edit { putString(AppPrefs.POPUP_OVERRIDES, "q ?"); remove(AppPrefs.KEY_LISTS) }
        assertEquals(null, prefs.storedKeyLists())
        assertEquals(listOf("?"), c.current[KeyId("full/q")]?.symbols)
        started(prefs)
        assertEquals(listOf("?"), KeyLists.parse(stored())!![KeyId("full/q")]?.symbols)
    }

    // a backup restored: 备份全部's zip (its metadata and the settings' file, read by the name the app's has), imported
    // through UserDataManager over this install's, then the start after it, in FcitxApplication.onCreate's order: the
    // hidden settings reset (the import's marker) and the retired dropped. 0.5.2's has no key_lists: its old ones migrate;
    // 0.6's (a TAB and a newline in it) is kept through the reset (m2)
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
        // this install's directories, its settings where the app reads them
        val dirs = dirsUnder(File(app.applicationInfo.dataDir))
        UserDataManager.import(zip.inputStream(), dirs).getOrThrow()
        val prefs = AppPrefs(app.getSharedPreferences(name, Context.MODE_PRIVATE))
        FcitxApplication.resetHiddenSettings(prefs, directBoot = false) { importedUserDataMarker(app) }
        return started(prefs)
    }

    @Test
    @Config(application = FcitxApplication::class)
    fun aBackupImportedIsMigratedAtTheStartAfterOr0_6sKept() {
        val old = imported("key-lists-052", mapOf(AppPrefs.POPUP_OVERRIDES to "q ! 1 Q\nw", AppPrefs.T9_SYMBOLS to "1 . ,"))
        val lists = KeyLists.parse(old.storedKeyLists())!!
        assertEquals(UserList(false, listOf("!", "1", "Q")), lists[KeyId("full/q")])
        assertEquals(UserList.Off, lists[KeyId("full/w")])
        assertEquals(listOf(".", ","), lists[KeyId("t9/1")]?.symbols)
        val new = imported("key-lists-060", mapOf(AppPrefs.KEY_LISTS to "@1\nfull/w\toff", AppPrefs.POPUP_OVERRIDES to "w 2"))
        assertEquals("@1\nfull/w\toff", new.storedKeyLists())
        assertEquals(UserList.Off, cache(new).current[KeyId("full/w")])
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

    // unlocked, the lists go to the device-protected copy, the old ones removed there (060-keys-unify S6: no keyboard reads
    // them); read there before the next unlock as the lists, whatever an older copy of the old ones says
    @Test
    @Config(application = FcitxApplication::class)
    fun theListsAreCopiedForBeforeTheFirstUnlock() {
        sp.edit { beta2() }
        val prefs = started()
        val de = PreferenceManager.getDefaultSharedPreferences(app.createDeviceProtectedStorageContext())
        de.edit { clear(); putString(AppPrefs.POPUP_OVERRIDES, "q ?") }
        val locked = AppPrefs(de)
        assertEquals(listOf("?"), cache(locked).current[KeyId("full/q")]?.symbols)
        prefs.syncToDeviceEncryptedStorage()
        assertEquals(stored(), de.getString(AppPrefs.KEY_LISTS, null))
        // the older copy there gone with them, no direct boot reading it (060-s6-review L5)
        assertFalse(de.contains(AppPrefs.POPUP_OVERRIDES))
        assertFalse(de.contains(AppPrefs.T9_SYMBOLS))
        assertEquals(KeyLists.parse(stored()), cache(locked).current)
    }

    // not migrated yet (an upgrade's first start before dropRetired, a 0.5 backup restored): no key_lists there either,
    // the device-protected copy's own old ones migrated in memory there, never a `@1` that would hide them for good
    @Test
    @Config(application = FcitxApplication::class)
    fun noListsStoredNoneCopied() {
        val prefs = AppPrefs(sp)
        val de = PreferenceManager.getDefaultSharedPreferences(app.createDeviceProtectedStorageContext())
        de.edit { clear(); putString(AppPrefs.KEY_LISTS, "@1\nfull/w\toff"); putString(AppPrefs.POPUP_OVERRIDES, "q ?") }
        prefs.syncToDeviceEncryptedStorage()
        assertFalse(de.contains(AppPrefs.KEY_LISTS))
        assertEquals(listOf("?"), cache(AppPrefs(de)).current[KeyId("full/q")]?.symbols)
    }
}
