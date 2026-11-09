/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.os.Parcel
import kotlinx.parcelize.Parceler
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.scene.look.BarBase
import org.fcitx.fcitx5.android.scene.look.BoardBase
import org.fcitx.fcitx5.android.scene.look.Box
import org.fcitx.fcitx5.android.scene.look.Crop
import org.fcitx.fcitx5.android.scene.look.Gap
import org.fcitx.fcitx5.android.scene.look.Insets
import org.fcitx.fcitx5.android.scene.look.KeyFill
import org.fcitx.fcitx5.android.scene.look.KeyLabel
import org.fcitx.fcitx5.android.scene.look.KeyOverride
import org.fcitx.fcitx5.android.scene.look.KeyPressed
import org.fcitx.fcitx5.android.scene.look.Keys
import org.fcitx.fcitx5.android.scene.look.KindStyle
import org.fcitx.fcitx5.android.scene.look.NineSlice
import org.fcitx.fcitx5.android.scene.look.Palette
import org.fcitx.fcitx5.android.scene.look.Picture
import org.fcitx.fcitx5.android.scene.look.PressEffect
import org.fcitx.fcitx5.android.scene.look.Text
import org.fcitx.fcitx5.android.scene.look.ThemeLook

/**
 * Theme file 3.0 as json, one to one with [ThemeLook] (ime-core has no serialization): this is the
 * only place the two meet. No field has a default, so every one is written and one missing does not
 * read; one not known does not either. What is out of range is refused by [ThemeLook] as it is made.
 */
@Serializable
internal data class ThemeFileJson(
    val name: String,
    val isDark: Boolean,
    val palette: PaletteJson,
    val picture: PictureJson?,
    val base: BaseJson,
    val bar: BarJson,
    val keys: KeysJson,
    val text: TextJson,
) {
    fun toTheme() = Theme.Custom(
        name, isDark,
        ThemeLook(palette.toPalette(), picture?.toPicture(), base.toBase(), bar.toBar(), keys.toKeys(), text.toText()),
    )

    companion object {
        fun of(theme: Theme.Custom) = theme.look.let {
            ThemeFileJson(
                theme.name, theme.isDark, PaletteJson.of(it.palette), it.picture?.let(PictureJson::of),
                BaseJson.of(it.base), BarJson.of(it.bar), KeysJson.of(it.keys), TextJson.of(it.text),
            )
        }
    }
}

/** [Theme.Custom] as theme file 3.0 (no version: [CustomThemeSerializer] stamps and checks it). */
internal object ThemeFileSerializer : KSerializer<Theme.Custom> {
    override val descriptor = ThemeFileJson.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Theme.Custom) =
        encoder.encodeSerializableValue(ThemeFileJson.serializer(), ThemeFileJson.of(value))

    override fun deserialize(decoder: Decoder): Theme.Custom =
        decoder.decodeSerializableValue(ThemeFileJson.serializer()).toTheme()
}

/** A [ThemeLook] in a Parcel: its json, as a theme file has it (a [Theme.Custom] parcelled carries its look so). */
internal object ThemeLookParceler : Parceler<ThemeLook> {
    override fun create(parcel: Parcel): ThemeLook =
        Json.decodeFromString(ThemeFileSerializer, parcel.readString()!!).look

    override fun ThemeLook.write(parcel: Parcel, flags: Int) =
        parcel.writeString(Json.encodeToString(ThemeFileSerializer, Theme.Custom("", false, this)))
}

@Serializable
internal data class PaletteJson(
    val backgroundColor: Int,
    val barColor: Int,
    val keyboardColor: Int,
    val keyBackgroundColor: Int,
    val keyTextColor: Int,
    val candidateTextColor: Int,
    val candidateLabelColor: Int,
    val candidateCommentColor: Int,
    val altKeyBackgroundColor: Int,
    val altKeyTextColor: Int,
    val accentKeyBackgroundColor: Int,
    val accentKeyTextColor: Int,
    val keyPressHighlightColor: Int,
    val keyShadowColor: Int,
    val popupBackgroundColor: Int,
    val popupTextColor: Int,
    val spaceBarColor: Int,
    val dividerColor: Int,
    val clipboardEntryColor: Int,
    val genericActiveBackgroundColor: Int,
    val genericActiveForegroundColor: Int,
    val keyBorderColor: Int,
    val keyHintColor: Int,
    val toolbarIconColor: Int,
) {
    // by name: a reordered Palette must not swap two colours unseen
    fun toPalette() = Palette(
        backgroundColor = backgroundColor,
        barColor = barColor,
        keyboardColor = keyboardColor,
        keyBackgroundColor = keyBackgroundColor,
        keyTextColor = keyTextColor,
        candidateTextColor = candidateTextColor,
        candidateLabelColor = candidateLabelColor,
        candidateCommentColor = candidateCommentColor,
        altKeyBackgroundColor = altKeyBackgroundColor,
        altKeyTextColor = altKeyTextColor,
        accentKeyBackgroundColor = accentKeyBackgroundColor,
        accentKeyTextColor = accentKeyTextColor,
        keyPressHighlightColor = keyPressHighlightColor,
        keyShadowColor = keyShadowColor,
        popupBackgroundColor = popupBackgroundColor,
        popupTextColor = popupTextColor,
        spaceBarColor = spaceBarColor,
        dividerColor = dividerColor,
        clipboardEntryColor = clipboardEntryColor,
        genericActiveBackgroundColor = genericActiveBackgroundColor,
        genericActiveForegroundColor = genericActiveForegroundColor,
        keyBorderColor = keyBorderColor,
        keyHintColor = keyHintColor,
        toolbarIconColor = toolbarIconColor,
    )

    companion object {
        fun of(p: Palette) = PaletteJson(
            backgroundColor = p.backgroundColor,
            barColor = p.barColor,
            keyboardColor = p.keyboardColor,
            keyBackgroundColor = p.keyBackgroundColor,
            keyTextColor = p.keyTextColor,
            candidateTextColor = p.candidateTextColor,
            candidateLabelColor = p.candidateLabelColor,
            candidateCommentColor = p.candidateCommentColor,
            altKeyBackgroundColor = p.altKeyBackgroundColor,
            altKeyTextColor = p.altKeyTextColor,
            accentKeyBackgroundColor = p.accentKeyBackgroundColor,
            accentKeyTextColor = p.accentKeyTextColor,
            keyPressHighlightColor = p.keyPressHighlightColor,
            keyShadowColor = p.keyShadowColor,
            popupBackgroundColor = p.popupBackgroundColor,
            popupTextColor = p.popupTextColor,
            spaceBarColor = p.spaceBarColor,
            dividerColor = p.dividerColor,
            clipboardEntryColor = p.clipboardEntryColor,
            genericActiveBackgroundColor = p.genericActiveBackgroundColor,
            genericActiveForegroundColor = p.genericActiveForegroundColor,
            keyBorderColor = p.keyBorderColor,
            keyHintColor = p.keyHintColor,
            toolbarIconColor = p.toolbarIconColor,
        )
    }
}

// as 2.1's crop rectangle was written (RectSerializer): its fields by name, in this order
@Serializable
internal data class BoxJson(val bottom: Int, val left: Int, val right: Int, val top: Int) {
    fun toBox() = Box(left, top, right, bottom)

    companion object {
        fun of(b: Box) = BoxJson(b.bottom, b.left, b.right, b.top)
    }
}

@Serializable
internal data class CropJson(val file: String, val cropRect: BoxJson?, val rotation: Int) {
    fun toCrop() = Crop(file, cropRect?.toBox(), rotation)

    companion object {
        fun of(c: Crop) = CropJson(c.file, c.cropRect?.let(BoxJson::of), c.rotation)
    }
}

@Serializable
internal data class PictureJson(
    val src: String,
    val portrait: CropJson,
    val landscape: CropJson?,
    val brightness: Int,
    val blurDp: Int,
) {
    fun toPicture() = Picture(src, portrait.toCrop(), landscape?.toCrop(), brightness, blurDp)

    companion object {
        fun of(p: Picture) = PictureJson(p.src, CropJson.of(p.portrait), p.landscape?.let(CropJson::of), p.brightness, p.blurDp)
    }
}

@Serializable
internal sealed interface BaseJson {
    @Serializable
    @SerialName("color")
    data object Color : BaseJson

    @Serializable
    @SerialName("picture")
    data object Picture : BaseJson

    fun toBase(): BoardBase = when (this) {
        Color -> BoardBase.Color
        Picture -> BoardBase.Picture
    }

    companion object {
        fun of(b: BoardBase): BaseJson = when (b) {
            BoardBase.Color -> Color
            BoardBase.Picture -> Picture
        }
    }
}

@Serializable
internal sealed interface BarJson {
    @Serializable
    @SerialName("transparent")
    data object Transparent : BarJson

    @Serializable
    @SerialName("color")
    data object Color : BarJson

    @Serializable
    @SerialName("picture")
    data class Picture(val blurDp: Int, val brightness: Int) : BarJson

    fun toBar(): BarBase = when (this) {
        Transparent -> BarBase.Transparent
        Color -> BarBase.Color
        is Picture -> BarBase.Picture(blurDp, brightness)
    }

    companion object {
        fun of(b: BarBase): BarJson = when (b) {
            BarBase.Transparent -> Transparent
            BarBase.Color -> Color
            is BarBase.Picture -> Picture(b.blurDp, b.brightness)
        }
    }
}

@Serializable
internal sealed interface FillJson {
    @Serializable
    @SerialName("color")
    data object Color : FillJson

    @Serializable
    @SerialName("image")
    data class Image(val file: String, val insets: List<Int>, val pxPerDp: Int) : FillJson

    @Serializable
    @SerialName("picture")
    data class Picture(val blurDp: Int, val brightness: Int) : FillJson

    fun toFill(): KeyFill = when (this) {
        Color -> KeyFill.Color
        is Image -> KeyFill.Image(slice(file, insets, pxPerDp))
        is Picture -> KeyFill.Picture(blurDp, brightness)
    }

    companion object {
        fun of(f: KeyFill): FillJson = when (f) {
            KeyFill.Color -> Color
            is KeyFill.Image -> f.slice.let { Image(it.file, it.insets.toList(), it.pxPerDp) }
            is KeyFill.Picture -> Picture(f.blurDp, f.brightness)
        }
    }
}

@Serializable
internal sealed interface PressedJson {
    @Serializable
    @SerialName("darken")
    data object Darken : PressedJson

    @Serializable
    @SerialName("color")
    data class Color(val argb: Int) : PressedJson

    @Serializable
    @SerialName("image")
    data class Image(val file: String, val insets: List<Int>, val pxPerDp: Int) : PressedJson

    fun toPressed(): KeyPressed = when (this) {
        Darken -> KeyPressed.Darken
        is Color -> KeyPressed.Color(argb)
        is Image -> KeyPressed.Image(slice(file, insets, pxPerDp))
    }

    companion object {
        fun of(p: KeyPressed): PressedJson = when (p) {
            KeyPressed.Darken -> Darken
            is KeyPressed.Color -> Color(p.argb)
            is KeyPressed.Image -> p.slice.let { Image(it.file, it.insets.toList(), it.pxPerDp) }
        }
    }
}

private fun slice(file: String, insets: List<Int>, pxPerDp: Int): NineSlice {
    require(insets.size == 4) { "insets are left, top, right, bottom" }
    return NineSlice(file, Insets(insets[0], insets[1], insets[2], insets[3]), pxPerDp)
}

private fun Insets.toList() = listOf(left, top, right, bottom)

@Serializable
internal data class KindJson(val fill: FillJson, val pressed: PressedJson) {
    fun toKind() = KindStyle(fill.toFill(), pressed.toPressed())

    companion object {
        fun of(k: KindStyle) = KindJson(FillJson.of(k.fill), PressedJson.of(k.pressed))
    }
}

@Serializable
internal enum class LabelJson {
    @SerialName("show")
    Show,

    @SerialName("hide")
    Hide,
}

@Serializable
internal data class OverrideJson(val fill: FillJson?, val pressed: PressedJson?, val label: LabelJson) {
    fun toOverride() = KeyOverride(fill?.toFill(), pressed?.toPressed(), if (label == LabelJson.Hide) KeyLabel.Hide else KeyLabel.Show)

    companion object {
        fun of(o: KeyOverride) = OverrideJson(
            o.fill?.let(FillJson::of), o.pressed?.let(PressedJson::of),
            if (o.label == KeyLabel.Hide) LabelJson.Hide else LabelJson.Show,
        )
    }
}

@Serializable
internal data class GapJson(val h: Int, val v: Int, val hLand: Int, val vLand: Int)

@Serializable
internal enum class PressJson {
    @SerialName("tint")
    Tint,

    @SerialName("ripple")
    Ripple,
}

@Serializable
internal data class KeysJson(
    val blocks: Boolean,
    val radiusDp: Int,
    val borderDp: Int,
    val shadowDp: Int,
    val gap: GapJson,
    val press: PressJson,
    val letter: KindJson,
    val function: KindJson,
    val space: KindJson,
    val enter: KindJson,
    val overrides: Map<String, OverrideJson>,
) {
    fun toKeys() = Keys(
        blocks, radiusDp, borderDp, shadowDp, Gap(gap.h, gap.v, gap.hLand, gap.vLand),
        if (press == PressJson.Ripple) PressEffect.Ripple else PressEffect.Tint,
        letter.toKind(), function.toKind(), space.toKind(), enter.toKind(),
        overrides.mapValues { it.value.toOverride() },
    )

    companion object {
        fun of(k: Keys) = KeysJson(
            k.blocks, k.radiusDp, k.borderDp, k.shadowDp, GapJson(k.gap.h, k.gap.v, k.gap.hLand, k.gap.vLand),
            if (k.press == PressEffect.Ripple) PressJson.Ripple else PressJson.Tint,
            KindJson.of(k.letter), KindJson.of(k.function), KindJson.of(k.space), KindJson.of(k.enter),
            k.overrides.mapValues { OverrideJson.of(it.value) },
        )
    }
}

@Serializable
internal data class TextJson(
    val keyFont: String?,
    val candidateFont: String?,
    val keyWeight: Int,
    val candidateWeight: Int,
    val keyScale: Int,
) {
    fun toText() = Text(keyFont, candidateFont, keyWeight, candidateWeight, keyScale)

    companion object {
        fun of(t: Text) = TextJson(t.keyFont, t.candidateFont, t.keyWeight, t.candidateWeight, t.keyScale)
    }
}
