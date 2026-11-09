/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.Context
import android.os.Environment
import androidx.core.content.edit
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.HostConfigDir
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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
        val moved = move()
        val copy = moved.single { it.name != uuid("monokai") }
        assertNotEquals(uuid("monokai"), copy.name)
        assertEquals(listOf(copy.name, uuid("monokai"), "x"), stored())
        assertEquals(changed, ThemeFilesManager.listThemes().single { it.name == uuid("monokai") })
        assertEquals(setOf(copy.name + ".json", uuid("monokai") + ".json"), jsons())
    }

    @Test
    fun aThemeNotListedMayHoldAName() {
        assertFalse(ThemeFilesManager.mayHoldTheme("MonetLight"))
        // a json that does not read (a later build's): not listed, its name the user's all the same
        File(dir, "MonetLight.json").writeText("""{"version":"9.0"}""")
        assertTrue(ThemeFilesManager.listThemes().none { it.name == "MonetLight" })
        assertTrue(ThemeFilesManager.mayHoldTheme("MonetLight"))
        assertFalse(ThemeFilesManager.mayHoldTheme("MonetDark"))
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
            assertTrue(ThemeFilesManager.mayHoldTheme("MonetLight"))
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
