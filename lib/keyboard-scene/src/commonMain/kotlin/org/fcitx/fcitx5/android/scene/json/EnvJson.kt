/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.json

import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.LetterCase
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard

/**
 * An [Env] as JSON: how the theme maker's page (060-render-p3 W1's library) says what to draw in, and
 * how the library's tests give it the snapshots' environments. Every member is optional, its default
 * [Env]'s; a name it does not know is a problem (a page's typo is not drawn as the default silently).
 *
 * `{"width", "height", "density", "landscape", "compact", "floating": text scale or null, "split",
 * "oneHanded"/"dock": "left"|"right"|"full", "dark", "flat", "shift": "none"|"once"|"lock", "board",
 * "typed": [scene, …] or null, "states": {id or role: ["on", …]}, "input": {…Input's},
 * "texts": {…Texts', "letterCase": "lower"|"shift"|"upper"}, "marks": {…MarkSettings', "upAt" and
 * "downAt": "top-right"|"bottom-right"|"top-left"|"bottom-left"|"bottom"|"none", "shortcuts":
 * {"name:up"|"name:down": {"icon": name} or {"text": text} or {"symbol": text} or {}}}}`: the members are the classes' own names.
 */
object EnvJson {
    /** [source] read; its problems, each with its line, where it does not say an environment. */
    fun read(source: String): Parsed<Env> = when (val json = Json.parse(source)) {
        is Parsed.Failed -> json
        is Parsed.Ok -> read(json.value)
    }

    /** [root] read as an environment. */
    // a value read is never a crash: a bug of ours it finds is the file's problem too, as Json.parse has it
    @Suppress("TooGenericExceptionCaught")
    fun read(root: JsonValue): Parsed<Env> = try {
        Parsed.Ok(env(Members(root)))
    } catch (e: EnvException) {
        Parsed.Failed(e.line, e.message.orEmpty())
    } catch (e: Exception) {
        Parsed.Failed(0, "the environment could not be read: ${e::class.simpleName}")
    }

    /** [KeyMarks.At] by its JSON name. */
    val AT: Map<String, KeyMarks.At> = mapOf(
        "top-right" to KeyMarks.At.TopRight, "bottom-right" to KeyMarks.At.BottomRight, "top-left" to KeyMarks.At.TopLeft,
        "bottom-left" to KeyMarks.At.BottomLeft, "bottom" to KeyMarks.At.Bottom, "none" to KeyMarks.At.None,
    )

    private fun env(m: Members): Env {
        val d = Env(m.number("width"), m.number("height"), m.number("density"))
        val env = Env(
            width = d.width,
            height = d.height,
            density = d.density,
            landscape = m.bool("landscape", d.landscape),
            compact = m.bool("compact", d.compact),
            floating = m.nullableNumber("floating", d.floating),
            split = m.bool("split", d.split),
            oneHanded = m.dock("oneHanded", d.oneHanded),
            dock = m.dock("dock", d.dock),
            dark = m.bool("dark", d.dark),
            flat = m.bool("flat", d.flat),
            input = m.obj("input")?.let(::input) ?: d.input,
            shift = m.choice("shift", SHIFTS, d.shift),
            texts = m.obj("texts")?.let(::texts) ?: d.texts,
            marks = m.obj("marks")?.let(::marks) ?: d.marks,
            states = m.obj("states")?.let(::states) ?: d.states,
            board = m.nullableString("board", d.board),
            typed = m.nullableStrings("typed", d.typed?.toList())?.toSet(),
        )
        m.done()
        if (!(env.width > 0 && env.height > 0 && env.density > 0)) throw EnvException(m.line, "width, height and density are to be above 0")
        return env
    }

    private fun input(m: Members): Input {
        val d = Input()
        return Input(
            ime = m.nullableString("ime", d.ime),
            scheme = m.nullableString("scheme", d.scheme),
            schemeTypes = m.nullableStrings("schemeTypes", d.schemeTypes.toList())?.toSet() ?: d.schemeTypes,
            table = m.nullableString("table", d.table),
            tableExtra = m.nullableString("tableExtra", d.tableExtra),
            roots = m.bool("roots", d.roots),
            field = m.nullableString("field", d.field),
            action = m.nullableString("action", d.action),
            voice = m.bool("voice", d.voice),
            holdVoice = m.bool("holdVoice", d.holdVoice),
            langKey = m.bool("langKey", d.langKey),
            composing = m.bool("composing", d.composing),
            lookingUp = m.bool("lookingUp", d.lookingUp),
            pad = m.nullableString("pad", d.pad),
        ).also { m.done() }
    }

    private fun texts(m: Members): Texts {
        val d = Texts()
        return Texts(
            imeName = m.string("imeName", d.imeName),
            comma = m.string("comma", d.comma),
            period = m.string("period", d.period),
            returnLabel = m.string("returnLabel", d.returnLabel),
            returnIcon = m.string("returnIcon", d.returnIcon),
            confirm = m.string("confirm", d.confirm),
            back = m.string("back", d.back),
            keepUppercase = m.bool("keepUppercase", d.keepUppercase),
            letterCase = m.choice("letterCase", CASES, d.letterCase),
            alts = if (m.has("alts")) m.obj("alts")?.let(::strings) else d.alts,
            punctuation = m.obj("punctuation")?.let(::strings) ?: d.punctuation,
            nineLetters = m.obj("nineLetters")?.let(::strings) ?: d.nineLetters,
            other = m.obj("other")?.let(::strings) ?: d.other,
            a11yGrouped = m.string("a11yGrouped", d.a11yGrouped),
            a11ySpace = m.string("a11ySpace", d.a11ySpace),
        ).also { m.done() }
    }

    private fun marks(m: Members): MarkSettings {
        val d = MarkSettings()
        return MarkSettings(
            upAt = m.choice("upAt", AT, d.upAt),
            downAt = m.choice("downAt", AT, d.downAt),
            symbols = m.bool("symbols", d.symbols),
            charShortcuts = m.bool("charShortcuts", d.charShortcuts),
            upOn = m.bool("upOn", d.upOn),
            downOn = m.bool("downOn", d.downOn),
            firstUp = m.bool("firstUp", d.firstUp),
            // a function key's bare name (`space:up`, before 060-keys-unify) read as its one name, `fn/space:up`
            shortcuts = m.obj("shortcuts")?.let { s -> s.all { name, v -> markName(name) to mark(Members(v)) } } ?: d.shortcuts,
        ).also { m.done() }
    }

    private fun markName(name: String): String {
        val way = name.lastIndexOf(':')
        return if (way <= 0) name else KeyId.of(name.substring(0, way)).name + name.substring(way)
    }

    private fun mark(m: Members): MarkContent {
        val icon = m.nullableString("icon", null)
        val text = m.nullableString("text", null)
        val symbol = m.nullableString("symbol", null)
        m.done()
        val given = listOfNotNull(icon, text, symbol)
        return when {
            given.size > 1 -> throw EnvException(m.line, "a mark is {\"icon\": name}, {\"text\": text}, {\"symbol\": text} or {}")
            icon != null -> MarkContent.Icon(icon)
            text != null -> MarkContent.Text(text)
            symbol != null -> MarkContent.Symbol(symbol)
            else -> MarkContent.Empty
        }
    }

    private fun states(m: Members): Map<String, Set<KeyState>> = m.all { id, v ->
        id to Members.strings(v).map { KeyState.of(it) ?: throw EnvException(v.line, "no key state \"$it\"") }.toSet()
    }

    private fun strings(m: Members): Map<String, String> = m.all { name, v -> name to Members.string(v) }

    private val SHIFTS = mapOf("none" to Shift.None, "once" to Shift.Once, "lock" to Shift.Lock)
    private val CASES = mapOf("lower" to LetterCase.Lower, "shift" to LetterCase.Shift, "upper" to LetterCase.Upper)
    private val DOCKS = mapOf("left" to SplitKeyboard.Dock.LEFT, "right" to SplitKeyboard.Dock.RIGHT, "full" to SplitKeyboard.Dock.FULL)

    private fun Members.dock(name: String, default: SplitKeyboard.Dock) = choice(name, DOCKS, default)

    private class EnvException(val line: Int, message: String) : Exception(message)

    /** An object's members, each taken once; [done] refuses any left (a name not known). */
    private class Members(value: JsonValue) {
        val line = value.line
        private val left: MutableMap<String, JsonValue> =
            LinkedHashMap((value as? JsonValue.Object ?: throw EnvException(value.line, "an object is to be here")).members)

        fun has(name: String) = name in left

        private fun take(name: String): JsonValue? = left.remove(name)

        fun number(name: String): Double = take(name)?.let(::number) ?: throw EnvException(line, "\"$name\" is to be given")

        fun nullableNumber(name: String, default: Double?): Double? = when (val v = take(name)) {
            null -> default
            is JsonValue.Null -> null
            else -> number(v)
        }

        fun bool(name: String, default: Boolean): Boolean = when (val v = take(name)) {
            null -> default
            is JsonValue.Bool -> v.value
            else -> throw EnvException(v.line, "\"$name\" is to be true or false")
        }

        fun string(name: String, default: String): String = take(name)?.let(::string) ?: default

        fun nullableString(name: String, default: String?): String? = when (val v = take(name)) {
            null -> default
            is JsonValue.Null -> null
            else -> string(v)
        }

        fun nullableStrings(name: String, default: List<String>?): List<String>? = when (val v = take(name)) {
            null -> default
            is JsonValue.Null -> null
            else -> strings(v)
        }

        fun <T> choice(name: String, of: Map<String, T>, default: T): T = when (val v = take(name)) {
            null -> default
            else -> string(v).let { of[it] ?: throw EnvException(v.line, "\"$name\" is one of ${of.keys.joinToString("|")}, not \"$it\"") }
        }

        fun obj(name: String): Members? = when (val v = take(name)) {
            null, is JsonValue.Null -> null
            else -> Members(v)
        }

        fun <K, V> all(each: (String, JsonValue) -> Pair<K, V>): Map<K, V> {
            val out = left.entries.associate { (k, v) -> each(k, v) }
            left.clear()
            return out
        }

        fun done() {
            left.entries.firstOrNull()?.let { (name, v) -> throw EnvException(v.line, "no member \"$name\" here") }
        }

        companion object {
            fun number(v: JsonValue): Double =
                (v as? JsonValue.Number)?.text?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw EnvException(v.line, "a number is to be here")

            fun string(v: JsonValue): String = (v as? JsonValue.Str)?.value ?: throw EnvException(v.line, "a string is to be here")

            fun strings(v: JsonValue): List<String> = (v as? JsonValue.Array)?.items?.map(::string) ?: throw EnvException(v.line, "a list is to be here")
        }
    }
}
