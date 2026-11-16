/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.layout

import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.scene.layout.LayoutReader

/**
 * What the import of a `.layout` file comes to (061-custom-layout §4.2), once it is read ([CustomLayoutFiles.read]) and
 * checked strictly ([LayoutCheck.check]): refused with its first [SHOWN] errors, nothing kept; refused for the phone
 * holding [CustomLayoutFiles.MAX_LAYOUTS] already; or one to confirm, with what it replaces and the texts its keys type.
 */
object LayoutImport {
    /** The most errors, and of the texts typed as written, a dialog lists; the rest only counted. */
    const val SHOWN = 5

    /** A layout of one's own on the phone, as the import compares with it: its [id], [name] and `<meta version>`. */
    data class Installed(val id: String, val name: String, val version: String?)

    sealed interface Verdict {
        /** Not imported: [errors] (the first [SHOWN]), [more] not shown. */
        data class Refused(val errors: List<LayoutCheck.Finding>, val more: Int) : Verdict

        /** Not imported: the phone has [max] layouts of its own already, and this is none of them. */
        data class TooMany(val max: Int) : Verdict

        /**
         * To confirm: [layout] as read, what [report] says of it, the one of its id it replaces ([replaces], null where none),
         * the texts its keys type that its base's do not ([commits], the first [SHOWN]; [moreCommits] not listed).
         */
        class Ok(
            val layout: Layout,
            val report: LayoutCheck.Report,
            val replaces: Installed?,
            val commits: List<String>,
            val moreCommits: Int,
        ) : Verdict
    }

    /** [text], which [report] is the strict check of, against the layouts [installed] on the phone. */
    fun judge(text: String, report: LayoutCheck.Report, installed: List<Installed>): Verdict {
        if (!report.ok) return Verdict.Refused(report.errors.take(SHOWN), (report.errors.size - SHOWN).coerceAtLeast(0))
        // the check read it: a report without errors is of a layout that reads
        val layout = layout(text) ?: return Verdict.Refused(listOf(LayoutCheck.Finding(0, "read", listOf("no layout"))), 0)
        val replaces = installed.firstOrNull { it.id == layout.id }
        if (replaces == null && installed.size >= CustomLayoutFiles.MAX_LAYOUTS) return Verdict.TooMany(CustomLayoutFiles.MAX_LAYOUTS)
        return Verdict.Ok(layout, report, replaces, report.commits.take(SHOWN), (report.commits.size - SHOWN).coerceAtLeast(0))
    }

    /** [text] read as a layout (its byte-order mark aside, as the check reads it); null where it does not read. */
    fun layout(text: String): Layout? = (LayoutReader.layout(text.removePrefix(BOM)) as? Parsed.Ok)?.value

    /** [layout] as one of the phone's, for a later import to compare with (its name as shown, [LayoutCheck.plain]). */
    fun installed(layout: Layout): Installed = Installed(layout.id, LayoutCheck.plain(layout.name), layout.meta?.version)

    /** The version an import is checked against ([LayoutCheck.check]'s `min-app`): [versionName] where it is a release's, else any (a dev build takes all). */
    fun appVersion(versionName: String?): String = versionName ?: ANY_VERSION

    private const val ANY_VERSION = "999999"
    private const val BOM = "﻿"
}
