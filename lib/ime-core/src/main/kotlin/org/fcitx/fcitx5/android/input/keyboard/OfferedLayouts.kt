/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.ShuangpinScheme
import org.fcitx.fcitx5.android.input.layout.CustomLayoutFiles

/**
 * Which of the user's layouts each slot's ([ImKeyboards.SLOTS]) keyboard choice lists (⋯ → 键盘, an input method's 键盘):
 * those 用在… turned on for it, its current one whatever is stored. Stored as [HEADER] then a line a slot, `pinyin id id…`;
 * [parse] never throws.
 */
class OfferedLayouts private constructor(
    private val slots: Map<String, List<String>>,
    // a later version's slot under this head, written back as it was
    private val unknown: List<String> = emptyList(),
) {

    fun of(slot: String): List<String> = slots[slot].orEmpty()

    fun with(slot: String, id: String): OfferedLayouts = if (id in of(slot)) this else put(slot, of(slot) + id)

    fun without(slot: String, id: String): OfferedLayouts = if (id !in of(slot)) this else put(slot, of(slot) - id)

    /** Deleted, or a file new under an id left behind (a restore that could not bring its file): on nowhere. */
    fun withoutAnywhere(id: String): OfferedLayouts = ImKeyboards.SLOTS.fold(this) { o, slot -> o.without(slot, id) }

    private fun put(slot: String, ids: List<String>) = OfferedLayouts(slots + (slot to ids), unknown)

    /** Those of [all] (in its order) [im]'s keyboard choice lists: on for it, or its current one. Not held to [ImKeyboards.usable]. */
    fun listed(im: String, chosen: ImKeyboards.Chosen, all: List<CustomChoice>): List<CustomChoice> {
        val on = of(ImKeyboards.slot(im))
        val current = chosen.layoutOf(im)
        return all.filter { it.id in on || it.id == current }
    }

    /** 用在：… under [id]'s card, and 用在…'s ticks: the slots listing it now ([layout] null: one that does not read). */
    fun usedBy(chosen: ImKeyboards.Chosen, id: String, layout: CustomChoice?, scheme: ShuangpinScheme): List<String> =
        ImKeyboards.SLOTS.filter { slot ->
            chosen.layoutOf(ImKeyboards.imOf(slot)) == id || (id in of(slot) && layout != null && ImKeyboards.usable(ImKeyboards.imOf(slot), layout, scheme))
        }

    /** What to store, and each slot's keyboard to set ([ImKeyboard.CUSTOM]: [layout]). */
    data class Ticked(val offered: OfferedLayouts, val keyboards: Map<String, ImKeyboard>)

    /**
     * 用在… confirmed for [layout] over [asked] with [ticked] on: one ticked anew is on and used; one left ticked stays as it
     * is; one unticked is off, back on its base where it was in use. [ask] (an import's question): only adds.
     */
    fun afterTicks(
        chosen: ImKeyboards.Chosen,
        layout: CustomChoice,
        scheme: ShuangpinScheme,
        asked: List<String>,
        ticked: Collection<String>,
        ask: Boolean,
    ): Ticked {
        val before = usedBy(chosen, layout.id, layout, scheme)
        var offered = this
        val keyboards = LinkedHashMap<String, ImKeyboard>()
        for (slot in asked) {
            val on = slot in ticked
            val was = slot in before && !ask
            when {
                on && !was -> {
                    offered = offered.with(slot, layout.id)
                    keyboards[slot] = ImKeyboard.CUSTOM
                }
                // its current one not stored (a stale backup's): kept listed once it is left
                on -> offered = offered.with(slot, layout.id)
                was -> {
                    offered = offered.without(slot, layout.id)
                    ImKeyboards.ticked(chosen, slot, layout, on = false, scheme)?.let { keyboards[slot] = it }
                }
            }
        }
        return Ticked(offered, keyboards)
    }

    fun serialize(): String = buildString {
        append(HEADER)
        for (slot in ImKeyboards.SLOTS) {
            val ids = of(slot).ifEmpty { null } ?: continue
            append('\n').append(NAMES.getValue(slot))
            ids.forEach { append(' ').append(it) }
        }
        unknown.forEach { append('\n').append(it) }
    }

    override fun equals(other: Any?): Boolean =
        other is OfferedLayouts && ImKeyboards.SLOTS.all { of(it) == other.of(it) } && other.unknown == unknown

    override fun hashCode(): Int = ImKeyboards.SLOTS.map(::of).hashCode() * 31 + unknown.hashCode()

    override fun toString(): String = "OfferedLayouts(${serialize().replace('\n', '|')})"

    companion object {
        const val HEADER = "@1"

        // stored by name, never the engine's ids, which may be renamed
        private val NAMES = mapOf(
            InputMethodCatalog.PINYIN to "pinyin",
            InputMethodCatalog.SHUANGPIN to "shuangpin",
            InputMethodCatalog.ENGLISH to "english",
            ImKeyboards.TABLE to "table",
        )
        private val SLOT_OF = NAMES.entries.associate { (slot, name) -> name to slot }

        val NONE = OfferedLayouts(emptyMap())

        /**
         * Null where nothing is stored ("" too) or under another head (a later version's backup): [migrate]d then, and
         * written as [HEADER] at the next change. An id no file can have is dropped.
         */
        fun parse(text: String?): OfferedLayouts? {
            if (text.isNullOrEmpty()) return null
            val lines = text.split('\n')
            if (lines.first().trim() != HEADER) return null
            val slots = LinkedHashMap<String, List<String>>()
            val unknown = ArrayList<String>()
            for (line in lines.drop(1)) {
                val words = line.trim().split(' ').filter { it.isNotEmpty() }
                val slot = SLOT_OF[words.firstOrNull() ?: continue]
                if (slot == null) {
                    unknown += line.trim()
                } else {
                    slots[slot] = (slots[slot].orEmpty() + words.drop(1).filter(CustomLayoutFiles::validId)).distinct()
                }
            }
            return OfferedLayouts(slots, unknown)
        }

        /** Before any was stored (an upgrade, an older backup restored): each slot's current one alone. */
        fun migrate(chosen: ImKeyboards.Chosen): OfferedLayouts = OfferedLayouts(
            ImKeyboards.SLOTS.mapNotNull { slot -> chosen.layoutOf(ImKeyboards.imOf(slot))?.takeIf(CustomLayoutFiles::validId)?.let { slot to listOf(it) } }.toMap(),
        )

        fun read(text: String?, chosen: ImKeyboards.Chosen): OfferedLayouts = parse(text) ?: migrate(chosen)
    }
}
