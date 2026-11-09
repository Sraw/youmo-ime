/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.Context
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.pinyin.NineKeyLetters
import org.fcitx.fcitx5.android.input.keyboard.GroupedKeyboard
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.LayoutSource
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.layout.Layout
import java.io.File

/**
 * A theme 4.0 pack's `layouts/<id>.xml` (060-render-p3 T8b, 060-render-v2 §15): what [SceneLayouts] reads first
 * while the pack is the theme in use, the app's own under it, keyboard by keyboard. A pack replaces only a layout
 * the app has ([BuiltInLayouts.RAW]: `qwerty`, `k14`, `k18`, `nine`, a fragment such as `grouped-bottom`), never adds
 * a keyboard; one of the same `id` and `family` as the app's, else the app's is drawn (logged once, [refuses]).
 * Kept under the pack's file and its `theme.xml`'s time: a pack imported again is read again.
 */
class ThemePackLayouts(private val context: Context, private val pack: Theme.Pack) : LayoutSource {

    override val key: String get() = "theme:${pack.file}@${pack.stamp}"

    override fun text(id: String): String? {
        if (!BuiltInLayouts.has(id)) return null
        val file = File(pack.dir, "${ThemePackFiles.LAYOUTS}/$id.xml")
        if (!file.isFile || file.length() > ThemeAssetLimits.MAX_LAYOUT_BYTES) return null
        return file.readText().removePrefix(BOM)
    }

    override fun refuses(id: String, layout: Layout): String? {
        if (layout.id != id) return "its id is ${layout.id}"
        val app = SceneLayouts.builtIn(context, id)?.layout ?: return null
        return "its family is ${layout.family}, the app's ${app.family}".takeIf { layout.family != app.family }
    }

    companion object {
        private const val BOM = "\uFEFF"

        /** Where the keyboards' layouts are read from first: the pack in use's, else the app's ([SceneLayouts.source]). */
        fun current(context: Context): LayoutSource =
            (ThemeManager.activeOrNull as? Theme.Pack)?.let { ThemePackLayouts(context, it) } ?: BuiltInLayouts(context)

        /** The layout a keyboard of the input method pages is drawn with ([ImKeyboard]'s board). */
        fun boardOf(keyboard: ImKeyboard): String = when (keyboard) {
            ImKeyboard.FULL -> "qwerty"
            ImKeyboard.KEYS18 -> "k18"
            ImKeyboard.KEYS14 -> "k14"
            ImKeyboard.NINE_KEYS -> "nine"
            ImKeyboard.ZHUYIN_NINE -> "zhuyin-nine"
            ImKeyboard.ZHUYIN_FULL -> "zhuyin"
        }

        /**
         * The name of the theme's layout [im]'s [keyboard] is drawn with now (`17 键`), null where it is the app's:
         * none in the pack, one refused, or (14 and 18 keys) groups [im] cannot type, the app's drawn instead (T9b).
         */
        fun themedName(context: Context, im: String, keyboard: ImKeyboard): String? {
            val id = boardOf(keyboard)
            val shown = SceneLayouts.of(context, id) ?: return null
            if (shown === SceneLayouts.builtIn(context, id)) return null
            val letters = when (id) {
                "k14" -> NineKeyLetters.G14
                "k18" -> NineKeyLetters.G18
                else -> return shown.layout.name
            }
            val drawn = GroupedKeyboard.grouping(context, letters, own = im == InputMethodCatalog.PINYIN).layout
            return shown.layout.name.takeIf { drawn === shown }
        }

        /** [label] (a keyboard's own name) with the theme's layout's it is drawn with ([themedName]): `18 键（主题：17 键）`. */
        fun label(context: Context, im: String, keyboard: ImKeyboard, label: String): String =
            themedName(context, im, keyboard)?.let { context.getString(R.string.keyboard_themed, label, it) } ?: label

        private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        private val COMMIT = Regex("=\\s*[\"']commit:")

        /** How many actions of a layout's [text] put text of its own (`commit:`) into what is typed: the import tells it (§15). */
        fun commits(text: String): Int = COMMIT.findAll(COMMENT.replace(text, "")).count()

        /** Of a pack's layout files [files] (`k18.xml`), the ids the app has, which it replaces, and the rest, not read. */
        fun replaced(files: List<String>): Pair<List<String>, List<String>> =
            files.map { it.removeSuffix(".xml") }.partition { BuiltInLayouts.has(it) }
    }
}
