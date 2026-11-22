/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.input.theme.KeyImageBudget
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.look.BarBase
import org.fcitx.fcitx5.android.scene.look.Crop
import org.fcitx.fcitx5.android.scene.look.Gap
import org.fcitx.fcitx5.android.scene.look.Keys
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.fcitx.fcitx5.android.scene.look.NineSlice
import org.fcitx.fcitx5.android.scene.look.Picture
import org.fcitx.fcitx5.android.scene.look.PressEffect
import org.fcitx.fcitx5.android.scene.look.Text
import org.fcitx.fcitx5.android.scene.look.ThemeLookReader
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.util.zip.ZipInputStream

/**
 * contract: the web theme maker and the app share the theme format. Its generated block (web/theme-maker/generated/)
 * is what this app has, and a page-written 3.0 theme (testdata/from-web.theme) imports as testdata/theme.json.
 * Change it here and `node --test web/theme-maker/test/format.test.mjs` fails till the page follows.
 */
@RunWith(RobolectricTestRunner::class)
// the real application: the import goes through appContext
@Config(application = FcitxApplication::class)
class ThemeMakerFormatTest {

    private val pretty = Json { prettyPrint = true }

    /** Every theme file type by the page's names, as keyboard-scene's ThemeLookReader/Writer handle them. */
    private val types = Json.parseToJsonElement(
        """
        {
        "ThemeFile":{"name":"string","isDark":"boolean","palette":"Palette","picture":"Picture?","base":"Base","bar":"Bar","keys":"Keys","text":"Text"},
        "Palette":{"backgroundColor":"int","barColor":"int","keyboardColor":"int","keyBackgroundColor":"int","keyTextColor":"int","candidateTextColor":"int","candidateLabelColor":"int","candidateCommentColor":"int","altKeyBackgroundColor":"int","altKeyTextColor":"int","accentKeyBackgroundColor":"int","accentKeyTextColor":"int","keyPressHighlightColor":"int","keyShadowColor":"int","popupBackgroundColor":"int","popupTextColor":"int","spaceBarColor":"int","dividerColor":"int","clipboardEntryColor":"int","genericActiveBackgroundColor":"int","genericActiveForegroundColor":"int","keyBorderColor":"int","keyHintColor":"int","toolbarIconColor":"int"},
        "Picture":{"src":"string","portrait":"Crop","landscape":"Crop?","brightness":"int","blurDp":"int"},
        "Crop":{"file":"string","cropRect":"Box?","rotation":"int"},
        "Box":{"bottom":"int","left":"int","right":"int","top":"int"},
        "Base":{"@sealed":{"color":{},"picture":{}}},
        "Bar":{"@sealed":{"color":{},"picture":{"blurDp":"int","brightness":"int"},"transparent":{}}},
        "Keys":{"blocks":"boolean","radiusDp":"int","borderDp":"int","shadowDp":"int","gap":"Gap","press":"Press","letter":"Kind","function":"Kind","space":"Kind","enter":"Kind","overrides":"{Override}"},
        "Gap":{"h":"int","v":"int","hLand":"int","vLand":"int"},
        "Press":{"@enum":["tint","ripple"]},
        "Kind":{"fill":"Fill","pressed":"Pressed"},
        "Fill":{"@sealed":{"color":{},"image":{"file":"string","insets":"[int]","pxPerDp":"int"},"picture":{"blurDp":"int","brightness":"int"}}},
        "Pressed":{"@sealed":{"color":{"argb":"int"},"darken":{},"image":{"file":"string","insets":"[int]","pxPerDp":"int"}}},
        "Override":{"fill":"Fill?","pressed":"Pressed?","label":"Label"},
        "Label":{"@enum":["show","hide"]},
        "Text":{"keyFont":"string?","candidateFont":"string?","keyWeight":"int","candidateWeight":"int","keyScale":"int"}
        }
        """,
    ).jsonObject

    private val defaultsTheme get() = Theme.Custom("defaults", false, LookDefaults.look(TestThemes.PixelDark.look.palette))

    // looks covering every sealed kind and enum value: theme.json's, the defaults', a bar of each other kind with the ripple
    private fun looks(): List<Theme.Custom> {
        val defaults = defaultsTheme
        return listOf(themeJson, defaults) + listOf(BarBase.Transparent, BarBase.Color).map { bar ->
            defaults.copy(look = defaults.look.copy(bar = bar, keys = defaults.look.keys.copy(press = PressEffect.Ripple)))
        }
    }

    private fun read(file: JsonObject) = ThemeLookReader.read(file.toString())

    // fields exactly those of its type, in order; each sealed kind and enum value met put in [seen]
    private fun walk(value: JsonElement, type: String, at: String, seen: MutableSet<String>) {
        if (type.endsWith("?")) {
            if (value !is JsonNull) walk(value, type.dropLast(1), at, seen)
            return
        }
        when {
            type == "int" -> assertTrue(at, value is JsonPrimitive && !value.isString && value.content.toIntOrNull() != null)
            type == "string" -> assertTrue(at, value is JsonPrimitive && value.isString)
            type == "boolean" -> assertTrue(at, value is JsonPrimitive && !value.isString && value.content in setOf("true", "false"))
            type.startsWith("[") -> value.jsonArray.forEachIndexed { i, v -> walk(v, type.drop(1).dropLast(1), "$at[$i]", seen) }
            type.startsWith("{") -> value.jsonObject.forEach { (k, v) -> walk(v, type.drop(1).dropLast(1), "$at.$k", seen) }
            else -> walkType(value, type, at, seen)
        }
    }

    private fun walkType(value: JsonElement, type: String, at: String, seen: MutableSet<String>) {
        val t = types.getValue(type).jsonObject
        t["@enum"]?.let { values ->
            val v = value.jsonPrimitive
            assertTrue(at, v.isString && JsonPrimitive(v.content) in values.jsonArray)
            seen += "$type.${v.content}"
            return
        }
        val o = value.jsonObject
        val sealed = t["@sealed"]?.jsonObject
        val fields = if (sealed == null) t else {
            val kind = o.getValue("type").jsonPrimitive.content
            assertTrue("$at: no $type of type $kind", kind in sealed)
            seen += "$type.$kind"
            JsonObject(mapOf("type" to JsonPrimitive("string")) + sealed.getValue(kind).jsonObject)
        }
        assertEquals(at, fields.keys.toList(), o.keys.toList())
        for ((k, v) in o) walk(v, fields.getValue(k).jsonPrimitive.content, "$at.$k", seen)
    }

    private fun JsonObject.at(path: List<String>, change: (JsonObject) -> JsonObject): JsonObject =
        if (path.isEmpty()) change(this) else JsonObject(this + (path[0] to getValue(path[0]).jsonObject.at(path.drop(1), change)))

    // its own first
    private fun JsonObject.objects(path: List<String> = emptyList()): List<List<String>> =
        listOf(path) + flatMap { (k, v) -> if (v is JsonObject) v.objects(path + k) else emptyList() }

    @Test
    fun theTypesAreWhatTheAppReadsAndWrites() {
        val seen = HashSet<String>()
        for (theme in looks()) {
            val file = Json.parseToJsonElement(Json.encodeToString(CustomThemeSerializer, theme)).jsonObject
            assertEquals("version", file.keys.first())
            walk(JsonObject(file - "version"), "ThemeFile", theme.name, seen)
            assertTrue(read(file) is Parsed.Ok)
            // each field as the reader takes it: none left out (nullable ones neither), none more (single keys a map)
            for (path in file.objects()) {
                val fields = path.fold(file) { o, k -> o.getValue(k).jsonObject }.keys.takeUnless { path.lastOrNull() == "overrides" }
                for (k in fields.orEmpty()) {
                    assertTrue("$path: $k left out", read(file.at(path) { JsonObject(it - k) }) is Parsed.Failed)
                }
                assertTrue("$path: a field more", read(file.at(path) { JsonObject(it + ("extra" to JsonPrimitive(1))) }) is Parsed.Failed)
            }
        }
        val all = types.flatMap { (name, t) ->
            val o = t.jsonObject
            val values = o["@sealed"]?.jsonObject?.keys ?: o["@enum"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            values.map { "$name.$it" }
        }
        assertEquals(all.toSet(), seen)
    }

    // a limit ime-core keeps to itself: only its require's IllegalArgumentException means too large; anything else, or a
    // limit past the scan's end, fails
    private fun largest(make: (Int) -> Unit): Int {
        fun takes(v: Int) = try {
            make(v)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
        assertFalse("the limit is past the scan's end", takes(SCAN_END + 1))
        return (0..SCAN_END).last(::takes)
    }

    private fun limits() = buildJsonObject {
        put("radiusDp", Keys.MAX_RADIUS_DP)
        put("borderDp", Keys.MAX_BORDER_DP)
        put("shadowDp", Keys.MAX_SHADOW_DP)
        put("gapDp", Gap.MAX_GAP_DP)
        put("blurDp", largest { Picture("s", Crop("p", null, 0), null, 70, it) })
        put("pxPerDp", NineSlice.MAX_PX_PER_DP)
        put("weights", JsonArray(Text.WEIGHTS.map(::JsonPrimitive)))
        put("keyScale", JsonArray(listOf(Text.MIN_SCALE, Text.MAX_SCALE).map(::JsonPrimitive)))
        put("entries", ThemeAssetLimits.MAX_ENTRIES)
        put("fileBytes", ThemeAssetLimits.MAX_FILE_BYTES)
        put("totalBytes", ThemeAssetLimits.MAX_TOTAL_BYTES)
        put("pictureSide", ThemeAssetLimits.MAX_PICTURE_SIDE)
        put("keyImageSide", ThemeAssetLimits.MAX_KEY_IMAGE_SIDE)
        put("keyImageBytes", ThemeAssetLimits.MAX_KEY_IMAGE_BYTES)
        put("iconBytes", ThemeAssetLimits.MAX_ICON_BYTES)
        put("rasterIconBytes", ThemeAssetLimits.MAX_RASTER_ICON_BYTES)
        put("rasterIconSide", ThemeAssetLimits.MAX_RASTER_ICON_SIDE)
        put("stickerBytes", ThemeAssetLimits.MAX_STICKER_BYTES)
        put("stickerSide", ThemeAssetLimits.MAX_STICKER_SIDE)
        put("stickers", ThemeAssetLimits.MAX_STICKERS)
        put("overrides", ThemeAssetLimits.MAX_OVERRIDES)
        put("layouts", ThemeAssetLimits.MAX_LAYOUTS)
        put("layoutBytes", ThemeAssetLimits.MAX_LAYOUT_BYTES)
        put("boardImageBytes", KeyImageBudget.LIMIT_BYTES)
        put("animatedBytes", ThemeAssetLimits.MAX_ANIMATED_BYTES)
        put("animatedSide", ThemeAssetLimits.MAX_ANIMATED_SIDE)
        put("animatedFrames", ThemeAssetLimits.MAX_ANIMATED_FRAMES)
        put("animatedFrameMs", ThemeAssetLimits.MIN_ANIMATED_FRAME_MS)
        put("effectImageSide", ThemeAssetLimits.MAX_EFFECT_IMAGE_SIDE)
        put("effectImageBytes", ThemeAssetLimits.MAX_EFFECT_IMAGE_BYTES)
        put("themeXmlBytes", ThemeAssetLimits.MAX_THEME_XML_BYTES)
    }

    @Test
    fun thePagesFormatIsTheApps() {
        val current = CustomThemeSerializer.CURRENT_VERSION
        val format = buildJsonObject {
            put("version", current)
            // what the page migrates itself (CustomThemeSerializer's 2.1 → 3.0); older ones it sends to the app
            put("migrates", JsonArray(listOf(JsonPrimitive("2.1"))))
            put("older", JsonArray(CustomThemeSerializer.knownVersions.filter { it != current && it != "2.1" }.map(::JsonPrimitive)))
            put("types", types)
            put("limits", limits())
            // names a pack may not take, both ThemeFilesManager.packClash rules: ThemePackFiles.builtinClash's and the built-in
            // packs' (ThemeManager.BuiltinPackNames)
            val clash = TestThemes.builtins.flatMap { (old, t) -> listOf(old, t.name, t.file) } +
                BuiltinThemeFiles.LEGACY.keys + BuiltinThemeFiles.RETIRED.keys + ThemeManager.BuiltinPackNames.sorted()
            put("builtinNames", JsonArray(clash.distinct().map(::JsonPrimitive)))
            put("defaults", buildJsonObject {
                val defaults = Json.parseToJsonElement(Json.encodeToString(CustomThemeSerializer, defaultsTheme)).jsonObject
                put("keys", defaults.getValue("keys"))
                put("text", defaults.getValue("text"))
            })
        }
        ThemeMakerTestdata.block("youmo-format", pretty.encodeToString(JsonElement.serializer(), format))
    }

    // off Android: an image is a PNG (its header's size), a font a TrueType or OpenType file
    private object Probe : ThemeFilesManager.AssetProbe {
        private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

        override fun imageSize(file: File): Pair<Int, Int>? {
            val bytes = file.readBytes()
            if (bytes.size < 24 || !bytes.copyOf(8).contentEquals(png)) return null
            return ByteBuffer.wrap(bytes).let { it.getInt(16) to it.getInt(20) }
        }

        override fun fontReads(file: File): Boolean {
            val tag = ByteBuffer.wrap(file.readBytes().copyOf(4)).int
            return tag == 0x00010000 || tag == 0x4f54544f
        }
    }

    private val real = ThemeFilesManager.probe

    private val dir get() = ThemeFilesManager.themeDir

    @Before
    fun empty() {
        phoneStorage()
        dir.deleteRecursively()
        dir.mkdirs()
        ThemeFilesManager.probe = Probe
        ThemeFilesManager.listThemes()
    }

    @After
    fun realProbe() {
        ThemeFilesManager.probe = real
    }

    private fun entries(zip: ByteArray) = ZipInputStream(zip.inputStream()).use { z ->
        generateSequence { z.nextEntry?.let { it.name to z.readBytes() } }.toMap()
    }

    private val fromWeb get() = ThemeMakerTestdata.file("from-web.theme").readBytes()

    private val themeJson get() = Json.decodeFromString(CustomThemeSerializer, ThemeMakerTestdata.file("theme.json").readText())

    @Test
    fun whatThePageExportsImportsAsTheThemeItMade() {
        val (_, theme, migrated) = ThemeFilesManager.importTheme(fromWeb.inputStream()).getOrThrow()
        assertFalse(migrated)
        // all of theme.json
        assertEquals(themeJson, theme.copy(look = theme.look.mapFiles { File(it).name }))
        val zip = entries(fromWeb)
        // the page writes the file as the app does, to the byte
        assertEquals(Json.encodeToString(CustomThemeSerializer, themeJson), zip.getValue("full.json").decodeToString())
        for (path in theme.look.files()) assertArrayEquals(path, zip.getValue(File(path).name), File(path).readBytes())
        assertEquals(theme.look.files().map { File(it).name }.toSet() + "full.json", zip.keys)
    }

    @Test
    fun whatTheAppExportsOfItImportsTheSame() {
        val (_, theme, _) = ThemeFilesManager.importTheme(fromWeb.inputStream()).getOrThrow()
        val out = ByteArrayOutputStream()
        ThemeFilesManager.exportJson(theme, out).getOrThrow()
        val exported = out.toByteArray()
        val (_, again, migrated) = ThemeFilesManager.importTheme(exported.inputStream()).getOrThrow()
        assertFalse(migrated)
        assertEquals(theme, again)
        // same entries and bytes (the app's compressed, the page's not)
        assertEquals(entries(fromWeb).mapValues { it.value.toList() }, entries(exported).mapValues { it.value.toList() })
    }
}

private const val SCAN_END = 1000
