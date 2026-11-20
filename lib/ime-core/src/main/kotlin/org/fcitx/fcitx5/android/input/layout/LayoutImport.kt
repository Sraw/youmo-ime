/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.layout

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.layout.LayoutReader

/** A read and strictly checked `.layout` import: refused with its first [SHOWN] errors, refused for too many layouts, or one to confirm. */
object LayoutImport {
    /** The rest only counted. */
    const val SHOWN = 5

    /** As the import compares with it; [version] `<meta version>`. */
    data class Installed(val id: String, val name: String, val version: String?)

    sealed interface Verdict {
        data class Refused(val errors: List<LayoutCheck.Finding>, val more: Int) : Verdict

        /** This is none of them. */
        data class TooMany(val max: Int) : Verdict

        /** [replaces] null where none; [commits] the texts its keys type that its base's do not, the first [SHOWN]. */
        class Ok(
            val layout: Layout,
            val report: LayoutCheck.Report,
            val replaces: Installed?,
            val commits: List<String>,
            val moreCommits: Int,
        ) : Verdict
    }

    /** [report] is [text]'s strict check. */
    fun judge(text: String, report: LayoutCheck.Report, installed: List<Installed>): Verdict {
        if (!report.ok) return Verdict.Refused(report.errors.take(SHOWN), (report.errors.size - SHOWN).coerceAtLeast(0))
        // a report without errors is of a layout that reads
        val layout = layout(text) ?: return Verdict.Refused(listOf(LayoutCheck.Finding(0, "read", listOf("no layout"))), 0)
        val replaces = installed.firstOrNull { it.id == layout.id }
        if (replaces == null && installed.size >= CustomLayoutFiles.MAX_LAYOUTS) return Verdict.TooMany(CustomLayoutFiles.MAX_LAYOUTS)
        return Verdict.Ok(layout, report, replaces, report.commits.take(SHOWN), (report.commits.size - SHOWN).coerceAtLeast(0))
    }

    /** Its byte-order mark aside, as the check reads it. */
    fun layout(text: String): Layout? = (LayoutReader.layout(text.removePrefix(BOM)) as? Parsed.Ok)?.value

    /** Its name as shown, [LayoutCheck.plain]. */
    fun installed(layout: Layout): Installed = Installed(layout.id, LayoutCheck.plain(layout.name), layout.meta?.version)

    /** [LayoutCheck.check]'s `min-app`: a dev build (no release version) takes all. */
    fun appVersion(versionName: String?): String = versionName ?: ANY_VERSION

    private const val ANY_VERSION = "999999"
    private const val BOM = "﻿"
}
