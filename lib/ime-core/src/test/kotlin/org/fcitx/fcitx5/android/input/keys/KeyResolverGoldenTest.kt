/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keys

import org.fcitx.fcitx5.android.host.ChinesePunctuation
import org.fcitx.fcitx5.android.input.scene.SceneData
import org.fcitx.fcitx5.android.input.shortcut.SwipeSlots
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.keys.Commit
import org.fcitx.fcitx5.android.scene.keys.DefaultMarks
import org.fcitx.fcitx5.android.scene.keys.Item
import org.fcitx.fcitx5.android.scene.keys.KeyId
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant
import org.fcitx.fcitx5.android.scene.keys.KeyboardVariant.Form
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every named key of every built-in keyboard as the resolver makes it with nothing the user set
 * (060-keys-unify §6.1): its long press (the row, `|`, the symbols; `=` an as-is item, `#` a keypad's, `@`
 * an action's), its first and its two marks, in a few states. Written to build/keys-golden.tsv on every run;
 * a change to src/test/resources/keys-golden.tsv is reviewed line by line and its reason given in its commit.
 * A board is here once per variant that makes a different key: English's boards are pinyin's (no English special
 * case, the variant has no language), and 双拼's nine keys are pinyin's too (NINE_SP's letters on 2 to 9 are
 * STANDARD_NINE's, its `;` on no digit, and `semicolon` reaches only the 18 keys' L), so neither has rows of its own;
 * 双拼's 18 keys with `;` do (shuangpin-18-ms).
 */
class KeyResolverGoldenTest {
    private class Board(val name: String, val file: String, val variant: KeyboardVariant)

    private val boards = listOf(
        Board("pinyin-full", "qwerty", KeyboardVariant(Form.Full)),
        Board("pinyin-nine", "nine", KeyboardVariant(Form.Nine)),
        Board("pinyin-14", "k14", KeyboardVariant(Form.Grouped14)),
        Board("pinyin-18", "k18", KeyboardVariant(Form.Grouped18)),
        Board("shuangpin-18-ms", "k18", KeyboardVariant(Form.Grouped18, semicolon = true)),
        Board("zhuyin-full", "zhuyin", KeyboardVariant(Form.ZhuyinFull)),
        Board("zhuyin-nine", "zhuyin-nine", KeyboardVariant(Form.ZhuyinNine)),
        Board("handwriting", "handwriting", KeyboardVariant(Form.Handwriting)),
        Board("number", "number", KeyboardVariant(Form.Number)),
        Board("symbols", "symbols", KeyboardVariant(Form.Panel)),
        Board("emoji", "emoji", KeyboardVariant(Form.Panel)),
        Board("emoticon", "emoticon", KeyboardVariant(Form.Panel)),
        Board("emoji-search", "emoji-search", KeyboardVariant(Form.Panel)),
        Board("expanded-side", "expanded-side", KeyboardVariant(Form.Panel)),
        // a theme's own groups and corners (the theme maker's example of an 18 keys of its own)
        Board("theme-k18", "../../web/theme-maker/examples/k17-motion/layouts/k18", KeyboardVariant(Form.Grouped18)),
    )

    private val zh = ChinesePunctuation.map

    private fun states(b: Board): List<Pair<String, KeyState>> = listOf("default" to KeyState(), "zh" to KeyState(punctuation = zh)) +
        if (b.variant.form == Form.Full) listOf("shift" to KeyState(caps = true), "roots" to KeyState(roots = { "R$it" })) else emptyList()

    private fun layout(file: String): Layout =
        (LayoutReader.layout(if (file.startsWith("../")) File(file + ".xml").readText() else SceneData.read("$file.xml")) as Parsed.Ok).value

    private val fragments by lazy { mapOf("grouped-bottom" to (LayoutReader.fragment(SceneData.read("grouped-bottom.xml")) as Parsed.Ok).value) }

    private fun item(i: Item, shown: String) = shown + when (val c = i.commit) {
        Commit.AsKey -> ""
        Commit.AsIs -> "="
        is Commit.Keypad -> "#"
        is Commit.Action -> "@" + c.send.removePrefix("action:")
    }

    private fun mark(m: MarkContent) = when (m) {
        is MarkContent.Symbol -> "s:" + m.text
        is MarkContent.Icon -> "i:" + m.name
        is MarkContent.Text -> "t:" + m.text
        MarkContent.Empty -> "-"
    }

    private fun popup(p: KeyPopup?) = when (p) {
        null -> "-"
        is KeyPopup.Roots -> "roots:" + p.text
        is KeyPopup.Items -> {
            val all = p.items.indices.map { item(p.items[it], p.labels[it]) }
            val row = p.inRow.coerceAtLeast(0)
            (all.take(row) + "|" + all.drop(row)).joinToString(" ")
        }
    }

    private fun table(): String = buildString {
        append("keyid\tboard\tstate\titems\tfirst\tup\tdown\n")
        for (b in boards) {
            val nodes = DefaultMarks.nodes(layout(b.file), fragments)
            val r = KeyResolver(b.variant, KeyLists.Empty, SwipeSlots.Default, nodes)
            for ((name, _) in nodes) for ((state, s) in states(b)) {
                val k = r.resolve(KeyId.of(name), s)
                append(listOf(k.id.name, b.name, state, popup(k.popup), k.first?.let { item(it, k.firstShown.orEmpty()) } ?: "-", mark(k.upMark), mark(k.downMark)).joinToString("\t")).append('\n')
            }
        }
    }

    // contract: keys-golden.tsv, every built-in key's default as the app shows it; a change there is a user-visible one
    @Test
    fun everyKeyIsAsTheGoldenTableHasIt() {
        val made = table()
        File("build/keys-golden.tsv").writeText(made)
        val golden = javaClass.getResource("/keys-golden.tsv")?.readText()
        assertTrue("src/test/resources/keys-golden.tsv differs from build/keys-golden.tsv: review, then copy it", golden == made)
    }
}
