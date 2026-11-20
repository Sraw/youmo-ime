/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import org.fcitx.fcitx5.android.engine.pinyin.SyllableGraph.Companion.SEPARATOR

/**
 * A 双拼 scheme: a key for each initial and final, so every syllable is two keys; [keys] as `zh=v ai=d ...`,
 * unlisted one-letter parts typed as themselves. [zero] (libime's notation) says how initial-less
 * syllables are typed: its letters stand for "no initial" (`o` in 微软: `oj` an), `*` puts the final's
 * first letter there (小鹤: `aa`, `ah`). The built-ins are three of libime's tables, so choices carry over.
 */
class ShuangpinScheme(keys: String, val zero: String) {

    private val initials = HashMap<String, String>()
    private val finals = HashMap<String, String>()

    internal val zeroKeys: String = zero.replace("*", "")

    /** Initial-less syllables led by the final's first letter (`*`). */
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

    /** A letter, or another key the scheme uses (`;` in 微软). */
    fun types(c: Char): Boolean = c in 'a'..'z' || c in zeroKeys || initials.values.any { c in it } || finals.values.any { c in it }

    /** In the order a user would reach for them. */
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

    /** Null if not initial plus final (Latin letters, 嗯); the scheme's taught way where several work. */
    fun encode(syllable: Int): String? {
        val (init, fin) = SpellingIndex.split(Syllables.spelling(syllable)) ?: return null
        val spelt = fin.takeIf { init.isEmpty() && it.length == 2 }
        // 小鹤 teaches an, not aj
        if (leadVowel && spelt != null) return spelt
        return codes(init, fin).firstOrNull() ?: spelt
    }

    /** Alone it starts a syllable, never is one whole. */
    internal fun typesInitial(key: Char): Boolean =
        initials.values.any { key in it } || key.toString() in SpellingIndex.INITIALS

    /** In [SpellingIndex.INITIALS]' order: 自然码's v types zh, and v itself none. */
    internal fun initialsOn(key: Char): List<String> = SpellingIndex.INITIALS.filter { key in initialKeys(it) }

    internal fun initialKeys(init: String): String {
        val mapped = initials[init].orEmpty()
        // libime reads a letter as its own initial even when it also types another
        return if (init.length == 1 && init !in mapped) mapped + init else mapped
    }

    internal fun finalKeys(fin: String): String = finals[fin] ?: if (fin.length == 1) fin else ""

    companion object {
        private val FINALS: Set<String> =
            (0 until Syllables.count).mapNotNullTo(HashSet()) { SpellingIndex.split(Syllables.spelling(it))?.second }

        /** Lower-case ASCII and punctuation (微软 types ing with `;`). */
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
