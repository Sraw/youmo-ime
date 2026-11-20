/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.shortcut

import org.fcitx.fcitx5.android.engine.phrase.TextVariables
import java.text.BreakIterator

/** What one way of a key's swipe reaches: every key has an up and a down slot. */
sealed interface SwipeSlot {

    /** 长按首选: the key's long-press first (its corner); nothing where that is off. */
    data object First : SwipeSlot

    data class Text(val text: String, val variables: Boolean = false) : SwipeSlot {
        init {
            require(text.isNotEmpty())
        }

        companion object {
            /** 使用变量 [ticked]: of variables only where they type it differently. */
            fun of(text: String, ticked: Boolean) = Text(text, ticked && TextVariables.fillsIn(text))
        }
    }

    data class Command(val command: KeyCommand) : SwipeSlot

    /** A character key's swipe this way is its tap; a function key's does nothing. */
    data object Empty : SwipeSlot

    val shortcut: KeyShortcut
        get() = when (this) {
            is Text -> KeyShortcut.Text(text, variables)
            is Command -> KeyShortcut.Command(command)
            First, Empty -> KeyShortcut.None
        }

    companion object {
        fun of(shortcut: KeyShortcut): SwipeSlot = when (shortcut) {
            is KeyShortcut.Text -> Text(shortcut.text, shortcut.variables)
            is KeyShortcut.Command -> Command(shortcut.command)
            KeyShortcut.None -> Empty
        }
    }
}

/** Which way a character key's preset reaches its long-press first; function keys' presets follow neither. */
enum class SwipeOrientation(val key: String) {
    FirstUp("first-up"),
    FirstDown("first-down");

    val flipped: SwipeOrientation get() = if (this == FirstUp) FirstDown else FirstUp

    companion object {
        fun ofKey(key: String): SwipeOrientation? = entries.firstOrNull { it.key == key }
    }
}

/** The slots before the user sets any; keys named as the layout's `shortcut=` names them (`full/a`, `t9/2`, `fn/<name>`). */
object SwipeSlotPreset {
    fun of(key: String, up: Boolean, orientation: SwipeOrientation): SwipeSlot {
        if (SwipeSlots.isFunction(key)) return SwipeSlot.of(KeyShortcutPreset.of(SwipeSlots.wayName(key, up)))
        val firstHere = up == (orientation == SwipeOrientation.FirstUp)
        return if (firstHere) SwipeSlot.First else SwipeSlot.of(KeyShortcutPreset.of(key))
    }
}

/**
 * The user's slots over [SwipeSlotPreset]'s. Stored as text: `@1<TAB>orientation`, then `key.up|down<TAB>value`
 * (`first`, `none`, `text:`…, `vtext:`…, or a command's key). A slot at its preset is not stored; a line that
 * does not parse is skipped, never a crash.
 */
class SwipeSlots private constructor(
    val orientation: SwipeOrientation,
    private val table: Map<String, SwipeSlot>,
) {

    fun resolve(key: String, up: Boolean): SwipeSlot = table[wayName(key, up)] ?: SwipeSlotPreset.of(key, up, orientation)

    fun isSet(key: String, up: Boolean): Boolean = wayName(key, up) in table

    /** Outlined on the settings page. */
    fun isSet(key: String): Boolean = wayName(key, true) in table || wayName(key, false) in table

    /** Then its marks are drawn anew each minute (KeyState.minute). */
    val fillsIn: Boolean get() = table.values.any { it is SwipeSlot.Text && it.variables }

    /** 首页单字 then reorders what one filters too. */
    val strokes: Boolean get() = table.values.any { it is SwipeSlot.Command && it.command.stroke != null }

    val keys: Set<String> get() = table.keys.mapTo(LinkedHashSet()) { it.substringBeforeLast('.') }

    /** The preset is no setting of the user's, so not kept. */
    fun with(key: String, up: Boolean, slot: SwipeSlot): SwipeSlots {
        val t = LinkedHashMap(table)
        if (slot == SwipeSlotPreset.of(key, up, orientation)) t.remove(wayName(key, up)) else t[wayName(key, up)] = slot
        return SwipeSlots(orientation, t)
    }

    /** 恢复默认. */
    fun without(keys: Collection<String>): SwipeSlots {
        val names = keys.flatMap { listOf(wayName(it, true), wayName(it, false)) }.toSet()
        return SwipeSlots(orientation, table.filterKeys { it !in names })
    }

    /** [up] null both: 设为长按首选、设为空. */
    fun withAll(keys: Collection<String>, up: Boolean?, slot: SwipeSlot): SwipeSlots =
        keys.fold(this) { s, k ->
            listOfNotNull(true.takeIf { up != false }, false.takeIf { up != true }).fold(s) { t, way -> t.with(k, way, slot) }
        }

    fun flipped(keys: Collection<String>): SwipeSlots =
        keys.fold(this) { s, k ->
            val up = s.resolve(k, true)
            val down = s.resolve(k, false)
            s.with(k, true, down).with(k, false, up)
        }

    /** Character keys by flipping the orientation; [functionKeys] each swapped explicitly (their presets have their own ways). */
    fun flippedAll(functionKeys: Collection<String>): SwipeSlots {
        val fns = (functionKeys + keys.filter(::isFunction)).toSet()
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

    fun applied(orientation: SwipeOrientation): SwipeSlots = SwipeSlots(orientation, emptyMap())

    fun serialize(): String = buildString {
        append(HEADER).append('\t').append(orientation.key)
        table.forEach { (name, slot) -> append('\n').append(name).append('\t').append(valueOf(slot)) }
    }

    override fun equals(other: Any?): Boolean = other is SwipeSlots && other.orientation == orientation && other.table == table

    override fun hashCode(): Int = orientation.hashCode() * 31 + table.hashCode()

    override fun toString(): String = "SwipeSlots($orientation, $table)"

    companion object {
        val Default = SwipeSlots(SwipeOrientation.FirstUp, emptyMap())

        private const val VERSION = "@"
        private const val HEADER = "${VERSION}1"
        private const val FIRST = "first"
        private const val NONE = "none"
        private const val TEXT = "text:"
        private const val VARIABLES_TEXT = "vtext:"
        /** KeyShortcutPreset reads the same names. */
        internal const val UP = ".up"
        internal const val DOWN = ".down"

        fun functionKey(name: String) = "${KeyShortcuts.FUNCTION}/$name"

        fun isFunction(key: String) = KeyShortcuts.formOf(key) == KeyShortcuts.FUNCTION

        /** The comma and full stop are function keys that type one (swiped to nothing, they are tapped). */
        fun typesAChar(key: String) = !isFunction(key) || key == functionKey("comma") || key == functionKey("period")

        fun wayName(key: String, up: Boolean) = key + if (up) UP else DOWN

        private fun valueOf(slot: SwipeSlot): String = when (slot) {
            SwipeSlot.First -> FIRST
            SwipeSlot.Empty -> NONE
            is SwipeSlot.Text -> (if (slot.variables) VARIABLES_TEXT else TEXT) + KeyShortcuts.escape(slot.text)
            is SwipeSlot.Command -> slot.command.key
        }

        private fun parseValue(value: String): SwipeSlot? = when {
            value == FIRST -> SwipeSlot.First
            value == NONE -> SwipeSlot.Empty
            value.startsWith(TEXT) -> text(value.removePrefix(TEXT), variables = false)
            value.startsWith(VARIABLES_TEXT) -> text(value.removePrefix(VARIABLES_TEXT), variables = true)
            else -> KeyCommand.ofKey(value)?.let(SwipeSlot::Command)
        }

        private fun text(stored: String, variables: Boolean): SwipeSlot? =
            KeyShortcut.clip(KeyShortcuts.unescape(stored), KeyShortcut.MAX_TEXT).takeIf { it.isNotEmpty() }?.let { SwipeSlot.Text(it, variables) }

        /**
         * Never throws: a missing or unknown header is [SwipeOrientation.FirstUp]; another version's (`@2`) is [Default]
         * whole, its lines may mean something else. Reading never rewrites the stored text, so a newer app finds its own.
         */
        fun parse(text: String): SwipeSlots {
            var orientation = SwipeOrientation.FirstUp
            val table = LinkedHashMap<String, SwipeSlot>()
            // lines end at \n alone: a text's \r is stored escaped (KeyShortcuts)
            for (raw in text.splitToSequence('\n')) {
                val line = raw.removeSuffix("\r")
                val tab = line.indexOf('\t')
                val name = if (tab < 0) line else line.substring(0, tab)
                val value = if (tab < 0) "" else line.substring(tab + 1)
                when {
                    name == HEADER -> SwipeOrientation.ofKey(value)?.let { orientation = it }
                    name.startsWith(VERSION) -> return Default
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

/** At most [SHOWN] graphemes then [ELLIPSIS]; whitespace runs as one space, a text of nothing else as signs (␣ ↵ ⇥). */
object KeyMarkText {
    const val SHOWN = 2
    const val ELLIPSIS = "…"

    private val SPACES = Regex("\\s+")

    /** A key with nothing seen on it would look unset. */
    private fun signs(text: String) = text.replace("\r\n", "\n").map {
        when (it) {
            '\n', '\r' -> '↵'
            '\t' -> '⇥'
            else -> '␣'
        }
    }.joinToString("")

    fun of(text: String): String {
        val line = text.replace(SPACES, " ").trim().ifEmpty { signs(text) }
        val bounds = BreakIterator.getCharacterInstance().apply { setText(line) }
        var end = 0
        repeat(SHOWN) {
            val next = bounds.following(end)
            if (next == BreakIterator.DONE) return line
            end = next
        }
        return if (end >= line.length) line else line.substring(0, end).trimEnd() + ELLIPSIS
    }

    /** Null where it is that short already. */
    fun shorter(text: String): String? {
        val shown = of(text)
        val bounds = BreakIterator.getCharacterInstance().apply { setText(shown) }
        val first = bounds.following(0).takeIf { it != BreakIterator.DONE } ?: return null
        val cut = shown.substring(0, first).trimEnd()
        return (cut + ELLIPSIS).takeIf { cut.isNotEmpty() && it != shown && first < shown.length }
    }
}
