/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import arrow.core.compose
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.theme.Box
import org.fcitx.fcitx5.android.input.theme.Crop
import org.fcitx.fcitx5.android.input.theme.LookDefaults
import org.fcitx.fcitx5.android.input.theme.Palette
import org.fcitx.fcitx5.android.input.theme.Picture
import org.fcitx.fcitx5.android.utils.NostalgicSerializer
import org.fcitx.fcitx5.android.utils.appContext

object CustomThemeSerializer : JsonTransformingSerializer<Theme.Custom>(ThemeFileSerializer) {

    val WithMigrationStatus = NostalgicSerializer(this) {
        it.jsonObject[VERSION]?.jsonPrimitive?.content != CURRENT_VERSION
    }

    override fun transformSerialize(element: JsonElement): JsonElement =
        element.jsonObject.addVersion()

    override fun transformDeserialize(element: JsonElement): JsonElement {
        val version = element.jsonObject[VERSION]?.let {
            val version = it.jsonPrimitive.content
            if (version !in knownVersions) throw UnknownVersion(version, knownVersions)
            version
        } ?: FALLBACK_VERSION
        return applyStrategy(version, element.jsonObject).removeVersion()
    }

    // first, as the theme maker writes it: a reader sees the version before what it governs
    private fun JsonObject.addVersion() =
        JsonObject(mapOf(VERSION to JsonPrimitive(CURRENT_VERSION)) + this)

    private fun JsonObject.removeVersion() =
        JsonObject(this - VERSION)

    private val EmptyTransform: (JsonObject) -> JsonObject = { it }

    private fun applyStrategy(oldVersion: String, obj: JsonObject) =
        strategies
            .takeWhile { it.version != oldVersion }
            .foldRight(EmptyTransform) { it, acc -> it.transformation compose acc }
            .invoke(obj)

    data class MigrationStrategy(
        val version: String,
        val transformation: (JsonObject) -> JsonObject
    )

    private val strategies: List<MigrationStrategy> =
        // Add migrations here
        listOf(
            // the 21 colours into the palette, the background image the picture's portrait cut, the
            // rest the defaults: drawn as 2.1 was with the default key style settings
            MigrationStrategy("3.0") { from21(it) },
            MigrationStrategy("2.1") {
                JsonObject(it.toMutableMap().apply {
                    put("candidateTextColor", getValue("keyTextColor"))
                    put("candidateLabelColor", getValue("keyTextColor"))
                    put("candidateCommentColor", getValue("altKeyTextColor"))
                })
            },
            MigrationStrategy("2.0") {
                JsonObject(it.toMutableMap().apply {
                    // `"backgroundImage": null` decodes to JsonNull, not a Kotlin null
                    if ((get("backgroundImage") ?: JsonNull) !is JsonNull) {
                        val popupBkgColor = if (getValue("isDark").jsonPrimitive.boolean) {
                            ThemePreset.PixelDark.popupBackgroundColor
                        } else {
                            ThemePreset.PixelLight.popupBackgroundColor
                        }
                        put("popupBackgroundColor", JsonPrimitive(popupBkgColor))
                        put("popupTextColor", getValue("keyTextColor"))
                        put("genericActiveBackgroundColor", getValue("accentKeyBackgroundColor"))
                        put("genericActiveForegroundColor", getValue("accentKeyTextColor"))
                    } else {
                        put("popupBackgroundColor", getValue("barColor"))
                        put("popupTextColor", getValue("keyTextColor"))
                        put("genericActiveBackgroundColor", getValue("accentKeyBackgroundColor"))
                        put("genericActiveForegroundColor", getValue("accentKeyTextColor"))
                    }
                })
            },
            MigrationStrategy("1.0", EmptyTransform)
        )

    /**
     * A theme of a format this build does not know, a later build's most likely: [message] the log's;
     * the user's ([getLocalizedMessage], what an import's dialog shows) read only then, so a JVM test needs no app.
     */
    class UnknownVersion(val version: String, known: List<String>) :
        IllegalStateException("$version is not in known versions: $known") {
        override fun getLocalizedMessage(): String =
            runCatching { appContext.getString(R.string.exception_theme_version, version) }.getOrDefault(message.orEmpty())
    }

    private const val VERSION = "version"

    internal const val CURRENT_VERSION = "3.0"
    private const val FALLBACK_VERSION = "1.0"

    internal val knownVersions = strategies.map { it.version }

    private fun from21(obj: JsonObject): JsonObject {
        fun color(key: String) = obj.getValue(key).jsonPrimitive.int
        val palette = Palette(
            backgroundColor = color("backgroundColor"),
            barColor = color("barColor"),
            keyboardColor = color("keyboardColor"),
            keyBackgroundColor = color("keyBackgroundColor"),
            keyTextColor = color("keyTextColor"),
            candidateTextColor = color("candidateTextColor"),
            candidateLabelColor = color("candidateLabelColor"),
            candidateCommentColor = color("candidateCommentColor"),
            altKeyBackgroundColor = color("altKeyBackgroundColor"),
            altKeyTextColor = color("altKeyTextColor"),
            accentKeyBackgroundColor = color("accentKeyBackgroundColor"),
            accentKeyTextColor = color("accentKeyTextColor"),
            keyPressHighlightColor = color("keyPressHighlightColor"),
            keyShadowColor = color("keyShadowColor"),
            popupBackgroundColor = color("popupBackgroundColor"),
            popupTextColor = color("popupTextColor"),
            spaceBarColor = color("spaceBarColor"),
            dividerColor = color("dividerColor"),
            clipboardEntryColor = color("clipboardEntryColor"),
            genericActiveBackgroundColor = color("genericActiveBackgroundColor"),
            genericActiveForegroundColor = color("genericActiveForegroundColor"),
        )
        val picture = (obj["backgroundImage"] as? JsonObject)?.let { image ->
            val rect = (image["cropRect"] as? JsonObject)?.let { r ->
                // an edge left out was -1 (the old Rect reader's), not a refused theme
                fun side(key: String) = r[key]?.jsonPrimitive?.int ?: -1
                Box(side("left"), side("top"), side("right"), side("bottom"))
            }
            Picture(
                src = image.getValue("srcFilePath").jsonPrimitive.content,
                portrait = Crop(
                    image.getValue("croppedFilePath").jsonPrimitive.content, rect,
                    image["cropRotation"]?.jsonPrimitive?.int ?: 0
                ),
                landscape = null,
                brightness = image["brightness"]?.jsonPrimitive?.int ?: 70,
                blurDp = 0,
            )
        }
        val theme = Theme.Custom(
            obj.getValue("name").jsonPrimitive.content,
            obj.getValue("isDark").jsonPrimitive.boolean,
            LookDefaults.look(palette, picture),
        )
        return Json.encodeToJsonElement(ThemeFileSerializer, theme).jsonObject
    }

}