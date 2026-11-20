/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard.canvas

import android.content.Context
import androidx.annotation.VisibleForTesting
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.layout.CustomKeyboards
import org.fcitx.fcitx5.android.data.theme.ThemePackLayouts
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.keys.DefaultMarks
import org.fcitx.fcitx5.android.scene.keys.NodeFacts
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Include
import org.fcitx.fcitx5.android.scene.layout.KeyData
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import timber.log.Timber

/**
 * Where a keyboard's layout XML text is read from, by id (`qwerty`, `k14`, a fragment's `grouped-bottom`);
 * null for none. Ids are kept as they are: a theme's styles name them.
 */
interface LayoutSource {
    fun text(id: String): String?

    /** Each source its own, the same each time it is made: a changed pack is read again by its key alone. */
    val key: String

    /** Null where taken; a theme pack's is held to what the app's [id] must have to be typed on ([ThemePackLayouts.judge]). */
    fun refuses(id: String, layout: Layout, fragments: Map<String, Fragment>): String? = null

    /** Null: as written (the app's own); a theme pack's takes the app's key data of its name (主题只管外观). */
    val keyData: KeyData.Mode? get() = null
}

/**
 * lib/keyboard-scene's commonMain/layouts, copied by the build as raw resources (copySceneLayouts, `-`
 * made `_`), so Robolectric reads them too; a new one adds its line.
 */
class BuiltInLayouts(private val context: Context) : LayoutSource {
    override fun text(id: String): String? =
        RAW[id]?.let { res -> context.resources.openRawResource(res).use { it.readBytes().decodeToString() } }

    override val key: String get() = KEY

    companion object {
        const val KEY = "built-in"

        fun has(id: String): Boolean = id in RAW

        /** Fragments a keyboard includes, not read as a keyboard's layout. */
        val FRAGMENTS: Set<String> = setOf("grouped-bottom")

        // named, not looked up by name (Resources.getIdentifier): the build keeps what is used
        val RAW: Map<String, Int> = mapOf(
            "qwerty" to R.raw.scene_layout_qwerty,
            "k14" to R.raw.scene_layout_k14,
            "k18" to R.raw.scene_layout_k18,
            "zhuyin" to R.raw.scene_layout_zhuyin,
            "zhuyin-nine" to R.raw.scene_layout_zhuyin_nine,
            "grouped-bottom" to R.raw.scene_layout_grouped_bottom,
            "number" to R.raw.scene_layout_number,
            "nine" to R.raw.scene_layout_nine,
            "symbols" to R.raw.scene_layout_symbols,
            "emoji" to R.raw.scene_layout_emoji,
            "emoticon" to R.raw.scene_layout_emoticon,
            "emoji-search" to R.raw.scene_layout_emoji_search,
            "expanded-side" to R.raw.scene_layout_expanded_side,
            "number-row" to R.raw.scene_layout_number_row,
        )
    }
}

/**
 * [base]: the board whose marks, defaults and theme styles it has: its own id, or a user's layout's
 * `based-on=`. [fallback]: the app's board drawn where it cannot be: a user's layout on another
 * family's board falls back to its own family's (a grouped layout on qwerty to k18); shape-bound
 * defaults (corners, 18's L with its `:`) are [fallback]'s too.
 */
class SceneLayout(
    val id: String,
    val layout: Layout,
    val fragments: Map<String, Fragment>,
    val base: String = id,
    private val appBoard: ((String) -> SceneLayout?)? = null,
    val fallback: String = base,
) {
    /** The env's `base`: none for the app's. Read by SceneBoard and SceneKeyCell both. */
    val stylesOf: String? get() = base.takeIf { it != id }

    /**
     * Read when first asked. In a user's layout a key of an app name is the app's key, whatever letters it
     * writes (DefaultMarks.ownNodes), found from [base].
     */
    val nodes: List<Pair<String, NodeFacts>> by lazy {
        appBoard?.let { board -> DefaultMarks.ownNodes(layout, fragments, base) { id -> board(id)?.let { it.layout to it.fragments } } }
            ?: DefaultMarks.nodes(layout, fragments)
    }
}

/**
 * Kept by id and [stamp], so a replaced file is read anew. The file is not trusted: held again to what
 * it must be ([refuses]: CustomKeyboards.refusal), refused whole with the base drawn instead. What it
 * does not write is its base's ([KeyData.Mode.Own]).
 */
class CustomLayoutSource(private val id: String, private val stamp: String) : LayoutSource {
    override fun text(id: String): String? = if (id == this.id) CustomKeyboards.text(id) else null

    override val key: String = "$PREFIX$id@$stamp"

    override fun refuses(id: String, layout: Layout, fragments: Map<String, Fragment>): String? = CustomKeyboards.refusal(this.id, stamp, layout)

    /** Its own family's, whatever its `based-on` says; its styles, marks and key data are still its based-on's. */
    fun fallback(layout: Layout): String = CustomKeyboards.baseOf(layout) ?: id

    override val keyData: KeyData.Mode get() = KeyData.Mode.Own

    companion object {
        /** [SceneLayouts] keeps them while a theme changes. */
        const val PREFIX = "custom:"
    }
}

/**
 * Each read once a process per source (main thread), failures too (logged once): [source]'s, else the
 * app's own. A layout that does not read draws nothing.
 */
object SceneLayouts {
    private sealed interface Read {
        class Ok(val layout: SceneLayout) : Read

        data object Unread : Read
    }

    private val read = HashMap<Pair<String, String>, Read>()

    private var current: String? = null

    /** Asked each time, so a theme change is followed. Set, those read are read again. */
    @VisibleForTesting
    var source: (Context) -> LayoutSource = ThemePackLayouts::current
        set(value) {
            field = value
            read.clear()
        }

    /** For a layout [source] lacks or cannot read: the app's own; a test's. */
    @VisibleForTesting
    var fallback: (Context) -> LayoutSource = ::BuiltInLayouts
        set(value) {
            field = value
            read.clear()
        }

    /** One step back to [fallback] (a source of the same key not read again); null where neither reads. */
    fun of(context: Context, id: String): SceneLayout? {
        val app = context.applicationContext ?: context
        val first = source(app)
        val back = fallback(app)
        // a pack left (re-imported, another in use): what was read of it dropped
        if (first.key != current) {
            current = first.key
            read.keys.removeAll { it.first != first.key && it.first != back.key && !it.first.startsWith(CustomLayoutSource.PREFIX) }
        }
        return readFrom(first, id, back) ?: readFrom(back, id, back)
    }

    /** What a keyboard that cannot take its source's layout draws instead (a grouping it cannot type). */
    fun builtIn(context: Context, id: String): SceneLayout? = fallback(context.applicationContext ?: context).let { readFrom(it, id, it) }

    /** Null where it does not read or is refused (logged): the keyboard draws its base then. An older stamp's dropped. */
    fun custom(context: Context, id: String, stamp: String): SceneLayout? {
        val source = CustomLayoutSource(id, stamp)
        read.keys.removeAll { it.first.startsWith("${CustomLayoutSource.PREFIX}$id@") && it.first != source.key }
        return readFrom(source, id, fallback(context.applicationContext ?: context))
    }

    /** Else the user's layout [id] as its file is now ([custom]). */
    fun board(context: Context, id: String): SceneLayout? =
        of(context, id) ?: CustomKeyboards.choice(id)?.takeIf { it.drawn }?.let { custom(context, id, it.stamp) }

    /** The cache (main thread only) left alone: for checks off the main thread (a pack's import). */
    fun parseBuiltIn(context: Context, id: String): SceneLayout? = fallback(context.applicationContext ?: context).let { load(it, id, it) }

    internal val cached: Int get() = read.size

    /** Null: all of them. */
    fun invalidate(key: String? = null) {
        if (key == null) read.clear() else read.keys.removeAll { it.first == key }
    }

    private fun readFrom(source: LayoutSource, id: String, back: LayoutSource): SceneLayout? =
        when (val r = read.getOrPut(source.key to id) { load(source, id, back)?.let(Read::Ok) ?: Read.Unread }) {
            is Read.Ok -> r.layout
            Read.Unread -> null
        }

    // a fragment the source has not, [back]'s (a pack's k18 including the app's grouped-bottom)
    private fun load(source: LayoutSource, id: String, back: LayoutSource): SceneLayout? {
        val text = runCatching { source.text(id) }.onFailure { Timber.e(it, "scene layout %s unread", id) }.getOrNull() ?: return null
        val layout = when (val l = LayoutReader.layout(text)) {
            is Parsed.Ok -> l.value
            is Parsed.Failed -> return null.also { Timber.e("scene layout %s: %s", id, l.problems) }
        }
        val fragments = HashMap<String, Fragment>()
        for (src in layout.parts.filterIsInstance<Include>().map { it.src }.distinct()) {
            val t = runCatching { source.text(src) ?: back.takeIf { it.key != source.key }?.text(src) }
                .getOrElse { e ->
                    Timber.e(e, "scene layout %s: fragment %s unread", id, src)
                    return null
                }
                ?: return null.also { Timber.e("scene layout %s: no fragment %s", id, src) }
            when (val f = LayoutReader.fragment(t)) {
                is Parsed.Ok -> fragments[src] = f.value
                is Parsed.Failed -> return null.also { Timber.e("scene layout %s: fragment %s: %s", id, src, f.problems) }
            }
        }
        source.refuses(id, layout, fragments)?.let { why ->
            return null.also { Timber.e("scene layout %s of %s not taken: %s", id, source.key, why) }
        }
        // a theme pack's board is its own id whatever based-on= it writes; a user's is the board it is based
        // on, as the web maker draws it, but falls back to its own family's
        val own = source as? CustomLayoutSource
        val basedOn = own?.let { layout.basedOn }
        val base = basedOn ?: id
        val instead = own?.fallback(layout) ?: id
        val held = source.keyData?.let { KeyData.of(id, layout, fragments, it, basedOn, shape = instead) }
            ?: return SceneLayout(id, layout, fragments, base, fallback = instead)
        // read as the resolver first asks, on the main thread
        return SceneLayout(id, held.layout, held.fragments, base, appBoard = if (own != null) { b -> readFrom(back, b, back) } else null, fallback = instead)
    }
}
