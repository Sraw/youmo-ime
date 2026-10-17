/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.phrase.EmojiData

/**
 * The engine's `emoji.data` ([EmojiData]) from its sources: Unicode's emoji-test.txt, which emoji
 * there are, how each is written in full (U+FE0F where it is text by default: ☺️, 🤦‍♂️) and the
 * panel's groups; CLDR's Chinese annotations (`common/annotations/zh.xml`, Unicode-3.0), each
 * emoji's keywords and its name (`type="tts"`); and this project's edits (`lexicon/emoji.tsv`),
 * which put the internet's words in (点赞, 狗头) and take out what CLDR has that a user would not
 * mean (加油 ⛽).
 *
 * Under a word, an emoji whose name it is comes first (笑脸 😃 before an emoji that has 笑脸 as one
 * of its keywords), then the rest as CLDR lists them; an edit's emoji come before both. Only words
 * of Han characters are kept, a keyword such as `OK` being no reading pinyin gives; a single
 * character only as an emoji's name (猫 🐈, 火 🔥) or an edit, CLDR's single-character keywords
 * (是 🫡, 你 🫵, 上 ⬆️) being the commonest characters, whose emoji no one means as often as they
 * are typed. CLDR names symbols too (`{`, `■`): only what emoji-test.txt has is kept.
 */
internal object EmojiAnnotations {

    class Annotation(val emoji: String, val keywords: List<String>, val name: String?)

    /** emoji-test.txt's emoji: the panel's [groups] ([EmojiData.GROUPS]), and each written in full by how it is written bare. */
    class Emoji(val groups: List<Pair<String, List<String>>>, val full: Map<String, String>)

    // emoji-test.txt's groups by the ids the panel knows them by; Component (the skin tones and
    // hair alone) is none: the panel puts a tone on an emoji itself
    private val GROUP_IDS = mapOf(
        "Smileys & Emotion" to "smileys", "People & Body" to "people", "Animals & Nature" to "nature",
        "Food & Drink" to "food", "Travel & Places" to "travel", "Activities" to "activities",
        "Objects" to "objects", "Symbols" to "symbols", "Flags" to "flags",
    )

    private val TONES = 0x1F3FB..0x1F3FF

    /**
     * emoji-test.txt's fully qualified emoji, by group; an emoji with a skin tone is in no group
     * (the panel's long press makes those), but is known in full too.
     */
    fun emoji(test: String): Emoji {
        val groups = LinkedHashMap<String, MutableList<String>>()
        val full = HashMap<String, String>()
        var group: String? = null
        for (line in test.lineSequence()) {
            if (line.startsWith("# group: ")) {
                val name = line.removePrefix("# group: ").trim()
                group = GROUP_IDS[name]
                require(group != null || name == "Component") { "a group emoji-test.txt has that the panel has not: $name" }
            } else if (!line.startsWith('#') && "; fully-qualified" in line) {
                val emoji = line.substringBefore(';').trim().split(' ').joinToString("") { String(Character.toChars(it.toInt(16))) }
                full[bare(emoji)] = emoji
                if (group != null && emoji.codePoints().noneMatch { it in TONES }) groups.getOrPut(group) { ArrayList() } += emoji
            }
        }
        require(groups.keys == GROUP_IDS.values.toSet()) { "emoji-test.txt lacks groups: ${GROUP_IDS.values - groups.keys}" }
        return Emoji(EmojiData.GROUPS.map { it to groups.getValue(it) }, full)
    }

    private val LINE = Regex("""<annotation cp="([^"]+)"( type="tts")?>([^<]*)</annotation>""")

    /** The annotations of CLDR's [xml] of what [emoji] has, in its order, each emoji written in full. */
    fun read(xml: String, emoji: Emoji): List<Annotation> {
        val keywords = LinkedHashMap<String, List<String>>()
        val names = HashMap<String, String>()
        for (m in LINE.findAll(xml)) {
            val cp = emoji.full[bare(unescape(m.groupValues[1]))] ?: continue
            val text = unescape(m.groupValues[3])
            if (m.groupValues[2].isEmpty()) keywords[cp] = text.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            else names[cp] = text.trim()
        }
        return keywords.map { (cp, k) -> Annotation(cp, k, names[cp]) }
    }

    /**
     * The words of [annotations] and [edits] (lexicon/emoji.tsv's lines), the edits' emoji written
     * in full as [emoji] has them: `word<TAB>emoji...` puts those first under the word,
     * `-word<TAB>emoji...` takes them out of it, `-word` alone takes the word out. `#` starts a comment.
     */
    fun words(annotations: List<Annotation>, edits: List<String>, emoji: Emoji): Map<String, List<String>> {
        val named = LinkedHashMap<String, MutableList<String>>()
        val keyed = LinkedHashMap<String, MutableList<String>>()
        for (a in annotations) {
            a.name?.takeIf(::isWord)?.let { named.getOrPut(it) { ArrayList() } += a.emoji }
            for (k in a.keywords) if (isWord(k) && k.length > 1) keyed.getOrPut(k) { ArrayList() } += a.emoji
        }
        val words = LinkedHashMap<String, MutableList<String>>()
        for ((w, e) in named) words.getOrPut(w) { ArrayList() } += e
        for ((w, e) in keyed) words.getOrPut(w) { ArrayList() } += e
        for (line in edits) {
            val text = line.substringBefore('#').trimEnd()
            if (text.isBlank()) continue
            val word = text.substringBefore('\t').trim()
            val listed = text.substringAfter('\t', "").split(' ').map { it.trim() }.filter { it.isNotEmpty() }
            val full = listed.map { requireNotNull(emoji.full[bare(it)]) { "not an emoji: $it, in $line" } }
            when {
                word.startsWith('-') && full.isEmpty() -> words.remove(word.substring(1))
                word.startsWith('-') -> words[word.substring(1)]?.removeAll { it in full }
                else -> {
                    require(isWord(word) && full.isNotEmpty()) { "not a word and its emoji: $line" }
                    words[word] = (full + words[word].orEmpty()).toMutableList()
                }
            }
        }
        return words.mapValues { (_, e) -> e.distinct().take(EmojiData.MAX_EMOJI) }.filterValues { it.isNotEmpty() }
    }

    /** A word pinyin can give: Han characters only, as long as a keyword is. */
    private fun isWord(w: String) =
        w.length in 1..EmojiData.MAX_WORD && w.all { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

    // an emoji as CLDR and a user write it, U+FE0F left out
    private fun bare(emoji: String) = emoji.replace("️", "")

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
