/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.widget.Toast
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.assertRows
import org.fcitx.fcitx5.android.utils.appContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** One toast, as a second takes the place of the first: an older format's theme under a taken name hid 「已迁移」 at once. */
@RunWith(RobolectricTestRunner::class)
@Config(application = FcitxApplication::class)
class ThemeImportedNoticeTest {

    private fun s(id: Int, vararg args: Any) = appContext.getString(id, *args)

    @Test
    fun eachThingAnImportSaysIsInOneToast() {
        val renamed = s(R.string.theme_imported_renamed, "Pixel", "Pixel 2")
        val migrated = s(R.string.theme_migrated)
        fun said(wasMigrated: Boolean, from: String?) = ThemeListFragment.importedNotice(appContext, wasMigrated, from, "Pixel 2")
        assertRows(
            "a theme of the format now, its name free: nothing to say" to { assertEquals(null, said(false, null)) },
            "an older format's theme, its name free: 「已迁移」, briefly" to {
                assertEquals(migrated to Toast.LENGTH_SHORT, said(true, null))
            },
            "the name it is under now read: the long toast, as before" to {
                assertEquals(renamed to Toast.LENGTH_LONG, said(false, "Pixel"))
            },
            "both, one toast: neither hidden by the other" to {
                assertEquals("$migrated\n$renamed" to Toast.LENGTH_LONG, said(true, "Pixel"))
            },
        )
    }
}
