/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.skin

/** Trime themes made here: a base file, a theme built on it with `__include` and `__patch`, and the same written out. */
object TrimeFixtures {
    // a 26-key keyboard as Trime themes write one: percents across, a spacer on either side of the home row
    private val keys: String = buildList {
        "qwertyuiop".forEach { add("{click: $it}") }
        add("{width: 5}")
        "asdfghjkl".forEach { add("{click: $it}") }
        add("{width: 5}")
        add("{click: Shift_L, width: 15}")
        "zxcvbnm".forEach { add("{click: $it}") }
        add("{click: BS, width: 15}")
        add("{click: Keyboard_symbols, width: 12}")
        add("{click: Keyboard_number, width: 12}")
        add("{click: comma}")
        add("{click: space, width: 41}")
        add("{click: period}")
        add("{click: Return, width: 15}")
        add("{width: 100, height: 6}")
    }.joinToString("\n") { "      - $it" }

    val base = """
        name: Base
        author: Cy
        style:
          color_scheme: sea
          round_corner: 4
          horizontal_gap: 2
          vertical_gap: 4
          keyboards: [.default, number]
        preset_keys:
          BS: {label: del, send: BackSpace}
        preset_color_schemes:
          sea:
            name: Sea
            back_color: 0x003366
            key_back_color: 0x336699
            key_text_color: 0xFFFFFF
            dark_scheme: night
          night:
            name: Night
            back_color: 0x000000
            key_back_color: 0x222222
            key_text_color: 0xEEEEEE
        preset_keyboards:
          number:
            width: 25
            keys: [{click: '1'}, {click: '2'}]
          default:
            ascii_mode: 0
            width: 10
            height: 50
            keys:
    """.trimIndent() + "\n" + keys + "\n"

    /** Built on [base]: its own name and corner, one colour of its own and one patched. */
    val wave = """
        name: Wave
        __include: base:/
        style:
          round_corner: 8
        preset_color_schemes:
          sea:
            key_text_color: 0xFFCC00
          __patch:
            - {night/key_back_color: 0x111111}
    """.trimIndent()

    /** [wave] as one file. */
    val waveWrittenOut: String = base.replace("name: Base", "name: Wave").replace("round_corner: 4", "round_corner: 8")
        .replace("key_text_color: 0xFFFFFF", "key_text_color: 0xFFCC00").replace("key_back_color: 0x222222", "key_back_color: 0x111111")

    fun files(vararg named: Pair<String, String>): Map<String, ByteArray> = named.associate { (n, t) -> n to t.encodeToByteArray() }

    /** [main] (its id `main`) put together with [others] (by id). */
    fun compose(main: String, vararg others: Pair<String, String>): RimeCompose.Composed {
        val texts = others.toMap()
        return RimeCompose { id -> texts[id]?.let(SkinYaml::parse) }.compose(SkinYaml.parse(main.trimIndent()), "main")
    }

    fun yaml(text: String): Any? = SkinYaml.parse(text.trimIndent())
}
