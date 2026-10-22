/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.user

import java.io.IOException
import java.nio.Buffer
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Other input methods' word lists, as the user dictionary imports them (U-101): a line of text
 * ([frequency] of one), and Sogou's cell dictionaries (.scel, [Scel]) read to such lines.
 */
object ImportFiles {
    // as WordLists.line reads numbers: 3D and NaN are words
    private val NUMBER = Regex("-?\\d+(\\.\\d+)?")
    private val SPACES = Regex("\\s+")

    /**
     * The count a list gives the word of [line] (`词 pin'yin 12`, `pin'yin 词 12`, libime's
     * `词\tpin'yin\t0`), its last number; null for none. Only orders the imported words among
     * themselves (D24), so a cost or a log probability read as a count does no harm beyond that.
     * Never the first field: a word of digits (`520 wu'er'ling`) is no count of itself.
     */
    fun frequency(line: String): Double? =
        line.trim().split(SPACES).drop(1).lastOrNull { NUMBER.matches(it) }?.toDoubleOrNull()

    /** A word of a cell dictionary: [text] read [pinyin] (syllables apart by `'`), with the count it gives, if any. */
    data class Word(val text: String, val pinyin: String, val frequency: Int?) {
        /** As a line of a word list: `词 pin'yin 12`. */
        fun line() = if (frequency == null) "$text $pinyin" else "$text $pinyin $frequency"
    }

    /**
     * Sogou's cell dictionary (.scel), as fcitx's scel2org5 reads it: its own table of syllables
     * (Sogou's default if empty), then groups of words of one reading, each word with a few bytes
     * of its own whose first two are its count; then, in newer files, phrases of 17 bytes of
     * information each. The deleted words' table is not read. A word of a syllable it does not
     * have, or of no reading, is left out.
     */
    object Scel {
        private val HEADERS = listOf(
            byteArrayOf(0x40, 0x15, 0, 0, 0x44, 0x43, 0x53, 0x01, 0x01, 0, 0, 0),
            byteArrayOf(0x40, 0x15, 0, 0, 0x45, 0x43, 0x53, 0x01, 0x01, 0, 0, 0),
            byteArrayOf(0x40, 0x15, 0, 0, 0xd2.toByte(), 0x6d, 0x53, 0x01, 0x01, 0, 0, 0),
        )
        private const val PHRASE_OFFSET = 0x5C
        private const val ENTRY_OFFSET = 0x120
        private const val PINYIN_OFFSET = 0x1540
        private const val PHRASE_INFO = 17

        /** Words a file gives at most: Sogou's biggest have some hundred thousand; past this it is no cell dictionary. */
        const val MAX_WORDS = 1_000_000

        // syllables a file's table has at most: pinyin's are some 420
        private const val MAX_SYLLABLES = 4096

        /** Whether [head] begins as a cell dictionary does. */
        fun isScel(head: ByteArray) = HEADERS.any { h -> head.size >= h.size && h.indices.all { head[it] == h[it] } }

        /**
         * The words of [bytes], a whole .scel file, in its order, to [each].
         *
         * @throws IOException if it is no cell dictionary, ends where a word should be, or has
         *   more than [MAX_WORDS] words or [MAX_SYLLABLES] syllables
         */
        @Throws(IOException::class)
        fun read(bytes: ByteArray, each: (Word) -> Unit) {
            if (!isScel(bytes)) throw IOException("not a .scel file")
            try {
                read(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN), each)
            } catch (e: BufferUnderflowException) {
                throw IOException("a .scel file cut short", e)
            }
        }

        // every read is relative or checked: a file cut short, or not as it should be, underflows
        private fun read(buffer: ByteBuffer, sink: (Word) -> Unit) {
            buffer.seek(PHRASE_OFFSET)
            // counts unsigned: one of 2^31 or more is a file cut short, not one of no words
            val phrases = buffer.u32()
            val phraseOffset = buffer.int
            buffer.seek(ENTRY_OFFSET)
            val entries = buffer.u32()
            buffer.seek(PINYIN_OFFSET)
            val syllables = syllables(buffer)
            var taken = 0
            val each = { word: Word ->
                if (++taken > MAX_WORDS) throw IOException("more than $MAX_WORDS words: no cell dictionary")
                sink(word)
            }
            var entriesLeft = entries
            while (entriesLeft-- > 0) {
                val words = buffer.u16()
                val reading = pinyin(buffer.indices(), syllables)
                repeat(words) {
                    val text = buffer.string()
                    val extra = buffer.u16()
                    val after = buffer.position() + extra
                    val frequency = if (extra >= 2) buffer.u16() else null
                    buffer.seek(after)
                    if (reading != null && text.isNotEmpty()) each(Word(text, reading, frequency))
                }
            }
            if (phrases > 0) buffer.seek(phraseOffset)
            var phrasesLeft = phrases
            while (phrasesLeft-- > 0) {
                val info = ByteArray(PHRASE_INFO).also { buffer.get(it) }
                if (info[2].toInt() == 1) {
                    val reading = pinyin(buffer.indices(), syllables)
                    val text = buffer.string()
                    if (reading != null && text.isNotEmpty()) each(Word(text, reading, null))
                } else {
                    // a code of some other kind, and its word: not pinyin's
                    buffer.skip(buffer.u16())
                    buffer.skip(buffer.u16())
                }
            }
        }

        // the file's table of syllables by index: Sogou's own if it has none
        private fun syllables(buffer: ByteBuffer): List<String> {
            val count = buffer.u32()
            if (count > MAX_SYLLABLES) throw IOException("$count syllables: no cell dictionary")
            val out = ArrayList<String>()
            repeat(count.toInt()) {
                buffer.u16() // its index, in order
                out += buffer.string().let { if (it == "lue" || it == "nue") it.replace("ue", "ve") else it }
            }
            return out.ifEmpty { DEFAULT_SYLLABLES }
        }

        // a reading of [indices] in [syllables], `'` apart; null for one it has not, or none
        private fun pinyin(indices: IntArray, syllables: List<String>): String? {
            if (indices.isEmpty() || indices.any { it !in syllables.indices }) return null
            return indices.joinToString("'") { syllables[it] }
        }

        private fun ByteBuffer.u16() = short.toInt() and 0xffff

        private fun ByteBuffer.u32() = int.toLong() and 0xffffffffL

        private fun ByteBuffer.skip(n: Int) = seek(position() + n)

        // Buffer's: ByteBuffer.position(int) returning ByteBuffer is Java 9's, not Android 6's. Past the end, as a read past it
        private fun ByteBuffer.seek(at: Int) {
            if (at !in 0..limit()) throw BufferUnderflowException()
            (this as Buffer).position(at)
        }

        // a length in bytes, then as many 16-bit indices
        private fun ByteBuffer.indices(): IntArray {
            val size = u16()
            // half a unit: no file of Sogou's, read as one cut short
            if (size % 2 != 0) throw BufferUnderflowException()
            return IntArray(size / 2) { u16() }
        }

        // a length in bytes, then UTF-16LE, ended early by a 0
        private fun ByteBuffer.string(): String {
            val units = indices()
            val end = units.indexOf(0).let { if (it < 0) units.size else it }
            return String(CharArray(end) { units[it].toChar() })
        }

        /** Sogou's syllables by index, for a file whose table is empty: scel2org5's guess. */
        private val DEFAULT_SYLLABLES: List<String> = (
            "a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu ca cai can cang cao ce cen ceng cha " +
                "chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan " +
                "cui cun cuo da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo e ei en " +
                "eng er fa fan fang fei fen feng fiao fo fou fu ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang " +
                "gui gun guo ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo ji jia jian jiang jiao " +
                "jie jin jing jiong jiu ju juan jue jun ka kai kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun " +
                "kuo la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lve lun luo lv ma " +
                "mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu na nai nan nang nao ne nei nen neng ni " +
                "nian niang niao nie nin ning niu nong nou nu nuan nve nun nuo nv o ou pa pai pan pang pao pei pen peng pi pian " +
                "piao pie pin ping po pou pu qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun ran rang rao re ren reng " +
                "ri rong rou ru rua ruan rui run ruo sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng " +
                "shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo ta tai tan tang tao te tei teng " +
                "ti tian tiao tie ting tong tou tu tuan tui tun tuo wa wai wan wang wei wen weng wo wu xi xia xian xiang xiao xie xin " +
                "xing xiong xiu xu xuan xue xun ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun za zai zan zang zao ze zei " +
                "zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi " +
                "zong zou zu zuan zui zun zuo A B C D E F G H I J K L M N O P Q R S T U V W X Y Z 0 1 2 3 4 5 6 7 8 9"
            ).split(' ')
    }
}
