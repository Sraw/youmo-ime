/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.phrase.EmojiData

/** The engine's `emoji.data` from emoji-test.txt, CLDR's Chinese annotations and `lexicon/emoji.tsv`, Han words only. */
internal object EmojiAnnotations {

    class Annotation(val emoji: String, val keywords: List<String>, val name: String?)

    /** emoji-test.txt's emoji: the panel's [groups] ([EmojiData.GROUPS]), and each written in full by how it is written bare. */
    class Emoji(val groups: List<Pair<String, List<String>>>, val full: Map<String, String>)

    // the panel's groups; Component (skin tones alone) is none, as the panel tones an emoji itself
    private val GROUP_IDS = mapOf(
        "Smileys & Emotion" to "smileys", "People & Body" to "people", "Animals & Nature" to "nature",
        "Food & Drink" to "food", "Travel & Places" to "travel", "Activities" to "activities",
        "Objects" to "objects", "Symbols" to "symbols", "Flags" to "flags",
    )

    private val TONES = 0x1F3FB..0x1F3FF

    /** emoji-test.txt's fully qualified emoji by group; a toned emoji is in no group (the long press makes those). */
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

    /** The words of [annotations] and [edits] (`word\temoji`, `-word\temoji`, `-word`); an edit that finds nothing goes to [stale]. */
    fun words(annotations: List<Annotation>, edits: List<String>, emoji: Emoji, stale: (String) -> Unit = {}): Map<String, List<String>> {
        val named = LinkedHashMap<String, MutableList<String>>()
        val keyed = LinkedHashMap<String, MutableList<String>>()
        for (a in annotations) {
            a.name?.takeIf(::isWord)?.let { named.getOrPut(it) { ArrayList() } += a.emoji }
            for (k in a.keywords) if (isWord(k) && k.codePointCount(0, k.length) > 1) keyed.getOrPut(k) { ArrayList() } += a.emoji
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
                word.startsWith('-') && full.isEmpty() -> if (words.remove(word.substring(1)) == null) stale(line)
                word.startsWith('-') -> if (words[word.substring(1)]?.removeAll { it in full } != true) stale(line)
                else -> {
                    require(isWord(word) && full.isNotEmpty()) { "not a word and its emoji: $line" }
                    words[word] = (full + words[word].orEmpty()).toMutableList()
                }
            }
        }
        return words.mapValues { (_, e) -> e.distinct().take(EmojiData.MAX_EMOJI) }.filterValues { it.isNotEmpty() }
    }

    /** A word pinyin can give: Han characters only, by code point, as EmojiData's reader counts it. */
    private fun isWord(w: String) =
        w.length in 1..EmojiData.MAX_WORD && w.codePoints().allMatch { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }

    // an emoji as CLDR and a user write it, U+FE0F left out
    private fun bare(emoji: String) = emoji.replace("\uFE0F", "")

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
