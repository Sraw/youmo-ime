/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/**
 * The two module files whose state the host keeps, read and written with fcitx's INI rules
 * ([IniFile]): `conf/punctuation.conf` (`ca/punctuation/punctuation.h:36-46`) and
 * `conf/chttrans.conf`'s `EnabledIM` (`ca/chttrans/chttrans.h:46-66`). What else a file holds (the
 * hotkeys, chttrans' engine and OpenCC profiles, which the host does not read, design §2.2) is kept as
 * it is; a value at its default is written commented out, as fcitx writes it.
 */
object ModuleConfigs {
    const val PUNCTUATION = "conf/punctuation.conf"
    const val CHTTRANS = "conf/chttrans.conf"

    private const val ENABLED = "Enabled"
    private const val HALF_WIDTH = "HalfWidthPuncAfterLetterOrNumber"
    private const val PAIRED = "TypePairedPunctuationsTogether"
    private const val ENABLED_IM = "EnabledIM"
    private const val TRUE = "True"
    private const val FALSE = "False"

    /** punctuation.conf ([text] null: no file): a bool that is not `True`/`False` exactly is its default (`marshallfunction.cpp:21-32`). */
    fun punctuation(text: String?): PunctuationConfig {
        val tree = IniFile.read(text.orEmpty())
        val default = PunctuationConfig()
        return PunctuationConfig(
            enabled = flag(tree, ENABLED, default.enabled),
            halfWidthAfterLatinOrNumber = flag(tree, HALF_WIDTH, default.halfWidthAfterLatinOrNumber),
            typePairedTogether = flag(tree, PAIRED, default.typePairedTogether),
        )
    }

    /** [old] (null: no file) with [config]'s three values set. */
    fun punctuationText(old: String?, config: PunctuationConfig): String {
        val tree = IniFile.read(old.orEmpty())
        val default = PunctuationConfig()
        put(tree, ENABLED, config.enabled, default.enabled)
        put(tree, HALF_WIDTH, config.halfWidthAfterLatinOrNumber, default.halfWidthAfterLatinOrNumber)
        put(tree, PAIRED, config.typePairedTogether, default.typePairedTogether)
        return IniFile.write(tree)
    }

    /** chttrans.conf's `EnabledIM` (`EnabledIM/0`, `/1`, … till the first missing, `marshallfunction.h:70-88`). */
    fun chttrans(text: String?): Set<String> {
        val list = IniFile.read(text.orEmpty()).find(ENABLED_IM) ?: return emptySet()
        return generateSequence(0) { it + 1 }.map { list.find(it.toString()) }.takeWhile { it != null }
            .mapNotNull { it?.value }.toCollection(LinkedHashSet())
    }

    /** [old] (null: no file) with `EnabledIM` set to [enabled], the rest of the file kept. */
    fun chttransText(old: String?, enabled: Set<String>): String {
        val read = IniFile.read(old.orEmpty())
        val tree = ConfigTree()
        for (child in read.subItems) if (child.name != ENABLED_IM) copy(child, tree[child.name])
        // a list at its default (empty) is implicit: nothing written
        if (enabled.isNotEmpty()) {
            val list = tree[ENABLED_IM]
            enabled.forEachIndexed { i, im -> list[i.toString()].value = im }
        }
        return IniFile.write(tree)
    }

    private fun flag(tree: ConfigTree, key: String, default: Boolean) = when (tree.find(key)?.value) {
        TRUE -> true
        FALSE -> false
        else -> default
    }

    private fun put(tree: ConfigTree, key: String, value: Boolean, default: Boolean) {
        tree[key].also {
            it.value = if (value) TRUE else FALSE
            it.implicit = value == default
        }
    }

    private fun copy(from: ConfigTree, to: ConfigTree) {
        to.value = from.value
        to.implicit = from.implicit
        for (child in from.subItems) copy(child, to[child.name])
    }
}
