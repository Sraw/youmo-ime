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
import org.fcitx.fcitx5.android.input.theme.ThemeFileName
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.utils.applyNavBarInsetsBottomPadding
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.queryFileName
import org.fcitx.fcitx5.android.utils.toast
import splitties.resources.styledDrawable

class ThemeListFragment : Fragment() {

    private lateinit var importLauncher: ActivityResultLauncher<String>

    private lateinit var exportLauncher: ActivityResultLauncher<String>

    private lateinit var themeListAdapter: ThemeListAdapter

    private var followSystemDayNightTheme by ThemeManager.prefs.followSystemDayNightTheme

    private var beingExported: Theme? = null

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
                    // the picker shows every file (.theme has no MIME type of its own): the name decides,
                    // and one without a name is refused as well
                    val name = cr.queryFileName(uri)
                    if (!ThemeFileName.isThemeFile(name)) {
                        ctx.importErrorDialog(R.string.exception_theme_filename, name ?: ctx.getString(R.string.theme_file_unnamed))
                        return@withLoadingDialog
                    }
                    try {
                        // the list read here, where it is changed, not on the thread that imports
                        val themes = ThemeManager.getAllThemes()
                        val opened = withContext(Dispatchers.IO) {
                            val inputStream = cr.openInputStream(uri)!!
                            ThemeFilesManager.openTheme(inputStream, themes).getOrThrow()
                        }
                        // a theme 4.0 pack: shown, taken only if the user does (060-render-p3 T8a)
                        if (opened is ThemeFilesManager.Opened.Pack) {
                            confirmPack(opened)
                            return@withLoadingDialog
                        }
                        val (newCreated, theme, migrated) = (opened as ThemeFilesManager.Opened.Json).imported
                        // one imported again under its name: its files new under the old names, the
                        // keyboard drawn anew if it is the one in use
                        ThemeManager.filesChanged(theme.name) { ThemeManager.refreshThemes() }
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
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
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
            override fun onExportTheme(theme: Theme) = exportTheme(theme)
            override fun onDeleteTheme(theme: Theme) = deleteTheme(theme)
            override fun onThemeInfo(theme: Theme) = themeInfo(theme)
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

    // the app lists, picks, imports, exports and deletes themes; it makes none, so 新增主题 is the import's file picker
    private fun addTheme() {
        // the themes not settled: an import would go nowhere the next start reads
        if (ThemeManager.refuseIfNotSettled()) return
        importLauncher.launch("*/*")
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

    private fun exportTheme(theme: Theme) {
        beingExported = theme
        exportLauncher.launch(ThemeFileName.of(ThemePackDialogs.title(theme)))
    }

    // a pack unpacked and checked: its metadata, what it warns of; taken on 导入, else let go
    private fun confirmPack(pack: ThemeFilesManager.Opened.Pack) {
        val ctx = requireContext()
        val title = ctx.getString(R.string.theme_pack_import_title, pack.staged.name)
        val discard = { lifecycleScope.launch(Dispatchers.IO) { pack.discard() } }
        ThemePackDialogs.builder(ctx, title, ThemePackDialogs.confirmText(ctx, pack), pack.staged.preview)
            .setPositiveButton(R.string.theme_pack_import) { _, _ ->
                lifecycleScope.withLoadingDialog(ctx) {
                    try {
                        withContext(Dispatchers.IO) { pack.commit().getOrThrow() }
                        ThemeManager.packPlaced(pack.staged.name)
                        // its versions one card, a pack it replaced gone: the list made again
                        themeListAdapter.resetThemes(ThemeManager.getAllThemes())
                        updateSelectedThemes()
                    } catch (e: Exception) {
                        ctx.importErrorDialog(e)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> discard() }
            .setOnCancelListener { discard() }
            .show()
    }

    private fun themeInfo(theme: Theme) {
        val ctx = requireContext()
        ThemePackDialogs.builder(ctx, ThemePackDialogs.title(theme), ThemePackDialogs.infoText(ctx, theme), ThemePackDialogs.preview(theme))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun deleteTheme(theme: Theme) {
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
