/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

/**
 * What was said with its hesitations out (dev/design/060-voice-post.md C): the filler sounds 呃
 * and 嗯 a speaker makes thinking, and a word stammered (这个这个, 我我). Only what cannot be meant:
 * 嗯嗯 (yes), 哦 and 啊 (they say something), 额 inside a word (金额, 份额), 看看 and 刚刚 (a word
 * doubled), 对对对 and 可以可以 (agreeing), 什么什么 (and so on), digits (六六六) are kept. A clause's
 * end and the next's start with a mark between are each kept (我爱你，你爱我); with no mark, the pronoun
 * is taken once (我告诉你你别去 -> 我告诉你别去): on the evaluation sets every such double in a clause was a
 * stammer (然后我我觉得, 你你到时候), none an object then a subject (11 clips worse kept, 2026-11-05).
 */
object VoiceDisfluency {

    private const val MARKS = "，。？！、；：,.?!;:"
    private const val STRONG = "？?。.！!"
    private const val GAP = "$MARKS\\s"

    // 呃 is no word's; 嗯 none's either, but 嗯嗯 is a yes. 额 and 唔 are words' (金额, 唔该): alone only, between
    // marks, ends or other fillers
    private const val FILLER = "(?<!嗯)嗯(?!嗯)|呃+|(?<=^|[${GAP}呃嗯])[额唔]+(?=$|[$GAP])"

    // the fillers of a run, with the marks and spaces about them; from the first of those only (a long run of
    // marks is not tried from each)
    private val FILLERS = Regex("(?<![$GAP])[$GAP]*(?:(?:$FILLER)[$GAP]*)+")

    /**
     * Words a speaker stammers on, starting again: the pronouns, the demonstratives, the conjunctions, the little
     * words. Not one that doubles to mean something: 看看, 刚刚 (words), 对对, 好好, 可以可以, 就是就是 (agreeing),
     * 什么什么 (and so on), 我的我的 (sorry), 这样这样 (so and so), 让让 (make way), 会会 (go and meet); nor 的, 和,
     * 从, whose doubles begin words of their own (的的确确, 和和气气, 从从容容).
     */
    private val STAMMERED = listOf(
        "这个", "那个", "然后", "但是", "因为", "所以", "如果", "还是", "可能", "其实", "而且", "我们", "你们", "他们",
        "她们", "咱们", "这些", "那些", "已经", "应该",
        "我", "你", "他", "她", "它", "这", "那", "就", "在", "也", "都", "要", "把", "被", "跟", "给", "不",
    ).sortedByDescending { it.length }

    // a word said again at once, a pause between or not
    private val STAMMER = Regex("(${STAMMERED.joinToString("|")})(?:[$MARKS]?\\1)+")
    private val MARK = Regex("[$MARKS]")

    /**
     * Words that end in one of the single characters stammered on: 现在在 is 现在 and 在, said once each. The common
     * ones; the engine's dictionary would know them all (when this goes into the app).
     */
    private val ENDING_IN = words(
        "现在 正在 存在 实在 所在 自在 好在 还在 就在 都在 也在 不在 " +
            "其他 其它 吉他 自我 忘我 迷你 刹那 成就 也就 早就 将就 迁就 本就 那就 这就 首都 成都 大都 全都 " +
            "需要 重要 主要 想要 只要 不要 还要 就要 必要 摘要 纪要 也要 都要 " +
            "一把 拖把 火把 车把 棉被 脚跟 高跟 交给 送给 供给 发给 还给 献给 带给 递给 留给 嫁给 卖给 借给 " +
            "要不 可不 并不 决不 绝不 从不 毫不 也不 都不 就不 还不"
    )

    /** And words that start in one: 要要求 is 要 and 要求. */
    private val STARTING_WITH = words(
        "要求 要素 要点 要领 要么 就业 就医 就诊 就餐 就近 被告 被子 把握 把关 把手 把戏 在于 在乎 在场 给予 也许 都市"
    )

    private fun words(spaced: String) = spaced.split(' ').filter { it.isNotEmpty() }.toSet()

    /** [text] without its hesitations. */
    fun clean(text: String): String {
        val unfilled = FILLERS.replace(text) { m -> instead(m.value, text.getOrNull(m.range.first - 1), text.getOrNull(m.range.last + 1)) }
        return STAMMER.replace(unfilled) { m ->
            val word = m.groupValues[1]
            val before = unfilled.getOrNull(m.range.first - 1)
            val after = unfilled.getOrNull(m.range.last + 1)
            when {
                // 不不 (不，不) at the end of what was said is "no, no", said so
                word == "不" && (after == null || after in MARKS) -> m.value
                // a clause's start said again (我，我觉得): once
                (before == null || before in MARKS) && !startsAWord(word, after) -> word
                // else a mark between is a clause's end and the next's start (我爱你，你爱我): each said once only
                else -> {
                    val pieces = MARK.split(m.value)
                    val marks = m.value.filter { it in MARKS }.map { "$it" } + ""
                    pieces.indices.joinToString("") { k ->
                        once(pieces[k], word, before.takeIf { k == 0 }, after.takeIf { k == pieces.lastIndex }) + marks[k]
                    }
                }
            }
        }
    }

    // what a run of fillers [run] leaves, [before] and [after] it: at the start nothing, as nothing was said before
    // them; else the marks either side as one, the strongest, but at the end only one that ends, and a pause's only
    // after the fillers is theirs (把那个嗯，报名费); a space between Latin words
    private fun instead(run: String, before: Char?, after: Char?): String {
        val pause = after != null && run.trimStart().first() in MARKS
        val mark = run.filter { it in MARKS }.takeIf { it.isNotEmpty() }?.let(::strongest)?.takeIf { it in STRONG || pause }
        return when {
            before == null -> ""
            mark != null -> mark + (if (run.last().isWhitespace() && after != null) " " else "")
            before.isLatin() && after?.isLatin() == true -> " "
            else -> ""
        }
    }

    private fun Char.isLatin() = this < '\u0080' && isLetterOrDigit()

    // [piece], [word] said once or more, as [word] once; twice where the first ends a word with what is [before] it
    // (现在在 -> 现在 在) or starts one with what is [after] it (要要求 -> 要 要求)
    private fun once(piece: String, word: String, before: Char?, after: Char?): String {
        val times = piece.length / word.length
        val keep = 1 + (if (word.length == 1 && before != null && "$before$word" in ENDING_IN) 1 else 0) +
            (if (startsAWord(word, after)) 1 else 0)
        return word.repeat(minOf(times, keep))
    }

    private fun startsAWord(word: String, after: Char?) = word.length == 1 && after != null && "$word$after" in STARTING_WITH

    // a question's, then a full stop's, then a pause's
    private fun strongest(marks: String): Char = marks.firstOrNull { it in "？?" } ?: marks.firstOrNull { it in "。.！!" } ?: marks.first()
}
