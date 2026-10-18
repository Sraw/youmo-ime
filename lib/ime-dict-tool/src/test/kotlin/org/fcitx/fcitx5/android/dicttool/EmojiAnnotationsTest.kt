/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.dicttool

import org.fcitx.fcitx5.android.engine.phrase.EmojiData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class EmojiAnnotationsTest {

    // emoji-test.txt as Unicode writes it: every group, a minimally qualified line, a skin tone, a component
    private val test = """
        # group: Smileys & Emotion
        1F603                                                  ; fully-qualified     # 😃 E0.6 grinning face with big eyes
        1F604                                                  ; fully-qualified     # 😄 E0.6 grinning face with smiling eyes
        263A FE0F                                              ; fully-qualified     # ☺️ E0.6 smiling face
        263A                                                   ; unqualified         # ☺ E0.6 smiling face
        2764 FE0F                                              ; fully-qualified     # ❤️ E0.6 red heart
        # group: People & Body
        1F44D                                                  ; fully-qualified     # 👍 E0.6 thumbs up
        1F44D 1F3FB                                            ; fully-qualified     # 👍🏻 E1.0 thumbs up: light skin tone
        1F926 200D 2642 FE0F                                   ; fully-qualified     # 🤦‍♂️ E4.0 man facepalming
        1F926 200D 2642                                        ; minimally-qualified # 🤦‍♂ E4.0 man facepalming
        # group: Component
        1F3FB                                                  ; component           # 🏻 E1.0 light skin tone
        # group: Animals & Nature
        1F408                                                  ; fully-qualified     # 🐈 E0.7 cat
        # group: Food & Drink
        26FD                                                   ; fully-qualified     # ⛽ E0.6 fuel pump
        # group: Travel & Places
        1F525                                                  ; fully-qualified     # 🔥 E0.6 fire
        # group: Activities
        1F389                                                  ; fully-qualified     # 🎉 E0.6 party popper
        # group: Objects
        1F4AA                                                  ; fully-qualified     # 💪 E1.0 flexed biceps
        # group: Symbols
        1F197                                                  ; fully-qualified     # 🆗 E0.6 OK button
        # group: Flags
        1F3C1                                                  ; fully-qualified     # 🏁 E0.6 chequered flag
    """.trimIndent()

    private val xml = """
        <annotations>
            <annotation cp="😃">哈哈 | 笑脸 | 是</annotation>
            <annotation cp="😃" type="tts">大笑</annotation>
            <annotation cp="😄">哈哈 | 大笑</annotation>
            <annotation cp="😄" type="tts">笑脸</annotation>
            <annotation cp="☺">微笑 | OK</annotation>
            <annotation cp="☺" type="tts">笑</annotation>
            <annotation cp="🤦‍♂">捂脸</annotation>
            <annotation cp="🐈">猫 | 宠物</annotation>
            <annotation cp="🐈" type="tts">猫</annotation>
            <annotation cp="⛽">加油 | 加油站</annotation>
            <annotation cp="■">方块</annotation>
            <annotation cp="&amp;">和</annotation>
        </annotations>
    """.trimIndent()

    private val emoji = EmojiAnnotations.emoji(test)

    @Test
    fun thePanelHasEachGroupFullyQualifiedWithNoSkinTonesNorComponents() {
        assertEquals(EmojiData.GROUPS, emoji.groups.map { it.first })
        assertEquals(listOf("😃", "😄", "☺️", "❤️"), emoji.groups[0].second)
        assertEquals(listOf("👍", "🤦‍♂️"), emoji.groups[1].second)
        assertEquals("🤦‍♂️", emoji.full["🤦‍♂"])
        assertEquals("👍🏻", emoji.full["👍🏻"])
        assertThrows(IllegalArgumentException::class.java) { EmojiAnnotations.emoji(test.replace("# group: Flags", "# group: Other")) }
        assertThrows(IllegalArgumentException::class.java) { EmojiAnnotations.emoji(test.substringBefore("# group: Flags")) }
    }

    @Test
    fun aWordsEmojiAreThoseItNamesFirstThenThoseItIsAKeywordOfOfHanWordsOnly() {
        val annotations = EmojiAnnotations.read(xml, emoji)
        // the symbols CLDR names too are none
        assertEquals(listOf("😃", "😄", "☺️", "🤦‍♂️", "🐈", "⛽"), annotations.map { it.emoji })
        val words = EmojiAnnotations.words(annotations, emptyList(), emoji)
        assertEquals(listOf("😄", "😃"), words["笑脸"])
        assertEquals(listOf("😃", "😄"), words["大笑"])
        assertEquals(listOf("😃", "😄"), words["哈哈"])
        assertEquals(listOf("🤦‍♂️"), words["捂脸"])
        // a single character as a name, not as a keyword
        assertEquals(listOf("☺️"), words["笑"])
        assertEquals(listOf("🐈"), words["猫"])
        assertFalse("是" in words)
        assertFalse("OK" in words)
    }

    @Test
    fun aWordsCharactersAreCountedByCodePoint() {
        // 𠮷 is one character in two chars: alone it is no keyword, beside another it is a word
        val annotations = EmojiAnnotations.read("""<annotation cp="🐈">𠮷 | 𠮷猫</annotation>""", emoji)
        val words = EmojiAnnotations.words(annotations, emptyList(), emoji)
        assertFalse("𠮷" in words)
        assertEquals(listOf("🐈"), words["𠮷猫"])
    }

    @Test
    fun theEditsGoFirstTakeOutAndAddWords() {
        val annotations = EmojiAnnotations.read(xml, emoji)
        val edits = listOf(
            "# a comment", "", "加油\t💪", "-加油\t⛽", "-加油站", "点赞\t👍  # the internet's", "哈哈\t☺ 😄", "-大笑\t😃 😄",
        )
        val stale = ArrayList<String>()
        val words = EmojiAnnotations.words(annotations, edits + listOf("-加油站", "-猫\t⛽"), emoji, stale::add)
        // taken out already, and an emoji the word has not: each told of
        assertEquals(listOf("-加油站", "-猫\t⛽"), stale)
        assertEquals(listOf("💪"), words["加油"])
        assertFalse("加油站" in words)
        assertEquals(listOf("👍"), words["点赞"])
        // written in full, once
        assertEquals(listOf("☺️", "😄", "😃"), words["哈哈"])
        // all taken out: no word
        assertFalse("大笑" in words)
        assertThrows(IllegalArgumentException::class.java) { EmojiAnnotations.words(annotations, listOf("方块\t■"), emoji) }
        assertThrows(IllegalArgumentException::class.java) { EmojiAnnotations.words(annotations, listOf("OK\t🆗"), emoji) }
    }
}
