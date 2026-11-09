/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.look

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.json.Json
import org.fcitx.fcitx5.android.scene.json.JsonValue

/** A theme file 3.0 as read: its name, whether it is dark, its look. */
data class ThemeLookFile(val name: String, val dark: Boolean, val look: ThemeLook)

/**
 * Theme file 3.0's json ([Json]) as a [ThemeLook], for the web's theme maker: what the app's
 * ThemeLookJson reads, the same way (held so by the app's ThemeLookReaderParityTest). Every field
 * is there, `null` written where one may be none; one not known is refused, as is a value out of
 * [ThemeLook]'s ranges. `"version": "3.0"` only: the app's migrations of older files are its own.
 * Its JSON is strict where kotlinx's is not (a key twice, a number in quotes, `8e0` for an int, a
 * control character unescaped: refused here), and a byte order mark is skipped (kotlinx refuses
 * one); no maker writes any of these. A problem is the file's, with its line, never a crash.
 */
object ThemeLookReader {
    const val VERSION = "3.0"

    // whatever else goes wrong (a bug of ours a file finds) a problem of the file too, as ThemeReader has it
    @Suppress("TooGenericExceptionCaught")
    fun read(source: String): Parsed<ThemeLookFile> = when (val json = Json.parse(source)) {
        is Parsed.Failed -> json
        is Parsed.Ok -> try {
            Parsed.Ok(file(json.value))
        } catch (e: ShapeException) {
            Parsed.Failed(e.line, e.message.orEmpty())
        } catch (e: Throwable) {
            Parsed.Failed(0, "the theme could not be read: ${e::class.simpleName}")
        }
    }

    private fun file(root: JsonValue): ThemeLookFile = fields(root) {
        val version = str("version")
        if (version != VERSION) fail(at("version"), "version \"$version\": this reads $VERSION only")
        val name = str("name")
        val dark = bool("isDark")
        val palette = obj("palette") { palette() }
        val picture = nullable("picture") { obj -> fields(obj) { picture() } }
        val base = obj("base") {
            when (val t = type()) {
                "color" -> BoardBase.Color
                "picture" -> BoardBase.Picture
                else -> fail(at("type"), "no base of type \"$t\"")
            }
        }
        val bar = obj("bar") {
            when (val t = type()) {
                "transparent" -> BarBase.Transparent
                "color" -> BarBase.Color
                "picture" -> make { BarBase.Picture(int("blurDp"), int("brightness")) }
                else -> fail(at("type"), "no bar of type \"$t\"")
            }
        }
        val keys = obj("keys") { keys() }
        val text = obj("text") { make { Text(nullableStr("keyFont"), nullableStr("candidateFont"), int("keyWeight"), int("candidateWeight"), int("keyScale")) } }
        ThemeLookFile(name, dark, make { ThemeLook(palette, picture, base, bar, keys, text) })
    }

    private fun Fields.palette(): Palette = make {
        Palette(
            backgroundColor = int("backgroundColor"),
            barColor = int("barColor"),
            keyboardColor = int("keyboardColor"),
            keyBackgroundColor = int("keyBackgroundColor"),
            keyTextColor = int("keyTextColor"),
            candidateTextColor = int("candidateTextColor"),
            candidateLabelColor = int("candidateLabelColor"),
            candidateCommentColor = int("candidateCommentColor"),
            altKeyBackgroundColor = int("altKeyBackgroundColor"),
            altKeyTextColor = int("altKeyTextColor"),
            accentKeyBackgroundColor = int("accentKeyBackgroundColor"),
            accentKeyTextColor = int("accentKeyTextColor"),
            keyPressHighlightColor = int("keyPressHighlightColor"),
            keyShadowColor = int("keyShadowColor"),
            popupBackgroundColor = int("popupBackgroundColor"),
            popupTextColor = int("popupTextColor"),
            spaceBarColor = int("spaceBarColor"),
            dividerColor = int("dividerColor"),
            clipboardEntryColor = int("clipboardEntryColor"),
            genericActiveBackgroundColor = int("genericActiveBackgroundColor"),
            genericActiveForegroundColor = int("genericActiveForegroundColor"),
            keyBorderColor = int("keyBorderColor"),
            keyHintColor = int("keyHintColor"),
            toolbarIconColor = int("toolbarIconColor"),
        )
    }

    private fun Fields.picture(): Picture {
        val src = str("src")
        val portrait = obj("portrait") { crop() }
        val landscape = nullable("landscape") { fields(it) { crop() } }
        return make { Picture(src, portrait, landscape, int("brightness"), int("blurDp")) }
    }

    // its rectangle's edges by name, as 2.1's was written
    private fun Fields.crop(): Crop {
        val file = str("file")
        val rect = nullable("cropRect") { fields(it) { Box(left = int("left"), top = int("top"), right = int("right"), bottom = int("bottom")) } }
        return make { Crop(file, rect, int("rotation")) }
    }

    private fun Fields.keys(): Keys {
        val blocks = bool("blocks")
        val radius = int("radiusDp")
        val border = int("borderDp")
        val shadow = int("shadowDp")
        val gap = obj("gap") { make { Gap(int("h"), int("v"), int("hLand"), int("vLand")) } }
        val press = when (val p = str("press")) {
            "tint" -> PressEffect.Tint
            "ripple" -> PressEffect.Ripple
            else -> fail(at("press"), "no press \"$p\"")
        }
        val kinds = listOf("letter", "function", "space", "enter").map { k -> obj(k) { make { KindStyle(obj("fill") { fill() }, obj("pressed") { pressed() }) } } }
        val overrides = members("overrides").also { o ->
            if (o.size > Keys.MAX_OVERRIDES) fail(at("overrides"), "over ${Keys.MAX_OVERRIDES} single keys")
        }.mapValues { (_, v) ->
            fields(v) {
                val fill = nullable("fill") { fields(it) { fill() } }
                val pressed = nullable("pressed") { fields(it) { pressed() } }
                val label = when (val l = str("label")) {
                    "show" -> KeyLabel.Show
                    "hide" -> KeyLabel.Hide
                    else -> fail(at("label"), "no label \"$l\"")
                }
                KeyOverride(fill, pressed, label)
            }
        }
        return make { Keys(blocks, radius, border, shadow, gap, press, kinds[0], kinds[1], kinds[2], kinds[3], overrides) }
    }

    private fun Fields.fill(): KeyFill = when (val t = type()) {
        "color" -> KeyFill.Color
        "image" -> KeyFill.Image(slice())
        "picture" -> make { KeyFill.Picture(int("blurDp"), int("brightness")) }
        else -> fail(at("type"), "no fill of type \"$t\"")
    }

    private fun Fields.pressed(): KeyPressed = when (val t = type()) {
        "darken" -> KeyPressed.Darken
        "color" -> KeyPressed.Color(int("argb"))
        "image" -> KeyPressed.Image(slice())
        else -> fail(at("type"), "no pressed face of type \"$t\"")
    }

    // insets left, top, right, bottom
    private fun Fields.slice(): NineSlice {
        val file = str("file")
        val insets = ints("insets")
        if (insets.size != INSETS) fail(at("insets"), "insets are four: left, top, right, bottom")
        return make { NineSlice(file, Insets(insets[0], insets[1], insets[2], insets[3]), int("pxPerDp")) }
    }

    private const val INSETS = 4

    private class ShapeException(val line: Int, message: String) : Exception(message)

    private fun fail(line: Int, message: String): Nothing = throw ShapeException(line, message)

    // [v]'s members as [read] takes them: every one, none other (the line of the first not taken)
    private fun <T> fields(v: JsonValue, read: Fields.() -> T): T {
        val o = v as? JsonValue.Object ?: fail(v.line, "an object was to be here")
        val f = Fields(o)
        val result = f.read()
        o.members.entries.firstOrNull { it.key !in f.taken }?.let { (k, value) -> fail(value.line, "\"$k\" is not of theme file $VERSION") }
        return result
    }

    /** One object's members, each taken by name; a value of the wrong kind or out of range a problem at its line. */
    private class Fields(val o: JsonValue.Object) {
        val taken = HashSet<String>()

        fun at(name: String): Int = o.members[name]?.line ?: o.line

        fun get(name: String): JsonValue {
            taken += name
            return o.members[name] ?: fail(o.line, "no \"$name\"")
        }

        fun str(name: String): String = (get(name) as? JsonValue.Str)?.value ?: fail(at(name), "\"$name\" is to be a string")

        fun nullableStr(name: String): String? = if (get(name) is JsonValue.Null) null else str(name)

        fun bool(name: String): Boolean = (get(name) as? JsonValue.Bool)?.value ?: fail(at(name), "\"$name\" is to be true or false")

        fun int(name: String): Int = int(get(name), name)

        fun ints(name: String): List<Int> = (get(name) as? JsonValue.Array)?.items?.map { int(it, name) } ?: fail(at(name), "\"$name\" is to be a list")

        fun type(): String = str("type")

        fun members(name: String): Map<String, JsonValue> = (get(name) as? JsonValue.Object)?.members ?: fail(at(name), "\"$name\" is to be an object")

        fun <T> obj(name: String, read: Fields.() -> T): T = fields(get(name), read)

        fun <T> nullable(name: String, read: (JsonValue) -> T): T? = get(name).let { if (it is JsonValue.Null) null else read(it) }

        // what ThemeLook refuses as it is made (a value out of range): a problem of this object
        fun <T> make(build: () -> T): T = try {
            build()
        } catch (e: IllegalArgumentException) {
            fail(o.line, e.message ?: "a value out of range")
        }

        // an Int as written: no fraction, no exponent, within Int's range (kotlinx's decodeInt the same)
        private fun int(v: JsonValue, name: String): Int {
            val text = (v as? JsonValue.Number)?.text ?: fail(v.line, "\"$name\" is to be a number")
            return text.takeIf { INTEGER.matches(it) }?.toIntOrNull() ?: fail(v.line, "\"$name\" is to be a whole number in Int's range")
        }
    }

    private val INTEGER = Regex("-?[0-9]+")
}
