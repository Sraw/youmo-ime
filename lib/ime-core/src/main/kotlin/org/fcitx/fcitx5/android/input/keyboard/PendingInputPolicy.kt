/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

/**
 * What happens to half-typed input when a key does something other than typing -- commits a
 * symbol, starts quick phrase or unicode input. fcitx is reset afterwards either way; this
 * decides what the user keeps first.
 */
object PendingInputPolicy {

    enum class Resolution {
        /** Take the first candidate, as Space would. */
        SelectFirstCandidate,

        /** Keep the preedit in the editor exactly as typed. */
        FinishComposing,

        /** The input method commits what was typed, its letters (Enter's), not a candidate. */
        CommitAsTyped,

        /** Nothing to keep. */
        None,
    }

    /**
     * Chinese input methods turn their preedit (pinyin, say) into the first candidate: the raw
     * preedit is rarely what anyone wants in the text. With no preedit the candidates on offer
     * are predictions, which are not taken. Other languages type the text itself as the
     * preedit, so it is kept as is.
     *
     * In an [address] field (a URL's, an email's) what is typed is what is meant: `xiaoming` then
     * `@` is xiaoming@, not 小明@. A candidate is still there to pick before the key.
     *
     * @param hasPreedit whether there is a preedit, in the editor or on fcitx's input panel
     */
    fun resolve(languageCode: String, hasPreedit: Boolean, address: Boolean = false): Resolution = when {
        !languageCode.startsWith("zh") -> Resolution.FinishComposing
        hasPreedit && address -> Resolution.CommitAsTyped
        hasPreedit -> Resolution.SelectFirstCandidate
        else -> Resolution.None
    }

    /**
     * Whether [key], the character a key sends the engine, is put in as it is in an [address] field,
     * as the address keys' are, what is typed before it committed as typed ([resolve]): through the
     * engine a digit picks a candidate and `_` is ——, and xiaoming2026@ came out 小明026@. A letter
     * is typed as ever, a space too, and so is a character the input method [reads] as a key of its
     * own (`Engines.readsAsKey`: the nine keys' digits, 微软双拼's `;`, the pinyins' `'` and a table's
     * keys, 二笔's `;`, while something is typed), and those the nine keys send for a syllable or a
     * letter.
     */
    fun typesAsIs(key: String, address: Boolean, reads: (Int) -> Boolean = { false }): Boolean {
        if (!address || key.codePointCount(0, key.length) != 1) return false
        val c = key.codePointAt(0)
        return when {
            Character.isLetter(c) || Character.isWhitespace(c) || Character.isISOControl(c) -> false
            Character.getType(c) == Character.PRIVATE_USE.toInt() -> false
            else -> !reads(c)
        }
    }

    /**
     * Whether the editor keeps the preedit when the keyboard goes (hidden, the screen turned, another
     * app): other languages' is their text; a Chinese one is pinyin, and kept it left `ni hao` in
     * the text. Its first candidate cannot take its place: it comes from fcitx's thread, after the
     * field may have gone, into another app's field even. So it goes, as typed into nothing.
     */
    fun keepsPreeditOnLeaving(languageCode: String): Boolean = !languageCode.startsWith("zh")

    /**
     * Whether the [preedit] the input method shows in the editor is taken out as it leaves
     * ([keepsPreeditOnLeaving]): an empty composing text takes the place of the editor's, or where
     * it holds none, of the selection. So not over text [selected] where the editor's last report
     * held no composing text ([editorComposes]): WebView's ends as the user selects, and Back then
     * took the word selected.
     */
    fun takesPreeditOut(languageCode: String, preedit: Boolean, selected: Boolean, editorComposes: Boolean): Boolean = when {
        !preedit || keepsPreeditOnLeaving(languageCode) -> false
        else -> !selected || editorComposes
    }
}
