/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import java.text.BreakIterator

/**
 * What one way of a key's swipe reaches (060-swipe-slots): every key has an up slot and a down slot,
 * letter and function keys alike.
 */
sealed interface SwipeSlot {

    /** 长按首选: the first of the key's long press, as the user set it or built in (its corner); nothing where that is off. */
    data object First : SwipeSlot

    /** A text committed as it is (a shortcut's [KeyShortcut.Text]). */
    data class Text(val text: String) : SwipeSlot {
        init {
            require(text.isNotEmpty())
        }
    }

    /** A command run ([KeyShortcut.Command]). */
    data class Command(val command: KeyCommand) : SwipeSlot

    /** Nothing: a character key's swipe this way is its tap, a function key's leaves the key as it was. */
    data object Empty : SwipeSlot

    /** What the slot runs as a shortcut; [KeyShortcut.None] for [First] and [Empty], which run none. */
    val shortcut: KeyShortcut
        get() = when (this) {
            is Text -> KeyShortcut.Text(text)
            is Command -> KeyShortcut.Command(command)
            First, Empty -> KeyShortcut.None
        }

    companion object {
        fun of(shortcut: KeyShortcut): SwipeSlot = when (shortcut) {
            is KeyShortcut.Text -> Text(shortcut.text)
            is KeyShortcut.Command -> Command(shortcut.command)
            KeyShortcut.None -> Empty
        }
    }
}

/**
 * Which way a character key's preset reaches its long press's first ([FirstUp], the default since 0.6.0:
 * 上滑 长按首选, 下滑 its shortcut) and which its shortcut ([FirstDown]: 0.5's default, kept for an upgrade).
 * A function key's presets have a way of their own ([KeyShortcutPreset]) and follow neither.
 */
enum class SwipeOrientation(val key: String) {
    FirstUp("first-up"),
    FirstDown("first-down");

    val flipped: SwipeOrientation get() = if (this == FirstUp) FirstDown else FirstUp

    companion object {
        fun ofKey(key: String): SwipeOrientation? = entries.firstOrNull { it.key == key }
    }
}

/**
 * The slots a key has before the user sets any. A key is named as its layout names it (`shortcut=`):
 * a character key by its keyboard's form and the key (`full/a`, `t9/2`), a function key `fn/<name>`
 * ([SwipeSlots.functionKey]). A character key: its long press's first one way ([SwipeOrientation]),
 * its [KeyShortcutPreset] the other; a function key: its presets up and down.
 */
object SwipeSlotPreset {
    fun of(key: String, up: Boolean, orientation: SwipeOrientation): SwipeSlot {
        if (SwipeSlots.isFunction(key)) return SwipeSlot.of(KeyShortcutPreset.of(SwipeSlots.wayName(key, up)))
        val firstHere = up == (orientation == SwipeOrientation.FirstUp)
        return if (firstHere) SwipeSlot.First else SwipeSlot.of(KeyShortcutPreset.of(key))
    }
}

/**
 * The swipe slots the user set, over [SwipeSlotPreset]'s, and the [orientation] the presets take.
 *
 * Stored as text: a first line `@1`, a tab and the orientation's key (the format's version 1); then a
 * line per slot: the key's name, `.up` or `.down`, a tab, then the value: `first`, `none`, `text:` and
 * the text (escaped as [KeyShortcuts] escapes it), or a command's key. A slot with no line has its
 * preset; one set to its preset is stored as none ([with]). A line that does not parse is skipped
 * (a command no version has any more: the slot back to its preset), never a crash.
 */
class SwipeSlots private constructor(
    val orientation: SwipeOrientation,
    private val table: Map<String, SwipeSlot>,
) {

    /** What [key]'s slot [up] (else down) holds: the user's, else its preset. */
    fun resolve(key: String, up: Boolean): SwipeSlot = table[wayName(key, up)] ?: SwipeSlotPreset.of(key, up, orientation)

    /** Whether the user set either of [key]'s slots: outlined on the settings page. */
    fun isSet(key: String): Boolean = wayName(key, true) in table || wayName(key, false) in table

    /** The keys the user set a slot of. */
    val keys: Set<String> get() = table.keys.mapTo(LinkedHashSet()) { it.substringBeforeLast('.') }

    /** [key]'s slot [up] as [slot]; the preset is no setting of the user's, so not kept. */
    fun with(key: String, up: Boolean, slot: SwipeSlot): SwipeSlots {
        val t = LinkedHashMap(table)
        if (slot == SwipeSlotPreset.of(key, up, orientation)) t.remove(wayName(key, up)) else t[wayName(key, up)] = slot
        return SwipeSlots(orientation, t)
    }

    /** [keys] back to their presets (恢复默认). */
    fun without(keys: Collection<String>): SwipeSlots {
        val names = keys.flatMap { listOf(wayName(it, true), wayName(it, false)) }.toSet()
        return SwipeSlots(orientation, table.filterKeys { it !in names })
    }

    /** [keys]' both slots set to [slot] where [up] (true up, false down, null both): 设为长按首选、设为空. */
    fun withAll(keys: Collection<String>, up: Boolean?, slot: SwipeSlot): SwipeSlots =
        keys.fold(this) { s, k ->
            listOfNotNull(true.takeIf { up != false }, false.takeIf { up != true }).fold(s) { t, way -> t.with(k, way, slot) }
        }

    /** [keys]' up and down slots swapped, each key's own. */
    fun flipped(keys: Collection<String>): SwipeSlots =
        keys.fold(this) { s, k ->
            val up = s.resolve(k, true)
            val down = s.resolve(k, false)
            s.with(k, true, down).with(k, false, up)
        }

    /**
     * Every key's up and down slots swapped: the character keys' presets by the orientation flipped, the
     * lines set swapped, and [functionKeys] (whose presets have their own ways) set each the other way.
     */
    fun flippedAll(functionKeys: Collection<String>): SwipeSlots {
        val fns = (functionKeys + keys.filter(::isFunction)).toSet()
        // each function key's slots as they are now, before its lines go
        val now = fns.associateWith { resolve(it, true) to resolve(it, false) }
        val swapped = LinkedHashMap<String, SwipeSlot>()
        table.forEach { (name, slot) ->
            val key = name.substringBeforeLast('.')
            if (!isFunction(key)) swapped[wayName(key, !name.endsWith(UP))] = slot
        }
        return now.entries.fold(SwipeSlots(orientation.flipped, swapped)) { s, (k, ways) ->
            s.with(k, true, ways.second).with(k, false, ways.first)
        }
    }

    /** Every key at the presets of [orientation] (预设): nothing the user set kept. */
    fun applied(orientation: SwipeOrientation): SwipeSlots = SwipeSlots(orientation, emptyMap())

    fun serialize(): String = buildString {
        append(HEADER).append('\t').append(orientation.key)
        table.forEach { (name, slot) -> append('\n').append(name).append('\t').append(valueOf(slot)) }
    }

    override fun equals(other: Any?): Boolean = other is SwipeSlots && other.orientation == orientation && other.table == table

    override fun hashCode(): Int = orientation.hashCode() * 31 + table.hashCode()

    override fun toString(): String = "SwipeSlots($orientation, $table)"

    companion object {
        /** A new install's: no slot set, the long press's first up. */
        val Default = SwipeSlots(SwipeOrientation.FirstUp, emptyMap())

        private const val HEADER = "@1"
        private const val FIRST = "first"
        private const val NONE = "none"
        private const val TEXT = "text:"
        private const val UP = ".up"
        private const val DOWN = ".down"

        /** A function key's name, as a layout's `shortcut="space"` is stored: `fn/space`. */
        fun functionKey(name: String) = "${KeyShortcuts.FUNCTION}/$name"

        /** A key's name as stored from a layout's `shortcut=`: a character key's as it is, a function key's [functionKey]. */
        fun keyOf(layoutName: String) = if ('/' in layoutName) layoutName else functionKey(layoutName)

        fun isFunction(key: String) = KeyShortcuts.formOf(key) == KeyShortcuts.FUNCTION

        /** [key]'s slot [up] as stored, and as [KeyShortcuts] names a way (`fn/space.up`). */
        fun wayName(key: String, up: Boolean) = key + if (up) UP else DOWN

        private fun valueOf(slot: SwipeSlot): String = when (slot) {
            SwipeSlot.First -> FIRST
            SwipeSlot.Empty -> NONE
            is SwipeSlot.Text -> TEXT + KeyShortcuts.escape(slot.text)
            is SwipeSlot.Command -> slot.command.key
        }

        private fun parseValue(value: String): SwipeSlot? = when {
            value == FIRST -> SwipeSlot.First
            value == NONE -> SwipeSlot.Empty
            value.startsWith(TEXT) -> KeyShortcut.clip(KeyShortcuts.unescape(value.removePrefix(TEXT)), KeyShortcut.MAX_TEXT)
                .takeIf { it.isNotEmpty() }?.let(SwipeSlot::Text)
            else -> KeyCommand.ofKey(value)?.let(SwipeSlot::Command)
        }

        /** Never throws: the header missing or unknown is [SwipeOrientation.FirstUp]; a bad line skipped; a later one for the same slot wins. */
        fun parse(text: String): SwipeSlots {
            var orientation = SwipeOrientation.FirstUp
            val table = LinkedHashMap<String, SwipeSlot>()
            // lines end at \n alone: a text's \r is stored escaped (KeyShortcuts, review S3)
            for (raw in text.splitToSequence('\n')) {
                val line = raw.removeSuffix("\r")
                val tab = line.indexOf('\t')
                val name = if (tab < 0) "" else line.substring(0, tab)
                val value = line.substring(tab + 1)
                when {
                    name == HEADER -> SwipeOrientation.ofKey(value)?.let { orientation = it }
                    '/' in name && (name.endsWith(UP) || name.endsWith(DOWN)) -> parseValue(value)?.let { table[name] = it }
                }
            }
            // a line at its preset (a backup edited by hand) is no setting
            val clean = table.filter { (name, slot) ->
                slot != SwipeSlotPreset.of(name.substringBeforeLast('.'), name.endsWith(UP), orientation)
            }
            return SwipeSlots(orientation, clean)
        }
    }
}

/**
 * A slot's text as a key draws it (要做 1): at most its first [SHOWN] characters as seen (grapheme
 * clusters: a flag, a family, an accented letter one each, as [org.fcitx.fcitx5.android.input.popup.PreviewLabel]
 * counts them), then [ELLIPSIS] where more were left out; line breaks and runs of spaces as one space.
 * The bubble shows it whole.
 */
object KeyMarkText {
    const val SHOWN = 2
    const val ELLIPSIS = "…"

    private val SPACES = Regex("\\s+")

    fun of(text: String): String {
        val line = text.replace(SPACES, " ").trim().ifEmpty { text }
        val bounds = BreakIterator.getCharacterInstance().apply { setText(line) }
        var end = 0
        repeat(SHOWN) {
            val next = bounds.following(end)
            if (next == BreakIterator.DONE) return line
            end = next
        }
        return if (end >= line.length) line else line.substring(0, end).trimEnd() + ELLIPSIS
    }
}
