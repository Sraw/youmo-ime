/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** The built-in themes that are packs (060-builtin-themes): unpacked from the assets, listed, never deleted. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class BuiltinPacksTest {

    @Test
    fun everyAssetIsListedInItsOrderAsTheAppsOwn() {
        val packs = ThemeManager.BuiltinPackThemes
        assertEquals(
            listOf("简蓝", "橙风", "素雅", "清心", "墨灰", "液态玻璃", "霓虹赛博", "国风·天青", "机械键帽", "奶油", "护眼·纸色", "多巴胺"),
            packs.map { it.file }.distinct(),
        )
        assertTrue(packs.all { it.builtin })
        // 墨灰 and 霓虹赛博 dark only, 国风·天青 light only
        assertEquals(listOf(true), packs.filter { it.file == "墨灰" }.map { it.isDark })
        assertEquals(listOf("简蓝#light", "简蓝#dark"), packs.filter { it.file == "简蓝" }.map { it.name })
        // listed after the user's and the built-in themes
        assertEquals(packs, ThemeManager.getAllThemes().takeLast(packs.size))
        // their pictures not decoded by the listing: each look made when drawn
        packs.forEach { assertTrue(it.name, File(it.dir, ThemePackFiles.THEME_XML).isFile) }
    }

    @Test
    fun aBuiltInPackIsNeverDeleted() {
        val pack = ThemeManager.BuiltinPackThemes.first()
        assertFalse(ThemeManager.deleteTheme(pack))
        assertTrue(File(pack.dir).isDirectory)
        assertEquals(pack, ThemeManager.getTheme(pack.name))
    }

    @Test
    fun aBuiltInPackKeepsTheVersionPickedAsTheBuiltInThemesDo() {
        val was = ThemeManager.prefs.normalModeTheme.getValue()
        try {
            // the system light (Robolectric's): the dark version all the same, not switched as an imported pair is
            ThemeManager.setNormalModeTheme(ThemeManager.getTheme("素雅#dark")!!)
            assertEquals("素雅#dark", ThemeManager.activeTheme.name)
        } finally {
            ThemeManager.setNormalModeTheme(was)
        }
    }

    @Test
    fun unpackedWhereTheKeyboardReadsBeforeTheFirstUnlock() {
        // device-protected storage: the credential-encrypted noBackupFilesDir is not there before the first unlock
        val de = appContext.createDeviceProtectedStorageContext().noBackupFilesDir
        assertEquals(de, BuiltinPacks.dir(appContext).parentFile)
    }

    @Test
    fun unpackedOnceAVersionAndNoneWhereOneFails() {
        val root = File(appContext.cacheDir, "bp-test").apply { deleteRecursively() }
        var opened = 0
        val open = { path: String -> opened++; BuiltinPacks.open(appContext, path) }
        assertTrue(BuiltinPacks.unpack(root, "v1", open = open))
        assertEquals(BuiltinPacks.FILES.size, opened)
        assertTrue(BuiltinPacks.unpack(root, "v1", open = open))
        assertEquals(BuiltinPacks.FILES.size, opened)
        assertEquals(BuiltinPacks.FILES.size, BuiltinPacks.list(root, ThemeManager.BuiltinThemes).distinctBy { it.file }.size)
        // a user's pack of the same name shown instead
        assertTrue(BuiltinPacks.list(root, ThemeManager.BuiltinThemes, setOf("奶油")).none { it.file == "奶油" })
        // its name still one of the built-in packs': an import of that name is refused as ever
        assertTrue("奶油" in BuiltinPacks.names(root))
        // a new version, one asset failing: none listed (tried again ten minutes later or at the next start)
        assertFalse(BuiltinPacks.unpack(root, "v2") { if (it == "cream") throw IOException("full") else BuiltinPacks.open(appContext, it) })
        assertEquals(emptyList<Theme.Pack>(), BuiltinPacks.list(root, ThemeManager.BuiltinThemes))
        root.deleteRecursively()
    }

    @Test
    fun aFolderGoneUnderTheStampIsUnpackedAgain() {
        // the stamp alone was trusted: a pack's folder deleted (a cleaner app) stayed gone until the next version
        val root = File(appContext.cacheDir, "bp-gone").apply { deleteRecursively() }
        assertTrue(BuiltinPacks.unpack(root, "v1") { BuiltinPacks.open(appContext, it) })
        val names = BuiltinPacks.names(root)
        assertEquals(BuiltinPacks.FILES.size, names.size)
        assertTrue(File(ThemePackFiles.folder(root), names.first()).deleteRecursively())
        assertTrue(BuiltinPacks.unpack(root, "v1") { BuiltinPacks.open(appContext, it) })
        assertEquals(BuiltinPacks.FILES.size, BuiltinPacks.list(root, ThemeManager.BuiltinThemes).distinctBy { it.file }.size)
        root.deleteRecursively()
    }

    @Test
    fun oneThatFailedIsTriedAgainOnlyTenMinutesLater() {
        // each refresh of the themes (each opening of the theme page) deleted and unpacked all of them again;
        // and a full disk cleared while the process lives on must still get them unpacked
        val root = File(appContext.cacheDir, "bp-failed").apply { deleteRecursively() }
        var opened = 0
        var full = true
        var clock = 0L
        val failing = { id: String -> opened++; if (full && id == "cream") throw IOException("full") else BuiltinPacks.open(appContext, id) }
        assertFalse(BuiltinPacks.unpack(root, "v1", { clock }, failing))
        val once = opened
        clock += 10L * 60 * 1_000_000_000 - 1
        assertFalse(BuiltinPacks.unpack(root, "v1", { clock }, failing))
        assertEquals(once, opened)
        full = false
        clock += 1
        assertTrue(BuiltinPacks.unpack(root, "v1", { clock }, failing))
        assertEquals(BuiltinPacks.FILES.size, BuiltinPacks.names(root).size)
        root.deleteRecursively()
    }

    @Test
    fun aZipThatDoesNotReadIsNoCrash() {
        // only some exceptions were caught: another one out of a zip the build got wrong crashed every start
        val root = File(appContext.cacheDir, "bp-bad").apply { deleteRecursively() }
        assertFalse(BuiltinPacks.unpack(root, "v1") { throw IllegalArgumentException("bad entry name") })
        assertEquals(emptyList<String>(), BuiltinPacks.names(root))
        root.deleteRecursively()
    }
}
