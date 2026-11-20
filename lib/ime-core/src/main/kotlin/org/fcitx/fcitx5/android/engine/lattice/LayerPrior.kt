/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.engine.lattice

import org.fcitx.fcitx5.android.engine.data.NgramModel
import org.fcitx.fcitx5.android.engine.data.WordLayers

/**
 * A fixed log10 adjustment per dictionary layer ([WordLayers]) and word-pack layer ([layer] names one,
 * [extra] maps a pack word): lifts recent words the model under-scores, including ones the user never
 * typed, which user counts cannot reach. The base layer stays at 0 (except via [parse], for measuring).
 */
class LayerPrior(
    private val layers: WordLayers,
    values: FloatArray = FloatArray(layers.count),
    /** The layer of a word past the dictionary's (a pack's, by [layer]'s index); -1 for none. */
    private val extra: PackLayers = PackLayers { -1 },
) {
    init {
        require(values.size == layers.count) { "${values.size} values for ${layers.count} layers" }
        require(values.all { it.isFinite() }) { "prior ${values.contentToString()}" }
    }

    /** Its own interface: an (Int) -> Int would box every scored word's id. */
    fun interface PackLayers {
        fun layerOf(word: Int): Int

        /** The model word [word] is scored as: a user word mapped so takes that word's layer. */
        fun modelWord(word: Int): Int = word
    }

    private val names = ArrayList(layers.names)
    private var values = values.copyOf()

    val count: Int get() = names.size

    operator fun get(layer: Int): Float = values[layer]

    fun of(word: Int): Float = values[layerOf(word)]

    private fun layerOf(word: Int): Int {
        // a user-chosen word scored as the model's word of its text takes that word's layer (a pack's too);
        // else a pack word is the pack's layer even if the dictionary has it, as the pack scores it then
        val model = extra.modelWord(word)
        if (model != word) return layers.layer(model)
        val packed = extra.layerOf(word)
        return if (packed >= 0) packed else layers.layer(word)
    }

    /** Added if new; at 0 unless [parse] names it. */
    fun layer(name: String): Int {
        val at = names.indexOf(name)
        if (at >= 0) return at
        names += name
        values = values.copyOf(names.size)
        return names.lastIndex
    }

    /** Held to 0, as a score must be. */
    fun scorer(inner: WordScorer): WordScorer = object : WordScorer {
        override fun score(prev2: Int, prev: Int, word: Int) = minOf(0f, inner.score(prev2, prev, word) + of(word))
        override fun context(prev2: Int, prev: Int) = inner.context(prev2, prev)
        override fun scoreAfter(context: Long, word: Int) = minOf(0f, inner.scoreAfter(context, word) + of(word))
        override fun trigrams(context: Long) = inner.trigrams(context)
        override fun scoreAfter(context: Long, trigrams: NgramModel.Trigrams, word: Int) = minOf(0f, inner.scoreAfter(context, trigrams, word) + of(word))
    }

    /** `name=value,...` of the non-zero layers, as [parse] takes it. */
    override fun toString() = names.indices.filter { values[it] != 0f }.joinToString(",") { "${names[it]}=${values[it]}" }

    companion object {
        /** [spec] as `name=value,...` over [layers]' names then [packs]' (indexed in that order for [extra]); unnamed layers are 0. */
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
