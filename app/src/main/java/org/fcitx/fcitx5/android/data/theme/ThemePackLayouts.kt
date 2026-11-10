/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.Context
import org.fcitx.fcitx5.android.input.keyboard.canvas.BuiltInLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.LayoutSource
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.theme.ThemeAssetLimits
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Include
import org.fcitx.fcitx5.android.scene.layout.Key
import org.fcitx.fcitx5.android.scene.layout.KeyAttrs
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.layout.PackLayouts
import org.fcitx.fcitx5.android.scene.layout.Row
import java.io.File

/**
 * A theme 4.0 pack's `layouts/<id>.xml` (060-render-p3 T8b, 060-render-v2 §15): what [SceneLayouts] reads first
 * while the pack is the theme in use, the app's own under it, keyboard by keyboard. A pack replaces only a keyboard
 * the app has ([BuiltInLayouts.RAW]: `qwerty`, `k14`, `k18`, `nine`…), never adds one; a fragment of its own
 * (`grouped-bottom.xml`) is read only by the pack's own keyboards that include it, the app's keep the app's. A
 * keyboard is taken only where it is as typable as the app's of its id ([judge]), else the app's is drawn (logged
 * once). Kept under the pack's file and its `theme.xml`'s time: a pack imported again is read again.
 */
class ThemePackLayouts(private val context: Context, private val pack: Theme.Pack) : LayoutSource {

    override val key: String get() = "$PREFIX${pack.file}@${pack.stamp}"

    override fun text(id: String): String? = read(File(pack.dir, ThemePackFiles.LAYOUTS), id)

    override fun refuses(id: String, layout: Layout, fragments: Map<String, Fragment>): String? =
        judge(id, layout, fragments, SceneLayouts.builtIn(context, id))

    /** What an import tells of a pack's layouts: the keyboards it replaces, the files it never uses with why, the `commit:` actions of those used. */
    class Report(val used: List<String>, val unused: List<Pair<String, String>>, val commits: Int)

    companion object {
        const val PREFIX = "theme:"
        private const val BOM = "\uFEFF"


        /** Where the keyboards' layouts are read from first: the pack in use's, else the app's ([SceneLayouts.source]). */
        fun current(context: Context): LayoutSource =
            (ThemeManager.activeOrNull as? Theme.Pack)?.let { ThemePackLayouts(context, it) } ?: BuiltInLayouts(context)

        /** The layout file [id] of the folder [dir], an id the app has only, within the limit, its byte-order mark gone. */
        fun read(dir: File, id: String): String? {
            if (!BuiltInLayouts.has(id)) return null
            val file = File(dir, "$id.xml")
            if (!file.isFile || file.length() > ThemeAssetLimits.MAX_LAYOUT_BYTES) return null
            return file.readText().removePrefix(BOM)
        }

        /** Why the pack's [layout] may not stand for the app's keyboard [id] ([app]), null where it may: [PackLayouts.judge], which the theme maker's page shares. */
        fun judge(id: String, layout: Layout, fragments: Map<String, Fragment>, app: SceneLayout?): String? =
            PackLayouts.judge(id, layout, fragments, app?.layout, app?.fragments.orEmpty())

        /** How many actions of [layout] and its [fragments] type text of their own (`commit:`): keys', their variants', popups', menus'. */
        fun commits(layout: Layout, fragments: Map<String, Fragment>): Int = commits(
            listOf(layout.defaults) + fragments.values.map { it.defaults },
            layout.parts.filterIsInstance<Row>() + fragments.values.flatMap { it.rows },
        )

        private fun commits(defaults: List<KeyAttrs>, rows: List<Row>): Int {
            fun of(a: KeyAttrs) = a.actions.values.count { it.startsWith("commit:") }
            val keys = rows.flatMap { it.cells }.filterIsInstance<Key>()
            return defaults.sumOf(::of) + rows.sumOf { of(it.defaults) } +
                keys.sumOf { k ->
                    of(k.attrs) + k.variants.sumOf { of(it.attrs) } + k.menu.count { it.action.startsWith("commit:") } +
                        k.popup?.items.orEmpty().count { it.second?.startsWith("commit:") == true }
                }
        }

        private sealed interface Taken {
            class Yes(val layout: Layout, val fragments: Map<String, Fragment>) : Taken

            /** Not a keyboard used: [why], null for a fragment (told unless a keyboard used includes it). */
            class Not(val why: String?) : Taken
        }

        // the pack's file [id] as it would be drawn ([report])
        private fun taken(dir: File, id: String, app: (String) -> SceneLayout?, appText: (String) -> String?): Taken {
            val text = (if (BuiltInLayouts.has(id)) read(dir, id) else null)
                ?: return Taken.Not(if (BuiltInLayouts.has(id)) "does not read" else "not a keyboard of the app")
            val layout = (LayoutReader.layout(text) as? Parsed.Ok)?.value
                ?: return Taken.Not("does not read".takeIf { LayoutReader.fragment(text) !is Parsed.Ok })
            val fragments = HashMap<String, Fragment>()
            for (src in layout.parts.filterIsInstance<Include>().map { it.src }.distinct()) {
                val f = (read(dir, src) ?: appText(src))?.let { (LayoutReader.fragment(it) as? Parsed.Ok)?.value }
                    ?: return Taken.Not("its fragment $src does not read")
                fragments[src] = f
            }
            return judge(id, layout, fragments, app(id))?.let { Taken.Not(it) } ?: Taken.Yes(layout, fragments)
        }

        /**
         * The pack's layout files [files] (`k18.xml`) in [dir], as they would be drawn: each keyboard the app has that
         * reads and is [judge]d typable used, its fragments the pack's where it has them; the rest with why not (a
         * fragment none of those includes is no keyboard). [app]: the app's layout of an id.
         */
        fun report(dir: File, files: List<String>, app: (String) -> SceneLayout?, appText: (String) -> String?): Report {
            val used = ArrayList<String>()
            val unused = ArrayList<Pair<String, String>>()
            // the fragments of the keyboards used, each counted once (k14 and k18 share the bottom row)
            val included = HashMap<String, Fragment>()
            var commits = 0
            val ids = files.map { it.removeSuffix(".xml") }
            for (id in ids) {
                when (val r = taken(dir, id, app, appText)) {
                    is Taken.Not -> r.why?.let { unused += id to it }
                    is Taken.Yes -> {
                        used += id
                        commits += commits(r.layout, emptyMap())
                        r.fragments.filterKeys { read(dir, it) != null }.forEach { (src, f) -> included[src] = f }
                    }
                }
            }
            commits += included.values.sumOf { f -> commits(listOf(f.defaults), f.rows) }
            ids.filter { it !in used && it !in included && unused.none { u -> u.first == it } }
                .forEach { unused += it to "a fragment no keyboard of the pack includes" }
            return Report(used, unused, commits)
        }
    }
}
