/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

import org.fcitx.fcitx5.android.scene.skin.SkinError.Reason
import org.fcitx.fcitx5.android.scene.skin.SkinFixtures.text
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Each format through [SkinConverter.convert] as the app calls it: [SkinConverter.READERS], containers, folders, names. */
class SkinWiringTest {
    private val platform = SkinFixtures.Platform()

    private fun convert(files: Map<String, ByteArray>, fileName: String = "skin.zip", p: SkinPlatform = platform) =
        SkinConverter.convert(files, p, fileName)

    private fun reason(files: Map<String, ByteArray>, fileName: String = "skin.zip", p: SkinPlatform = platform) =
        assertFailsWith<SkinError> { convert(files, fileName, p) }.reason

    private fun unsupported(files: Map<String, ByteArray>, fileName: String = "skin.zip") =
        assertFailsWith<SkinError> { convert(files, fileName) }.let { it.reason to it.detail }

    private fun florisFiles() = mapOf(
        "extension.json" to text("""{"meta":{"id":"moss","title":"Moss"},"themes":[{"id":"day","label":"Moss Day"}]}"""),
        "stylesheets/day.json" to text("""{"window":{"background":"#e0f0e0"},"key":{"background":"#ffffff","foreground":"#203020"}}"""),
    )

    private fun sogouWithBoards() = SkinFixtures.sogou(periods = 1).also { it["1080/port/26.ini"] = text(SogouFixtures.QWERTY) }

    @Test
    fun eachFormatIsReadByItsReader() {
        assertEquals("Sea Breeze", convert(SkinFixtures.baidu(), "a.bds").name)
        assertEquals("Plum", convert(BaiduFixtures.bda(), "a.bda").name)
        assertEquals("Warm Orange", convert(SkinFixtures.sogou(), "a.ssf").name)
        assertEquals("Moss Day", convert(florisFiles(), "a.flex").name)
        assertEquals("Base", convert(mapOf("base.trime.yaml" to text(TrimeFixtures.base)), "base.trime.yaml").name)
        val xunfei = convert(XunfeiFixtures.store(), "a.it")
        assertEquals("春日", xunfei.name)
        assertTrue(SkinNote.NO_DARK in xunfei.notes)
    }

    @Test
    fun aSkinWithoutANameIsNamedAsItsFileWithoutItsExtension() {
        assertEquals("my skin", convert(SkinFixtures.baidu(name = ""), "my skin.bdi").name)
        assertEquals("a.b", convert(SkinFixtures.baidu(name = ""), "dir/a.b.bds").name)
    }

    @Test
    fun theKeysOfASkinAreTheAppsLayoutsWhereThePlatformHasThem() {
        val c = convert(sogouWithBoards(), "a.ssf")
        assertEquals(setOf("qwerty"), c.layouts.keys)
        assertFalse(SkinNote.LAYOUT in c.notes)
        SkinFixtures.read(c, c.themeXmlWithLayouts)
        assertTrue("qwerty" in convert(mapOf("base.trime.yaml" to text(TrimeFixtures.base)), "base.trime.yaml").layouts)
        // a platform without the app's layouts: none written
        val none = object : SkinPlatform by platform {
            override fun layoutSources() = null
        }
        val bare = convert(sogouWithBoards(), "a.ssf", none)
        assertTrue(bare.layouts.isEmpty())
    }

    @Test
    fun aSogouContainerAloneOrInAZipIsOpened() {
        val sealed = SogouFixtures.container(SkinFixtures.sogou())
        val p = SogouFixtures.Sealed()
        assertEquals("Warm Orange", convert(mapOf("a.ssf" to sealed), "a.ssf", p).name)
        assertEquals(SkinLimits.MAX_TOTAL_BYTES.toInt() - sealed.size, p.asked)
        // one .ssf among other files (and macOS's): what they and it take is not left for what it holds
        val readme = ByteArray(1000)
        val zipped = mapOf("readme.txt" to readme, "x/a.SSF" to sealed, "__MACOSX/x/._a.SSF" to ByteArray(4000))
        assertEquals("Warm Orange", convert(zipped, "a.zip", p).name)
        assertEquals(SkinLimits.MAX_TOTAL_BYTES.toInt() - readme.size - sealed.size, p.asked)
        // no cipher on the platform; two of them, neither opened
        assertEquals(Reason.SOGOU_ENCRYPTED, reason(mapOf("a.ssf" to sealed), "a.ssf"))
        assertEquals(Reason.UNKNOWN, reason(mapOf("a.ssf" to sealed, "b.ssf" to sealed), "a.zip", p))
        // a computer skin inside is said so
        val pc = SogouFixtures.container(mapOf("skin.ini" to text("[General]\nname=pc"), "bg.png" to byteArrayOf(1)))
        assertEquals(Reason.SOGOU_PC, reason(mapOf("pc.ssf" to pc), "pc.ssf", p))
    }

    @Test
    fun aContainerShortOfWhatIsLeftIsTooBigNotUnreadable() {
        // the other files one array many times over: past what may be unpacked in all, without allocating it
        val mb8 = ByteArray(8 shl 20)
        val zipped = (0 until (SkinLimits.MAX_TOTAL_BYTES / mb8.size).toInt()).associate { "pad$it" to mb8 } +
            ("a.ssf" to SogouFixtures.container(SkinFixtures.sogou()))
        assertEquals(Reason.UNPACKED_TOO_BIG, reason(zipped, "a.zip", SogouFixtures.Sealed()))
    }

    @Test
    fun aShopsZipKeepsItsTopFolderAndOpeningOnceIsTheSameAsConverting() {
        val shop = XunfeiFixtures.store()
        val opened = SkinConverter.open(shop, platform)
        assertTrue(opened.keys.all { it.startsWith(XunfeiFixtures.TOP) })
        assertTrue(XunfeiSkin.accepts(opened.keys))
        for ((files, name) in listOf(shop to "a.it", SkinFixtures.sogou() to "a.ssf", BaiduFixtures.bda() to "a.bda")) {
            val direct = convert(files, name)
            val twice = SkinConverter.convertOpened(SkinConverter.open(files, platform), platform, name)
            assertEquals(direct.themeXml, twice.themeXml)
            assertEquals(direct.notes, twice.notes)
            assertEquals(direct.layouts, twice.layouts)
            assertEquals(direct.files.map { it.name }, twice.files.map { it.name })
        }
        // a download's one folder is taken off where no reader takes the names as they are, and only once
        val wrapped = florisFiles().mapKeys { "repo-main/${it.key}" }
        assertEquals(florisFiles().keys, SkinConverter.open(wrapped, platform).keys)
        val twiceWrapped = florisFiles().mapKeys { "a/b/${it.key}" }
        assertEquals(Reason.UNKNOWN, reason(twiceWrapped))
    }

    @Test
    fun formatsToldApartAreSaidSoByName() {
        val proto = byteArrayOf(1)
        assertEquals(Reason.UNSUPPORTED to SkinFormat.SHOUXIN, unsupported(mapOf("skin.json" to text("{}"), "light/res/skin.proto" to proto)))
        assertEquals(Reason.UNSUPPORTED to SkinFormat.SHOUXIN, unsupported(mapOf("skin.json" to text("{}")), "a.sxi"))
        // a .sxi zipped by Finder: the one file in it
        assertEquals(Reason.UNSUPPORTED to SkinFormat.SHOUXIN, unsupported(mapOf("a.sxi" to proto, "__MACOSX/._a.sxi" to proto)))
        assertEquals(
            Reason.UNSUPPORTED to SkinFormat.GBOARD,
            unsupported(mapOf("metadata.json" to text("{}"), "style_sheet_md2.css" to text("@def color_label #fff;"))),
        )
        // a pack of Gboard themes, by its name or what it holds; one theme zipped in a zip is not one
        assertEquals(SkinFormat.GBOARD, SkinConverter.unsupported("x.pack", emptySet()))
        assertEquals(Reason.UNSUPPORTED to SkinFormat.GBOARD, unsupported(mapOf("Ink.zip" to proto, "Ink" to proto, "Moss.zip" to proto, "Moss" to proto)))
        assertEquals(Reason.UNKNOWN, reason(mapOf("Ink.zip" to proto)))
        // a 仓 skin's YAML would be taken for a Trime theme's
        val cang = mapOf("t/config.yaml" to text(TrimeFixtures.base), "t/jsonnet/main.jsonnet" to text("{}"))
        assertEquals(Reason.UNSUPPORTED to SkinFormat.CANG, unsupported(cang))
        assertEquals(Reason.UNSUPPORTED to SkinFormat.CANG, unsupported(mapOf("base.trime.yaml" to text(TrimeFixtures.base)), "a.CSKIN"))
        // a Trime theme's repository with sources in jsonnet/, a skin with a picture zipped beside it: neither of those
        assertEquals("Base", convert(mapOf("base.trime.yaml" to text(TrimeFixtures.base), "jsonnet/main.jsonnet" to text("{}"))).name)
        assertEquals("Sea Breeze", convert(SkinFixtures.baidu().also { it["res/extra"] = proto; it["res/extra.zip"] = proto }, "a.bds").name)
        assertEquals("Sea Breeze", convert(SkinFixtures.baidu().also { it["x"] = proto; it["x.zip"] = proto }, "a.bds").name)
        assertEquals(Reason.UNSUPPORTED to SkinFormat.APK, unsupported(mapOf("AndroidManifest.xml" to proto, "resources.arsc" to proto)))
        assertEquals(Reason.UNSUPPORTED to SkinFormat.APK, unsupported(mapOf("classes.dex" to proto), "theme.apk"))
        // a Sogou own-made skin: settings by screen width and no keys' places, said so rather than no keyboard
        val diy = mapOf("phoneTheme.ini" to text("[General]\n"), "1080/skin.ini" to text("[General]\n"), "1080/0.png" to proto)
        assertEquals(Reason.UNSUPPORTED to SkinFormat.SOGOU_DIY, unsupported(diy))
        assertEquals(Reason.NO_KEYBOARD, reason(diy - "1080/skin.ini"))
        // by its name alone, before it is read
        assertEquals(SkinFormat.APK, SkinConverter.unsupported("x.apk", emptySet()))
        assertEquals(null, SkinConverter.unsupported("x.zip", emptySet()))
        // half of a sign is none: still no skin
        assertEquals(Reason.UNKNOWN, reason(mapOf("skin.json" to text("{}"))))
        assertEquals(Reason.UNKNOWN, reason(mapOf("metadata.json" to text("{}"))))
        assertEquals(Reason.UNKNOWN, reason(mapOf("AndroidManifest.xml" to proto)))
    }

    @Test
    fun theReadersInOrder() {
        val readers = SkinConverter.READERS.map { (accepts, _) -> accepts }
        val skins = listOf(SkinFixtures.sogou(), SkinFixtures.baidu(), BaiduFixtures.bda(), florisFiles(), mapOf("a.trime.yaml" to text(TrimeFixtures.base)), XunfeiFixtures.store())
        // each skin taken by its own reader only
        for ((i, s) in skins.withIndex()) assertEquals(listOf(i), readers.indices.filter { readers[it](s.keys) }, "skin $i")
        assertContains(convert(BaiduFixtures.bda(), "a.bda").themeXml, "<theme")
    }
}
