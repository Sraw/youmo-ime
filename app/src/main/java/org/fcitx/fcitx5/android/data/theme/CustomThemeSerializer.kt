/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.os.Parcel
import kotlinx.parcelize.Parceler
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.look.Box
import org.fcitx.fcitx5.android.scene.look.Crop
import org.fcitx.fcitx5.android.scene.look.LookDefaults
import org.fcitx.fcitx5.android.scene.look.Palette
import org.fcitx.fcitx5.android.scene.look.Picture
import org.fcitx.fcitx5.android.scene.look.ThemeLook
import org.fcitx.fcitx5.android.scene.look.ThemeLookFile
import org.fcitx.fcitx5.android.scene.look.ThemeLookReader
import org.fcitx.fcitx5.android.scene.look.ThemeLookWriter
import org.fcitx.fcitx5.android.utils.NostalgicSerializer
import org.fcitx.fcitx5.android.utils.appContext

/** Written and read by keyboard-scene ([ThemeLookWriter], [ThemeLookReader]), older versions migrated first. */
object CustomThemeSerializer : KSerializer<Theme.Custom> {

    // before WithMigrationStatus, which takes it as it is made
    override val descriptor = JsonElement.serializer().descriptor

    val WithMigrationStatus = NostalgicSerializer(this) {
        it.jsonObject[VERSION]?.jsonPrimitive?.content != CURRENT_VERSION
    }

    override fun serialize(encoder: Encoder, value: Theme.Custom) =
        (encoder as JsonEncoder).encodeJsonElement(Json.parseToJsonElement(write(value)))

    override fun deserialize(decoder: Decoder): Theme.Custom {
        val element = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        val version = element[VERSION]?.let {
            val version = it.jsonPrimitive.content
            if (version !in knownVersions) throw UnknownVersion(version, knownVersions)
            version
        } ?: FALLBACK_VERSION
        val file = JsonObject(mapOf(VERSION to JsonPrimitive(CURRENT_VERSION)) + applyStrategy(version, element).removeVersion())
        // an import's limits refuse too many (ThemeAssetLimits), in the user's words
        return when (val read = ThemeLookReader.read(file.toString(), maxOverrides = Int.MAX_VALUE)) {
            is Parsed.Ok -> read.value.let { Theme.Custom(it.name, it.dark, it.look) }
            // without the line: the reader's json is this one rewritten on one line
            is Parsed.Failed -> throw SerializationException(read.problems.joinToString("; ") { it.message })
        }
    }

    private fun write(theme: Theme.Custom) = ThemeLookWriter.write(ThemeLookFile(theme.name, theme.isDark, theme.look))

    private fun JsonObject.removeVersion() =
        JsonObject(this - VERSION)

    private val EmptyTransform: (JsonObject) -> JsonObject = { it }

    private fun applyStrategy(oldVersion: String, obj: JsonObject) =
        strategies
            .takeWhile { it.version != oldVersion }
            .foldRight(EmptyTransform) { it, acc -> { obj -> it.transformation(acc(obj)) } }
            .invoke(obj)

    data class MigrationStrategy(
        val version: String,
        val transformation: (JsonObject) -> JsonObject
    )

    private val strategies: List<MigrationStrategy> =
        // Add migrations here
        listOf(
            // drawn as 2.1 was with the default key style settings
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
                            0xff373737.toInt() // the built-in PixelDark's of then (pixel#dark's)
                        } else {
                            0xffeeeeee.toInt() // PixelLight's
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

    /** The localized message is read only on demand, so a JVM test needs no app. */
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
                // the old Rect reader wrote -1 for a missing edge
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
        return Json.parseToJsonElement(write(theme)).jsonObject
    }
}

internal object ThemeLookParceler : Parceler<ThemeLook> {
    override fun create(parcel: Parcel): ThemeLook = when (val read = ThemeLookReader.read(parcel.readString()!!, maxOverrides = Int.MAX_VALUE)) {
        is Parsed.Ok -> read.value.look
        // written by this build just now: a problem is a bug of ours
        is Parsed.Failed -> error(read.problems.joinToString("; "))
    }

    override fun ThemeLook.write(parcel: Parcel, flags: Int) =
        parcel.writeString(ThemeLookWriter.write(ThemeLookFile("", false, this)))
}