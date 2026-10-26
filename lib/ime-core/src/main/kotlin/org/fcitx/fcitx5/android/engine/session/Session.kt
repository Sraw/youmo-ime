/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.session

import org.fcitx.fcitx5.android.engine.host.Keyboard
import org.fcitx.fcitx5.android.engine.learn.Kinds
import org.fcitx.fcitx5.android.engine.learn.LearnRecord.Commit
import org.fcitx.fcitx5.android.engine.learn.control.Why

/**
 * An input method engine as its host sees it: actions in, what to show and commit out. The host
 * (the fcitx5 addon) turns key events into [Action]s and renders each [Snapshot]; the engine
 * keeps everything else.
 */
interface Session {
    fun apply(action: Action): Snapshot

    /**
     * Whether [c] goes into the input now. Other keys are the host's to act on: space and digits
     * pick; the rest are sent as [Action.Key] all the same, to end the input, then typed.
     */
    fun reads(c: Char): Boolean

    /**
     * Whether it takes every character itself, as English does: space ends a word and digits are
     * typed, neither picks; an arrow commits what is typed; a key gone to the app resets it.
     */
    val takesEveryCharacter: Boolean get() = false

    /**
     * Whether a keypad's digit picks the candidate it numbers now, though the session
     * [takesEveryCharacter]: English typed on the nine keys, whose digits are no text of it.
     */
    val picksByDigit: Boolean get() = false

    /**
     * Whether what the user picks is learned. The host turns it off where the app asks it not to
     * (a password, an incognito tab), before the actions typed there.
     */
    var learning: Boolean

    /**
     * The candidates from the [from]th of all, at most [count]: for a host that shows more than
     * the page, such as fcitx's candidate bar scrolled sideways.
     */
    fun candidates(from: Int, count: Int): List<Choice>

    /** What a long press on the candidate at [index] of all offers, where [Snapshot.actionable]. */
    fun offers(index: Int): Set<Offer>

    /**
     * Why the candidate at [index] of all is where it is (U-81): its tier, its uses, the code it was
     * ranked under (a prediction's: [Kinds.PREDICTION] after the word before). Null where the
     * session does not say.
     */
    fun why(index: Int): Why? = null

    /**
     * What was learned changed under the input (a long press taken back, a clear): the candidates
     * read anew, to be shown ([Keyboard.AGAIN]); null where nothing is on show to read again.
     */
    fun reread(): Snapshot? = null

    /** Whether [reread] reads what was learned as it is now; a session that does not is made anew after an undo. */
    val rereads: Boolean get() = false

    /**
     * What learning leaves out in the field ([Action.Context.learnFlags]), told a session made after
     * the field's context: nothing else of the field, whose text it does not know (review-int S5).
     */
    fun fieldFlags(flags: Int) {}

    /** A key went to the app, not here (Enter, an arrow with nothing typed), or the cursor moved: what came before it is past. */
    fun passedOn() {}

    /** The learning record of what was committed last, null for none: for a session over this one to take back (a table's lookup, U-03). */
    val lastCommit: Commit? get() = null

    /**
     * The app typed [text] itself, right after what is before the cursor (a key where English
     * suggests nothing; empty for none, [ERASED] for a Backspace): any offer goes, what was before
     * stays. The host passes it on first ([passedOn]).
     */
    fun typed(text: String) {}

    companion object {
        /**
         * What the app [typed] for a Backspace it did itself: what the editor's Backspace takes
         * before the cursor gone (in an opted-in editor one code point; else a key event, what the
         * editor decides, perhaps an emoji sequence or a flag whole), or what was selected.
         * English takes one code point off what it knows and forgets all of it where the code
         * point taken or the char left may belong to a cluster (a CR taken or left: perhaps a
         * "\r\n"'s half) (EnglishSession.erased); for a selection the service reads the context
         * right after (handleBackspaceKey).
         */
        const val ERASED = "\b"
    }
}

/**
 * What a long press on a candidate may offer, each done by the [Action] of its name; [bit] is its
 * bit in the offers the androidengine addon reads (EngineOffer in androidengine_public.h).
 */
enum class Offer(val bit: Int) {
    FORGET(1 shl 0), PIN(1 shl 1), UNPIN(1 shl 2), BLOCK(1 shl 3),

    /** A custom phrase (a built-in date too) taken off the settings' list, by [Action.Unpin]: no word to unpin is one. */
    DELETE_PHRASE(1 shl 4),
}

/** A candidate as a host lists it, with its [hint] (see [Snapshot.hints]). */
data class Choice(val text: String, val hint: String = "")

/** What a user does. */
sealed class Action {
    /**
     * A key typed. One the engine [reads][Session.reads] goes into the input; any other ends it,
     * committed as the engine would have it, and is the app's (the snapshot is not
     * [handled][Snapshot.handled]): what comes after it does not run on from what came before.
     * [glided]: typed by a glide, no key pressed (learning files it by its pinyin). [at]: where
     * across its key a grouped keyboard's code was tapped, 0 the left edge and 1 the right
     * ([Keyboard.tapKey][org.fcitx.fcitx5.android.engine.host.Keyboard.tapKey]); NaN for none.
     */
    data class Key(val char: Char, val glided: Boolean = false, val at: Float = Float.NaN) : Action()

    object Backspace : Action()

    /** The candidate at [index] on the page shown (a digit key; space is 0). */
    data class Select(val index: Int) : Action()

    /** The candidate at [index] of all, whatever page is shown (a tap on the host's list). */
    data class Pick(val index: Int) : Action()

    /**
     * Forgets what was learned of the candidate at [index] of all (a long press on it), then
     * reads the input again: see [Session.offers].
     */
    data class Forget(val index: Int) : Action()

    /** Makes the candidate at [index] of all a custom phrase of the input, the first of them. */
    data class Pin(val index: Int) : Action()

    /** Deletes the custom phrase the candidate at [index] of all is. */
    data class Unpin(val index: Int) : Action()

    /** Never offers again the word the candidate at [index] of all is (the user can unblock it in the settings). */
    data class Block(val index: Int) : Action()

    object NextPage : Action()
    object PreviousPage : Action()

    /**
     * Takes the syllable at [index] of [Snapshot.syllables] for the first keys it was offered
     * for: on the nine keys, where those keys type several (`64` ni, mi, o …). [of] is the
     * [Snapshot.syllablesId] of the list it was picked from, [ANY] for whichever is offered: a
     * pick from a list a key typed since replaced is no pick, or it would take another syllable.
     */
    data class Syllable(val index: Int, val of: Int = ANY) : Action() {
        companion object {
            const val ANY = -1

            /**
             * Ids of the syllables offered run from 0 to this, less one, then again: 99 so the
             * private use area's last 64 are left over (Keyboard.SYLLABLES), the grouped keyboards' codes there.
             */
            const val IDS = 99

            /** At most this many syllables are offered. */
            const val MAX = 64
        }
    }

    /**
     * Types [letter] as itself, not as any letter of its key: on the nine keys, a letter chosen
     * from a key's long press. [again]: the key pressed again soon (连按), [letter] the one the run
     * came to; after the same key not yet picked a press of its own, read as a key or with the run
     * as the letter, the context to pick (see [org.fcitx.fcitx5.android.engine.pinyin.T9Segmenter]).
     * [glided] as for [Key].
     */
    data class Letter(val letter: Char, val again: Boolean = false, val glided: Boolean = false) : Action()

    /**
     * A key of the nine keys typed for English: [digit] (2 to 9), any of its letters, the session
     * spelling the word ([upper]: that letter a capital, Shift on as it was pressed); or a letter
     * a long press pinned there, itself, the word still the nine keys' (never corrected).
     */
    data class NineKey(val digit: Char, val upper: Boolean = false) : Action()

    /** Commits what was typed, as typed (enter). */
    object CommitRaw : Action()

    /**
     * Handwriting: shows what the pad last read (HandwritingSession.offer). Sent as a key, so that
     * it comes in order with the keys and taps around it; nothing to any other session.
     */
    object Ink : Action()

    /**
     * Glide typing: the keyboard's last glide read, as offered to the session (EnglishSession.offerGlide
     * commits its word, GlideSession, a pinyin's or a table's, types its reading). Sent as a key, as [Ink]; nothing to any
     * other session.
     */
    object Glide : Action()

    /**
     * Puts the cursor [at] that character of the preedit (a tap in the composed text, where the
     * app shows it): what is typed next goes there, and backspace deletes before it. English,
     * pinyin (the nine keys too), the tables and the stroke lookup keep a cursor in their input;
     * elsewhere (handwriting, words offered) the snapshot is not [handled][Snapshot.handled].
     */
    data class Cursor(val at: Int) : Action()

    /**
     * Moves the cursor in the input [by] keys, back where negative (an arrow, a swipe on space);
     * [Int.MIN_VALUE] and [Int.MAX_VALUE] to its start and end (Home, End). Where no cursor is
     * kept, as for [Cursor].
     */
    data class Step(val by: Int) : Action() {
        /** Where a cursor at [cursor] in an input [length] keys long goes: no further than its ends, [START] and [END] too. */
        fun applyTo(cursor: Int, length: Int): Int = (cursor.toLong() + by).coerceIn(0, length.toLong()).toInt()

        companion object {
            val START = Step(Int.MIN_VALUE)
            val END = Step(Int.MAX_VALUE)
        }
    }

    /**
     * The user pauses: some of the work that may put a better reading first, where the
     * snapshot [refines][Snapshot.refines]. Any other action drops what is left of it.
     */
    object Refine : Action()

    /** Drops everything: the input, and what was committed as context (the cursor moved away). */
    object Reset : Action()

    /**
     * Drops everything as [Reset] does, then takes [before], the text before the cursor, as what
     * the input to come follows: what the host read off the editor, where the engine committed
     * nothing yet (a field focused, the cursor put somewhere). [cleared]: [before] is what was
     * written last in a field that has since cleared itself (a chat's box once a message is sent),
     * which what is typed next follows, but the field is empty: nothing is before the cursor.
     * [learnFlags]: what learning leaves out in the field (the host's, as told with it); null, as
     * the engine's own contexts have it (a glide's word held back, a lookup committed), leaves them
     * as they were (review-1a-r2 S1). [started]: the field was (re)started, its commits no longer
     * this session's to take back (U-03); [after], the text after the cursor if the host read it,
     * for an edit put back among them (design-1c §2). [selected]: text is selected, [before] what
     * is before it and [after] what is after it, as if it were cut: nothing deleted yet (review-1c M2).
     * [offers]: the session may offer candidates for [before] now (English's word typed in place of
     * its correction); not where it is not the input method shown, which nothing would reset.
     * [said]: the editor itself answered [before], so an empty one is a field really empty; false
     * where it would not say (null) or nothing was read: an empty [before] then is not known (review-1c M1).
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
            /**
             * As much of the text before the cursor as the host reads into a context, and keeps of
             * what it wrote where the field has none (the service's ContextChars, ContextMemory.KEEP):
             * the last two words, and what the pinyin reranker has room for. A context as long may
             * be the end of more text, no field's start.
             */
            const val HOST_CHARS = 64
        }
    }
}

/**
 * The engine's state after an action.
 *
 * @property commit text to put into the app now, or empty
 * @property preedit what is being typed, as the engine reads it
 * @property candidates the page shown
 * @property handled false when the action is the app's to act on (backspace with nothing typed)
 * @property predicting the candidates follow what was committed, and read no input (联想)
 * @property hints shown beside each candidate, empty or one per candidate: what is left of its
 *   code to type, or its whole code when the input does not spell it out (a wildcard in a
 *   table's code, or a table's pinyin lookup)
 * @property total how many candidates there are in all, -1 if not yet known
 * @property first the index among all of the first candidate of the page shown
 * @property actionable whether a long press on a candidate may offer something: see
 *   [Session.offers]
 * @property labels the keys picking the candidates shown, in order, where they are not the
 *   digits (letters such as `qwertyuiop` where a table's codes are digits); empty for the digits
 * @property refines whether [Action.Refine] would do more: the host sends it while the user
 *   pauses, till this is false
 * @property syllables what the first keys not yet taken as a syllable may be, likeliest first,
 *   a letter for an initial last: on the nine keys, for [Action.Syllable]; empty elsewhere
 * @property syllablesId which list [syllables] is: another for each change of it, below
 *   [Action.Syllable.IDS]
 * @property cursor where in [preedit] the cursor is, in characters; -1 at its end
 * @property deleteBefore how many code points before the cursor go first (the app deletes in code points), before [commit]: English
 *   puts a space it added after a word behind the punctuation typed next (`word .` as `word. `)
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
)
