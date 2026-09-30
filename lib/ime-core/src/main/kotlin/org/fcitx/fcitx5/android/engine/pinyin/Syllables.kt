/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.pinyin

import java.util.zip.CRC32

/**
 * Every syllable the pinyin dictionary may use. A syllable's id is its index here, and compiled
 * dictionaries store those ids, so this list is append-only: reordering or removing an entry
 * would silently re-spell every word in an existing data file. Data files record how many
 * syllables they were built with and the [checksum] of those; [matches] accepts a file built
 * before later syllables were appended, and rejects one whose ids mean something else.
 *
 * `v` stands for ü (lv, nve). The upper-case letters are spelt-out Latin letters, as in A股 or
 * T恤; `r` is the rhotic 儿 of words like 这儿; `m`, `n`, `ng` are the interjections 呣, 嗯.
 * The set is what libime's dictionary uses (dict-20260703), which includes a few rare syllables
 * such as fiao, kei and bong.
 */
object Syllables {

    private const val SPELLINGS =
        "a ai an ang ao ba bai ban bang bao bei ben beng bi bian biang biao bie bin bing bo bong bu " +
            "ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou " +
            "chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo da dai dan dang " +
            "dao de dei den deng di dia dian diao die din ding diu dong dou du duan dui dun duo e ei en " +
            "eng er fa fan fang fei fen feng fiao fo fou fu ga gai gan gang gao ge gei gen geng gong gou " +
            "gu gua guai guan guang gui gun guo ha hai han hang hao he hei hen heng hong hou hu hua huai " +
            "huan huang hui hun huo ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun ka kai " +
            "kan kang kao ke kei ken keng kong kou ku kua kuai kuan kuang kui kun kuo la lai lan lang lao " +
            "le lei leng li lia lian liang liao lie lin ling liu lo long lou lu luan lun luo lv lve m ma " +
            "mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu n na nai nan nang " +
            "nao ne nei nen neng ng ni nia nian niang niao nie nin ning niu nong nou nu nuan nun nuo nv " +
            "nve o ou pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu qi qia qian " +
            "qiang qiao qie qin qing qiong qiu qu quan que qun r ran rang rao re ren reng ri rong rou ru " +
            "rua ruan rui run ruo sa sai san sang sao se sen seng sha shai shan shang shao she shei shen " +
            "sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo ta " +
            "tai tan tang tao te tei teng ti tian tiao tie ting tong tou tu tuan tui tun tuo wa wai wan " +
            "wang wei wen weng wo wong wu xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun " +
            "ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun za zai zan zang zao ze zei zen " +
            "zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan " +
            "zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo " +
            "A B C D E F G H I J K L M N O P Q R S T U V W X Y Z"

    private val spellings: List<String> = SPELLINGS.split(' ')

    private val ids: Map<String, Int> = spellings.withIndex().associate { (id, s) -> s to id }

    val count: Int get() = spellings.size

    /** CRC32 of the first [count] spellings; changes whenever their ids do. */
    fun checksum(count: Int = this.count): Long =
        CRC32().apply { update(spellings.subList(0, count).joinToString(" ").toByteArray(Charsets.UTF_8)) }.value

    /** Whether data built with the first [count] syllables, summed to [checksum], uses this table's ids. */
    fun matches(count: Int, checksum: Long): Boolean = count in 1..this.count && checksum(count) == checksum

    fun spelling(id: Int): String = spellings[id]

    /** @return the id of [spelling], or -1 if it is not a syllable */
    fun id(spelling: String): Int = ids[spelling] ?: -1
}
