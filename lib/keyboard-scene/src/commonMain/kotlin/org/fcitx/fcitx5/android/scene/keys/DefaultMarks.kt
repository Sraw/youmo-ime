/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.keys

import org.fcitx.fcitx5.android.scene.engine.MarkContent
import org.fcitx.fcitx5.android.scene.layout.BuiltInKeyData
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Include
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.KeyData
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.Row
import org.fcitx.fcitx5.android.scene.layout.Text

/**
 * The marks a layout's keys draw with no setting of the user's (C2: the theme maker's, and the app's
 * on a new install): each named key's two slots as [SlotDefaults] has them, a first as its long press's
 * ([KeyDefaults]) shown as the punctuation maps it. Keyed as MarkSettings.shortcuts is (`full/q:up`).
 */
object DefaultMarks {

    /** [layout]'s keys and its [fragments]' (by id) on [variant] (else the one its family says), their [nodes] as given (a user's layout's: [ownNodes]). */
    fun shortcuts(
        layout: Layout,
        fragments: Map<String, Fragment>,
        variant: KeyboardVariant = variantOf(layout),
        punctuation: Map<String, String> = emptyMap(),
        firstUp: Boolean = true,
        nodes: List<Pair<String, NodeFacts>> = nodes(layout, fragments),
    ): Map<String, MarkContent> {
        val marks = LinkedHashMap<String, MarkContent>()
        for ((name, facts) in nodes) {
            val id = KeyId.of(name)
            // a key of `mark="false"` (a character's, not a function's) draws no first of its defaults'
            val list = KeyDefaults.of(id, variant, facts)?.takeUnless { facts.markOff && !id.isFunction }
            for (up in listOf(true, false)) {
                marks["$name:${if (up) "up" else "down"}"] = when (val slot = SlotDefaults.of(id, up, firstUp)) {
                    SlotDefaults.Slot.First -> list?.first?.takeIf { it.text.isNotEmpty() }?.let { MarkContent.Symbol(shown(it, punctuation)) } ?: MarkContent.Empty
                    is SlotDefaults.Slot.Command -> MarkContent.Icon(SlotDefaults.icon(slot.key))
                    SlotDefaults.Slot.None -> MarkContent.Empty
                }
            }
        }
        return marks
    }

    /**
     * [shortcuts] as EnvJson's `marks` member reads them (`{"shortcuts": {"full/q:up": {"symbol": "1"}, …}}`), one key
     * a line in their order: what the theme maker passes as its environment's marks.
     */
    fun json(shortcuts: Map<String, MarkContent>): String = shortcuts.entries.joinToString(",\n", "{\"shortcuts\": {\n", "\n}}\n") { (name, mark) ->
        val value = when (mark) {
            is MarkContent.Icon -> "{\"icon\": ${quote(mark.name)}}"
            is MarkContent.Symbol -> "{\"symbol\": ${quote(mark.text)}}"
            is MarkContent.Text -> "{\"text\": ${quote(mark.text)}}"
            MarkContent.Empty -> "{}"
        }
        "  ${quote(name)}: $value"
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when {
            c == '"' || c == '\\' -> append('\\').append(c)
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> append(c)
        }
        append('"')
    }

    /** An item as a key shows it: one typed as a key as the punctuation maps it (`.` 。, `"` “), the rest as written. */
    fun shown(item: Item, punctuation: Map<String, String>): String =
        if (item.commit == Commit.AsKey) punctuation[item.text] ?: item.text else item.text

    /**
     * The variant a layout's family stands for, its default input method's. The 14 and 18 keys are told apart by the
     * app's keyboard the layout is of, [basedOn] (`k18`): a theme pack's is of its own id (PackLayouts.judge), which its
     * callers pass whatever `based-on=` it writes; a user's layout of the one it was made from,
     * its `based-on=`, whatever its own id.
     */
    fun variantOf(layout: Layout, basedOn: String = layout.basedOn ?: layout.id): KeyboardVariant = KeyboardVariant(
        when (layout.family) {
            "letters" -> KeyboardVariant.Form.Full
            "nine" -> KeyboardVariant.Form.Nine
            "grouped" -> if (basedOn == "k18") KeyboardVariant.Form.Grouped18 else KeyboardVariant.Form.Grouped14
            "zhuyin" -> KeyboardVariant.Form.ZhuyinFull
            "zhuyin-nine" -> KeyboardVariant.Form.ZhuyinNine
            "number" -> KeyboardVariant.Form.Number
            else -> KeyboardVariant.Form.Panel
        },
    )

    /** Each named key of [layout] (its fragments' too, its variants' names) with what its node tells its long press. */
    fun nodes(layout: Layout, fragments: Map<String, Fragment>): List<Pair<String, NodeFacts>> {
        val rows = layout.parts.flatMap { part ->
            when (part) {
                is Row -> listOf(part to layout.defaults)
                is Include -> fragments[part.src]?.let { f -> f.rows.map { it to f.defaults.over(layout.defaults) } }.orEmpty()
            }
        }
        var place = 0
        val out = ArrayList<Pair<String, NodeFacts>>()
        for ((row, defaults) in rows) for (cell in row.cells) {
            val key = cell as? Key ?: continue
            val a = key.attrs.over(row.defaults.over(defaults))
            val facts = NodeFacts(
                text = textOf(a.actions["send"]),
                label = (a.label as? Text.Literal)?.text,
                typing = KeyDefaults.typing(a.role, a.kind, (a.label as? Text.Literal)?.text ?: a.label?.let { "_" }, a.actions["preview"]),
                popup = key.popup,
                letters = key.letters?.group,
                layoutFirst = a.actions["swipe-up"] ?: (a.alt as? Text.Literal)?.text,
                // KeyData's where it set one (a theme's or a user's layout: the app's key of its name's), else counted
                place = if (key.letters != null) (place++).let { key.place ?: it } else -1,
                function = a.kind == "function",
                markOff = a.mark == false,
            )
            val names = listOfNotNull(a.shortcut) + key.variants.mapNotNull { it.attrs.shortcut }
            for (name in names.distinct()) out += name to facts
        }
        return out.distinctBy { it.first }
    }

    /**
     * [layout]'s [nodes] as a user's layout's keys have them:
     * a key of a name of the app's is that key of the app's, its long press and corner the app's board's node of the
     * name's ([appBoard]), whatever letters= or alt= the layout writes (LayoutCheck's `held` tells of them); a name of
     * its own, what the layout writes. [basedOn]: the board KeyData held it to, its own `based-on`. [board]
     * reads one of the app's boards, its fragments with it, by its id.
     */
    fun ownNodes(
        layout: Layout,
        fragments: Map<String, Fragment>,
        basedOn: String? = layout.basedOn,
        board: (String) -> Pair<Layout, Map<String, Fragment>>?,
    ): List<Pair<String, NodeFacts>> {
        val read = HashMap<String, Map<String, NodeFacts>>()
        return nodes(layout, fragments).map { (name, facts) ->
            val id = appBoard(name, basedOn) ?: return@map name to facts
            name to (read.getOrPut(id) { board(id)?.let { (l, f) -> nodes(l, f).toMap() }.orEmpty() }[name] ?: facts)
        }
    }

    /** The app's board key [name] is of, looked for as KeyData looks: [basedOn]'s, then [KeyData.ORDER]'s, then any other's by id; null where none has it. */
    fun appBoard(name: String, basedOn: String?): String? {
        val boards = BuiltInKeyData.BOARDS
        if (basedOn != null && boards[basedOn]?.named?.containsKey(name) == true) return basedOn
        return LOOKED_IN.firstOrNull { boards[it]?.named?.containsKey(name) == true }
    }

    // [appBoard]'s boards after the based-on, made once: asked of every node of a layout on the main thread
    private val LOOKED_IN by lazy { (KeyData.ORDER + BuiltInKeyData.BOARDS.keys.sorted()).distinct() }

    // a send= that is a character (`q`, `,`, `:`) rather than an action (`sym:BackSpace`, `action:zhuyin`)
    private fun textOf(send: String?): String? {
        if (send == null) return null
        val head = send.substringBefore(':', "")
        val action = send.length > 1 && head.isNotEmpty() && head.all { it in 'a'..'z' || it == '-' }
        return when {
            !action -> send
            head == "key" -> send.substringAfter(':')
            else -> null
        }
    }
}
