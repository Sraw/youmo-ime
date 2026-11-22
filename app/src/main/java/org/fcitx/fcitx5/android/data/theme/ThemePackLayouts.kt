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
import org.fcitx.fcitx5.android.scene.layout.KeyData
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.layout.LayoutReader
import org.fcitx.fcitx5.android.scene.layout.PackLayouts
import org.fcitx.fcitx5.android.scene.layout.Row
import java.io.File

/** Only replaces keyboards the user does not pick among their own; one less typable than the app's is not taken. */
class ThemePackLayouts(private val context: Context, private val pack: Theme.Pack) : LayoutSource {

    override val key: String get() = "$PREFIX${pack.file}@${pack.stamp}"

    override fun text(id: String): String? = read(File(pack.dir, ThemePackFiles.LAYOUTS), id)

    override fun refuses(id: String, layout: Layout, fragments: Map<String, Fragment>): String? =
        judge(id, layout, fragments, SceneLayouts.builtIn(context, id))

    // 主题只管外观: held and swiped actions are the app keys'
    override val keyData: KeyData.Mode get() = KeyData.Mode.Pack

    /** [ignored]: held/swiped actions not taken; [listed]: the layouts 「一起导入」 would make, [lacking] what those lack. */
    class Report(
        val used: List<String>,
        val unused: List<Pair<String, String>>,
        val commits: Int,
        val ignored: Int = 0,
        val listed: List<String> = emptyList(),
        val listedCommits: Int = 0,
        val replaced: List<String> = emptyList(),
        val notFitting: Int = 0,
        val lacking: List<Pair<String, List<LayoutCheck.Finding>>> = emptyList(),
    ) {
        fun listing(replaced: List<String>, notFitting: Int) = Report(used, unused, commits, ignored, listed, listedCommits, replaced, notFitting, lacking)
    }

    companion object {
        const val PREFIX = "theme:"
        private const val BOM = "\uFEFF"


        fun current(context: Context): LayoutSource =
            (ThemeManager.activeOrNull as? Theme.Pack)?.let { ThemePackLayouts(context, it) } ?: BuiltInLayouts(context)

        /** Only ids a pack replaces, within the limit, BOM stripped. */
        fun read(dir: File, id: String): String? {
            if (!PackLayouts.replaces(id)) return null
            val file = File(dir, "$id.xml")
            if (!file.isFile || file.length() > ThemeAssetLimits.MAX_LAYOUT_BYTES) return null
            return file.readText().removePrefix(BOM)
        }

        /** Shared with the theme maker page. */
        fun judge(id: String, layout: Layout, fragments: Map<String, Fragment>, app: SceneLayout?): String? =
            PackLayouts.judge(id, layout, fragments, app?.layout, app?.fragments.orEmpty())

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
            class Yes(val layout: Layout, val fragments: Map<String, Fragment>, val ignored: List<KeyData.Ignored>) : Taken

            /** [why] null for a fragment (told unless a used keyboard includes it). */
            class Not(val why: String?) : Taken
        }

        private fun taken(dir: File, id: String, app: (String) -> SceneLayout?, appText: (String) -> String?): Taken {
            val text = read(dir, id) ?: return Taken.Not(if (PackLayouts.replaces(id)) "does not read" else "not a keyboard of the app")
            val layout = (LayoutReader.layout(text) as? Parsed.Ok)?.value
                ?: return Taken.Not("does not read".takeIf { LayoutReader.fragment(text) !is Parsed.Ok })
            val fragments = HashMap<String, Fragment>()
            for (src in layout.parts.filterIsInstance<Include>().map { it.src }.distinct()) {
                val f = (read(dir, src) ?: appText(src))?.let { (LayoutReader.fragment(it) as? Parsed.Ok)?.value }
                    ?: return Taken.Not("its fragment $src does not read")
                fragments[src] = f
            }
            judge(id, layout, fragments, app(id))?.let { return Taken.Not(it) }
            // a pack's board is of its own id whatever based-on= says
            return KeyData.of(id, layout, fragments, KeyData.Mode.Pack, basedOn = null).let { Taken.Yes(it.layout, it.fragments, it.ignored) }
        }

        fun report(
            dir: File,
            files: List<String>,
            app: (String) -> SceneLayout?,
            appText: (String) -> String?,
            listed: List<Pair<String, PackLayouts.Converted>> = emptyList(),
        ): Report {
            val used = ArrayList<String>()
            val unused = ArrayList<Pair<String, String>>()
            // each counted once (k14 and k18 share the bottom row)
            val included = HashMap<String, Fragment>()
            val ignored = HashSet<KeyData.Ignored>()
            var commits = 0
            var listedCommits = 0
            val ids = files.map { it.removeSuffix(".xml") }
            val made = listed.toMap()
            val inList = HashSet<String>()
            for (id in ids) {
                val c = made[id]
                // a listed one took the bottom row in: no fragment unused
                if (c is PackLayouts.Converted.Done) {
                    inList += includes(File(dir, "$id.xml"))
                    listedCommits += c.commits.size
                }
                if (c is PackLayouts.Converted.Not) unused += id to c.why
                if (c != null) continue
                when (val r = taken(dir, id, app, appText)) {
                    is Taken.Not -> r.why?.let { unused += id to it }
                    is Taken.Yes -> {
                        used += id
                        commits += commits(r.layout, emptyMap())
                        ignored += r.ignored
                        r.fragments.filterKeys { read(dir, it) != null }.forEach { (src, f) -> included[src] = f }
                    }
                }
            }
            commits += included.values.sumOf { f -> commits(listOf(f.defaults), f.rows) }
            ids.filter { it !in used && it !in included && it !in inList && it !in made && unused.none { u -> u.first == it } }
                .forEach { unused += it to "a fragment no keyboard of the pack includes" }
            // in the order made, not the files'
            val done = listed.filter { it.first in ids }.mapNotNull { it.second as? PackLayouts.Converted.Done }
            val lacking = done.mapNotNull { d -> d.problems.filter { it.key == "lacks" }.takeIf { it.isNotEmpty() }?.let { d.name to it } }
            return Report(used, unused, commits, ignored.size, done.map { it.name }, listedCommits, lacking = lacking)
        }

        private fun includes(file: File): List<String> {
            val text = file.takeIf { it.isFile && it.length() <= ThemeAssetLimits.MAX_LAYOUT_BYTES }?.readText()?.removePrefix(BOM) ?: return emptyList()
            return (LayoutReader.layout(text) as? Parsed.Ok)?.value?.parts?.filterIsInstance<Include>()?.map { it.src }.orEmpty()
        }
    }
}
