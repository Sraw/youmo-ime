/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

/** This fork's package before it became 幽默输入法. */
const val LEGACY_APPLICATION_ID_ROOT = "org.fcitx.fcitx5.android"

/** [Own] for this app's data, [Legacy] for the same build type's from before the rename (so backups outlive it), else null. */
sealed interface UserDataOrigin {
    data object Own : UserDataOrigin

    /** Android names the default preferences after the package: they are renamed on the way in. */
    data class Legacy(val preferences: String, val renamed: String) : UserDataOrigin

    companion object {
        fun of(exporter: String, applicationId: String, root: String): UserDataOrigin? = when {
            exporter == applicationId -> Own
            applicationId.startsWith(root) &&
                exporter == LEGACY_APPLICATION_ID_ROOT + applicationId.removePrefix(root) ->
                Legacy(preferencesFile(exporter), preferencesFile(applicationId))
            else -> null
        }

        fun preferencesFile(packageName: String) = "${packageName}_preferences.xml"
    }
}
