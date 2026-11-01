/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.address

/**
 * When what an address field was left with is learned ([AddressBook.left]): as another field
 * starts, and only if the user changed its text. The keyboard hidden and shown again, the screen
 * turned or the theme changed (the bar made anew), the app restarting the same field: the same
 * field, its text as it first started still what a change is told by. The same field is one of the
 * same name with the text it was last seen with: the fields a Compose screen or a web page holds
 * share their view's name, so one of them gone to is another field, its text another (a prefilled
 * 备用邮箱 after 邮箱, an empty 抄送 after 收件人). A field's whole text as read, null where more
 * than was read ([seen]); an address the app filled in, or a selection the field started with, is
 * not the user's.
 *
 * The addresses forgotten in the settings ([cleared] changed since the field started): what it held
 * then is not learned again, as if the field had started with it.
 *
 * Nothing is kept of a field that is not learned from (无痕, a password's, before the first
 * unlock, the habits off): its text is not even held here. Kept by the service, not the bar, so a
 * bar made anew does not forget it.
 */
class AddressLearning {

    /** A field left to be learned from: its kind, its text, and the text it [started] with (the app's, not learned). */
    data class Left(val field: AddressField, val text: String, val started: String = "")

    // the field started last, as ContextMemory.field names it
    private var name: String? = null
    private var field: AddressField? = null
    private var started: String? = null
    private var seen: String? = null

    // nothing read yet of the field (the connection gave nothing): its first read is how it started
    private var unread = false

    // the settings' count of forgettings as the field started
    private var cleared = 0

    /**
     * The field [name] started, an address's [field] or null, learned from where [learns], its
     * whole [text] (null where more than was read) if [read], else nothing could be read: what the
     * field before it is to be learned from, if it was another; [cleared], the settings' count of
     * addresses forgotten.
     */
    fun start(name: String, field: AddressField?, learns: Boolean, text: String?, read: Boolean = true, cleared: Int = 0): Left? {
        val kept = field?.takeIf { learns }
        if (sameField(name, kept, text, read)) {
            forgotten(cleared)
            seen(text, read)
            return null
        }
        val left = left().takeIf { cleared == this.cleared }
        this.cleared = cleared
        this.name = name
        this.field = kept
        unread = kept != null && !read
        started = text?.takeIf { kept != null && read }
        seen = started
        return left
    }

    // the field started last, its text as last seen (or nothing read, now or before): not another
    private fun sameField(name: String, field: AddressField?, text: String?, read: Boolean): Boolean {
        if (field == null || name != this.name || field != this.field) return false
        return !read || unread || text == seen
    }

    /** The field's whole text read again (null where more than was read); nothing if not [read]. */
    fun seen(text: String?, read: Boolean = true) {
        if (field == null || !read) return
        if (unread) {
            started = text
            unread = false
        }
        seen = text
    }

    /** What the field is to be learned from if left now: its text, changed by the user, and not blank. */
    fun left(): Left? {
        val f = field ?: return null
        val text = seen?.takeIf { it.isNotBlank() && it != started } ?: return null
        return Left(f, text, started.orEmpty())
    }

    // forgotten since the field started: what it holds now as if it had started with it
    private fun forgotten(cleared: Int) {
        if (cleared == this.cleared) return
        this.cleared = cleared
        started = seen
    }

    /** What is to be learned now, the service going away: the field as left, then none. */
    fun end(cleared: Int = 0): Left? = left().takeIf { cleared == this.cleared }.also {
        name = null
        field = null
        started = null
        seen = null
    }
}
