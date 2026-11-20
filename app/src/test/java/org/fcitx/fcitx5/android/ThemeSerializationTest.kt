/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.graphics.Rect
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.fcitx.fcitx5.android.data.theme.CustomThemeSerializer
import org.fcitx.fcitx5.android.data.theme.TestThemes
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.backgroundImage
import org.fcitx.fcitx5.android.data.theme.deriveCustomBackground
import org.fcitx.fcitx5.android.engine.assertRows
import org.fcitx.fcitx5.android.scene.look.KeyLabel
import org.fcitx.fcitx5.android.scene.look.KeyOverride
import org.fcitx.fcitx5.android.scene.look.KeyPressed
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class ThemeSerializationTest {

    private fun Theme.Custom.toJson() = Json.encodeToString(CustomThemeSerializer, this)
    private fun String.toCustomTheme() =
        Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, this)

    /** A theme document at each known schema version: adding a version means one entry plus one test. */
    private object Fixtures {

        val v1 = """
            {
                "name": "",
                "backgroundImage": {
                    "croppedFilePath": "",
                    "srcFilePath": "",
                    "cropRect": null
                },
                "backgroundColor": -13816531,
                "barColor": 1275068416,
                "keyboardColor": 0,
                "keyBackgroundColor": 1275068415,
                "keyTextColor": -1,
                "altKeyBackgroundColor": 218103807,
                "altKeyTextColor": -905969665,
                "accentKeyBackgroundColor": -10577930,
                "accentKeyTextColor": -1,
                "keyPressHighlightColor": 520093696,
                "keyShadowColor": 0,
                "spaceBarColor": 1275068415,
                "dividerColor": 536870911,
                "clipboardEntryColor": 855638015,
                "isDark": true,
                "version": "1.0"
            }
        """.trimIndent()

        val v1NoBackgroundImage = """
            {
                "name": "",
                "backgroundImage": null,
                "backgroundColor": -13816531,
                "barColor": 1275068416,
                "keyboardColor": 0,
                "keyBackgroundColor": 1275068415,
                "keyTextColor": -1,
                "altKeyBackgroundColor": 218103807,
                "altKeyTextColor": -905969665,
                "accentKeyBackgroundColor": -10577930,
                "accentKeyTextColor": -1,
                "keyPressHighlightColor": 520093696,
                "keyShadowColor": 0,
                "spaceBarColor": 1275068415,
                "dividerColor": 536870911,
                "clipboardEntryColor": 855638015,
                "isDark": true,
                "version": "1.0"
            }
        """.trimIndent()

        val v2 = """
            {
               "name":"",
               "backgroundImage":{
                  "croppedFilePath":"",
                  "srcFilePath":"",
                  "cropRect": null
               },
               "backgroundColor":-13816531,
               "barColor":1275068416,
               "keyboardColor":0,
               "keyBackgroundColor":1275068415,
               "keyTextColor":-1,
               "altKeyBackgroundColor":218103807,
               "altKeyTextColor":-905969665,
               "accentKeyBackgroundColor":-10577930,
               "accentKeyTextColor":-1,
               "keyPressHighlightColor":520093696,
               "keyShadowColor":0,
               "popupBackgroundColor":-13158601,
               "popupTextColor":-1,
               "spaceBarColor":1275068415,
               "dividerColor":536870911,
               "clipboardEntryColor":855638015,
               "genericActiveBackgroundColor":-10577930,
               "genericActiveForegroundColor":-1,
               "isDark":true,
               "version":"2.0"
            }
        """.trimIndent()

        val v21 = """
            {
               "name":"",
               "backgroundImage":{
                  "croppedFilePath":"",
                  "srcFilePath":"",
                  "cropRect": null
               },
               "backgroundColor":-13816531,
               "barColor":1275068416,
               "keyboardColor":0,
               "keyBackgroundColor":1275068415,
               "keyTextColor":-1,
               "candidateTextColor":-1,
               "candidateLabelColor":-1,
               "candidateCommentColor":-905969665,
               "altKeyBackgroundColor":218103807,
               "altKeyTextColor":-905969665,
               "accentKeyBackgroundColor":-10577930,
               "accentKeyTextColor":-1,
               "keyPressHighlightColor":520093696,
               "keyShadowColor":0,
               "popupBackgroundColor":-13158601,
               "popupTextColor":-1,
               "spaceBarColor":1275068415,
               "dividerColor":536870911,
               "clipboardEntryColor":855638015,
               "genericActiveBackgroundColor":-10577930,
               "genericActiveForegroundColor":-1,
               "isDark":true,
               "version":"2.1"
            }
        """.trimIndent()
    }

    @Test
    fun preservation() {
        val fakeCustomTheme =
            TestThemes
                .TransparentDark
                .deriveCustomBackground("", "", "")

        val (decoded, migrated) = fakeCustomTheme.toJson().toCustomTheme()

        Assert.assertEquals("Migration shouldn't happen", false, migrated)
        Assert.assertEquals("Versioning preserves the original structure", fakeCustomTheme, decoded)
    }

    @Test
    fun version1() {
        val (decoded, migrated) = Fixtures.v1.toCustomTheme()
        Assert.assertEquals("Migration should happen", true, migrated)
        Assert.assertEquals(
            "popupBackgroundColor from preset",
            TestThemes.PixelDark.popupBackgroundColor,
            decoded.popupBackgroundColor
        )
        Assert.assertEquals("Round trip", decoded, decoded.toJson().toCustomTheme().first)
    }

    @Test
    fun version1WithoutBackgroundImage() {
        val (decoded, migrated) = Fixtures.v1NoBackgroundImage.toCustomTheme()
        Assert.assertEquals("Migration should happen", true, migrated)
        // a JSON null decodes to JsonNull rather than a Kotlin null, which the migration once mistook for "has a background image"
        Assert.assertEquals("popupBackgroundColor from barColor", decoded.barColor, decoded.popupBackgroundColor)
        Assert.assertEquals("Round trip", decoded, decoded.toJson().toCustomTheme().first)
    }

    @Test
    fun version2() {
        val (decoded, migrated) = Fixtures.v2.toCustomTheme()
        Assert.assertEquals("Migration should happen", true, migrated)
        Assert.assertEquals("Round trip", decoded, decoded.toJson().toCustomTheme().first)
    }

    @Test
    fun version2MigratesCandidateColors() {
        val (decoded, _) = Fixtures.v2.toCustomTheme()
        Assert.assertEquals("candidateTextColor from keyTextColor", decoded.keyTextColor, decoded.candidateTextColor)
        Assert.assertEquals("candidateLabelColor from keyTextColor", decoded.keyTextColor, decoded.candidateLabelColor)
        Assert.assertEquals(
            "candidateCommentColor from altKeyTextColor",
            decoded.altKeyTextColor,
            decoded.candidateCommentColor
        )
    }

    @Test
    fun version21() {
        val (decoded, migrated) = Fixtures.v21.toCustomTheme()
        Assert.assertEquals("Migration should happen", true, migrated)
        Assert.assertEquals(-10577930, decoded.genericActiveBackgroundColor)
        Assert.assertEquals(LookDefaults.KEYS, decoded.look.keys)
        Assert.assertEquals("Round trip", decoded, decoded.toJson().toCustomTheme().first)
    }

    @Test
    fun version3() {
        val theme = Fixtures.v21.toCustomTheme().first
        val (decoded, migrated) = theme.toJson().toCustomTheme()
        Assert.assertEquals("Migration shouldn't happen", false, migrated)
        Assert.assertEquals(theme, decoded)
    }

    private fun JsonObject.at(vararg path: String, change: (JsonObject) -> JsonObject): JsonObject =
        if (path.isEmpty()) change(this)
        else JsonObject(this + (path[0] to getValue(path[0]).jsonObject.at(*path.drop(1).toTypedArray(), change = change)))

    @Test
    fun version3IsReadOnlyAsItIsWritten() {
        val theme = Fixtures.v21.toCustomTheme().first.let { t ->
            val q = KeyOverride(null, KeyPressed.Color(1), KeyLabel.Hide)
            t.copy(look = t.look.copy(keys = t.look.keys.copy(overrides = mapOf("qwerty/q" to q))))
        }
        val written = Json.parseToJsonElement(theme.toJson()).jsonObject
        Assert.assertEquals(theme to false, written.toString().toCustomTheme())
        val refused = mapOf(
            "a field more" to written.at { JsonObject(it + ("extra" to JsonPrimitive(1))) },
            "a field more in a key's style" to written.at("keys", "letter") { JsonObject(it + ("extra" to JsonPrimitive(1))) },
            "no bar" to written.at { JsonObject(it - "bar") },
            "no picture (nullable)" to written.at { JsonObject(it - "picture") },
            "no landscape cut (nullable)" to written.at("picture") { JsonObject(it - "landscape") },
            "no key font (nullable)" to written.at("text") { JsonObject(it - "keyFont") },
            "no override fill (nullable)" to written.at("keys", "overrides", "qwerty/q") { JsonObject(it - "fill") },
            "a fill of no known type" to written.at("keys", "letter", "fill") { JsonObject(it + ("type" to JsonPrimitive("gradient"))) },
            "a base of no known type" to written.at("base") { JsonObject(it + ("type" to JsonPrimitive("video"))) },
        )
        assertRows(refused.entries, { it.key }) { (case, json) ->
            Assert.assertThrows(case, SerializationException::class.java) { json.toString().toCustomTheme() }
        }
    }

    @Test
    fun version1AndVersion2AgreeAfterMigration() {
        val fromV1 = Fixtures.v1.toCustomTheme().first
        val fromV2 = Fixtures.v2.toCustomTheme().first
        Assert.assertEquals("Migration chain is consistent", fromV2, fromV1)
    }

    @Test
    fun unknownVersionIsRejected() {
        val raw = Fixtures.v21.replace("\"version\":\"2.1\"", "\"version\":\"99.0\"")
        val e = Assert.assertThrows(CustomThemeSerializer.UnknownVersion::class.java) { raw.toCustomTheme() }
        Assert.assertEquals("99.0", e.version)
    }

    @Test
    fun missingVersionFallsBackToOldest() {
        // no version field at all is treated as 1.0, so the full migration chain applies
        val raw = JsonObject(Json.parseToJsonElement(Fixtures.v1).jsonObject - "version").toString()
        val (decoded, migrated) = raw.toCustomTheme()
        Assert.assertEquals("Migration should happen", true, migrated)
        Assert.assertEquals("Same as an explicit 1.0 document", Fixtures.v1.toCustomTheme().first, decoded)
    }

    @Test
    fun serializingStampsCurrentVersion() {
        val theme = Fixtures.v21.toCustomTheme().first
        Assert.assertTrue("Serialized form carries a version", theme.toJson().startsWith("{\"version\":\"${CustomThemeSerializer.CURRENT_VERSION}\""))
    }

    /** Needs a real `android.graphics.Rect`: off Robolectric it is an android.jar stub that cannot be constructed. */
    @RunWith(RobolectricTestRunner::class)
    class WithCropRect {

        private val withCrop = Fixtures.v21.replace(
            "\"cropRect\": null",
            "\"cropRect\": {\"bottom\": 400, \"left\": 10, \"right\": 300, \"top\": 20}",
        )

        private fun String.toCustomTheme() =
            Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, this)

        private val Theme.Custom.crop get() = backgroundImage!!.cropRect!!

        @Test
        fun theCropIsReadFieldByField() {
            Assert.assertEquals(Rect(10, 20, 300, 400), withCrop.toCustomTheme().first.crop)
        }

        @Test
        fun theCropSurvivesARoundTrip() {
            val theme = withCrop.toCustomTheme().first
            val again = Json.encodeToString(CustomThemeSerializer, theme).toCustomTheme().first
            Assert.assertEquals(theme, again)
            Assert.assertEquals(Rect(10, 20, 300, 400), again.crop)
        }

        @Test
        fun missingEdgesAreMinusOne() {
            val partial = Fixtures.v21.replace("\"cropRect\": null", "\"cropRect\": {\"left\": 10}")
            Assert.assertEquals(Rect(10, -1, -1, -1), partial.toCustomTheme().first.crop)
        }
    }
}
