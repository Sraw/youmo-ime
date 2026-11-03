/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.applyNavBarInsetsBottomPadding
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.openUrl
import org.fcitx.fcitx5.android.utils.queryFileName
import org.fcitx.fcitx5.android.utils.toast
import splitties.resources.styledDrawable

class ThemeListFragment : Fragment() {

    private lateinit var importLauncher: ActivityResultLauncher<String>

    private lateinit var exportLauncher: ActivityResultLauncher<String>

    private lateinit var themeListAdapter: ThemeListAdapter

    private var followSystemDayNightTheme by ThemeManager.prefs.followSystemDayNightTheme

    private var beingExported: Theme.Custom? = null

    @Keep
    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        lifecycleScope.launch {
            updateSelectedThemes(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        importLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                val cr = ctx.contentResolver
                lifecycleScope.withLoadingDialog(ctx) {
                    val name = cr.queryFileName(uri) ?: return@withLoadingDialog
                    val ext = name.substringAfterLast('.')
                    if (ext != "zip") {
                        ctx.importErrorDialog(R.string.exception_theme_filename, ext)
                        return@withLoadingDialog
                    }
                    try {
                        val (newCreated, theme, migrated) = withContext(Dispatchers.IO) {
                            val inputStream = cr.openInputStream(uri)!!
                            ThemeFilesManager.importTheme(inputStream).getOrThrow()
                        }
                        // one imported again under its name: its files new under the old names
                        ThemeManager.filesChanged()
                        ThemeManager.refreshThemes()
                        if (newCreated) {
                            themeListAdapter.prependTheme(theme)
                        } else {
                            themeListAdapter.replaceTheme(theme)
                        }
                        if (migrated) {
                            ctx.toast(R.string.theme_migrated)
                        }
                    } catch (e: Exception) {
                        ctx.importErrorDialog(e)
                    }
                }
            }
        exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
                if (uri == null) return@registerForActivityResult
                val ctx = requireContext()
                val exported = beingExported ?: return@registerForActivityResult
                beingExported = null
                lifecycleScope.withLoadingDialog(requireContext()) {
                    try {
                        withContext(Dispatchers.IO) {
                            val outputStream = ctx.contentResolver.openOutputStream(uri)!!
                            ThemeFilesManager.exportTheme(exported, outputStream).getOrThrow()
                        }
                    } catch (e: Exception) {
                        ctx.toast(e)
                    }
                }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        themeListAdapter = object : ThemeListAdapter() {
            override fun onAddNewTheme() = addTheme()
            override fun onSelectTheme(theme: Theme) = selectTheme(theme)
            override fun onExportTheme(theme: Theme.Custom) = exportTheme(theme)
            override fun onDeleteTheme(theme: Theme.Custom) = deleteTheme(theme)
        }
        ThemeManager.refreshThemes()
        themeListAdapter.setThemes(ThemeManager.getAllThemes())
        updateSelectedThemes()
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        return ResponsiveThemeListView(requireContext()).apply {
            adapter = themeListAdapter
            applyNavBarInsetsBottomPadding()
        }
    }

    private fun updateSelectedThemes(activeTheme: Theme? = null) {
        val active = activeTheme ?: ThemeManager.activeTheme
        var light: Theme? = null
        var dark: Theme? = null
        if (followSystemDayNightTheme) {
            light = ThemeManager.prefs.lightModeTheme.getValue()
            dark = ThemeManager.prefs.darkModeTheme.getValue()
        }
        themeListAdapter.setSelectedThemes(active, light, dark)
    }

    // themes are made on the web page only (exported there as a zip, imported here); the app picks,
    // exports and deletes them
    private fun addTheme() {
        // the themes not settled: an import would go nowhere the next start reads
        if (ThemeManager.refuseIfNotSettled()) return
        val ctx = requireContext()
        val actions = arrayOf(
            getString(R.string.import_from_file),
            getString(R.string.make_theme_on_web),
        )
        AlertDialog.Builder(ctx)
            .setTitle(R.string.new_theme)
            .setNegativeButton(android.R.string.cancel, null)
            .setItems(actions) { _, i ->
                when (i) {
                    0 -> importLauncher.launch("application/zip")
                    1 -> ctx.openUrl(Const.themeMakerUrl)
                }
            }
            .show()
    }

    private fun selectTheme(theme: Theme) {
        if (followSystemDayNightTheme) {
            val ctx = requireContext()
            AlertDialog.Builder(ctx)
                .setIcon(ctx.styledDrawable(android.R.attr.alertDialogIcon))
                .setTitle(R.string.configure)
                .setMessage(R.string.theme_message_follow_system_day_night_mode_enabled)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.disable_it) { _, _ ->
                    followSystemDayNightTheme = false
                    lifecycleScope.launch {
                        ThemeManager.setNormalModeTheme(theme)
                        updateSelectedThemes()
                    }
                }
                .show()
            return
        }
        ThemeManager.setNormalModeTheme(theme)
    }

    private fun exportTheme(theme: Theme.Custom) {
        beingExported = theme
        exportLauncher.launch(theme.name + ".zip")
    }

    private fun deleteTheme(theme: Theme.Custom) {
        if (ThemeManager.refuseIfNotSettled()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_theme)
            .setMessage(R.string.delete_theme_msg)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (ThemeManager.deleteTheme(theme.name)) themeListAdapter.removeTheme(theme.name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        super.onDestroy()
    }
}
