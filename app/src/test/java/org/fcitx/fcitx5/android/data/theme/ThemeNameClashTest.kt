/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.Context
import android.os.Environment
import android.os.Looper
import androidx.core.content.edit
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.EngineNotices
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowEnvironment
import java.io.File
import java.util.UUID

/**
 * 060-render-p3 T3 review: a theme of the user's named as a built-in theme is referred to now (`monokai`,
 * `pixel#dark`: 0.5's import refused only 0.5's names) would hide it, the user's found first. At the start
 * it is saved again under a UUID, its files as they are, and each setting naming it names the copy: the
 * user sees what they saw, and the built-in theme can be picked. A kill midway is finished at the next
 * start, no second copy. And a setting naming a theme the user may have though it is not listed is not
 * moved to a built-in one for good.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemeNameClashTest {

    private val dir get() = ThemeFilesManager.themeDir

    private val sp = RuntimeEnvironment.getApplication().getSharedPreferences("theme-name-clash-test", Context.MODE_PRIVATE)

    private val prefs by lazy { ThemePrefs(sp) }

    private val slots = listOf("normal_mode_theme", "light_mode_theme", "dark_mode_theme")

    @Before
    fun empty() {
        phoneStorage()
        dir.deleteRecursively()
        dir.mkdirs()
        // the process's first listing done
        ThemeFilesManager.listThemes()
        sp.edit(commit = true) { clear() }
    }

    // no theme left for a later test's list (the theme page's screenshots), nor a json that does not read
    @After
    fun noThemesLeft() {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun store(vararg names: String) = sp.edit(commit = true) { slots.zip(names).forEach { (k, v) -> putString(k, v) } }

    private fun stored() = slots.map { sp.getString(it, null) }

    // a theme of the user's with a picture, its json written at [modified]
    private fun saved(name: String, modified: Long, of: Theme.Builtin = TestThemes.TransparentDark): Theme.Custom {
        val key = UUID.nameUUIDFromBytes("files of $name".encodeToByteArray())
        val theme = of.deriveCustomBackground(
            name, File(dir, "$key-cropped.png").apply { writeText("p") }.path, File(dir, "$key-src").apply { writeText("s") }.path
        )
        ThemeFilesManager.saveThemeFiles(theme)
        File(dir, "$name.json").setLastModified(modified)
        return theme
    }

    private val clashes = { name: String -> BuiltinThemeFiles.find(ThemeManager.BuiltinThemes, name) != null }

    private fun uuid(name: String) = UUID.nameUUIDFromBytes(name.encodeToByteArray()).toString()

    private fun move(repoint: (String, String) -> Boolean = prefs::renameThemeRefs) =
        ThemeFilesManager.moveClashing(ThemeFilesManager.listThemes(), clashes, repoint)

    private fun jsons() = dir.list()!!.filter { it.endsWith(".json") }.toSet()

    private fun pictures() = dir.list()!!.filter { !it.endsWith(".json") && it != ".saving" }.toSet()

    @Test
    fun aThemeNamedAsABuiltInThemeInEachSlotIsSavedAgainUnderAUuidTheSlotNamingIt() {
        val names = listOf("mine", "monokai", "nord#light", "pixel#dark", "deep-blue#dark")
        names.forEachIndexed { i, name -> saved(name, 1_000_000_000_000 + i * 1000L) }
        assertFalse(clashes("mine"))
        assertTrue(names.drop(1).all(clashes))
        store("monokai", "nord#light", "pixel#dark")
        val before = ThemeFilesManager.listThemes()
        val files = pictures()
        val mine = File(dir, "mine.json").readText()
        val moved = move()
        assertEquals(listOf(uuid("monokai"), uuid("nord#light"), uuid("pixel#dark")), stored())
        // each where it was in the list, the theme it was: its look (its picture's files), its lightness
        assertEquals(before.map { if (clashes(it.name)) uuid(it.name) else it.name }, moved.map { it.name })
        assertEquals(before.map { it.look }, moved.map { it.look })
        assertEquals(before.map { it.isDark }, moved.map { it.isDark })
        assertEquals(files, pictures())
        // on disk so, in the same order (each json's age kept)
        assertEquals(moved, ThemeFilesManager.listThemes())
        assertEquals(setOf("mine.json") + names.drop(1).map { uuid(it) + ".json" }, jsons())
        // the one not clashing untouched
        assertEquals(mine, File(dir, "mine.json").readText())
        // the next start: nothing more to do, the list as it is
        val again = ThemeFilesManager.listThemes()
        assertSame(again, ThemeFilesManager.moveClashing(again, clashes, prefs::renameThemeRefs))
        assertEquals(listOf(uuid("monokai"), uuid("nord#light"), uuid("pixel#dark")), stored())
    }

    @Test
    fun aSlotNamingAThemeNotClashingOrABuiltInOneIsLeft() {
        saved("mine", 1_000_000_000_000)
        saved("monokai", 1_000_000_001_000)
        store("mine", "pixel#light", "monokai")
        move()
        assertEquals(listOf("mine", "pixel#light", uuid("monokai")), stored())
    }

    @Test
    fun aKillMidwayIsFinishedAtTheNextStartNoSecondCopy() {
        saved("monokai", 1_000_000_000_000)
        store("monokai", "monokai", "monokai")
        // killed before the settings were written: the copy there, every slot on the old one, still there
        val first = move { _, _ -> false }
        assertEquals(listOf("monokai"), first.map { it.name })
        assertEquals(setOf("monokai.json", uuid("monokai") + ".json"), jsons())
        assertEquals(listOf("monokai", "monokai", "monokai"), stored())
        // the next start lists both: done onto the same copy
        val second = move()
        assertEquals(listOf(uuid("monokai")), second.map { it.name })
        assertEquals(setOf(uuid("monokai") + ".json"), jsons())
        assertEquals(List(3) { uuid("monokai") }, stored())
        // killed after the settings were written, before the old json went: the same
        saved("pixel#dark", 1_000_000_002_000)
        store("pixel#dark", uuid("monokai"), "pixel#dark")
        move { from, to -> prefs.renameThemeRefs(from, to) && false }
        assertEquals(listOf(uuid("pixel#dark"), uuid("monokai"), uuid("pixel#dark")), stored())
        assertEquals(listOf(uuid("pixel#dark"), uuid("monokai")), move().map { it.name })
        assertEquals(setOf(uuid("monokai") + ".json", uuid("pixel#dark") + ".json"), jsons())
    }

    @Test
    fun aCopyChangedSinceIsNotWrittenOver() {
        // the copy made at a start, then changed; an old backup brings the old json back
        val changed = saved(uuid("monokai"), 1_000_000_000_000, of = TestThemes.TransparentLight)
        saved("monokai", 1_000_000_001_000)
        store("monokai", uuid("monokai"), "x")
        // killed before the settings were written, twice: the same copy each start, no third json
        repeat(2) {
            assertTrue(move { _, _ -> false }.any { it.name == "monokai" })
            assertEquals(setOf("monokai.json", uuid("monokai#1") + ".json", uuid("monokai") + ".json"), jsons())
        }
        val moved = move()
        assertEquals(listOf(uuid("monokai#1"), uuid("monokai")), moved.map { it.name })
        assertEquals(listOf(uuid("monokai#1"), uuid("monokai"), "x"), stored())
        assertEquals(changed, ThemeFilesManager.listThemes().single { it.name == uuid("monokai") })
        assertEquals(setOf(uuid("monokai#1") + ".json", uuid("monokai") + ".json"), jsons())
    }

    @Test
    fun aJsonNamedOtherwiseIsMovedToItsName() {
        saved("monokai", 1_000_000_000_000)
        File(dir, "monokai.json").renameTo(File(dir, "foo.json"))
        saved("mine", 1_000_000_001_000)
        // a second json of a name listed already: not listed; one reading as the same theme deleted (else it would bring
        // the theme back once that is deleted), one of another theme of the name left as it is
        File(dir, "mine.json").copyTo(File(dir, "bar.json"))
        File(dir, "baz.json").writeText(File(dir, "mine.json").readText().replaceFirst("{", "{ "))
        val another = TestThemes.TransparentLight.deriveCustomNoBackground("mine")
        File(dir, "qux.json").writeText(kotlinx.serialization.json.Json.encodeToString(CustomThemeSerializer, another))
        store("monokai", "mine", "x")
        val moved = move()
        assertEquals(listOf("mine", uuid("monokai")), moved.map { it.name })
        assertEquals(listOf(uuid("monokai"), "mine", "x"), stored())
        assertEquals(setOf("qux.json", "mine.json", uuid("monokai") + ".json"), jsons())
        File(dir, "qux.json").delete()
        // one of a name not clashing moved keeps its age (the list's order)
        saved("kept", 1_000_000_000_500)
        File(dir, "kept.json").renameTo(File(dir, "old.json"))
        assertEquals(listOf("mine", "kept", uuid("monokai")), ThemeFilesManager.listThemes().map { it.name })
        assertEquals(1_000_000_000_500, File(dir, "kept.json").lastModified())
        ThemeFilesManager.deleteThemeFiles(ThemeFilesManager.listThemes().single { it.name == "kept" })
        // one not clashing deleted is gone: not listed again from its json of another name
        saved("other", 1_000_000_002_000)
        File(dir, "other.json").renameTo(File(dir, "baz.json"))
        ThemeFilesManager.deleteThemeFiles(ThemeFilesManager.listThemes().single { it.name == "other" })
        assertEquals(listOf("mine", uuid("monokai")), ThemeFilesManager.listThemes().map { it.name })
        assertEquals(setOf("mine.json", uuid("monokai") + ".json"), jsons())
    }

    // contract: Android's sepolicy denies apps hard links (`neverallow all_untrusted_apps file_type:file link`): a
    // JVM test links fine, so no run here can tell; the source is read instead, the json move held to not using one
    @Test
    fun aJsonIsMovedWithoutAHardLink() {
        val source = File("src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt").readText()
        assertFalse(source.contains("createLink") || source.contains("Os.link"))
    }

    // a json that does not read (a later build's), not listed. The user told it does not read: said here,
    // not left for a later test's keyboard to show
    private fun unreadable(name: String) {
        val before = EngineNotices.sayer
        EngineNotices.forgetToldOnce()
        EngineNotices.sayer = EngineNotices.Sayer { _, _ -> true }
        try {
            File(dir, "$name.json").writeText("""{"version":"9.0"}""")
            assertTrue(ThemeFilesManager.listThemes().none { it.name == name })
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            EngineNotices.sayer = before
            EngineNotices.forgetToldOnce()
        }
    }

    @Test
    fun aThemeNotListedMayHoldAName() {
        val legacy = BuiltinThemeFiles.LEGACY.keys
        assertEquals(emptyList<String>(), ThemeFilesManager.mayHoldThemes(legacy))
        // its name the user's all the same
        unreadable("MonetLight")
        assertEquals(listOf("MonetLight"), ThemeFilesManager.mayHoldThemes(legacy))
    }

    @Test
    fun aStartMovesAClashingThemeThenTheOldNamesNotOneTheUserMayHold() {
        saved("monokai", 1_000_000_000_000)
        unreadable("MonetLight")
        store("monokai", "PixelDark", "MonetLight")
        val (themes, changed) = ThemeManager.listCustomThemes(prefs)
        assertTrue(changed)
        assertEquals(listOf(uuid("monokai")), themes.map { it.name })
        assertEquals(listOf(uuid("monokai"), "pixel#dark", "MonetLight"), stored())
        // the next start: nothing to change
        assertEquals(themes to false, ThemeManager.listCustomThemes(prefs))
        // an old name alone: changed too (the copy for before the first unlock synced)
        store("DeepBlue")
        assertTrue(ThemeManager.listCustomThemes(prefs).second)
        assertEquals("deep-blue", stored().first())
    }

    @Test
    fun aRefreshMovesAClashingThemeAndSyncsTheCopyBeforeTheFirstUnlock() {
        val sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext)
        val de = androidx.preference.PreferenceManager.getDefaultSharedPreferences(appContext.createDeviceProtectedStorageContext())
        val key = ThemeManager.prefs.normalModeTheme.key
        val was = sp.getString(key, null)
        // themes settled since the start (A4): one named as a built-in theme there now
        saved("monokai", 1_000_000_000_000)
        sp.edit(commit = true) { putString(key, "monokai") }
        try {
            ThemeManager.refreshThemes()
            assertEquals(uuid("monokai"), sp.getString(key, null))
            assertEquals(uuid("monokai"), de.getString(key, null))
            assertEquals(listOf(uuid("monokai")), ThemeManager.getAllThemes().filterIsInstance<Theme.Custom>().map { it.name })
            assertTrue(ThemeManager.getTheme("monokai") is Theme.Builtin)
        } finally {
            sp.edit(commit = true) { if (was == null) remove(key) else putString(key, was) }
            dir.listFiles()?.forEach { it.deleteRecursively() }
            ThemeManager.refreshThemes()
            ThemeManager.syncToDeviceEncryptedStorage()
        }
    }

    @Test
    fun themesNotSettledAreNotMovedAndAnyNameMayBeTheUsers() {
        saved("monokai", 1_000_000_000_000)
        val listed = ThemeFilesManager.listThemes()
        val external = appContext.getExternalFilesDir(null)!!
        // an upgraded install started without its external storage, its themes not moved in yet (A4)
        dir.deleteRecursively()
        File(appContext.filesDir, "engine").mkdirs()
        ShadowEnvironment.setExternalStorageState(external, Environment.MEDIA_REMOVED)
        HostConfigDir.forget()
        try {
            assertFalse(ThemeFilesManager.writable)
            assertEquals(listOf("MonetLight"), ThemeFilesManager.mayHoldThemes(listOf("MonetLight")))
            store("monokai", "monokai", "monokai")
            assertSame(listed, ThemeFilesManager.moveClashing(listed, clashes, prefs::renameThemeRefs))
            assertEquals(listOf("monokai", "monokai", "monokai"), stored())
            // nothing written where the themes stand in meanwhile
            assertTrue(ThemeFilesManager.themeDir.list().isNullOrEmpty())
        } finally {
            File(appContext.filesDir, "engine").deleteRecursively()
            phoneStorage()
        }
    }
}
