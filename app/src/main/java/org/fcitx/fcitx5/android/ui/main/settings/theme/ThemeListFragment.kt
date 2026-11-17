/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.layout.ThemeLayoutsToList
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.theme.ThemeFileName
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.settings.openInputStreamOrThrow
import org.fcitx.fcitx5.android.ui.main.settings.openOutputStreamOrThrow
import org.fcitx.fcitx5.android.utils.applyNavBarInsetsBottomPadding
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.queryFileName
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber

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
                    // and one without a name is refused as well; another input method's skin is converted
                    val name = cr.queryFileName(uri)
                    val skin = ThemeFileName.isSkinFile(name)
                    if (!skin && !ThemeFileName.isThemeFile(name)) {
                        ctx.importErrorDialog(R.string.exception_theme_filename, name ?: ctx.getString(R.string.theme_file_unnamed))
                        return@withLoadingDialog
                    }
                    try {
                        // the list read here, where it is changed, not on the thread that imports
                        val themes = ThemeManager.getAllThemes()
                        val opened = withContext(Dispatchers.IO) {
                            // not over the start's listing of the packs (ThemeLayoutsToList.atStart): the pack it reads
                            // replaced, the layouts it would list told before it listed them
                            ThemeLayoutsToList.awaitStart()
                            val inputStream = cr.openInputStreamOrThrow(uri)
                            if (skin) {
                                ThemeFilesManager.openSkin(inputStream, name!!, themes).getOrThrow()
                            } else {
                                ThemeFilesManager.openTheme(inputStream, themes).getOrThrow()
                            }
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
                        importedNotice(ctx, migrated, opened.imported.renamedFrom, theme.name)?.let { (text, length) -> ctx.toast(text, length) }
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
                            val outputStream = ctx.contentResolver.openOutputStreamOrThrow(uri)
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

    /** The built-in theme [file]'s card scrolled to and given to [found], once it is laid out. */
    fun reveal(file: String, found: (View) -> Unit) {
        val list = view as? RecyclerView ?: return
        val at = themeListAdapter.entries.indexOfFirst { it is Theme.Builtin && it.file == file }.takeIf { it >= 0 } ?: return
        val position = at + ThemeListAdapter.OFFSET
        list.scrollToPosition(position)
        list.post {
            val holder = list.findViewHolderForAdapterPosition(position) as? ThemeListAdapter.ViewHolder ?: return@post
            found((holder.ui as? ThemeThumbnailUi)?.card ?: holder.itemView)
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

    // the app makes no themes: 新增主题 imports a file, or opens the web theme maker to make one
    private fun addTheme() {
        // the themes not settled: an import would go nowhere the next start reads
        if (ThemeManager.refuseIfNotSettled()) return
        ThemeAddMenu.show(requireContext()) { importLauncher.launch("*/*") }
    }

    private fun selectTheme(theme: Theme) {
        // a built-in theme of two versions: each slot its version, as a pack's
        val builtinPair = (theme as? Theme.Builtin)?.let { t ->
            val versions = ThemeManager.BuiltinThemes.filter { it.file == t.file }
            val light = versions.firstOrNull { !it.isDark }
            val dark = versions.firstOrNull { it.isDark }
            if (light != null && dark != null) light to dark else null
        }
        val (light, dark) = ThemeSlots.picked(theme, ThemeManager.packPair(theme) ?: builtinPair, followSystemDayNightTheme) ?: run {
            ThemeManager.setNormalModeTheme(theme)
            return
        }
        ThemeManager.prefs.lightModeTheme.setValue(light)
        ThemeManager.prefs.darkModeTheme.setValue(dark)
        // the normal slot too: following turned off later shows this pick, not the one before (written as the
        // slots are: the theme in use found anew by the system's mode)
        ThemeManager.prefs.normalModeTheme.setValue(theme)
        updateSelectedThemes()
    }

    private fun exportTheme(theme: Theme) {
        beingExported = theme
        exportLauncher.launch(ThemeFileName.of(ThemePackDialogs.title(theme)))
    }

    // a pack unpacked and checked: its metadata, what it warns of; taken on 导入, else let go
    private fun confirmPack(pack: ThemeFilesManager.Opened.Pack) {
        val ctx = requireContext()
        val title = ctx.getString(R.string.theme_pack_import_title, pack.staged.name)
        // let go however the dialog closes but by 导入 (cancelled, dismissed); one left by a fragment destroyed
        // with it open is cleared by a later open (ThemeFilesManager.openTheme)
        var taken = false
        // its boards the user picks among their own put in 外观 → 布局 with it (061-custom-layout §11), ticked unless unticked
        val withLayouts = pack.staged.layoutReport.listed.takeIf { it.isNotEmpty() }?.let {
            CheckBox(ctx).apply {
                setText(R.string.theme_pack_layouts_with)
                isChecked = true
            }
        }
        ThemePackDialogs.builder(ctx, title, ThemePackDialogs.confirmText(ctx, pack), pack.staged.preview, withLayouts)
            .setPositiveButton(R.string.theme_pack_import) { _, _ ->
                taken = true
                val listLayouts = withLayouts?.isChecked == true
                lifecycleScope.withLoadingDialog(ctx) {
                    try {
                        withContext(Dispatchers.IO) { pack.commit().getOrThrow() }
                        ThemeManager.packPlaced(pack.staged.name)
                        // its versions one card, a pack it replaced gone: the list made again
                        themeListAdapter.resetThemes(ThemeManager.getAllThemes())
                        updateSelectedThemes()
                    } catch (e: Exception) {
                        ctx.importErrorDialog(e)
                        return@withLoadingDialog
                    }
                    // the theme in, whatever becomes of its layouts: what fails listing them said as theirs, not as the
                    // import's failure (R26 F8378: it was, the list not made again)
                    if (withLayouts != null) {
                        try {
                            listLayouts(pack.staged.name, listLayouts, pack.staged.layoutReport.listed.size)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                            Timber.w(e, "theme pack %s: layouts not listed", pack.staged.name)
                            context?.toast(R.string.theme_pack_layouts_not_listed, Toast.LENGTH_LONG)
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!taken) lifecycleScope.launch(Dispatchers.IO) { pack.discard() } }
            .show()
    }

    // the pack [name] just imported, its boards put in 外观 → 布局 ([listed], none chosen), told where fewer than the
    // confirmation listed ([expected]: the list full, one failed); or, unticked, recorded not to be listed by a restore.
    // The theme imported whatever becomes of them
    private suspend fun listLayouts(name: String, listed: Boolean, expected: Int) {
        val pack = ThemeManager.Packs.firstOrNull { it.file == name } ?: return
        if (!listed) {
            withContext(Dispatchers.IO) {
                runCatching { ThemeLayoutsToList.notListed(pack) }.onFailure { Timber.w(it, "theme pack %s: not recorded unlisted", name) }
            }
            return
        }
        val made = withContext(Dispatchers.IO) {
            runCatching { ThemeLayoutsToList.run(pack) }.onFailure { Timber.w(it, "theme pack %s: layouts not listed", name) }.getOrNull()
        }
        // here, on the main thread, where the settings pages change them too
        made?.forEach { ThemeLayoutsToList.copySettings(it.done.renames) }
        context?.let { ctx -> layoutsNotListedNotice(ctx, made, expected)?.let { ctx.toast(it, Toast.LENGTH_LONG) } }
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
                if (ThemeManager.deleteTheme(theme)) themeListAdapter.removeTheme(theme)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        super.onDestroy()
    }

    companion object {
        /**
         * What a theme imported says, one toast and its length: [migrated] (an older format's, made anew), and where its
         * name was another theme's (a built-in one's since 0.6.0) [renamedFrom], the name it is under now, [name]. One
         * toast: the newest takes the place of one shown (showBriefly), so a second would hide the first (R26 F8137).
         * Null, nothing to say.
         */
        @VisibleForTesting
        internal fun importedNotice(ctx: Context, migrated: Boolean, renamedFrom: String?, name: String): Pair<String, Int>? {
            val lines = listOfNotNull(
                ctx.getString(R.string.theme_migrated).takeIf { migrated },
                renamedFrom?.let { ctx.getString(R.string.theme_imported_renamed, it, name) },
            )
            if (lines.isEmpty()) return null
            return lines.joinToString("\n") to if (renamedFrom != null) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        }

        /**
         * What an import with 「一起导入」 says of its boards: [made] (null: the listing failed) fewer than the [expected]
         * the confirmation listed (the list full, one failed), that not all were put in 外观 → 布局 (ST-72, ST-84). Null,
         * all listed (R26 F8518).
         */
        @VisibleForTesting
        internal fun layoutsNotListedNotice(ctx: Context, made: List<ThemeLayoutsToList.Listed>?, expected: Int): String? =
            ctx.getString(R.string.theme_pack_layouts_not_listed).takeIf { made == null || made.size < expected }
    }
}
