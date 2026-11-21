/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.glide.Glide as GlidePath
import org.fcitx.fcitx5.android.engine.glide.GlideReading
import org.fcitx.fcitx5.android.engine.handwriting.InkReading
import org.fcitx.fcitx5.android.engine.runtime.Keyboard
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.control.BlockScope
import org.fcitx.fcitx5.android.engine.learn.control.Why
import org.fcitx.fcitx5.android.engine.reading.WordReading
import org.fcitx.fcitx5.android.engine.pinyin.Touch

/** An input method engine as its host (the fcitx5 addon) sees it: [Action]s in, a [Snapshot] out. */
interface Session {
    fun apply(action: Action): Snapshot

    /** Whether [c] goes into the input now; other keys are the host's (space and digits pick) and are still sent as [Action.Key] to end the input. */
    fun reads(c: Char): Boolean

    /** Whether [c] as [Action.Key] ends the input instead of going in: not read, or not where it stands (双拼's `;`). */
    fun endsInput(c: Char): Boolean = !reads(c)

    /** Whether it takes every character itself, as English does: space and digits pick nothing. */
    val takesEveryCharacter: Boolean get() = false

    /** Whether a keypad digit picks though [takesEveryCharacter]: English on the nine keys. */
    val picksByDigit: Boolean get() = false

    /** Whether picks are learned; the host turns it off for a password or incognito field, before what is typed there. */
    var learning: Boolean

    /** At most [count] candidates from the [from]th, for a host that shows more than the page. */
    fun candidates(from: Int, count: Int): List<Choice>

    fun offers(index: Int): Set<Offer>

    /** Why the candidate at [index] is where it is: its tier, uses and code; null where the session does not say. */
    fun why(index: Int): Why? = null

    /** How the candidate's words were read, for 候选词注音; null where unknown (read by text then). */
    fun readingOf(index: Int): List<WordReading>? = null

    /** The text before the candidate at [index] if it is a character looked up (handwriting, parts, strokes): readings and its card go by it. */
    fun lookedUp(index: Int): String? = null

    /** Where a block of the candidate reaches if the session says (strokes: under them alone; a table's pinyin lookup: everywhere); null for the input method's rule. */
    fun blockScope(index: Int): BlockScope? = null

    /** What was learned changed under the input: the candidates read anew; null where nothing is shown. */
    fun reread(): Snapshot? = null

    /** Whether [reread] reads what was learned as it is now; one that does not is made anew after an undo. */
    val rereads: Boolean get() = false

    /** The field's [Action.Context.learnFlags], told a session made after the context. */
    fun fieldFlags(flags: Int) {}

    /** A key went to the app, or the cursor moved: what came before is past. */
    fun passedOn() {}

    /** The last commit's record, for a session over this one to take back. */
    val lastCommit: Commit? get() = null

    /** The app typed [text] itself after the cursor's text (empty for none, [ERASED] for a Backspace): any offer goes. The host calls [passedOn] first. */
    fun typed(text: String) {}

    /** Ends what is typed with nothing learned of what was not picked: picked words put in, the keys after them handed back raw (a table's 拼 turned off mid-lookup). */
    fun handBack(): HandedBack {
        val was = learning
        learning = false
        val keys = try {
            apply(Action.CommitRaw).commit
        } finally {
            learning = was
        }
        return HandedBack("", null, keys)
    }

    companion object {
        /**
         * What the app [typed] for a Backspace it did itself: the editor decides what goes (a code point, or a whole
         * emoji or flag), so English forgets all where a cluster may be cut (a CR may be half of "\r\n").
         */
        const val ERASED = "\b"
    }
}

/** What [Session.handBack] ends with: [picked] put in, its [record], and the raw [keys] after it. */
data class HandedBack(val picked: String, val record: Commit?, val keys: String)

/** What a long press may offer, each done by the [Action] of its name; [bit] is read by the addon (EngineOffer in androidengine_public.h). */
enum class Offer(val bit: Int) {
    FORGET(1 shl 0), PIN(1 shl 1), UNPIN(1 shl 2), BLOCK(1 shl 3),

    /** A custom phrase (or built-in) taken off the settings' list, by [Action.Unpin]. */
    DELETE_PHRASE(1 shl 4),

    /** A common word's replacement taken off its word, by [Action.Unpin]. */
    DELETE_COMMON_WORD(1 shl 5),
}

data class Choice(val text: String, val hint: String = "", val readings: List<String> = emptyList())

sealed class Action {
    /**
     * A key typed. One the engine reads goes in; any other ends the input and is the app's (not handled).
     * [glided]: typed by a glide. [at]: where across its key a grouped keyboard's code was tapped (the 14 keys: 0
     * left, 1 right; a layout's own keyboard packs across and down, [NineKeyLetters.place][org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters.place]); NaN for none.
     * [touch]: the tap and its neighbour keys, for neighbour correction; null for hardware keys, TalkBack, glides.
     */
    data class Key(val char: Char, val glided: Boolean = false, val at: Float = Float.NaN, val touch: Touch? = null) : Action()

    object Backspace : Action()

    /** The candidate at [index] on the page shown (a digit key; space is 0). */
    data class Select(val index: Int) : Action()

    /** The candidate at [index] of all (a tap on the host's list). */
    data class Pick(val index: Int) : Action()

    /** Forgets what was learned of the candidate at [index] of all, then rereads. */
    data class Forget(val index: Int) : Action()

    data class Pin(val index: Int) : Action()

    data class Unpin(val index: Int) : Action()

    /** Never offers the candidate's word again (unblockable in the settings). */
    data class Block(val index: Int) : Action()

    object NextPage : Action()
    object PreviousPage : Action()

    /** Takes syllable [index] of [Snapshot.syllables] for the first keys (nine keys); [of] is the list's [Snapshot.syllablesId], so a pick from a list replaced since takes nothing. */
    data class Syllable(val index: Int, val of: Int = ANY) : Action() {
        companion object {
            const val ANY = -1

            /** Syllable ids run 0..this-1, then wrap: 97 so Keyboard.SYLLABLES ends at U+F83F, leaving U+F840 on to grouped keyboards' codes (NineKeyLetters.CUSTOM_BLOCK, ZhuyinKeys). */
            const val IDS = 97

            const val MAX = 64
        }
    }

    /** Types [letter] as itself (nine keys: chosen from a long press); [again]: 连按 came to it (see [org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter]). */
    data class Letter(val letter: Char, val again: Boolean = false, val glided: Boolean = false) : Action()

    /** A nine-key press for English: [digit] (2 to 9), any of its letters ([upper] with Shift), or a pinned letter itself; [touch] as for [Key]. */
    data class NineKey(val digit: Char, val upper: Boolean = false, val touch: Touch? = null) : Action()

    /** Commits what was typed, as typed (Enter). */
    object CommitRaw : Action()

    /** Handwriting: what the pad read of [piece], or that it finished; sent with [Keyboard.INK] so it stays in order with keys. */
    data class Ink(val piece: Int, val reading: InkReading? = null) : Action()

    /** Glide typing: the [glide] or its [readings] as read when sent; sent with [Keyboard.GLIDE], as [Ink]. */
    data class Glide(val glide: GlidePath?, val readings: List<GlideReading>? = null) : Action()

    /** 笔画筛选: a stroke drawn while something is typed, one of [Strokes.KEYS][org.fcitx.fcitx5.android.engine.stroke.Strokes.KEYS] ([StrokeFilter][org.fcitx.fcitx5.android.engine.stroke.StrokeFilter]); sent as [Keyboard.strokeKey]. */
    data class Stroke(val stroke: Char) : Action()

    /** Puts the cursor [at] that preedit character; sessions keeping no cursor (handwriting, predictions) leave it unhandled. */
    data class Cursor(val at: Int) : Action()

    /** Moves the input cursor [by] keys; [Int.MIN_VALUE]/[Int.MAX_VALUE] to the start/end. */
    data class Step(val by: Int) : Action() {
        fun applyTo(cursor: Int, length: Int): Int = (cursor.toLong() + by).coerceIn(0, length.toLong()).toInt()

        companion object {
            val START = Step(Int.MIN_VALUE)
            val END = Step(Int.MAX_VALUE)
        }
    }

    /** The user pauses: a slice of work that may put a better reading first, while [Snapshot.refines]. */
    object Refine : Action()

    /** The table's 拼 key: pinyin lookup toggled, as [TableOptions.pinyinKey][org.fcitx.fcitx5.android.engine.table.TableOptions.pinyinKey] typed first would. */
    object LookUp : Action()

    /** Drops the input and the context (the cursor moved away). */
    object Reset : Action()

    /** A table's offered word tapped ([Snapshot.wordOffer]): word [index] of offer [id]; [before] the field's text before the cursor read as tapped. */
    data class Remember(val id: Int, val index: Int, val before: String) : Action()

    /** The notice's 撤销 of a [Remember]: [text] saved under [code] taken back with its commit. */
    data class Unremember(val text: String, val code: String) : Action()

    /** Offer [id] stayed up on the notice strip long enough to read: left untapped, it is not offered again in the field. */
    data class OfferShown(val id: Int) : Action()

    /** 联想 put away (the bar's ×): unlike [Reset], the context, the session's own space and a correction's undo stay. */
    object DropPredictions : Action()

    /**
     * As [Reset], then takes [before] (the text before the cursor) as what the input follows. [cleared]: [before] was
     * written last in a field that has since emptied (a chat's sent message). [learnFlags]: null keeps the old ones
     * (the engine's own contexts). [started]: the field restarted, its commits no longer to be taken back. [after]:
     * the text after the cursor, if read. [selected]: text is selected, as if cut. [offers]: the session may offer
     * candidates for [before] now. [said]: the editor answered, so an empty [before] is really empty.
     */
    data class Context(
        val before: String,
        val cleared: Boolean = false,
        val learnFlags: Int? = null,
        val started: Boolean = false,
        val after: String? = null,
        val selected: Boolean = false,
        val offers: Boolean = true,
        val said: Boolean = false,
    ) : Action() {
        companion object {
            /** How much text before the cursor the host reads into a context (ContextChars, ContextMemory.KEEP): the last two words and the reranker's room; so it may be the end of more text. */
            const val HOST_CHARS = 64
        }
    }
}

/**
 * The engine's state after an action.
 *
 * @property handled false when the action is the app's (backspace with nothing typed)
 * @property predicting the candidates follow what was committed (联想), reading no input
 * @property hints per candidate: what is left of its code, or its whole code where the input does not spell it
 * @property total all candidates, -1 if not yet known
 * @property first the index of the page's first candidate
 * @property labels keys picking the candidates where not the digits (a table whose codes are digits)
 * @property refines whether [Action.Refine] would do more
 * @property syllables what the first untaken keys may be (nine keys), for [Action.Syllable]
 * @property syllablesId which list [syllables] is, below [Action.Syllable.IDS]
 * @property cursor where in [preedit] the cursor is, in characters; -1 at its end
 * @property deleteBefore code points to delete before [commit]: English moves its added space after punctuation (`word .` as `word. `)
 * @property lookingUp [preedit] is a table's pinyin lookup: the host marks it (拼)
 * @property readings 候选词注音: empty, or per candidate one string per code point
 */
data class Snapshot(
    val commit: String,
    val preedit: String,
    val candidates: List<String>,
    val page: Int,
    val hasPreviousPage: Boolean,
    val hasNextPage: Boolean,
    val handled: Boolean,
    val predicting: Boolean,
    val hints: List<String> = emptyList(),
    val total: Int = candidates.size,
    val first: Int = 0,
    val actionable: Boolean = false,
    val labels: String = "",
    val refines: Boolean = false,
    val syllables: List<String> = emptyList(),
    val syllablesId: Int = 0,
    val deleteBefore: Int = 0,
    val cursor: Int = -1,
    val lookingUp: Boolean = false,
    val readings: List<List<String>> = emptyList(),
    /** A key chose from a list shown (digit, space, the syllable bar): what it took depends on that list. */
    val picked: Boolean = false,
    /** Words a table offers to remember, shown after a pause; null: none, or the last one withdrawn. */
    val wordOffer: WordOffer? = null,
    /** Which candidate space takes while [predicting] (-1 none): 空格上屏联想词 marks it as the first is marked while typing. */
    val spacePicks: Int = -1,
    /** 空格上屏联想词 is on and [predicting]: only [spacePicks] is marked, none when space types a space. */
    val spaceMarks: Boolean = false,
    /** The offered word a tap ([Action.Remember]) just committed and saved. */
    val remembered: OfferedWord? = null,
)

/** A table's words to remember, each [OfferedWord.text] under its [OfferedWord.code]; [id] tells one offer from the next. */
data class WordOffer(val id: Int, val words: List<OfferedWord>)

data class OfferedWord(val text: String, val code: String)
