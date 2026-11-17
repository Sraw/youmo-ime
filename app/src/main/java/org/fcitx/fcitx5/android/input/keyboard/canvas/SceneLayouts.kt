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
 * Where a keyboard's layout is read from, by its id (`qwerty`, `k14`, a fragment's `grouped-bottom`):
 * the text of layout-1's XML, null for none. Never the files themselves (060-render-v2 §15): the app's
 * own now ([BuiltInLayouts]); P3 a theme pack's `layouts/<id>.xml` first and the app's under it,
 * keyboard by keyboard. Ids are kept as they are: a theme's styles name them.
 */
interface LayoutSource {
    fun text(id: String): String?

    /**
     * What the layouts read from it are kept under, with their ids ([SceneLayouts.invalidate]): each
     * source its own, the same each time it is made. The app's: [BuiltInLayouts.KEY]; P3, a theme
     * pack's by the pack's id, so a pack changed is read again by its key and no other's.
     */
    val key: String

    /**
     * Why [layout], read as [id] from this source, is not taken (the app's drawn instead, logged once); null where it
     * is: a theme pack's held to what the app's of [id] must have to be typed on ([ThemePackLayouts.judge]).
     */
    fun refuses(id: String, layout: Layout, fragments: Map<String, Fragment>): String? = null

    /**
     * Whose a key's long press, corner and swipes are on a layout read from it ([KeyData]): null, as written (the app's
     * own); a theme pack's, the app's key's of its name (主题只管外观, 061-custom-layout §19).
     */
    val keyData: KeyData.Mode? get() = null
}

/**
 * The layouts the app has: lib/keyboard-scene's commonMain/layouts, copied by the build as raw
 * resources (copySceneLayouts, a `-` made `_`), so Robolectric reads them too: each board's, which
 * the scene draws; a new one adds its line.
 */
class BuiltInLayouts(private val context: Context) : LayoutSource {
    override fun text(id: String): String? =
        RAW[id]?.let { res -> context.resources.openRawResource(res).use { it.readBytes().decodeToString() } }

    override val key: String get() = KEY

    companion object {
        const val KEY = "built-in"

        /** Whether the keyboard of the layout [id] is moved to the scene: the app has its layout (060-render-p2 S4). */
        fun has(id: String): Boolean = id in RAW

        /** The ids of [RAW] that are fragments a keyboard includes, no keyboard's layout: not read as one. */
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
 * A keyboard's layout and the fragments it includes (a shared bottom row), by their ids: what [SceneBuilder][org.fcitx.fcitx5.android.scene.engine.SceneBuilder.build] takes.
 * [base]: the board whose marks, defaults and theme styles it has (061-custom-layout §5.3, §10): its own id but for a
 * layout of the user's, its own `based-on=`, whatever family that is (R26 F8117). [fallback]: the app's board drawn in
 * its place where it cannot be: [base] but for a user's layout on another family's board, its own family's
 * (CustomKeyboards.baseOf: a grouped layout on qwerty falls back to k18, as it is held to its keys); the 14 or 18 keys'
 * defaults that go by the keys' shape (corners, 18's L with its `:`) are [fallback]'s too (R26 F8437).
 */
class SceneLayout(
    val id: String,
    val layout: Layout,
    val fragments: Map<String, Fragment>,
    val base: String = id,
    private val appBoard: ((String) -> SceneLayout?)? = null,
    val fallback: String = base,
) {
    /**
     * The board whose theme styles it takes besides its own id's, the env's `base` (061-custom-layout §10): a user's
     * layout's [base], none for the app's. The keyboard (SceneBoard) and a settings cap of its key (SceneKeyCell) both.
     */
    val stylesOf: String? get() = base.takeIf { it != id }

    /**
     * Its named keys with what their nodes tell their long press (KeyResolver's), read when first asked: a user's
     * layout's ([appBoard] given: the app's boards by id) of the app's names the app's boards' (DefaultMarks.ownNodes;
     * L6 review S2: a key of the app's name is the app's key, whatever letters it writes), found from [base] as KeyData
     * found its data (its own based-on, R26 F8117).
     */
    val nodes: List<Pair<String, NodeFacts>> by lazy {
        appBoard?.let { board -> DefaultMarks.ownNodes(layout, fragments, base) { id -> board(id)?.let { it.layout to it.fragments } } }
            ?: DefaultMarks.nodes(layout, fragments)
    }
}

/**
 * Layout [id] of the user's own (061-custom-layout §5, L3), its file's [stamp] as read: what a keyboard that chose it
 * draws. Kept by both ([key]), so a file replaced is read anew. The file is not trusted: held again to what it must be to
 * be typed on as it is read ([refuses]: CustomKeyboards.refusal), refused whole, the base drawn in its place. Its keys'
 * long press, corners and swipes are its own as written, what it does not write its base's ([KeyData.Mode.Own]).
 */
class CustomLayoutSource(private val id: String, private val stamp: String) : LayoutSource {
    override fun text(id: String): String? = if (id == this.id) CustomKeyboards.text(id) else null

    override val key: String = "$PREFIX$id@$stamp"

    override fun refuses(id: String, layout: Layout, fragments: Map<String, Fragment>): String? = CustomKeyboards.refusal(this.id, stamp, layout)

    /**
     * The app's board it is drawn as where it cannot be: its own family's, whatever its `based-on` says (L3 review S2).
     * Only that: its styles, marks and keys' data are its own based-on's ([SceneLayout.base], R26 F8117).
     */
    fun fallback(layout: Layout): String = CustomKeyboards.baseOf(layout) ?: id

    override val keyData: KeyData.Mode get() = KeyData.Mode.Own

    companion object {
        /** What the keys of the user's layouts' sources start with: [SceneLayouts] keeps them while a theme changes. */
        const val PREFIX = "custom:"
    }
}

/**
 * The keyboards' layouts, each read once a process from each source (main thread), one that did not
 * read too (logged once): [source]'s, and where it has none or it does not read, the app's own
 * ([BuiltInLayouts]; P3, a theme pack's first). A keyboard whose layout the app has is drawn by the
 * canvas where it is on (060-render-p2 S4): one whose layout does not read, nothing drawn, logged
 * (never its views again, U8); one whose layout the app has not, nothing (logged).
 */
object SceneLayouts {
    private sealed interface Read {
        class Ok(val layout: SceneLayout) : Read

        data object Unread : Read
    }

    // by the source's key and the layout's id
    private val read = HashMap<Pair<String, String>, Read>()

    // the key of the source [of] read from last
    private var current: String? = null

    /**
     * Where the layouts come from first: the theme pack in use's, else the app's ([ThemePackLayouts.current]: asked each
     * time, so a theme changed is followed, the input view made again); a test's own. Set, those read are read again.
     */
    @VisibleForTesting
    var source: (Context) -> LayoutSource = ThemePackLayouts::current
        set(value) {
            field = value
            read.clear()
        }

    /** Where a layout [source] has none of, or one that does not read, comes from: the app's own; a test's. */
    @VisibleForTesting
    var fallback: (Context) -> LayoutSource = ::BuiltInLayouts
        set(value) {
            field = value
            read.clear()
        }

    /** The layout [id], its fragments with it: [source]'s, else [fallback]'s (the one step back; a source of the same key not read again); null where neither reads. */
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

    /**
     * The layout [id] as the app has it ([fallback]'s), whatever [source] has: what a keyboard that cannot
     * take its source's layout draws instead, the block gone by whole (a grouping it cannot type, 060-render-p3 T9b).
     */
    fun builtIn(context: Context, id: String): SceneLayout? = fallback(context.applicationContext ?: context).let { readFrom(it, id, it) }

    /**
     * Layout [id] of the user's as its file [stamp] is ([CustomLayoutSource]), its fragments the app's; null where it does
     * not read or is refused (logged): the keyboard draws its base then. Read once a stamp; an older stamp's dropped.
     */
    fun custom(context: Context, id: String, stamp: String): SceneLayout? {
        val source = CustomLayoutSource(id, stamp)
        read.keys.removeAll { it.first.startsWith("${CustomLayoutSource.PREFIX}$id@") && it.first != source.key }
        return readFrom(source, id, fallback(context.applicationContext ?: context))
    }

    /**
     * The board a settings page draws a key on by [id] (KeyCaps, SceneKeyCell): the keyboards' ([of]), else the user's
     * layout [id] as its file is now where it is drawn ([custom]; 按键字符和快捷键's tab of it, 061-custom-layout §9).
     */
    fun board(context: Context, id: String): SceneLayout? =
        of(context, id) ?: CustomKeyboards.choice(id)?.takeIf { it.drawn }?.let { custom(context, id, it.stamp) }

    /** The app's layout [id] read anew, the cache (main thread only) left alone: for checks off the main thread (a pack's import). */
    fun parseBuiltIn(context: Context, id: String): SceneLayout? = fallback(context.applicationContext ?: context).let { load(it, id, it) }

    /** How many layouts are kept read (tests). */
    internal val cached: Int get() = read.size

    /** The layouts read from the source of [key] (all of them where null) read again when next asked for: P3, a theme pack changed. */
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
        // a theme pack's board is of its own id, whatever based-on= it writes (refuses holds it to that one); a layout of
        // one's own is of the board it is based on, as the web's maker draws it (R26 F8117: a grouped layout on qwerty took
        // k18's styles and marks here, qwerty's there), but falls back to its own family's
        val own = source as? CustomLayoutSource
        val basedOn = own?.let { layout.basedOn }
        val base = basedOn ?: id
        val instead = own?.fallback(layout) ?: id
        val held = source.keyData?.let { KeyData.of(id, layout, fragments, it, basedOn, shape = instead) }
            ?: return SceneLayout(id, layout, fragments, base, fallback = instead)
        // the app's boards a user's layout's keys of their names are of: read as the resolver first asks, on the main thread
        return SceneLayout(id, held.layout, held.fragments, base, appBoard = if (own != null) { b -> readFrom(back, b, back) } else null, fallback = instead)
    }
}
