/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.layout.CustomLayouts
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.skin.Pixels
import org.fcitx.fcitx5.android.scene.skin.SkinAction
import org.fcitx.fcitx5.android.scene.skin.SkinBoard
import org.fcitx.fcitx5.android.scene.skin.SkinConversion
import org.fcitx.fcitx5.android.scene.skin.SkinFormat
import org.fcitx.fcitx5.android.scene.skin.SkinKeyBox
import org.fcitx.fcitx5.android.scene.skin.SkinLayoutWriter
import org.fcitx.fcitx5.android.scene.skin.SkinTheme
import org.fcitx.fcitx5.android.scene.skin.SkinImage
import org.fcitx.fcitx5.android.scene.skin.SkinNote
import org.fcitx.fcitx5.android.scene.skin.PixelsImage
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemePackDialogs
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Another input method's skin converted and taken as a 4.0 pack; skins made here (none third-party in the repo), images faked. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemeSkinImportTest : ThemePackFixture() {
    private val realCodec = ThemeFilesManager.skinCodec

    // an image is "IMG", width, height, ARGB ints; one written is "<w>x<h>", as PackProbe reads it
    private object FakeCodec : ThemeFilesManager.SkinCodec {
        override fun image(bytes: ByteArray): SkinImage? {
            if (bytes.size < 11 || String(bytes, 0, 3) != "IMG") return null
            val b = ByteBuffer.wrap(bytes, 3, bytes.size - 3)
            val w = b.int
            val h = b.int
            if (bytes.size < 11 + 4 * w * h) return null
            return PixelsImage(Pixels(w, h, IntArray(w * h) { b.int }))
        }

        override fun legacyText(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

        override fun png(pixels: Pixels) = "${pixels.width}x${pixels.height}".toByteArray()
    }

    @Before
    fun fakeCodec() {
        ThemeFilesManager.skinCodec = FakeCodec
    }

    @After
    fun realCodec() {
        ThemeFilesManager.skinCodec = realCodec
    }

    private fun img(p: Pixels): ByteArray = ByteBuffer.allocate(11 + 4 * p.argb.size).apply {
        put("IMG".toByteArray())
        putInt(p.width)
        putInt(p.height)
        p.argb.forEach { putInt(it) }
    }.array()

    private fun wav(ms: Int): ByteArray {
        val rate = 22050
        val n = rate * ms / 1000
        return ByteBuffer.allocate(44 + 2 * n).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + 2 * n); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(2 * n)
            repeat(n) { putShort(((it % 40 - 20) * 300).toShort()) }
        }.array()
    }

    private fun skinZip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { z ->
            for ((name, b) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(b)
            }
        }
    }.toByteArray()

    // a key face (6 px transparent margin), a panel picture, colours, a click
    private fun baidu(name: String = "Sea Breeze"): ByteArray {
        val face = Pixels(162, 180).also { it.paste(Pixels.filled(150, 168, 0xFFFFFFFF.toInt()), 6, 6) }
        val letters = "qwertyuiopasdfghjklzxcvbnm".mapIndexed { i, c ->
            "[KEY$i]\nVIEW_RECT=${(i % 10) * 108},${i / 10 * 150},108,150\nBACK_STYLE=1\nFORE_STYLE=3\nCENTER=$c\n"
        }.joinToString("")
        return skinZip(
            "Info.txt" to "Name=$name\nAuthor=Ann\n".toByteArray(),
            "demo.png" to img(Pixels.filled(300, 200, 0xFF336699.toInt())),
            "res/keys.png" to img(face),
            "res/keys.til" to "[IMG1]\nSOURCE_RECT=0,0,162,180\nINNER_RECT=30,30,102,120\n".toByteArray(),
            // small and stretched: a flat image packs past the zip-bomb ratio
            "res/panel.png" to img(Pixels.filled(108, 60, 0xFFDDE4EE.toInt())),
            "res/panel.til" to "[IMG1]\nSOURCE_RECT=0,0,108,60\n".toByteArray(),
            "res/click.wav" to wav(60),
            "port/res/default.css" to "[STYLE1]\nNM_IMG=keys,1\nHL_COLOR=cccccc\n[STYLE2]\nNM_COLOR=aabbcc\n[STYLE3]\nNM_COLOR=222222\n[STYLE4]\nNM_IMG=panel,1\n[STYLE7]\nPRESS_SOUND_PATH=click.wav\n".toByteArray(),
            "port/gen.ini" to "[PANEL]\nSIZE=1080,600\nBACK_STYLE=4\nSOUND_STYLE=7\n[CAND]\nVIEW_RECT=0,0,1080,126\n".toByteArray(),
            "port/cand1.cnd" to "[CAND]\nFORE_STYLE=3\n".toByteArray(),
            "port/py_26.ini" to (letters + "[KEY90]\nBACK_STYLE=2\nFORE_STYLE=3\nCENTER=F38\n").toByteArray(),
        )
    }

    private fun skin(bytes: ByteArray, name: String = "sea.bds") = ThemeFilesManager.openSkin(bytes.inputStream(), name, ThemeManager.getAllThemes())

    private fun skinRefusal(bytes: ByteArray, name: String = "sea.bds") = skin(bytes, name).exceptionOrNull()!!.message

    @Test
    fun aBaiduSkinIsShownAsAPackThenTaken() {
        val pack = skin(baidu()).getOrThrow()
        val staged = pack.staged
        assertEquals("Sea Breeze", staged.name)
        assertEquals("Ann", staged.theme.meta!!.author)
        assertEquals(mapOf(SoundKey.LETTER to "sound-letter.wav"), staged.theme.sounds)
        assertTrue(SkinNote.LAYOUT in pack.notes.keys)
        assertTrue(pack.fromSkin)
        // the key face cut to its body
        assertEquals("150x168", File(staged.from, "l-key-n.png").readText())
        assertTrue(File(staged.from, "board.png").isFile)
        assertTrue(File(staged.from, "preview.png").isFile)
        // what did not carry over, told with what it is
        val text = ThemePackDialogs.confirmText(appContext, pack)
        assertTrue(text, text.contains(appContext.getString(R.string.theme_pack_skin_notes)))
        assertTrue(text, text.contains(appContext.getString(R.string.theme_pack_skin_note_layout)))
        pack.commit().getOrThrow()
        assertTrue(File(packs, "Sea Breeze/theme.xml").isFile)
        assertFalse(unpacking.listFiles().orEmpty().any { it.isDirectory && it.list().orEmpty().isNotEmpty() })
    }

    @Test
    fun aSkinWithoutANameIsNamedAsItsFile() {
        assertEquals("my skin", skin(baidu(name = ""), "my skin.bdi").getOrThrow().staged.name)
    }

    @Test
    fun whatIsNoSkinIsRefusedWithWhy() {
        fun s(id: Int, vararg args: String) = appContext.getString(id, *args)
        assertEquals(s(R.string.exception_skin_not_zip), skinRefusal("hello".toByteArray()))
        assertEquals(s(R.string.exception_skin_sogou_encrypted), skinRefusal("Skin\u0003\u0000xxxx".toByteArray(), "pc.ssf"))
        assertEquals(s(R.string.exception_skin_unsafe_name, "../evil.ini"), skinRefusal(skinZip("../evil.ini" to byteArrayOf(1))))
        assertEquals(s(R.string.exception_skin_sogou_pc), skinRefusal(skinZip("skin.ini" to "[x]".toByteArray()), "pc.ssf"))
        assertEquals(s(R.string.exception_skin_unknown), skinRefusal(skinZip("a.txt" to "x".toByteArray())))
        assertEquals(s(R.string.exception_skin_no_keyboard), skinRefusal(skinZip("port/gen.ini" to "[PANEL]".toByteArray())))
        // nothing left unpacked by a refusal
        assertFalse(unpacking.listFiles().orEmpty().any { it.list().orEmpty().isNotEmpty() })
    }

    @Test
    fun aSkinThatRunsOutOfMemoryOrStackIsRefused() {
        fun failing(error: Error) = object : ThemeFilesManager.SkinCodec by FakeCodec {
            override fun image(bytes: ByteArray): SkinImage = throw error
        }
        ThemeFilesManager.skinCodec = failing(OutOfMemoryError())
        assertEquals(appContext.getString(R.string.exception_skin_unpacked_too_big), skinRefusal(baidu()))
        ThemeFilesManager.skinCodec = failing(StackOverflowError())
        assertEquals(appContext.getString(R.string.exception_skin_not_zip), skinRefusal(baidu()))
        assertFalse(unpacking.listFiles().orEmpty().any { it.list().orEmpty().isNotEmpty() })
    }

    @Test
    fun aTrimeThemeAndAFlorisBoardOneInAZip() {
        val yaml = "name: Ink\nauthor: Cy\npreset_color_schemes:\n  default: {back_color: 0x223344, key_back_color: 0xFFFFFF, key_text_color: 0x000000}\n"
        val trime = skin(yaml.toByteArray(), "ink.trime.yaml").getOrThrow()
        assertEquals("Ink", trime.staged.name)
        assertEquals("Cy", trime.staged.theme.meta!!.author)
        val flex = skinZip(
            "extension.json" to """{"themes":[{"id":"moss","label":"Moss"}]}""".toByteArray(),
            "stylesheets/moss.json" to """{"window":{"background":"#e0f0e0"},"key":{"background":"#ffffff"}}""".toByteArray(),
        )
        // a download: a folder holding the .flex
        val pack = skin(skinZip("moss-main/README.md" to byteArrayOf(1), "moss-main/moss.flex" to flex), "moss-main.zip").getOrThrow()
        assertEquals("Moss", pack.staged.name)
        val text = ThemePackDialogs.confirmText(appContext, pack)
        assertTrue(text, text.contains(appContext.getString(R.string.theme_pack_skin_note_panels)))
    }

    @Test
    fun aZipOfNoSkinIsAThemeToRename() {
        val zip = skinZip("Ocean.json" to "{}".toByteArray())
        assertEquals(appContext.getString(R.string.exception_theme_filename, "Ocean.zip"), skinRefusal(zip, "Ocean.zip"))
        // under a skin's own extension: no skin
        assertEquals(appContext.getString(R.string.exception_skin_unknown), skinRefusal(zip, "Ocean.flex"))
    }

    // upstream fcitx5-android's export: a 2.1 `<name>.json` with the picture's two files, a .zip renamed to .theme
    @Test
    fun anUpstreamThemeIsImported() {
        val names = listOf(
            "backgroundColor", "barColor", "keyboardColor", "keyBackgroundColor", "keyTextColor", "candidateTextColor",
            "candidateLabelColor", "candidateCommentColor", "altKeyBackgroundColor", "altKeyTextColor", "accentKeyBackgroundColor",
            "accentKeyTextColor", "keyPressHighlightColor", "keyShadowColor", "popupBackgroundColor", "popupTextColor",
            "spaceBarColor", "dividerColor", "clipboardEntryColor", "genericActiveBackgroundColor", "genericActiveForegroundColor",
        )
        val colours = names.mapIndexed { i, n -> "\"$n\":${0xFF102030.toInt() + i}" }.joinToString(",")
        val json = """{"name":"Ocean","isDark":true,"backgroundImage":{"croppedFilePath":"ocean-cropped.png",""" +
            """"srcFilePath":"ocean-src.png","brightness":60,"cropRect":{"bottom":1600,"left":0,"right":1080,"top":0},""" +
            """"cropRotation":0},$colours,"version":"2.1"}"""
        val zip = skinZip(
            "ocean-cropped.png" to "1080x1600".toByteArray(),
            "ocean-src.png" to "1080x2400".toByteArray(),
            "Ocean.json" to json.toByteArray(),
        )
        val opened = open(zip).getOrThrow() as ThemeFilesManager.Opened.Json
        val (created, theme, migrated) = opened.imported
        assertTrue(created)
        assertTrue(migrated)
        assertEquals("Ocean", theme.name)
        assertTrue(theme.isDark)
        // 2.1's own candidate colours kept (not the 2.0 → 2.1 step's, from the key text)
        assertEquals(0xFF102030.toInt() + 5, theme.candidateTextColor)
        assertEquals(0xFF102030.toInt() + 7, theme.candidateCommentColor)
        assertEquals(0xFF102030.toInt() + 13, theme.keyShadowColor)
        assertEquals(60, theme.look.picture!!.brightness)
    }

    // a 26 keys of the app's rows, at the skin's sizes
    private fun qwertyBoard(): SkinBoard {
        fun row(y: Double, x0: Double, keys: List<Pair<SkinAction, Double>>): List<SkinKeyBox> {
            var x = x0
            return keys.map { (a, w) -> SkinKeyBox(x, y, w, 150.0, a).also { x += w } }
        }
        fun letters(s: String) = s.map { SkinAction.Letter(it) to 108.0 }
        fun roles(vararg r: Pair<String, Double>) = r.map { SkinAction.Role(it.first) to it.second }
        return SkinBoard(
            "qwerty",
            listOf(
                row(0.0, 0.0, letters("qwertyuiop")),
                row(150.0, 54.0, letters("asdfghjkl")),
                row(300.0, 0.0, roles("shift" to 162.0) + letters("zxcvbnm") + roles("backspace" to 162.0)),
                row(450.0, 0.0, roles("symbols" to 120.0, "numbers" to 120.0, "comma" to 100.0, "space" to 480.0, "period" to 100.0, "enter" to 160.0)),
            ),
            1080.0,
        )
    }

    // the Baidu skin with a layout of its own, as a reader that finds one converts it; an id not of the four is not written
    private fun withLayout(notes: Map<SkinNote, List<String>>? = null): ThemeFilesManager.Opened.Pack {
        val c = SkinImport.convert(baidu().inputStream(), "sea.bds", FakeCodec)
        val written = SkinLayoutWriter.write(qwertyBoard(), CustomLayouts.sources)!!
        val withLayouts = c.themeXml.replace("""min-app="0.6.0"""", """min-app="${written.minApp}"""")
        val conversion = SkinConversion(c.name, c.themeXml, c.files, notes ?: c.notes, mapOf("qwerty" to written.text, "symbols" to written.text), withLayouts)
        return ThemeFilesManager.converted(conversion, ThemeManager.getAllThemes())
    }

    @Test
    fun aSkinsLayoutsAreLeftOutUnlessTaken() {
        ThemeFilesManager.versionName = "0.6.3"
        val pack = withLayout()
        assertTrue(pack.fromSkin)
        assertEquals(listOf("qwerty.xml"), pack.staged.layouts)
        val text = ThemePackDialogs.confirmText(appContext, pack)
        assertTrue(text, text.contains(appContext.resources.getQuantityString(R.plurals.theme_pack_skin_layouts, 1, 1, "Sea Breeze · 26 键")))
        pack.commit(withLayouts = false).getOrThrow()
        assertFalse(File(packs, "Sea Breeze/layouts").exists())
        assertTrue(File(packs, "Sea Breeze/theme.xml").readText().contains("""min-app="0.6.0""""))
        withLayout().commit(withLayouts = true).getOrThrow()
        assertTrue(File(packs, "Sea Breeze/layouts/qwerty.xml").isFile)
        assertTrue(File(packs, "Sea Breeze/theme.xml").readText().contains("""min-app="0.6.3""""))
        assertFalse(unpacking.listFiles().orEmpty().any { it.isDirectory && it.list().orEmpty().isNotEmpty() })
    }

    @Test
    fun notesAreToldWithWhatTheyCount() {
        ThemeFilesManager.versionName = "0.6.3"
        val notes = mapOf(SkinNote.LAYOUT_SOME to listOf("nine"), SkinNote.TOOLBAR_ICONS to listOf("3"), SkinNote.INCLUDES to emptyList(), SkinNote.NO_DARK to emptyList())
        val text = ThemePackDialogs.confirmText(appContext, withLayout(notes))
        assertTrue(text, text.contains(appContext.getString(R.string.theme_pack_skin_note_layout_some, "九键")))
        assertTrue(text, text.contains(appContext.getString(R.string.theme_pack_skin_note_toolbar_icons_count, 3)))
        assertTrue(text, text.contains(appContext.getString(R.string.theme_pack_skin_note_includes)))
        assertTrue(text, text.contains(appContext.getString(R.string.theme_pack_skin_note_no_dark)))
    }

    @Test
    fun everyNoteIsSaid() {
        val withArguments = setOf(SkinNote.LAYOUT_SOME, SkinNote.TOOLBAR_ICONS, SkinNote.INCLUDES)
        assertEquals(SkinNote.entries.toSet() - withArguments, ThemePackDialogs.SKIN_NOTES.keys)
    }

    @Test
    fun everyUnsupportedFormatIsSaid() {
        assertEquals(SkinFormat.ALL.toSet(), SkinImport.FORMATS.keys)
        assertEquals(SkinFormat.ALL.size, SkinImport.FORMATS.values.map { appContext.getString(it) }.distinct().size)
    }

    @Test
    fun theConvertersShareOfAPackIsThePacks() {
        assertEquals(ThemeAssetLimits.MAX_ENTRIES.toLong(), SkinTheme.MAX_PACK_ENTRIES.toLong())
        assertEquals(ThemeAssetLimits.MAX_TOTAL_BYTES, SkinTheme.MAX_PACK_BYTES)
        assertEquals(ThemeAssetLimits.MAX_THEME_XML_BYTES, SkinTheme.MAX_THEME_XML_BYTES)
    }

    @Test
    fun aSkinNamedAsABuiltInThemeIsRefused() {
        assertEquals(appContext.getString(R.string.exception_theme_name_clash), skinRefusal(baidu(name = "nord")))
    }
}
