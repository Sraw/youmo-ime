/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.graphics.Rect
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.theme.CustomThemeSerializer
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class ThemeSerializationTest {

    private fun Theme.Custom.toJson() = Json.encodeToString(CustomThemeSerializer, this)
    private fun String.toCustomTheme() =
        Json.decodeFromString(CustomThemeSerializer.WithMigrationStatus, this)

    /**
     * A theme document at each known schema version. Everything below is derived from these,
     * so adding a new version means adding one entry plus one test.
     */
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

        /** v1 without a background image: exercises the other branch of the 2.0 migration. */
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

        /** Adds popup/generic-active colors over v1. Still missing the candidate colors. */
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

        /** The current schema: adds candidateTextColor/candidateLabelColor/candidateCommentColor. */
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
            ThemePreset
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
        // over a background image, the popup gets the preset matching the theme's darkness
        Assert.assertEquals(
            "popupBackgroundColor from preset",
            ThemePreset.PixelDark.popupBackgroundColor,
            decoded.popupBackgroundColor
        )
        Assert.assertEquals("Round trip", decoded, decoded.toJson().toCustomTheme().first)
    }

    @Test
    fun version1WithoutBackgroundImage() {
        val (decoded, migrated) = Fixtures.v1NoBackgroundImage.toCustomTheme()
        Assert.assertEquals("Migration should happen", true, migrated)
        // With no background image to clash with, the popup takes the theme's own bar color.
        // A JSON null decodes to JsonNull rather than a Kotlin null, which the migration used
        // to mistake for "has a background image", handing out a preset color instead.
        Assert.assertEquals("popupBackgroundColor from barColor", decoded.barColor, decoded.popupBackgroundColor)
        Assert.assertEquals("Round trip", decoded, decoded.toJson().toCustomTheme().first)
    }

    @Test
    fun version2() {
        // 2.0 predates the current schema, so decoding it must report a migration
        val (decoded, migrated) = Fixtures.v2.toCustomTheme()
        Assert.assertEquals("Migration should happen", true, migrated)
        Assert.assertEquals("Round trip", decoded, decoded.toJson().toCustomTheme().first)
    }

    @Test
    fun version2MigratesCandidateColors() {
        // the 2.1 migration seeds candidate colors from the key colors
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
        // the current schema: decoding must not report a migration
        val (decoded, migrated) = Fixtures.v21.toCustomTheme()
        Assert.assertEquals("Migration shouldn't happen", false, migrated)
        Assert.assertEquals("Round trip", decoded, decoded.toJson().toCustomTheme().first)
    }

    @Test
    fun version1AndVersion2AgreeAfterMigration() {
        // v1 and v2 describe the same theme; migrating either must land on the same result
        val fromV1 = Fixtures.v1.toCustomTheme().first
        val fromV2 = Fixtures.v2.toCustomTheme().first
        Assert.assertEquals("Migration chain is consistent", fromV2, fromV1)
    }

    @Test
    fun unknownVersionIsRejected() {
        val raw = Fixtures.v21.replace("\"version\":\"2.1\"", "\"version\":\"99.0\"")
        Assert.assertThrows(IllegalStateException::class.java) { raw.toCustomTheme() }
    }

    @Test
    fun missingVersionFallsBackToOldest() {
        // no version field at all is treated as 1.0, so the full migration chain applies
        val raw = Fixtures.v1.replace(",\n                \"version\": \"1.0\"", "")
        val (decoded, migrated) = raw.toCustomTheme()
        Assert.assertEquals("Migration should happen", true, migrated)
        Assert.assertEquals("Same as an explicit 1.0 document", Fixtures.v1.toCustomTheme().first, decoded)
    }

    @Test
    fun serializingStampsCurrentVersion() {
        val theme = Fixtures.v21.toCustomTheme().first
        Assert.assertTrue("Serialized form carries a version", theme.toJson().contains("\"version\":\"2.1\""))
    }

    /**
     * A background image's crop rectangle, which needs a real `android.graphics.Rect`: off
     * Robolectric, `Rect` is an android.jar stub that cannot even be constructed.
     */
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

        /** Missing edges decode as -1 rather than failing the whole theme. */
        @Test
        fun missingEdgesAreMinusOne() {
            val partial = Fixtures.v21.replace("\"cropRect\": null", "\"cropRect\": {\"left\": 10}")
            Assert.assertEquals(Rect(10, -1, -1, -1), partial.toCustomTheme().first.crop)
        }
    }
}
