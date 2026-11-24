/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.layout

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Uat
import org.fcitx.fcitx5.android.scene.skin.assertRows
import org.fcitx.fcitx5.android.scene.testData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// 18 + 13: past Groups.CUSTOM_MAX
internal val THIRTEEN_GROUPS = (1..13).joinToString("") { "<key id=\"g$it\" width=\"1\" label=\"{group:a}\" letters=\"a\" shortcut=\"my-k18/g$it\"/>" }

// a board lacking keys only warns; what cannot be drawn and the basic limits stay errors
class LayoutLacksTest {
    private val boards = listOf(
        "emoji-search", "emoji", "emoticon", "expanded-side", "k14", "k18", "nine", "number-row", "number",
        "qwerty", "symbols", "zhuyin-nine", "zhuyin",
    )
    private val sources = LayoutCheck.Sources(boards.associateWith { testData("$it.xml") }, mapOf("grouped-bottom" to testData("grouped-bottom.xml")))

    private fun own(id: String) = testData("$id.xml").replaceFirst("id=\"$id\"", "id=\"my-$id\" based-on=\"$id\"")

    private val qwerty = own("qwerty")

    private fun check(text: String, strict: Boolean = true, app: String = "0.6.1") = LayoutCheck.check(text, sources, app, strict = strict)

    private fun loaded(text: String) = when (val p = LayoutReader.layout(text)) {
        is Parsed.Ok -> LayoutCheck.loaded(p.value, sources, "0.6.1").map { it.key }
        else -> listOf("unread $p")
    }

    private fun withRow(keys: String, board: String = qwerty) = board.replace("</keyboard>", "<row>$keys</row>\n</keyboard>")

    private fun dropKey(text: String, id: String) = text.replaceFirst(Regex("<key id=\"${Regex.escape(id)}\"(?:[^>]*?/>|[^>]*?[^/]>[\\s\\S]*?</key>)"), "")

    private class Lacking(val why: String, val board: String, val lacks: List<List<String>>, val pads: List<String> = emptyList())

    // the nine keys with no 0 and a Return two rows tall: 0.6.1 refused it for its 0
    @Test
    @Uat("ST-197")
    fun theNineKeysWithNoZeroAreTakenWithOneWarning() {
        val r = check(testData("layout-check/nine-big-enter.layout"), app = "0.6.3")
        assertEquals(emptyList(), r.errors)
        assertEquals(listOf(LayoutCheck.Finding(0, "lacks", listOf("digits", "0"))), r.warnings)
        assertEquals("0.6.3", r.minApp)
    }

    // what a board lacks of its base or family is told, each thing once, and the board taken; the app draws it as it is
    @Test
    @Uat("ST-197")
    fun whatABoardLacksIsToldOnceAndTaken() {
        val nine = own("nine")
        val k18 = own("k18")
        val symbols = own("symbols")
        val number = own("number")
        val numberPads = number.replaceFirst("family=\"number\"", "family=\"number\" pads=\"full phone\"")
        assertRows(
            listOf(
                Lacking("26 keys, no ⌫: deleting is the toolbar's then", dropKey(qwerty, "backspace"), listOf(listOf("backspace"))),
                Lacking("26 keys, no Return", dropKey(qwerty, "enter"), listOf(listOf("enter"))),
                Lacking("26 keys, no Shift: no capitals", dropKey(qwerty, "shift"), listOf(listOf("shift"))),
                Lacking("26 keys, no 地球键: switched by the space bar's hold", dropKey(qwerty, "lang"), listOf(listOf("lang"))),
                Lacking(
                    "26 keys, no 地球键 and no space: the space bar's hold is gone too, said in the one finding",
                    dropKey(dropKey(qwerty, "lang"), "space"),
                    listOf(listOf("lang-no-space")),
                ),
                Lacking("26 keys, no q, no x: one finding, the letters together", dropKey(dropKey(qwerty, "q"), "x"), listOf(listOf("letters", "q x"))),
                Lacking("nine, no 0: the sample's", dropKey(nine, "0"), listOf(listOf("digits", "0"))),
                Lacking(
                    "nine, no 2: told once, not as its base's and its family's",
                    nine.replace(Regex("<key id=\"2\"[^>]*>[\\s\\S]*?</key>"), "<gap width=\"22\"/>"),
                    listOf(listOf("digits", "2")),
                ),
                Lacking(
                    "nine, no side column: told once",
                    nine.replace(Regex("<slot [^>]*kind=\"nine-side\"[^>]*/>"), "<gap width=\"16\"/>"),
                    listOf(listOf("nine-side")),
                ),
                Lacking("18 keys, no WE key: its letters, no groups error", k18.replace(Regex("<key [^>]*letters=\"we\"[^>]*/>"), ""), listOf(listOf("letters", "e w"))),
                Lacking("symbols, no 返回", symbols.replace("send=\"panel-back\"", "send=\"layout:Text\""), listOf(listOf("panel-back"))),
                Lacking("symbols, no ⌫: told once, as its base's", dropKey(symbols, "backspace"), listOf(listOf("backspace"))),
                Lacking("symbols, no grid", symbols.replace(Regex("<slot [^>]*kind=\"symbol-grid\"[^>]*/>"), ""), listOf(listOf("symbol-grid"))),
                Lacking("number, no 返回: its one pad, no pad named", dropKey(number, "back"), listOf(listOf("back")), listOf("full")),
                Lacking("number, no 5: by what it types", dropKey(number, "5"), listOf(listOf("pad-digits", "5")), listOf("full")),
                Lacking(
                    "number, 返回 on the full pad only: the phone pad named",
                    numberPads.replace("long-press=\"panel-lock\"/>", "long-press=\"panel-lock\" when=\"pad=full\"/>"),
                    listOf(listOf("back", "", "phone")),
                    listOf("phone"),
                ),
                Lacking("number, no ⌫: its base's and its pad's told once", dropKey(number, "backspace"), listOf(listOf("backspace")), listOf("full")),
            ),
            { it.why },
        ) { row ->
            val r = check(row.board)
            assertEquals(emptyList(), r.errors)
            assertEquals(row.lacks, r.warnings.filter { it.key == "lacks" }.map { it.args })
            assertEquals("0.6.3", r.minApp, "a board lacking keys is 0.6.3's")
            assertEquals(row.pads, r.padsLacking)
            assertEquals(emptyList(), loaded(row.board), "drawn as imported")
            assertTrue(check(row.board, strict = false).ok, "restored as imported")
        }
    }

    // a number board is held by what its keys type, as 0.6.2 held it: renamed digits warn of nothing new
    @Test
    fun aNumberBoardsDigitsAreHeldByWhatTheyTypeAndALetterOnItIsTold() {
        val number = own("number")
        val renamed = (0..9).fold(number) { t, d -> t.replaceFirst("<key id=\"$d\"", "<key id=\"d$d\"") }
        for (strict in listOf(true, false)) {
            val r = check(renamed, strict)
            assertEquals(emptyList(), r.errors, "strict $strict")
            assertEquals(check(number, strict).warnings.filter { it.key != "unnamed" }, r.warnings.filter { it.key != "unnamed" }, "strict $strict")
            assertEquals(check(number, strict).minApp, r.minApp, "strict $strict")
        }
        assertRows(
            listOf(
                Triple("a key typing a", "<key id=\"ka\" kind=\"function\" label=\"a\" send=\"commit:a\" shortcut=\"my-number/ka\"/>", listOf("ka")),
                Triple("a key typing A by its code", "<key id=\"kb\" kind=\"function\" label=\"B\" send=\"code:U+0042\" shortcut=\"my-number/kb\"/>", listOf("kb")),
                Triple("a key of letters=", "<key id=\"kc\" label=\"abc\" letters=\"abc\" shortcut=\"my-number/kc\"/>", listOf("kc")),
                Triple("a key showing 1 typing 1: no letter", "<key id=\"kd\" kind=\"function\" label=\"1\" send=\"sym:KP_1\" shortcut=\"my-number/kd\"/>", emptyList()),
            ),
            { it.first },
        ) { (_, key, told) ->
            val r = check(withRow(key, number))
            assertEquals(told, r.warnings.filter { it.key == "pad-letters" }.map { it.args[0] }, r.errors.toString())
            assertEquals(check(number).minApp, r.minApp, "pad-letters asks no newer app")
        }
    }

    // what cannot be drawn, and the limits of format, identity and safety, stay errors
    @Test
    fun whatCannotBeDrawnAndTheBasicLimitsAreStillErrors() {
        val noNine = LayoutCheck.Sources(sources.layouts - "nine", sources.fragments)
        val rows: List<Triple<String, String, () -> LayoutCheck.Report>> = listOf(
            Triple("too big", "too-big", { check(qwerty + "<!--" + "x".repeat(PackLayouts.MAX_BYTES.toInt()) + "-->") }),
            Triple("does not read", "read", { check(qwerty.replace("<row split=\"halves\">", "<row split=\"halves\" bogus=\"1\">")) }),
            Triple("an icon key with no a11y=", "read", { check(withRow("<key id=\"ic\" kind=\"function\" icon=\"backspace\" send=\"lang\" shortcut=\"my-qwerty/ic\"/>")) }),
            Triple("for a newer app", "newer", { check(qwerty.replaceFirst("<defaults", "<meta min-app=\"9.0.0\"/>\n  <defaults")) }),
            Triple("an id too long", "id-length", { check(qwerty.replace("id=\"my-qwerty\"", "id=\"${"a".repeat(33)}\"")) }),
            Triple("the app's id", "id-built-in", { check(testData("qwerty.xml")) }),
            Triple("a key name prefix for an id", "id-prefix", { check(qwerty.replace("id=\"my-qwerty\"", "id=\"g18\"")) }),
            Triple("a name too long", "name-length", { check(qwerty.replace("name=\"26 键\"", "name=\"${"名".repeat(25)}\"")) }),
            Triple("a family not known", "family", { check(own("zhuyin").replace("based-on=\"zhuyin\"", "based-on=\"qwerty\"")) }),
            Triple("no based-on", "based-on", { check(qwerty.replace(" based-on=\"qwerty\"", "")) }),
            Triple("no app board to fall back to", "base", { LayoutCheck.check(own("nine"), noNine, "0.6.1") }),
            Triple("an unseen character in its name", "hidden-chars", { check(qwerty.replace("name=\"26 键\"", "name=\"26&#x202E;键\"")) }),
            Triple("an unseen character typed", "hidden-chars", { check(qwerty.replace("send=\"commit:/\"", "send=\"commit:&#x202E;\"")) }),
            Triple("a letter running a command", "command", { check(qwerty.replace("send=\"e\" shortcut=\"full/e\"", "send=\"e\" shortcut=\"backspace\"")) }),
            Triple("a name with no board before it", "name-no-slash", { check(qwerty.replace("shortcut=\"full/q\"", "shortcut=\"mine\"")) }),
            Triple("a name under the app's prefix", "name-prefix", { check(qwerty.replace("shortcut=\"full/q\"", "shortcut=\"g18/mine\"")) }),
            Triple("a fragment not the app's", "include", { check(own("k18").replace("src=\"grouped-bottom\"", "src=\"my-bottom\"")) }),
            Triple("a board the app has not", "layout-target", { check(qwerty.replace("send=\"layout:Number\"", "send=\"layout:Emoji\"")) }),
            Triple("a label too long", "label-long", { check(qwerty.replace("label=\"拼\"", "label=\"${"拼".repeat(17)}\"")) }),
            Triple("a text too long", "commit-long", { check(qwerty.replace("send=\"commit:/\"", "send=\"commit:${"x".repeat(LayoutCheck.MAX_COMMIT + 1)}\"")) }),
            Triple("31 groups, past the engine's block", "groups", { check(withRow(THIRTEEN_GROUPS, own("k18"))) }),
            Triple("not laid out on a screen", "scene", { check(qwerty.replace("width=\"1.4\" label=\".com\"", "width=\"5\" label=\".com\"")) }),
        )
        assertRows(rows, { it.first }) { (_, key, made) -> assertTrue(key in made().errors.map { it.key }, made().errors.toString()) }
    }

    // contract: 0.6.0 has shipped, so what its judge refused a pack's board for lacking changes only on purpose
    @Test
    fun what060RefusedIsItsJudgesKinds() {
        assertEquals(
            setOf(
                "backspace", "enter", "lang", "lang-no-space", "digits", "pad-digits",
                "handwriting-pad", "nine-side", "handwriting-candidates", "symbol-categories", "symbol-grid",
            ),
            LayoutLacks.REFUSED_060,
        )
        val letters = LayoutCheck.Finding(0, "lacks", listOf("letters", "q"))
        val rows = listOf(
            Triple("a letters board's letter: its reader's error", "letters" to letters, true),
            Triple("a grouped board's letter: not asked for", "grouped" to letters, false),
            Triple("not a lack", "letters" to LayoutCheck.Finding(0, "blank", listOf("letters")), false),
        )
        assertRows(rows, { it.first }) { (_, made, refused) -> assertEquals(refused, LayoutCheck.refusedBy060(made.first, made.second)) }
    }
}
