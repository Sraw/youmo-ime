/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Companion.SEPARATOR

/**
 * A 双拼 scheme: the key for each initial and each final, so that every syllable is two keys,
 * initial then final. [keys] lists them as `zh=v ai=d ...`; an initial or a one-letter final
 * not listed is typed with its own letter.
 *
 * Syllables with no initial (a, ai, er ...) are typed as [zero] says, in libime's notation:
 * each letter in it is a key that stands for "no initial" before the final's key (`o` in 微软:
 * `oj` for an), and `*` puts the final's first letter there instead, doubled for a one-letter
 * final (小鹤: `aa` a, `ah` ang). A two-letter one may also be typed as spelt (`ai`, `ou`):
 * always with `*`, otherwise wherever those two keys type no syllable exactly.
 *
 * The built-in schemes are three of libime's, with its tables, so a user's choice carries over: 0.5
 * dropped the other five (the user's 2026-11-01), a config naming one read as 小鹤 (SchemeMigration).
 */
class ShuangpinScheme(keys: String, val zero: String) {

    private val initials = HashMap<String, String>()
    private val finals = HashMap<String, String>()

    internal val zeroKeys: String = zero.replace("*", "")

    /** Whether a syllable with no initial is led by its final's first letter (`*`). */
    internal val leadVowel: Boolean = '*' in zero

    init {
        for (entry in keys.split(' ').filter { it.isNotEmpty() }) {
            val part = entry.substringBefore('=')
            val key = entry.substringAfter('=', "")
            require(key.length == 1 && isKey(key[0])) { "bad key in $entry" }
            require(part in SpellingIndex.INITIALS || part in FINALS) { "no initial or final in $entry" }
            val into = if (part in SpellingIndex.INITIALS) initials else finals
            into[part] = into[part].orEmpty() + key
        }
        require(zeroKeys.all(::isKey)) { "bad zero keys $zero" }
    }

    /** Whether [c] types something: a letter, or another key the scheme gives a part (`;` in 微软). */
    fun types(c: Char): Boolean = c in 'a'..'z' || c in zeroKeys || initials.values.any { c in it } || finals.values.any { c in it }

    /** The keys that type [init] followed by [fin], in the order a user would reach for them. */
    internal fun codes(init: String, fin: String): List<String> {
        val finalKeys = finalKeys(fin)
        val codes = ArrayList<String>()
        if (init.isNotEmpty()) {
            for (i in initialKeys(init)) for (f in finalKeys) codes += "$i$f"
            return codes
        }
        if (leadVowel) {
            val leads = if (fin.length == 1) finalKeys else finalKeys(fin.take(1))
            for (l in leads) for (f in finalKeys) codes += "$l$f"
        }
        for (z in zeroKeys) for (f in finalKeys) codes += "$z$f"
        return codes
    }

    /**
     * How [syllable] is typed, or null if it is no initial plus a final (the Latin letters, 嗯).
     * Where a scheme accepts several ways, the one it teaches.
     */
    fun encode(syllable: Int): String? {
        val (init, fin) = SpellingIndex.split(Syllables.spelling(syllable)) ?: return null
        val spelt = fin.takeIf { init.isEmpty() && it.length == 2 }
        // 小鹤 teaches an, not aj
        if (leadVowel && spelt != null) return spelt
        return codes(init, fin).firstOrNull() ?: spelt
    }

    /** Whether [key] types an initial, so that alone it is the start of a syllable and never one whole. */
    internal fun typesInitial(key: Char): Boolean =
        initials.values.any { key in it } || key.toString() in SpellingIndex.INITIALS

    /** The initials [key] types, in [SpellingIndex.INITIALS]' order: 自然码's v zh, and v itself none. */
    internal fun initialsOn(key: Char): List<String> = SpellingIndex.INITIALS.filter { key in initialKeys(it) }

    internal fun initialKeys(init: String): String {
        val mapped = initials[init].orEmpty()
        // libime reads a letter as its own initial even when it also types another one
        return if (init.length == 1 && init !in mapped) mapped + init else mapped
    }

    internal fun finalKeys(fin: String): String = finals[fin] ?: if (fin.length == 1) fin else ""

    companion object {
        private val FINALS: Set<String> =
            (0 until Syllables.count).mapNotNullTo(HashSet()) { SpellingIndex.split(Syllables.spelling(it))?.second }

        /** Keys a scheme may use: lower-case ASCII and punctuation (微软 types ing with `;`). */
        internal fun isKey(c: Char) = c.code in '!'.code..'~'.code && !c.isUpperCase() && c != SEPARATOR

        // tables from libime's shuangpindata.h
        val ZIRANMA = ShuangpinScheme(
            "zh=v ch=i sh=u ai=l an=j ang=h ao=k ei=z en=f eng=g er=r ia=w ian=m iang=d iao=c ie=x in=n ing=y " +
                "iong=s iu=q ong=s ou=b ua=w uai=y uan=r uang=d ue=t ui=v un=p uo=o ve=t v=v",
            "o*",
        )
        val MICROSOFT = ShuangpinScheme(
            "zh=v ch=i sh=u ai=l an=j ang=h ao=k ei=z en=f eng=g er=r ia=w ian=m iang=d iao=c ie=x in=n ing=; " +
                "iong=s iu=q ong=s ou=b ua=w uai=y uan=r uang=d ue=t ui=v un=p uo=o ve=v v=y",
            "o",
        )

        val XIAOHE = ShuangpinScheme(
            "zh=v ch=i sh=u ai=d an=j ang=h ao=c ei=w en=f eng=g ia=x ian=m iang=l iao=n ie=p in=b ing=k " +
                "iong=s iu=q ong=s ou=z ua=x uai=k uan=r uang=l ue=t ui=v un=y uo=o ve=t v=v",
            "*",
        )
    }
}
