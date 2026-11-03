/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.input.theme.Area
import org.fcitx.fcitx5.android.input.theme.Box
import org.fcitx.fcitx5.android.input.theme.BoxBlur
import org.fcitx.fcitx5.android.input.theme.Crop
import org.fcitx.fcitx5.android.input.theme.Gap
import org.fcitx.fcitx5.android.input.theme.Insets
import org.fcitx.fcitx5.android.input.theme.KeyFaceGeometry
import org.fcitx.fcitx5.android.input.theme.KeyFill
import org.fcitx.fcitx5.android.input.theme.KeyImageBudget
import org.fcitx.fcitx5.android.input.theme.KeyKind
import org.fcitx.fcitx5.android.input.theme.KeyLabel
import org.fcitx.fcitx5.android.input.theme.KeyOverride
import org.fcitx.fcitx5.android.input.theme.KeyPressed
import org.fcitx.fcitx5.android.input.theme.KeyStyleResolver
import org.fcitx.fcitx5.android.input.theme.Keys
import org.fcitx.fcitx5.android.input.theme.KindStyle
import org.fcitx.fcitx5.android.input.theme.LookDefaults
import org.fcitx.fcitx5.android.input.theme.NineSlice
import org.fcitx.fcitx5.android.input.theme.NineSliceLayout
import org.fcitx.fcitx5.android.input.theme.Picture
import org.fcitx.fcitx5.android.input.theme.PictureWindow
import org.fcitx.fcitx5.android.input.theme.PressEffect
import org.fcitx.fcitx5.android.input.theme.Text
import org.fcitx.fcitx5.android.input.theme.BarBase
import org.fcitx.fcitx5.android.input.theme.BoardBase
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
 * The rules the theme maker draws its preview by, computed by ime-core's code for a fixed set of
 * cases (web/theme-maker/test/testdata/rules.json), which the page's own test computes again in
 * JavaScript; and the files it reads (theme.json, a 3.0 theme; from-app.zip and from-app-2.1.zip,
 * what this app and one before 3.0 export). Changed here, the page's test fails till it follows.
 */
@RunWith(RobolectricTestRunner::class)
// the real application: the export goes through appContext (where the themes are)
@Config(application = FcitxApplication::class)
class ThemeMakerRulesTest {

    private val json = Json { prettyPrint = true }

    private fun area(a: Area?): JsonElement =
        a?.let { buildJsonArray { listOf(it.left, it.top, it.right, it.bottom).forEach { v -> add(JsonPrimitive(v)) } } } ?: JsonNull

    private fun keysJson(keys: Keys) = json.encodeToJsonElement(KeysJson.of(keys))

    private fun JsonObjectBuilder.ints(name: String, values: IntArray) =
        put(name, JsonArray(values.map { JsonPrimitive(it) }))

    private val looks = mapOf(
        "default" to LookDefaults.KEYS,
        "flat" to LookDefaults.KEYS.copy(blocks = false),
        "bordered" to LookDefaults.KEYS.copy(radiusDp = 12, borderDp = 2, shadowDp = 0, gap = Gap(5, 9, 2, 3)),
        "tight" to LookDefaults.KEYS.copy(radiusDp = 24, shadowDp = 3, gap = Gap(0, 0, 0, 0)),
    )

    private fun geometry() = buildJsonArray {
        val cells = listOf(100f to 160f, 400f to 160f, 400f to 40f, 400f to 60f, 150f to 160f, 50f to 60f, 37.5f to 121.25f)
        for ((lookName, keys) in looks) for ((w, h) in cells) for (kind in KeyKind.entries) {
            for (landscape in listOf(false, true)) for (density in listOf(2f, 2.75f)) for (margin in listOf(true, false)) {
                if (!margin && (landscape || density != 2f)) continue
                val face = KeyFaceGeometry.of(w, h, kind, keys, landscape, density, margin)
                add(buildJsonObject {
                    put("keys", lookName)
                    put("width", w); put("height", h); put("kind", kind.name.lowercase())
                    put("landscape", landscape); put("density", density); put("margin", margin)
                    put("face", area(face.face)); put("shadow", area(face.shadow)); put("pressed", area(face.pressed))
                    put("border", face.border); put("radius", face.radius); put("oval", face.oval)
                })
            }
        }
    }

    private fun nineSlice() = buildJsonArray {
        val cases = listOf(
            Triple(90 to 90, NineSlice("f", Insets(30, 30, 30, 30), 3), Area(0f, 0f, 200f, 100f) to 2f),
            Triple(100 to 10, NineSlice("f", Insets(20, 0, 60, 0), 1), Area(0f, 0f, 40f, 10f) to 1f),
            Triple(64 to 32, NineSlice("f", Insets(0, 0, 0, 0), 2), Area(5f, 5f, 105f, 55f) to 3f),
            Triple(48 to 48, NineSlice("f", Insets(12, 8, 12, 16), 2), Area(3.5f, 7f, 96.5f, 153f) to 2.75f),
            Triple(30 to 30, NineSlice("f", Insets(10, 10, 10, 10), 1), Area(0f, 0f, 15f, 12f) to 2f),
        )
        for ((src, slice, target) in cases) {
            add(buildJsonObject {
                put("srcWidth", src.first); put("srcHeight", src.second)
                put("insets", JsonArray(listOf(slice.insets.left, slice.insets.top, slice.insets.right, slice.insets.bottom).map { JsonPrimitive(it) }))
                put("pxPerDp", slice.pxPerDp); put("target", area(target.first)); put("density", target.second)
                put("pieces", JsonArray(NineSliceLayout.of(src.first, src.second, slice, target.first, target.second).map {
                    buildJsonObject { put("src", area(it.src)); put("dst", area(it.dst)) }
                }))
            })
        }
    }

    private fun pictures() = buildJsonArray {
        // a picture's width and height, the board's
        val cases = listOf(
            (2000 to 1000) to (1000 to 1000), (500 to 2000) to (1000 to 1000), (1080 to 720) to (1080 to 720),
            (4032 to 3024) to (1080 to 830), (720 to 1280) to (2340 to 600),
        )
        for ((pic, board) in cases) {
            val (pw, ph) = pic
            val (bw, bh) = board
            val p = PictureWindow.centerCrop(pw, ph, bw.toFloat(), bh.toFloat())
            val key = Area(bw * 0.1f, bh * 0.25f, bw * 0.2f, bh * 0.5f)
            add(buildJsonObject {
                put("picWidth", pw); put("picHeight", ph); put("boardWidth", bw); put("boardHeight", bh)
                put("scale", p.scale); put("dx", p.dx); put("dy", p.dy)
                put("key", area(key)); put("under", area(PictureWindow.under(p, key)))
            })
        }
    }

    private fun blur() = buildJsonArray {
        // a fixed pseudo-random picture: the same numbers in the page's test
        var seed = 12345L
        fun next(): Int {
            seed = (seed * 1103515245 + 12345) and 0x7fffffff
            return (seed ushr 8).toInt()
        }
        for ((w, h, radius) in listOf(Triple(5, 1, 1), Triple(8, 6, 2), Triple(13, 7, 3), Triple(4, 4, 0))) {
            val px = IntArray(w * h) { next() or (0xff shl 24) }
            add(buildJsonObject {
                put("width", w); put("height", h); put("radius", radius)
                ints("pixels", px); ints("blurred", BoxBlur.blur(px, w, h, radius))
            })
        }
        for ((w, h, factor) in listOf(Triple(5, 3, 2), Triple(9, 9, 4), Triple(2, 1, 4))) {
            val px = IntArray(w * h) { next() or (0xff shl 24) }
            add(buildJsonObject {
                put("width", w); put("height", h); put("shrink", factor)
                ints("pixels", px); ints("shrunk", BoxBlur.shrink(px, w, h, factor))
            })
        }
    }

    private fun slice(file: String) = NineSlice(file, Insets(8, 8, 8, 8), 2)

    private val styled = LookDefaults.KEYS.copy(
        press = PressEffect.Ripple,
        function = KindStyle(KeyFill.Color, KeyPressed.Image(slice("fn-down.png"))),
        space = KindStyle(KeyFill.Image(slice("space.png")), KeyPressed.Color(0x40ffffff)),
        letter = KindStyle(KeyFill.Picture(8, 60), KeyPressed.Darken),
        overrides = mapOf(
            "qwerty/q" to KeyOverride(KeyFill.Image(slice("q.png")), null, KeyLabel.Hide),
            "*/backspace" to KeyOverride(KeyFill.Image(slice("bs.png")), KeyPressed.Color(7), KeyLabel.Hide),
            "nine/backspace" to KeyOverride(null, KeyPressed.Color(8), KeyLabel.Show),
            "zhuyin/q" to KeyOverride(KeyFill.Image(slice("zq.png")), null, KeyLabel.Show),
            "k20/r1c1" to KeyOverride(KeyFill.Image(slice("new.png")), null, KeyLabel.Show),
            // a letter by every board's name: not a role, so not looked up
            "*/q" to KeyOverride(KeyFill.Image(slice("all-q.png")), null, KeyLabel.Hide),
        ),
    )

    private fun styles() = buildJsonObject {
        put("keys", keysJson(styled))
        put("cases", buildJsonArray {
            val queries = listOf(
                Triple("qwerty", "q", KeyKind.Letter), Triple("qwerty", "w", KeyKind.Letter),
                Triple("zhuyin", "q", KeyKind.Letter), Triple("nine", "q", KeyKind.Letter),
                Triple("qwerty", "backspace", KeyKind.Function), Triple("nine", "backspace", KeyKind.Function),
                Triple("qwerty", null, KeyKind.Function), Triple(null, "backspace", KeyKind.Function),
                Triple("qwerty", "space", KeyKind.Space), Triple("qwerty", "enter", KeyKind.Enter),
                Triple("k20", "r1c1", KeyKind.Letter), Triple("k14", "r1c1", KeyKind.Letter),
            )
            for ((board, key, kind) in queries) {
                val s = KeyStyleResolver.resolve(styled, board, key, kind)
                add(buildJsonObject {
                    put("board", board); put("key", key); put("kind", kind.name.lowercase())
                    put("style", json.encodeToJsonElement(OverrideJson.of(KeyOverride(s.fill, s.pressed, s.label))))
                })
            }
        })
        put("imagesOn", buildJsonArray {
            for ((board, names) in listOf("qwerty" to listOf("q", "w", "backspace", "space"), "nine" to listOf("backspace", "2"), "zhuyin" to listOf("q"))) {
                add(buildJsonObject {
                    put("board", board); put("names", JsonArray(names.map { JsonPrimitive(it) }))
                    put("files", JsonArray(KeyStyleResolver.imagesOn(styled, board, names).map { it.file }.sorted().map { JsonPrimitive(it) }))
                })
            }
        })
    }

    private fun budget() = buildJsonObject {
        put("limit", KeyImageBudget.LIMIT_BYTES)
        put("cases", buildJsonArray {
            val sets = listOf(
                listOf(100 to 150, 100 to 150),
                (1..52).map { 300 to 300 },
                (1..80).map { 1024 to 1024 },
                listOf(1 to 1, 3 to 5, 999 to 7),
            )
            for (sizes in sets) {
                val images = sizes.mapIndexed { i, (w, h) -> KeyImageBudget.Image("k$i", w, h) }
                val sample = KeyImageBudget.sampleSize(images)
                add(buildJsonObject {
                    put("images", JsonArray(sizes.map { (w, h) -> JsonArray(listOf(JsonPrimitive(w), JsonPrimitive(h))) }))
                    put("bytes", KeyImageBudget.bytes(images)); put("sampleSize", sample)
                    put("sampledBytes", KeyImageBudget.bytes(images, sample))
                })
            }
        })
    }

    // the 2.1 json an app before 3.0 exported (its colours, a picture), and what it is read as
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

    private fun migration() = buildJsonObject {
        put("from", Json.parseToJsonElement(v21))
        put("to", Json.parseToJsonElement(Json.encodeToString(CustomThemeSerializer, Json.decodeFromString(CustomThemeSerializer, v21))))
    }

    @Test
    fun rules() {
        val rules = buildJsonObject {
            put("keys", buildJsonObject { looks.forEach { (name, keys) -> put(name, keysJson(keys)) } })
            put("geometry", geometry())
            put("nineSlice", nineSlice())
            put("pictures", pictures())
            put("blur", blur())
            put("styles", styles())
            put("budget", budget())
            put("migration", migration())
        }
        ThemeMakerTestdata.check("rules.json", json.encodeToString(JsonElement.serializer(), rules) + "\n")
    }

    // a theme with everything 3.0 has: the picture cut for portrait and (turned) for landscape, blurred,
    // letters on it blurred, a nine-slice space, an image and a colour pressed, a role's key, a board's
    // key, a later build's board's key (kept, drawn by none), both fonts
    private val full = ThemePreset.PixelDark.deriveCustomNoBackground("full").let { t ->
        t.copy(
            look = t.look.copy(
                picture = Picture(
                    "full-src.png",
                    Crop("full-portrait.png", Box(0, 300, 1440, 1800), 0),
                    Crop("full-landscape.png", Box(0, 900, 1440, 1500), 90),
                    70, 4,
                ),
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
        // as the app writes a theme's file (its version first), only indented
        ThemeMakerTestdata.check("theme.json", json.encodeToString(CustomThemeSerializer, full) + "\n")
    }

    // a w×h PNG of one colour: the zips' images are real ones, for the page to decode
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

    // a zip as the app exports one, its times fixed: the same bytes every run, in any time zone
    // (a time in millis is stored as the local date, so a CI in UTC would write other bytes; and not
    // 1980-01-01 00:00, which is the DOS time of "before 1980" and gets a zoned timestamp added)
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
        // this app's: the json names its files by name, each in the zip once, the json last
        val theme = full.copy(name = "from-app", look = full.look.copy(text = full.look.text.copy(keyFont = null, candidateFont = null))).let { t ->
            t.copy(look = t.look.mapFiles { it.replace("full-", "from-app-") })
        }
        val files = theme.look.files().map { it to png(if ("landscape" in it) 24 else 16, if ("landscape" in it) 12 else 24, 0x80336699.toInt()) }
        // in the theme dir, as the app keeps them (it exports no other file), and out through its own export
        phoneStorage()
        val dir = ThemeFilesManager.themeDir.apply { mkdirs() }
        files.forEach { (name, bytes) -> File(dir, name).writeBytes(bytes) }
        val out = ByteArrayOutputStream()
        ThemeFilesManager.exportTheme(theme.copy(look = theme.look.mapFiles { File(dir, it).path }), out).getOrThrow()
        // its entries as it wrote them, in its order; only their times fixed (it stamps the time of the export)
        val exported = zip(entries(out.toByteArray()))
        assertEquals(theme.look.files() + "from-app.json", entries(exported).map { it.first })
        ThemeMakerTestdata.check("from-app.zip", exported)
        // one before 3.0: its two images and its 2.1 json
        val old = zip(listOf("old-cropped.png" to png(16, 24, -0x1000000), "old-src" to png(32, 48, -0x1000000), "old.json" to v21.encodeToByteArray()))
        ThemeMakerTestdata.check("from-app-2.1.zip", old)
    }
}
