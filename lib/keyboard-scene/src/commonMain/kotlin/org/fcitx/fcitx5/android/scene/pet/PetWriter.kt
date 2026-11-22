/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.pet

import org.fcitx.fcitx5.android.scene.theme.ThemeWriter

/** A [Pet] as theme.xml's `<pet>`, every attribute its place reads written, so [PetReader] reads back the same pet. */
object PetWriter {

    fun write(pet: Pet): String = buildString {
        append("  <pet place=\"${pet.place.id}\" size=\"${dp(pet.sizeDp)}\"")
        if (pet.place == Pet.Place.KEYBOARD) append(" align=\"${pet.align.id}\" offset-x=\"${dp(pet.offsetXDp)}\" offset-y=\"${dp(pet.offsetYDp)}\"")
        appendLine(">")
        pet.states.entries.sortedBy { it.key.ordinal }.forEach { (name, s) ->
            appendLine(
                "    <state name=\"${name.id}\" sheet=\"${escape(s.sheet)}\" frames=\"${s.frames}\" columns=\"${s.columns}\" " +
                    "fps=\"${s.fps}\" loop=\"${s.loop}\"/>",
            )
        }
        appendLine("  </pet>")
    }

    private fun dp(v: Double) = if (v == 0.0) "0" else ThemeWriter.number(v) + "dp"

    // a file name has no quote, angle bracket or control character (ThemeReader.FILE)
    private fun escape(s: String) = s.replace("&", "&amp;")
}
