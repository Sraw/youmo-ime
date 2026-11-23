/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.input.theme.Area
import org.fcitx.fcitx5.android.input.theme.NineSliceLayout
import org.fcitx.fcitx5.android.scene.look.BarBase
import org.fcitx.fcitx5.android.scene.look.BoardBase
import org.fcitx.fcitx5.android.scene.look.Box
import org.fcitx.fcitx5.android.scene.look.Crop
import org.fcitx.fcitx5.android.scene.look.Insets
import org.fcitx.fcitx5.android.scene.look.KeyFill
import org.fcitx.fcitx5.android.scene.look.KeyLabel
import org.fcitx.fcitx5.android.scene.look.KeyOverride
import org.fcitx.fcitx5.android.scene.look.KeyPressed
import org.fcitx.fcitx5.android.scene.look.KindStyle
import org.fcitx.fcitx5.android.scene.look.NineSlice
import org.fcitx.fcitx5.android.scene.look.Picture
import org.fcitx.fcitx5.android.scene.look.Text
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * contract: fixtures the page's node test reads: a 2.1 theme brought to 3.0 (migration.json, redone in JavaScript),
 * theme.json, and what this app and a pre-3.0 one export. Change them here and the page's test fails till it follows.
 * The app's nine-slice edges are held to the ones CanvasPainterTest pins for the page's NineSliceCut.
 */
@RunWith(RobolectricTestRunner::class)
// the real application: the export goes through appContext
@Config(application = FcitxApplication::class)
class ThemeMakerRulesTest {

    private val json = Json { prettyPrint = true }

    // a pre-3.0 app's 2.1 export (colours, a picture), and what it is read as
    private val v21 = """
        {"name":"old","isDark":true,"backgroundImage":{"croppedFilePath":"old-cropped.png","srcFilePath":"old-src",
        "brightness":55,"cropRect":{"bottom":1400,"left":10,"right":1090,"top":20},"cropRotation":90},
        "backgroundColor":-16777216,"barColor":1275068416,"keyboardColor":0,"keyBackgroundColor":452984831,
        "keyTextColor":-1,"candidateTextColor":-1,"candidateLabelColor":-1,"candidateCommentColor":-1711276033,
        "altKeyBackgroundColor":218103807,"altKeyTextColor":-1711276033,"accentKeyBackgroundColor":-10577930,
        "accentKeyTextColor":-1,"keyPressHighlightColor":520093696,"keyShadowColor":0,"popupBackgroundColor":-14540254,
        "popupTextColor":-1,"spaceBarColor":452984831,"dividerColor":536870911,"clipboardEntryColor":855638015,
        "genericActiveBackgroundColor":-10577930,"genericActiveForegroundColor":-1,"version":"2.1"}
    """.trimIndent().replace("\n", "")

    // what an older 2.1 left out: no brightness (70), no turn (0), crop edges missing (-1 each)
    private val v21Partial = v21
        .replace("\"brightness\":55,", "")
        .replace(",\"cropRotation\":90", "")
        .replace("\"cropRect\":{\"bottom\":1400,\"left\":10,\"right\":1090,\"top\":20}", "\"cropRect\":{\"left\":10,\"top\":20}")

    private fun migrated(from: String) =
        Json.parseToJsonElement(Json.encodeToString(CustomThemeSerializer, Json.decodeFromString(CustomThemeSerializer, from)))


    @Test
    fun migration() {
        val migration = buildJsonObject {
            check(v21Partial.length < v21.length - 40) { "the partial 2.1 sample left nothing out" }
            put("from", Json.parseToJsonElement(v21))
            put("to", migrated(v21))
            put("partialFrom", Json.parseToJsonElement(v21Partial))
            put("partialTo", migrated(v21Partial))
        }
        ThemeMakerTestdata.check("migration.json", json.encodeToString(JsonElement.serializer(), migration) + "\n")
    }

    // everything 3.0 has: portrait and landscape cuts, blurs, nine-slice space, pressed image and colour, role, board and
    // later-build keys, both fonts
    private val full = TestThemes.PixelDark.deriveCustomNoBackground("full").let { t ->
        t.copy(
            look = t.look.copy(
                picture = Picture(
                    "full-src.png",
                    Crop("full-portrait.png", Box(0, 300, 1440, 1800), 0),
                    Crop("full-landscape.png", Box(0, 900, 1440, 1500), 90),
                    70, 4,
                ),
                // letters over the picture: their colour at 30 %, as the page turns it
                palette = t.look.palette.run { copy(keyBackgroundColor = keyBackgroundColor and 0xFFFFFF or (0x4D shl 24)) },
                base = BoardBase.Picture,
                bar = BarBase.Picture(12, 60),
                keys = t.look.keys.copy(
                    radiusDp = 8, borderDp = 1, shadowDp = 0,
                    letter = KindStyle(KeyFill.Picture(10, 80), KeyPressed.Darken),
                    function = KindStyle(KeyFill.Color, KeyPressed.Color(-10577930)),
                    space = KindStyle(KeyFill.Image(NineSlice("full-space.png", Insets(12, 12, 12, 12), 3)), KeyPressed.Darken),
                    enter = KindStyle(KeyFill.Color, KeyPressed.Image(NineSlice("full-enter-down.png", Insets(8, 6, 8, 6), 2))),
                    overrides = mapOf(
                        "*/enter" to KeyOverride(KeyFill.Image(NineSlice("full-enter.png", Insets(0, 0, 0, 0), 3)), null, KeyLabel.Hide),
                        "qwerty/q" to KeyOverride(null, KeyPressed.Color(-1499549), KeyLabel.Show),
                        "k20/r1c1" to KeyOverride(KeyFill.Color, null, KeyLabel.Show),
                    ),
                ),
                text = Text("full-font.ttf", "full-candidates.ttf", 700, 400, 110),
            ),
        )
    }

    @Test
    fun aFullThemeAsAFile() {
        // as the app writes a theme file (version first), only indented
        ThemeMakerTestdata.check("theme.json", json.encodeToString(CustomThemeSerializer, full) + "\n")
    }

    // real images, for the page to decode
    private fun png(w: Int, h: Int, argb: Int): ByteArray {
        fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
            val crc = CRC32()
            out.write(java.nio.ByteBuffer.allocate(4).putInt(data.size).array())
            val t = type.encodeToByteArray()
            out.write(t); out.write(data)
            crc.update(t); crc.update(data)
            out.write(java.nio.ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
        }
        val raw = ByteArrayOutputStream()
        repeat(h) {
            raw.write(0)
            repeat(w) { raw.write(argb ushr 16 and 0xff); raw.write(argb ushr 8 and 0xff); raw.write(argb and 0xff); raw.write(argb ushr 24) }
        }
        val deflater = Deflater(9).apply { setInput(raw.toByteArray()); finish() }
        val idat = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) idat.write(buf, 0, deflater.deflate(buf))
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        chunk(out, "IHDR", java.nio.ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(6).put(0).put(0).put(0).array())
        chunk(out, "IDAT", idat.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    // fixed times: the same bytes every run in any zone (millis store as local date; 1980-01-01 00:00 is DOS's "before
    // 1980" and gets a zoned timestamp added)
    private fun zip(entries: List<Pair<String, ByteArray>>) = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name).apply { timeLocal = LocalDateTime.of(2026, 1, 1, 0, 0) })
                zip.write(data)
            }
        }
    }.toByteArray()

    private fun entries(zip: ByteArray) = ZipInputStream(zip.inputStream()).use { z ->
        generateSequence { z.nextEntry?.let { it.name to z.readBytes() } }.toList()
    }

    @Test
    fun whatTheAppExports() {
        // this app's: files named by name, each once, the json last
        val theme = full.copy(name = "from-app", look = full.look.copy(text = full.look.text.copy(keyFont = null, candidateFont = null))).let { t ->
            t.copy(look = t.look.mapFiles { it.replace("full-", "from-app-") })
        }
        val files = theme.look.files().map { it to png(if ("landscape" in it) 24 else 16, if ("landscape" in it) 12 else 24, 0x80336699.toInt()) }
        // through its own export, from the theme dir
        phoneStorage()
        val dir = ThemeFilesManager.themeDir.apply { mkdirs() }
        files.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
        val out = ByteArrayOutputStream()
        ThemeFilesManager.exportJson(theme.copy(look = theme.look.mapFiles { File(dir, it).path }), out).getOrThrow()
        // as it wrote them, only their times fixed
        val exported = zip(entries(out.toByteArray()))
        assertEquals(theme.look.files() + "from-app.json", entries(exported).map { it.first })
        ThemeMakerTestdata.check("from-app.theme", exported)
        // pre-3.0: its two images and its 2.1 json
        val old = zip(listOf("old-cropped.png" to png(16, 24, -0x1000000), "old-src" to png(32, 48, -0x1000000), "old.json" to v21.encodeToByteArray()))
        ThemeMakerTestdata.check("from-app-2.1.theme", old)
    }

    // CanvasPainterTest's cases, box for box: no inset on a side keeps the target's edge, the others whole pixels, ties to even
    @Test
    fun nineSliceEdgesAreThePages() {
        val cut = NineSliceLayout.of(40, 40, NineSlice("f", Insets(0, 8, 12, 0), 2), Area(3.5f, 7.25f, 96.5f, 60.75f), 2.6f)
        assertEquals(
            listOf(Area(3.5f, 7.25f, 81f, 18f), Area(81f, 7.25f, 96.5f, 18f), Area(3.5f, 18f, 81f, 60.75f), Area(81f, 18f, 96.5f, 60.75f)),
            cut.map { it.dst },
        )
        val tie = NineSliceLayout.of(20, 20, NineSlice("f", Insets(4, 4, 4, 4), 2), Area(0.5f, 1.5f, 20.5f, 10.5f), 1f)
        assertEquals(listOf(0.5f, 2f, 18f), tie.take(3).map { it.dst.left })
        assertEquals(listOf(1.5f, 4f, 8f), listOf(tie[0], tie[3], tie[6]).map { it.dst.top })
    }
}
