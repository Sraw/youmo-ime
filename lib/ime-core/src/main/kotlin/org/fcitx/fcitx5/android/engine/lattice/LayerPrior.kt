/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.data.WordLayers

/**
 * A log10 adjustment to every word of each layer of the dictionary ([WordLayers]), and of each
 * word pack's layer ([layer] gives a name an index; [extra] tells a pack word's): the words of
 * the last years, say, which the model scores as unknown or as rarer than they are now, lifted
 * as one. One value a layer, so it reaches words the user has never typed, where the user
 * model's counts cannot. The base layer, the first, is what the others are measured against and
 * stays at 0 (but for [parse], for measuring).
 *
 * The values are fixed now: the session no longer learns them from picks (learning-ux
 * impl/design-1b.md §4), so nothing in the app calls [learn] or sets [journal]. They stay, with
 * [step] and ±[bound], for measuring a learned prior again; [restore] still takes back what an
 * older build kept by name (`pinyin.user`, UserLog), a name no build or pack uses any more just
 * sitting in the log, a few bytes.
 */
class LayerPrior(
    private val layers: WordLayers,
    values: FloatArray = FloatArray(layers.count),
    private val step: Float = STEP,
    private val bound: Float = BOUND,
    /** The layer index of a word past the dictionary's (a pack's, listed with the index from [layer]); -1 for a word of no pack. */
    private val extra: PackLayers = PackLayers { -1 },
) {
    init {
        require(values.size == layers.count) { "${values.size} values for ${layers.count} layers" }
        require(values.all { it.isFinite() }) { "prior ${values.contentToString()}" }
        require(step > 0f && bound > 0f) { "step $step bound $bound" }
    }

    /** Hears of each value changed by [learn], to keep it. */
    fun interface Journal {
        fun changed(name: String, value: Float)
    }

    /** Gives [extra]'s answer: an interface of its own, as an (Int) -> Int boxes the id of every word scored. */
    fun interface PackLayers {
        fun layerOf(word: Int): Int

        /** The model's word [word] is scored as: a word of the user's mapped so takes that word's layer. */
        fun modelWord(word: Int): Int = word
    }

    var journal: Journal? = null

    private val names = ArrayList(layers.names)
    private var values = values.copyOf()

    val count: Int get() = names.size

    operator fun get(layer: Int): Float = values[layer]

    fun of(word: Int): Float = values[layerOf(word)]

    private fun layerOf(word: Int): Int {
        // a word the user chose, scored as the model's of its text, is that word's layer, a pack's
        // word too; else a pack's word is the pack's layer even when the dictionary has it in a
        // layer of its own, as it is the pack that scores it then
        val model = extra.modelWord(word)
        if (model != word) return layers.layer(model)
        val packed = extra.layerOf(word)
        return if (packed >= 0) packed else layers.layer(word)
    }

    /** The index of the layer called [name], given one if it is new; at 0 until [restore]d or [learn]ed. */
    fun layer(name: String): Int {
        val at = names.indexOf(name)
        if (at >= 0) return at
        names += name
        values = values.copyOf(names.size)
        return names.lastIndex
    }

    /** [inner]'s scores with the prior added, held to 0 as a score must be. */
    fun scorer(inner: WordScorer): WordScorer = object : WordScorer {
        override fun score(prev2: Int, prev: Int, word: Int) = minOf(0f, inner.score(prev2, prev, word) + of(word))
        override fun context(prev2: Int, prev: Int) = inner.context(prev2, prev)
        override fun scoreAfter(context: Long, word: Int) = minOf(0f, inner.scoreAfter(context, word) + of(word))
        override fun trigrams(context: Long) = inner.trigrams(context)
        override fun scoreAfter(context: Long, trigrams: NgramModel.Trigrams, word: Int) = minOf(0f, inner.scoreAfter(context, trigrams, word) + of(word))
    }

    /**
     * Learns from a pick: the user committed [picked] where [first] was offered first. A layer
     * they went past the first choice to a word of goes up a step; one whose word the first
     * choice had, and they left, goes down one. Taking the first choice as it was teaches nothing.
     * @return whether a value changed: a decoder's kept scores are then stale
     */
    fun learn(picked: IntArray, first: IntArray): Boolean {
        var changed = false
        for (layer in 1 until names.size) {
            val wanted = picked.any { layerOf(it) == layer }
            val offered = first.any { layerOf(it) == layer }
            if (wanted != offered) changed = move(layer, if (wanted) step else -step) || changed
        }
        return changed
    }

    private fun move(layer: Int, by: Float): Boolean {
        val value = (values[layer] + by).coerceIn(-bound, bound)
        if (value == values[layer]) return false
        values[layer] = value
        journal?.changed(names[layer], value)
        return true
    }

    /** Takes [name]'s [value] back from a log, for any layer but the base: a pack's may be listed only later. */
    fun restore(name: String, value: Float): Boolean {
        if (name == names[0] || !value.isFinite()) return false
        // the layer first: the array is grown if the name is new
        val layer = layer(name)
        values[layer] = value.coerceIn(-bound, bound)
        return true
    }

    /** Each layer's name and value, the base and the layers at 0 left out. */
    fun forEach(action: (String, Float) -> Unit) {
        for (layer in 1 until names.size) if (values[layer] != 0f) action(names[layer], values[layer])
    }

    /** As [parse] takes it: `name=value,...` of the layers not at 0. */
    override fun toString() = names.indices.filter { values[it] != 0f }.joinToString(",") { "${names[it]}=${values[it]}" }

    companion object {
        const val STEP = 0.05f
        const val BOUND = 1f

        /**
         * [spec] as `name=value,name=value` over [layers]' names and [packs]' (each given its
         * index in that order, for [extra] to return); a layer not named is 0. Fixed values, for
         * measuring: not held to [BOUND] as learned ones are.
         */
        fun parse(layers: WordLayers, spec: String, packs: List<String> = emptyList(), extra: PackLayers = PackLayers { -1 }): LayerPrior {
            val prior = LayerPrior(layers, extra = extra)
            packs.forEach { prior.layer(it) }
            spec.split(',').filter { it.isNotEmpty() }.forEach { part ->
                val name = part.substringBefore('=')
                val index = prior.names.indexOf(name)
                require(index >= 0) { "no layer \"$name\" among ${prior.names}" }
                // toFloatOrNull takes NaN and Infinity, which would break the decoder's pruning
                prior.values[index] = requireNotNull(part.substringAfter('=', "").toFloatOrNull()?.takeIf { it.isFinite() }) { "bad prior \"$part\"" }
            }
            return prior
        }
    }
}
