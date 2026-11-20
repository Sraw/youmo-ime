/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.res.Configuration
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.candidates.SceneBar
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.theme.Argb
import org.fcitx.fcitx5.android.scene.theme.SystemColors
import org.fcitx.fcitx5.android.scene.theme.ThemeReader
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemePackListingTest : ThemePackFixture() {

    @Test
    fun aListingChecksAStaleStampOffItsOwnThreadAndListsThePackMeanwhile() {
        val queued = mutableListOf<Runnable>()
        val pack = placed(sample())[0]
        val stamp = File(pack.dir, ".checked")
        stamp.delete()
        ThemePackFiles.recheck = { queued += it }
        // listed at once, the check queued, not run by the listing
        assertEquals(listOf("mine#light", "mine#dark"), ThemeFilesManager.listPacks().map { it.name })
        assertEquals(1, queued.size)
        assertFalse(stamp.exists())
        queued.removeAt(0).run()
        assertTrue(stamp.isFile)
        ThemeFilesManager.listPacks()
        assertEquals(emptyList<Runnable>(), queued)
    }

    @Test
    fun aPackTheQueuedCheckRefusesIsListedThenLeftOut() {
        placed(sample())
        // a key one cannot type hidden, written after the import's check: the stamp stale
        File(packs, "mine/theme.xml").writeText(xml(styles = """<style select="key.function" hidden="true"/>"""))
        val queued = mutableListOf<Runnable>()
        var told = 0
        ThemePackFiles.recheck = { queued += it }
        ThemePackFiles.onRefused = { told++ }
        assertEquals(listOf("mine#light", "mine#dark"), ThemeFilesManager.listPacks().map { it.name })
        assertEquals(1, queued.size)
        assertEquals(0, told)
        // off the listing's thread: refused, the list reread, which leaves it out
        Thread(queued.removeAt(0)).apply { start() }.join()
        assertEquals(1, told)
        assertEquals(emptyList<Theme.Pack>(), ThemeFilesManager.listPacks())
    }

    @Test
    fun aPackChangedSinceItWasListedIsDrawnFromItsNewFile() {
        val dark = placed(sample())[1]
        ThemeFilesManager.listPacks()
        val file = File(packs, "mine/theme.xml")
        val listedAt = file.lastModified()
        // a style after the icons is not read: the new one goes before them
        file.writeText(sampleXml.replace("<icon ", """<style select="candidate" gap="7dp"/><icon """))
        // a later time, not the clock's: a file system may not tell a second's writes apart
        file.setLastModified(listedAt + 10_000L)
        assertEquals(7.0, SceneBar.of(appContext, dark).gapDp, 0.0)
    }

    @Test
    fun aPackFolderNotListedIsReplacedUnlessALaterBuilds() {
        val kept = appContext.getString(R.string.exception_theme_newer_kept)
        File(packs, "mine").mkdirs()
        // a later build's, unread here: kept, said so
        val later = listOf(
            xml(meta = """<meta min-app="99.0.0"/>""", styles = "<later/>"),
            """<theme xmlns="${ThemeReader.NAMESPACE}" name="mine" version="5.0"><sound/></theme>""",
        )
        for (text in later) {
            File(packs, "mine/theme.xml").writeText(text)
            ThemeManager.refreshThemes()
            assertEquals(kept, refusal(sample()))
            assertEquals(text, File(packs, "mine/theme.xml").readText())
        }
        // past the theme.xml limit is not read whole to tell: replaced
        val huge = "<!--" + "x".repeat(ThemeAssetLimits.MAX_THEME_XML_BYTES.toInt()) + "--></theme>"
        File(packs, "mine/theme.xml").writeText(later[0].replace("</theme>", huge))
        ThemeManager.refreshThemes()
        opened(sample()).apply { assertTrue(replaces) }.discard()
        // cut short (a restore killed midway) or unreadable: not listed; reimporting replaces and lists it
        val cutShort: () -> Unit = { File(packs, "mine/round.ttf").delete() }
        val broken: () -> Unit = { File(packs, "mine/theme.xml").writeText("<theme") }
        for (spoil in listOf(cutShort, broken)) {
            File(packs, "mine").deleteRecursively()
            placed(sample())
            spoil()
            ThemeManager.refreshThemes()
            assertTrue(ThemeManager.Packs.none { it.file == "mine" })
            val pack = opened(sample())
            assertTrue(pack.replaces)
            pack.commit().getOrThrow().also { ThemeManager.packPlaced(it[0].file) }
            assertEquals(listOf("mine#light", "mine#dark"), ThemeManager.Packs.map { it.name })
        }
    }

    @Test
    fun aNewPackPastTheMostIsRefusedOneReplacingIsNot() {
        val most = (1..ThemeAssetLimits.MAX_PACKS).map { Theme.Pack("p$it", false, "p$it", File(packs, "p$it").path, 0) }
        assertEquals(appContext.getString(R.string.exception_theme_packs, ThemeAssetLimits.MAX_PACKS.toString()), refusal(sample(), most))
        nothingLeft()
        placed(sample())
        val mine = ThemeManager.Packs
        assertTrue(open(sample(), most.drop(1) + mine).isSuccess)
    }

    @Test
    fun aListingReadsTheNewestMostPacks() {
        val source = xml(name = "p0")
        (0..ThemeAssetLimits.MAX_PACKS).forEach { i ->
            File(packs, "p$i").mkdirs()
            File(packs, "p$i/theme.xml").writeText(source.replace("name=\"p0\"", "name=\"p$i\""))
            // newest by theme.xml's time (the import's), not the folder's
            File(packs, "p$i/theme.xml").setLastModified(1_000_000_000_000L + i * 1000L)
        }
        val listed = ThemeFilesManager.listPacks().map { it.file }.distinct()
        assertEquals(ThemeAssetLimits.MAX_PACKS, listed.size)
        assertFalse("p0" in listed)
    }

    @Test
    fun aPackAnImportWouldRefuseIsNotListed() {
        fun planted(folder: String, text: String) {
            File(packs, folder).mkdirs()
            File(packs, "$folder/theme.xml").writeText(text)
        }
        planted("pixel", xml(name = "pixel"))
        planted("MaterialLight", xml(name = "MaterialLight"))
        planted("x#dark", xml(name = "x#dark"))
        planted("hides", xml(name = "hides", styles = """<style select="key.function" hidden="true"/>"""))
        assertEquals(emptyList<Theme.Pack>(), ThemeFilesManager.listPacks())
        ThemeManager.refreshThemes()
        assertTrue(ThemeManager.getTheme("pixel#dark") is Theme.Builtin)
    }

    @Test
    fun aPackWhoseFileNoLongerReadsIsDrawnAsTheBuiltInTheme() {
        val dark = opened(sample()).commit().getOrThrow()[1]
        File(packs, "mine/theme.xml").writeText("<theme")
        assertEquals("pixel", dark.scene.name)
        assertEquals(ThemeManager.builtin("pixel#dark").palette, dark.look.palette)
    }

    @Test
    fun aPacksBarAndPanelsAreDrawnFromItsOwnFile() {
        val dark = placed(sample(xml(styles = """<style select="candidate" gap="7dp"/><style select="bar" height="120%"/>""")))[1]
        assertEquals(7.0, SceneBar.of(appContext, dark).gapDp, 0.0)
        ThemeManager.setNormalModeTheme(dark)
        assertEquals(120.0, SceneBar.heightPercent(appContext), 0.0)
    }

    @Test
    fun aPackInUseReplacedIsToldAsChanged() {
        val first = placed(sample())
        ThemeManager.setNormalModeTheme(first[1])
        val told = mutableListOf<Theme>()
        val listener = ThemeManager.OnThemeChangeListener { told += it }
        ThemeManager.addOnChangedListener(listener)
        try {
            placed(sample(xml(styles = """<style select="candidate" gap="7dp"/>""")))
            assertEquals(listOf("mine#dark"), told.map { it.name }.distinct())
            assertEquals(7.0, SceneBar.of(appContext, ThemeManager.activeTheme).gapDp, 0.0)
        } finally {
            ThemeManager.removeOnChangedListener(listener)
        }
    }

    @Test
    fun aPackIsReadAgainOnlyWhenTheSystemsColoursChange() {
        val monet = BuiltinThemeFiles.text(appContext.resources, R.raw.scene_theme_monet).replace("name=\"monet\"", "name=\"mine\"")
        val dark = placed(zip("theme.xml" to monet))[1]
        ThemeManager.setNormalModeTheme(dark)
        val told = mutableListOf<Theme>()
        val listener = ThemeManager.OnThemeChangeListener { told += it }
        ThemeManager.addOnChangedListener(listener)
        val config = appContext.resources.configuration
        try {
            // turned, say: same colours, nothing reread or redrawn
            val scene = (ThemeManager.activeTheme as Theme.Pack).scene
            ThemeManager.onSystemPlatteChange(config)
            assertEquals(emptyList<Theme>(), told)
            assertSame(scene, (ThemeManager.activeTheme as Theme.Pack).scene)
            // the wallpaper's colours changed: reread with them, redrawn
            val now = ThemePackFiles.systemColors
            try {
                ThemePackFiles.systemColors = SystemColors(mapOf("primary" to Argb(0xff123456.toInt())), emptyMap())
                ThemeManager.onSystemPlatteChange(config)
                assertEquals(listOf("mine#dark"), told.map { it.name })
                assertEquals(now, ThemePackFiles.systemColors)
                assertNotEquals(true, scene === (ThemeManager.activeTheme as Theme.Pack).scene)
            } finally {
                ThemePackFiles.systemColors = now
            }
        } finally {
            ThemeManager.removeOnChangedListener(listener)
        }
    }

    // a two-version pack follows the system whatever the slot names
    @Test
    fun aPackOfBothVersionsFollowsTheSystemABuiltInPairKeepsItsSlots() {
        val (light, dark) = placed(sample())
        val darkSlot = ThemeManager.prefs.darkModeTheme.getValue()
        val day = Configuration(appContext.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
        val night = Configuration(day).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        try {
            ThemeManager.onSystemPlatteChange(day)
            ThemeManager.setNormalModeTheme(dark)
            assertEquals("mine#light", ThemeManager.activeTheme.name)
            ThemeManager.onSystemPlatteChange(night)
            assertEquals("mine#dark", ThemeManager.activeTheme.name)
            assertEquals(ThemeManager.packPair(light), light to dark)
            ThemeManager.prefs.darkModeTheme.setValue(light)
            ThemeManager.prefs.followSystemDayNightTheme.setValue(true)
            assertEquals("mine#dark", ThemeManager.activeTheme.name)
            ThemeManager.prefs.followSystemDayNightTheme.setValue(false)
            ThemeManager.setNormalModeTheme(ThemeManager.builtin("material#light"))
            assertEquals("material#light", ThemeManager.activeTheme.name)
            assertEquals(null, ThemeManager.packPair(ThemeManager.builtin("material#light")))
        } finally {
            ThemeManager.prefs.darkModeTheme.setValue(darkSlot)
            ThemeManager.onSystemPlatteChange(appContext.resources.configuration)
        }
    }

    @Test
    fun aKillBetweenAPlacesRenamesLosesNoPack() {
        placed(sample())
        val before = tree(File(packs, "mine"))
        // failing between them: the replaced pack put back
        ThemePackFiles.beforeInPlace = { throw java.io.IOException("killed") }
        assertTrue(opened(sample(xml(styles = """<style select="candidate" gap="7dp"/>"""))).commit().isFailure)
        ThemePackFiles.beforeInPlace = null
        assertEquals(before, tree(File(packs, "mine")))
        assertEquals(emptyList<String>(), File(packs, ".saving").list().orEmpty().toList())
        // killed between them: the aside one put back at the next listing, the half-written new one cleared
        File(packs, "mine").renameTo(File(packs, ".saving/old-1c2d/mine").also { it.parentFile!!.mkdirs() })
        File(packs, ".saving/new-3e4f").mkdirs()
        File(packs, ".saving/new-3e4f/theme.xml").writeText("half")
        ThemeFilesManager.forgetSwept()
        ThemeFilesManager.listThemes()
        assertEquals(listOf("mine#light", "mine#dark"), ThemeFilesManager.listPacks().map { it.name })
        assertEquals(before, tree(File(packs, "mine")))
        assertFalse(File(packs, ".saving").exists())
        // an aside one whose place another took: cleared, not put over it
        File(packs, ".saving/old-5a6b/mine").mkdirs()
        File(packs, ".saving/old-5a6b/mine/theme.xml").writeText("older")
        ThemeFilesManager.forgetSwept()
        ThemeFilesManager.listPacks()
        assertEquals(before, tree(File(packs, "mine")))
        assertFalse(File(packs, ".saving").exists())
    }

    @Test
    fun whatAKillLeftOfAPackBeingWrittenIsClearedAtTheFirstListing() {
        File(packs, ".saving/0c1d").apply { mkdirs() }.resolve("theme.xml").writeText("half")
        ThemeFilesManager.forgetSwept()
        ThemeFilesManager.listThemes()
        assertEquals(emptyList<Theme.Pack>(), ThemeFilesManager.listPacks())
        assertFalse(File(packs, ".saving").exists())
        File(packs, "broken").mkdirs()
        File(packs, "broken/theme.xml").writeText("<theme")
        File(packs, "other").mkdirs()
        File(packs, "other/theme.xml").writeText(xml(name = "else"))
        assertEquals(emptyList<Theme.Pack>(), ThemeFilesManager.listPacks())
        assertTrue(File(packs, "broken/theme.xml").exists())
    }
}
