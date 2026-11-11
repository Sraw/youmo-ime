/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene

import org.fcitx.fcitx5.android.scene.Fixtures.ok
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.json.EnvJson
import org.fcitx.fcitx5.android.scene.look.LookTheme
import org.fcitx.fcitx5.android.scene.look.ThemeLookReader
import org.fcitx.fcitx5.android.scene.engine.LetterCase
import org.fcitx.fcitx5.android.scene.rules.KeyMarks
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every snapshot's ask written down for the theme maker's library (060-render-p3 W1): facade/shots.json,
 * each shot's board, theme ([ThemeSource]: a file, styles and fonts added) and environment ([EnvJson]),
 * each popup's ask the same. web/theme-maker/test/engine.test.mjs builds them through the library and
 * holds every scene to its snapshot, so the page draws what these tests hold (and the app, PaintTranscriptTest,
 * draws them as the page). The JVM writes it with the snapshots (-Psnapshots.record=true); both targets compare.
 */
class FacadeShotsTest {

    // contract: the files web/theme-maker/test/engine.test.mjs reads (shots.json), written here
    @Test
    fun theShotsAreWrittenForTheLibrary() {
        val text = FacadeShots.json()
        val path = "$TEST_DATA/facade/shots.json"
        if (recording()) {
            writeText(path, text)
            return
        }
        assertEquals(readText(path), text, "$path differs: review, then record anew (-Psnapshots.record=true)")
    }

    // the theme 3.0 the page's tests have (look-3.0.json) made 4.0 as the app makes it, its files' names: the
    // library's convert30 is held to these
    // contract: look-3.0.xml and look-3.0-files.txt, which web/theme-maker/test/engine.test.mjs compares with
    @Test
    fun aTheme30IsMade40ForTheLibrary() {
        val file = ThemeLookReader.read(testData("look-3.0.json")).ok()
        val name = LookTheme.themeName(file.name)
        val made = mapOf(
            "facade/look-3.0.xml" to LookTheme.xml(file.look, name, file.dark),
            "facade/look-3.0-files.txt" to LookTheme.fileNames(file.look).entries.joinToString("") { "${it.key} ${it.value}\n" },
        )
        for ((path, text) in made) {
            if (recording()) writeText("$TEST_DATA/$path", text) else assertEquals(readText("$TEST_DATA/$path"), text, "$path differs")
        }
    }

    // each environment as written reads back as itself: what the library builds in is the shot's
    @Test
    fun everyEnvironmentReadsBackAsItself() {
        val envs = Shots.all.map { it.name to it.env } + Shots.popups.map { it.name to it.env }
        val wrong = envs.filter { (_, env) -> EnvJson.read(FacadeShots.env(env)).ok() != env }.map { it.first }
        assertTrue(wrong.isEmpty(), "read back otherwise: $wrong")
    }

    @Test
    fun anEnvironmentWithEveryMemberReadsBackAsItself() {
        val env = Env(
            width = 787.5, height = 612.0, density = 2.625, landscape = true, compact = true, floating = 0.85, split = true,
            oneHanded = SplitKeyboard.Dock.LEFT, dock = SplitKeyboard.Dock.FULL, dark = true, flat = true,
            input = Input(
                ime = "table", scheme = "Ziranma", schemeTypes = setOf("semicolon"), table = "wbx", tableExtra = "x", roots = true,
                field = null, action = "send", voice = true, holdVoice = true, langKey = false, composing = true, lookingUp = true, pad = "phone",
            ),
            shift = Shift.Lock,
            texts = Texts(
                imeName = "拼音", comma = "，", period = "。", returnLabel = "换行", returnIcon = "send", confirm = "OK", back = "Back",
                keepUppercase = true, letterCase = LetterCase.Upper, alts = mapOf("q" to "1"), punctuation = mapOf("," to "，"),
                nineLetters = mapOf("2" to "abc"), other = mapOf("root:g" to "王\n一"), a11yGrouped = "%1\$s；%2\$s", a11ySpace = "空格 %1\$s",
            ),
            marks = MarkSettings(
                upAt = KeyMarks.At.BottomLeft, downAt = KeyMarks.At.None, symbols = false, charShortcuts = false, upOn = false, downOn = false, firstUp = false,
                shortcuts = mapOf(
                    "fn/copy:up" to MarkContent.Icon("copy"), "fn/paste:down" to MarkContent.Text("粘\"贴\\"), "full/q:up" to MarkContent.Symbol("1"),
                    "full/q:down" to MarkContent.Corner, "full/w:up" to MarkContent.Empty,
                ),
            ),
            states = mapOf("reading" to setOf(KeyState.On), "lock" to setOf(KeyState.Locked, KeyState.Disabled)),
            board = "qwerty",
            typed = setOf("letters"),
        )
        assertEquals(env, EnvJson.read(FacadeShots.env(env)).ok())
    }

    @Test
    fun aWrongMemberIsAProblemWithItsLine() {
        val read = EnvJson.read("{\"width\": 1, \"height\": 2, \"density\": 1,\n\"shift\": \"twice\"}")
        assertTrue(read is Parsed.Failed && read.problems.single().line == 2, "$read")
        val unknown = EnvJson.read("{\"width\": 1, \"height\": 2, \"density\": 1, \"input\": {\"imee\": \"x\"}}")
        assertTrue(unknown is Parsed.Failed && "imee" in unknown.problems.single().message, "$unknown")
        assertTrue(EnvJson.read("{\"width\": 0, \"height\": 2, \"density\": 1}") is Parsed.Failed)
        assertTrue(EnvJson.read("{\"height\": 2, \"density\": 1}") is Parsed.Failed)
        assertTrue(EnvJson.read("[1]") is Parsed.Failed)
        assertTrue(EnvJson.read("{\"width\": 1, \"height\": 2, \"density\": 1, \"marks\": {\"shortcuts\": {\"a:up\": {\"icon\": \"x\", \"text\": \"y\"}}}}") is Parsed.Failed)
        assertTrue(EnvJson.read("{\"width\": 1, \"height\": 2, \"density\": 1, \"marks\": {\"shortcuts\": {\"a:up\": {\"corner\": false}}}}") is Parsed.Failed)
        assertTrue(EnvJson.read("{\"width\": 1, \"height\": 2, \"density\": 1, \"states\": {\"a\": [\"pressed-ish\"]}}") is Parsed.Failed)
        assertTrue(EnvJson.read("{\"width\": \"1\", \"height\": 2, \"density\": 1}") is Parsed.Failed)
        assertTrue(EnvJson.read("{\"width\": 1, \"height\": 2, \"density\": 1, \"dark\": 1}") is Parsed.Failed)
        assertTrue(EnvJson.read("{") is Parsed.Failed)
    }
}

/** The shots' asks as JSON ([FacadeShotsTest]): one shot a line, members in a fixed order, numbers as both targets write them. */
object FacadeShots {
    fun json(): String {
        val shots = Shots.all.map { s ->
            obj("name" to str(s.name), "board" to str(s.board), "theme" to theme(s.theme), "env" to env(s.env))
        }
        val popups = Shots.popups.map { p ->
            obj("name" to str(p.name), "theme" to theme(p.theme), "env" to env(p.env), "request" to request(p.ask, p.env))
        }
        return "{\n\"shots\": [\n" + shots.joinToString(",\n") + "\n],\n\"popups\": [\n" + popups.joinToString(",\n") + "\n]\n}\n"
    }

    private fun theme(t: ThemeSource) = obj(
        "file" to str(t.file),
        "styles" to t.styles.takeIf { it.isNotEmpty() }?.let { s -> arr(s.map(::str)) },
        "fonts" to t.fonts.takeIf { it.isNotEmpty() }?.let(::str),
    )

    /** [env] as EnvJson reads it: only what is not the default. */
    fun env(env: Env): String {
        val d = Env(env.width, env.height, env.density)
        return obj(
            "width" to num(env.width), "height" to num(env.height), "density" to num(env.density),
            "landscape" to bool(env.landscape, d.landscape),
            "compact" to bool(env.compact, d.compact),
            "floating" to (if (env.floating == d.floating) null else env.floating?.let(::num) ?: "null"),
            "split" to bool(env.split, d.split),
            "oneHanded" to dock(env.oneHanded, d.oneHanded),
            "dock" to dock(env.dock, d.dock),
            "dark" to bool(env.dark, d.dark),
            "flat" to bool(env.flat, d.flat),
            "input" to input(env.input).takeIf { env.input != d.input },
            "shift" to (if (env.shift == d.shift) null else str(env.shift.name.lowercase())),
            "texts" to texts(env.texts).takeIf { env.texts != d.texts },
            "marks" to marks(env.marks).takeIf { env.marks != d.marks },
            "states" to env.states.takeIf { it != d.states }?.let { s -> obj(s.entries.map { (k, v) -> k to arr(v.map { str(it.xml) }.sorted()) }) },
            "board" to (if (env.board == d.board) null else nullable(env.board)),
            "typed" to (if (env.typed == d.typed) null else env.typed?.let { t -> arr(t.sorted().map(::str)) } ?: "null"),
        )
    }

    private fun input(i: Input): String {
        val d = Input()
        fun s(name: String, v: String?, dv: String?) = name to (if (v == dv) null else nullable(v))
        fun b(name: String, v: Boolean, dv: Boolean) = name to bool(v, dv)
        return obj(
            s("ime", i.ime, d.ime), s("scheme", i.scheme, d.scheme),
            "schemeTypes" to (if (i.schemeTypes == d.schemeTypes) null else arr(i.schemeTypes.sorted().map(::str))),
            s("table", i.table, d.table), s("tableExtra", i.tableExtra, d.tableExtra), b("roots", i.roots, d.roots),
            s("field", i.field, d.field), s("action", i.action, d.action), b("voice", i.voice, d.voice), b("holdVoice", i.holdVoice, d.holdVoice),
            b("langKey", i.langKey, d.langKey), b("composing", i.composing, d.composing), b("lookingUp", i.lookingUp, d.lookingUp),
            s("pad", i.pad, d.pad),
        )
    }

    private fun texts(t: Texts): String {
        val d = Texts()
        fun s(name: String, v: String, dv: String) = name to (if (v == dv) null else str(v))
        fun m(name: String, v: Map<String, String>?, dv: Map<String, String>?) =
            name to (if (v == dv) null else v?.let { obj(it.entries.map { (k, x) -> k to str(x) }) } ?: "null")
        return obj(
            s("imeName", t.imeName, d.imeName), s("comma", t.comma, d.comma), s("period", t.period, d.period),
            s("returnLabel", t.returnLabel, d.returnLabel), s("returnIcon", t.returnIcon, d.returnIcon),
            s("confirm", t.confirm, d.confirm), s("back", t.back, d.back),
            "keepUppercase" to bool(t.keepUppercase, d.keepUppercase),
            "letterCase" to (if (t.letterCase == d.letterCase) null else str(t.letterCase.name.lowercase())),
            m("alts", t.alts, d.alts), m("punctuation", t.punctuation, d.punctuation), m("nineLetters", t.nineLetters, d.nineLetters),
            m("other", t.other, d.other), s("a11yGrouped", t.a11yGrouped, d.a11yGrouped), s("a11ySpace", t.a11ySpace, d.a11ySpace),
        )
    }

    private fun marks(m: MarkSettings): String {
        val d = MarkSettings()
        fun at(name: String, v: Any, dv: Any) = name to (if (v == dv) null else str(EnvJson.AT.entries.first { it.value == v }.key))
        return obj(
            at("upAt", m.upAt, d.upAt), at("downAt", m.downAt, d.downAt),
            "symbols" to bool(m.symbols, d.symbols), "charShortcuts" to bool(m.charShortcuts, d.charShortcuts),
            "upOn" to bool(m.upOn, d.upOn), "downOn" to bool(m.downOn, d.downOn), "firstUp" to bool(m.firstUp, d.firstUp),
            "shortcuts" to (
                if (m.shortcuts == d.shortcuts) {
                    null
                } else {
                    obj(
                        m.shortcuts.entries.map { (k, v) ->
                            k to when (v) {
                                is MarkContent.Icon -> obj("icon" to str(v.name))
                                is MarkContent.Text -> obj("text" to str(v.text))
                                is MarkContent.Symbol -> obj("symbol" to str(v.text))
                                MarkContent.Corner -> obj("corner" to "true")
                                MarkContent.Empty -> obj()
                            }
                        },
                    )
                }
                ),
        )
    }

    /** [ask] as the library's buildPopup takes it, its sizes in px of [env]'s density, as PopupAsk.Preview.scene has them. */
    private fun request(ask: PopupAsk, env: Env): String = when (ask) {
        is PopupAsk.Preview -> obj(
            "kind" to str("preview"), "text" to str(ask.text), "keyWidth" to num(ask.keyDp * env.density),
            "size" to (if (ask.words) str("words") else null),
        )
        is PopupAsk.Keys -> obj(
            "kind" to str("keys"), "labels" to arr(ask.labels.map(::str)), "rows" to arr(ask.rows.map { r -> arr(r.map { it.toString() }) }),
            "columns" to ask.columns.toString(), "room" to num(env.width), "selected" to ask.selected.toString(),
        )
        is PopupAsk.Menu -> obj(
            "kind" to str("menu"),
            "cells" to arr(ask.cells.map { c -> obj("label" to str(c.label), "icon" to c.icon?.let(::str)) }),
            "order" to arr(ask.order.map { it.toString() }), "selected" to ask.selected.toString(),
            "disabled" to arr(ask.disabled.sorted().map { it.toString() }),
        )
    }

    // ------------------------------------------------------------------ JSON, written

    private fun bool(v: Boolean, default: Boolean): String? = if (v == default) null else v.toString()

    private fun dock(v: SplitKeyboard.Dock, default: SplitKeyboard.Dock): String? = if (v == default) null else str(v.name.lowercase())

    private fun nullable(v: String?): String = v?.let(::str) ?: "null"

    /** A number as both targets write it: a whole one without its point. */
    private fun num(v: Double): String = if (v == floor(v) && v < WHOLE) v.toLong().toString() else v.toString()

    private fun str(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c < ' ' -> append("\\u").append(c.code.toString(HEX).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }

    private fun arr(items: List<String>) = items.joinToString(", ", "[", "]")

    // members whose value is null are left out
    private fun obj(vararg members: Pair<String, String?>) = obj(members.toList())

    private fun obj(members: List<Pair<String, String?>>) =
        members.filter { it.second != null }.joinToString(", ", "{", "}") { (k, v) -> "${str(k)}: $v" }

    private const val HEX = 16
    private const val WHOLE = 1e15
}
