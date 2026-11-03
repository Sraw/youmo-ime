/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.input.theme.Gap
import org.fcitx.fcitx5.android.input.theme.KeyImageBudget
import org.fcitx.fcitx5.android.input.theme.Keys
import org.fcitx.fcitx5.android.input.theme.LookDefaults
import org.fcitx.fcitx5.android.input.theme.NineSlice
import org.fcitx.fcitx5.android.input.theme.Picture
import org.fcitx.fcitx5.android.input.theme.Crop
import org.fcitx.fcitx5.android.input.theme.Text
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The theme maker page and the app read and write the same theme files: the page's generated blocks
 * of web/theme-maker/index.html (`youmo-format`: the file's version, fields and limits; `youmo-presets`:
 * the built-in themes) are what this app has, and what the page exports (testdata/from-web.zip, which
 * its own test holds byte for byte) imports as testdata/theme.json. Changed here, the page's test
 * (`node --test web/theme-maker/test/format.test.mjs`) fails till it follows.
 */
@RunWith(RobolectricTestRunner::class)
// the real application: the import goes through appContext (where the themes are)
@Config(application = FcitxApplication::class)
class ThemeMakerFormatTest {

    private val pretty = Json { prettyPrint = true }

    // the theme json's type of a descriptor: ThemeFileJson → ThemeFile, the page's name for it
    private fun typeName(d: SerialDescriptor) = d.serialName.removeSuffix("?").substringAfterLast('.').removeSuffix("Json")

    /** Every type of the theme file, by the page's names: a class's fields in the order written, a sealed one's types, an enum's values. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun types(): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        fun ref(d: SerialDescriptor): String {
            val base = when (val kind: SerialKind = d.kind) {
                PrimitiveKind.INT -> "int"
                PrimitiveKind.STRING -> "string"
                PrimitiveKind.BOOLEAN -> "boolean"
                StructureKind.LIST -> "[" + ref(d.getElementDescriptor(0)) + "]"
                StructureKind.MAP -> "{" + ref(d.getElementDescriptor(1)) + "}"
                StructureKind.CLASS, StructureKind.OBJECT, PolymorphicKind.SEALED, SerialKind.ENUM -> {
                    val name = typeName(d)
                    if (name !in out) {
                        out[name] = JsonPrimitive("") // taken before its fields: no type twice
                        out[name] = when (kind) {
                            SerialKind.ENUM -> buildJsonObject { put("@enum", JsonArray(d.elementNames.map(::JsonPrimitive))) }
                            PolymorphicKind.SEALED -> {
                                val subs = d.getElementDescriptor(1)
                                buildJsonObject {
                                    put("@sealed", JsonObject(subs.elementNames.zip(subs.elementDescriptors).associate { (n, s) -> n to fields(s, ::ref) }))
                                }
                            }
                            else -> fields(d, ::ref)
                        }
                    }
                    name
                }
                else -> error("${d.serialName}: $kind is not in a theme file")
            }
            return if (d.isNullable) "$base?" else base
        }
        ref(ThemeFileJson.serializer().descriptor)
        return JsonObject(out)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun fields(d: SerialDescriptor, ref: (SerialDescriptor) -> String) =
        JsonObject(d.elementNames.zip(d.elementDescriptors).associate { (n, e) -> n to JsonPrimitive(ref(e)) })

    // the largest value [make] takes: a limit ime-core keeps to itself (its require is the rule)
    private fun largest(make: (Int) -> Unit) = (0..1000).last { runCatching { make(it) }.isSuccess }

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
        put("overrides", ThemeAssetLimits.MAX_OVERRIDES)
        put("boardImageBytes", KeyImageBudget.LIMIT_BYTES)
    }

    @Test
    fun thePagesFormatIsTheApps() {
        val current = CustomThemeSerializer.CURRENT_VERSION
        val format = buildJsonObject {
            put("version", current)
            // what the page brings to the current version itself (CustomThemeSerializer's 2.1 → 3.0);
            // older ones it sends to the app first
            put("migrates", JsonArray(listOf(JsonPrimitive("2.1"))))
            put("older", JsonArray(CustomThemeSerializer.knownVersions.filter { it != current && it != "2.1" }.map(::JsonPrimitive)))
            put("types", types())
            put("limits", limits())
            put("defaults", buildJsonObject {
                put("keys", pretty.encodeToJsonElement(KeysJson.of(LookDefaults.KEYS)))
                put("text", pretty.encodeToJsonElement(TextJson.of(LookDefaults.TEXT)))
            })
        }
        ThemeMakerTestdata.block("youmo-format", pretty.encodeToString(JsonElement.serializer(), format))
    }

    @Test
    fun thePagesBuiltInThemesAreTheApps() {
        // as the app lists them (ThemeManager.BuiltinThemes), then the two a picture's theme starts from
        val themes = listOf(
            ThemePreset.MaterialLight, ThemePreset.MaterialDark, ThemePreset.PixelLight, ThemePreset.PixelDark,
            ThemePreset.NordLight, ThemePreset.NordDark, ThemePreset.DeepBlue, ThemePreset.Monokai,
            ThemePreset.AMOLEDBlack, ThemePreset.TransparentDark, ThemePreset.TransparentLight,
        )
        val presets = buildJsonArray {
            for (t in themes) add(buildJsonObject {
                put("name", t.name)
                put("isDark", t.isDark)
                put("palette", Json.encodeToJsonElement(PaletteJson.of(t.look.palette)))
            })
        }
        ThemeMakerTestdata.block("youmo-presets", pretty.encodeToString(JsonElement.serializer(), presets))
    }

    // an image is a PNG (its header's size), a font a TrueType or OpenType file: what an import reads, off Android
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

    private val dir get() = ThemeFilesManager.newCustomBackgroundImages().second.parentFile!!

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

    private val fromWeb get() = ThemeMakerTestdata.file("from-web.zip").readBytes()

    private val themeJson get() = Json.decodeFromString(CustomThemeSerializer, ThemeMakerTestdata.file("theme.json").readText())

    @Test
    fun whatThePageExportsImportsAsTheThemeItMade() {
        val (_, theme, migrated) = ThemeFilesManager.importTheme(fromWeb.inputStream()).getOrThrow()
        assertFalse(migrated)
        // the colours, the picture's cuts, the keys, the single keys, the fonts: all of theme.json
        assertEquals(themeJson, theme.copy(look = theme.look.mapFiles { File(it).name }))
        val zip = entries(fromWeb)
        // the page writes the file as the app does, to the byte
        assertEquals(Json.encodeToString(CustomThemeSerializer, themeJson), zip.getValue("full.json").decodeToString())
        // each file in the theme's dir as the zip had it
        for (path in theme.look.files()) assertArrayEquals(path, zip.getValue(File(path).name), File(path).readBytes())
        assertEquals(theme.look.files().map { File(it).name }.toSet() + "full.json", zip.keys)
    }

    @Test
    fun whatTheAppExportsOfItImportsTheSame() {
        val (_, theme, _) = ThemeFilesManager.importTheme(fromWeb.inputStream()).getOrThrow()
        val out = ByteArrayOutputStream()
        ThemeFilesManager.exportTheme(theme, out).getOrThrow()
        val exported = out.toByteArray()
        val (_, again, migrated) = ThemeFilesManager.importTheme(exported.inputStream()).getOrThrow()
        assertFalse(migrated)
        assertEquals(theme, again)
        // the page's zip and the app's: the same entries, the same bytes (the app's compressed, the page's not)
        assertEquals(entries(fromWeb).mapValues { it.value.toList() }, entries(exported).mapValues { it.value.toList() })
    }
}
