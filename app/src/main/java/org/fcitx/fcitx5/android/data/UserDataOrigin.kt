/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

/** This fork was built as upstream's org.fcitx.fcitx5.android until 2026-10-02, when it became 幽默输入法. */
const val LEGACY_APPLICATION_ID_ROOT = "org.fcitx.fcitx5.android"

/**
 * Where user data exported by [exporter] comes from, for the app [applicationId] whose id without the
 * build type's suffix is [root]: [Own] when it is this app's, [Legacy] when it is the same build type's
 * from before the renaming, so that a backup outlives it, and null for any other app's.
 */
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

        /** PreferenceManager.getDefaultSharedPreferences's file in shared_prefs. */
        fun preferencesFile(packageName: String) = "${packageName}_preferences.xml"
    }
}
