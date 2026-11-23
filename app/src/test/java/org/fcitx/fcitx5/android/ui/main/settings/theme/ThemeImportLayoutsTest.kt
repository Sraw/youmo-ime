/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.DialogInterface
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.layout.CustomLayouts
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePackFixture
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.scene.skin.SkinFill
import org.fcitx.fcitx5.android.scene.skin.SkinKey
import org.fcitx.fcitx5.android.scene.skin.SkinLook
import org.fcitx.fcitx5.android.scene.skin.SkinSource
import org.fcitx.fcitx5.android.scene.skin.SkinTheme
import org.fcitx.fcitx5.android.scene.skin.Variant
import org.fcitx.fcitx5.android.uat.SettingsHarness
import org.fcitx.fcitx5.android.uat.SkinUatSamples
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** 一起导入 in the import dialog: unticked for a skin's keyboards, ticked for a pack's, and taken as ticked. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemeImportLayoutsTest : ThemePackFixture() {

    private val st = SettingsHarness()

    @After
    fun close() = st.close()

    private fun skin(): ThemeFilesManager.Opened.Pack {
        val look = SkinLook(1080).apply {
            boardColor = 0xFFDDE4EE.toInt()
            keys["key"] = SkinKey(SkinFill.Color(0xFFFFFFFF.toInt()), SkinFill.Color(0xFFCCCCCC.toInt()), 0xFF222222.toInt(), null, icons = false)
            layouts += SkinUatSamples.qwertyBoard()
        }
        val source = object : SkinSource {
            override val name = "Sea Breeze"
            override val author = ""
            override val preview: ByteArray? = null
            override val variants = setOf(Variant.LIGHT)
            override fun look(variant: Variant) = look
        }
        val conversion = SkinTheme(source, { null }, "sea.bds", CustomLayouts.sources).write()
        return ThemeFilesManager.converted(conversion, ThemeManager.getAllThemes())
    }

    private fun themePack(): ThemeFilesManager.Opened.Pack {
        val qwerty = CustomLayouts.sources.layouts.getValue("qwerty").replace("name=\"26 键\"", "name=\"主题 26 键\"")
        return opened(zip("theme.xml" to xml("mint"), "layouts/qwerty.xml" to qwerty))
    }

    private fun shown(pack: ThemeFilesManager.Opened.Pack): AlertDialog {
        st.open(SettingsRoute.Theme)
        fun Fragment.all(): List<Fragment> = listOf(this) + childFragmentManager.fragments.flatMap { it.all() }
        val list = st.activity.supportFragmentManager.fragments.flatMap { it.all() }.filterIsInstance<ThemeListFragment>().single()
        list.confirmPack(pack)
        st.idle()
        return ShadowDialog.getLatestDialog() as AlertDialog
    }

    private fun box(dialog: AlertDialog): CheckBox = KeyboardHarness.all(dialog.window!!.decorView).filterIsInstance<CheckBox>().single()

    private fun take(dialog: AlertDialog, name: String) {
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        until("$name placed", step = st::idle) { ThemeManager.Packs.any { it.file == name } }
        st.idle()
    }

    private fun placedXml(name: String) = File(packs, "$name/theme.xml").readText()

    @Test
    fun aSkinsKeyboardsAreOfferedUntickedAndLeftOut() {
        ThemeFilesManager.versionName = "0.6.3"
        val pack = skin()
        assertTrue(pack.fromSkin)
        val dialog = shown(pack)
        assertFalse(box(dialog).isChecked)
        assertEquals(st.s(R.string.theme_pack_layouts_with), box(dialog).text.toString())
        take(dialog, "Sea Breeze")
        assertFalse(File(packs, "Sea Breeze/layouts").exists())
        assertTrue(placedXml("Sea Breeze").contains("""min-app="0.6.0""""))
    }

    @Test
    fun aSkinsKeyboardsTickedAreTaken() {
        ThemeFilesManager.versionName = "0.6.3"
        val dialog = shown(skin())
        box(dialog).isChecked = true
        take(dialog, "Sea Breeze")
        assertTrue(File(packs, "Sea Breeze/layouts/qwerty.xml").isFile)
        assertTrue(placedXml("Sea Breeze").contains("""min-app="0.6.3""""))
    }

    @Test
    fun aPacksLayoutsAreOfferedTicked() {
        ThemeFilesManager.versionName = "0.6.3"
        val pack = themePack()
        assertFalse(pack.fromSkin)
        val dialog = shown(pack)
        assertTrue(box(dialog).isChecked)
        // unticked, a pack keeps its layouts as 0.6.1 did: only not listed
        box(dialog).isChecked = false
        take(dialog, "mint")
        assertTrue(File(packs, "mint/layouts/qwerty.xml").isFile)
    }
}
