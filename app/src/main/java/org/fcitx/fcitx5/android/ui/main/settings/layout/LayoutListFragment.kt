/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.layout

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import java.io.IOException
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.data.layout.CustomKeyboards
import org.fcitx.fcitx5.android.data.layout.CustomLayouts
import org.fcitx.fcitx5.android.data.layout.ThemeLayoutsToList
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePackFiles
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.input.keyboard.CustomChoice
import org.fcitx.fcitx5.android.input.keyboard.ImKeyboards
import org.fcitx.fcitx5.android.input.keyboard.KeyboardLabels
import org.fcitx.fcitx5.android.input.keyboard.PanelLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayout
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemes
import org.fcitx.fcitx5.android.input.layout.CustomLayoutFiles
import org.fcitx.fcitx5.android.input.layout.LayoutImport
import org.fcitx.fcitx5.android.input.layout.LayoutThumbnail
import org.fcitx.fcitx5.android.scene.layout.KeyData
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.LayoutCheck
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.settings.openInputStreamOrThrow
import org.fcitx.fcitx5.android.ui.main.settings.openOutputStreamOrThrow
import org.fcitx.fcitx5.android.ui.main.settings.showingFileErrors
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeFragment
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeNames
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeThumbnailKeys
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeThumbnailUi
import org.fcitx.fcitx5.android.utils.applyNavBarInsetsBottomPadding
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.styledColor
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp
import timber.log.Timber

/**
 * 外观 → 布局 (061-custom-layout §4.3): the layouts by what they are (字母键盘, 数字键盘, 符号面板), the app's first, then
 * the user's own, each a card of its keys in little in the theme in use. A card tapped shows in the preview above (no
 * setting changed), one of the user's of letters offering 用在… (the input methods it may be typed on, ticked where they
 * use it); one of the user's long pressed: 信息, 导出, 删除. **+**: import a `.layout` file, or open the layout maker in
 * the browser. Under each of the user's, 「用在：拼音、英文」: the input methods whose keyboard it is (L3, §4.3). The number
 * keyboard's and the symbol panel's are one choice each (L5, §7, §8): a card of theirs tapped offers 数字键盘用这个 (the
 * app's one too, to go back to it), the one in use says 「用在：数字键盘」.
 */
class LayoutListFragment : Fragment() {

    /** A row of the list: a section's title, or a layout's card. */
    sealed interface Item {
        class Header(val title: Int) : Item

        /** A layout: the app's ([entry] null) or the user's; [scene] what the preview draws, null where it does not read. */
        class Card(
            val id: String,
            val name: String,
            val entry: CustomLayouts.Entry?,
            val scene: SceneLayout?,
            val cells: List<LayoutThumbnail.Cell>,
            /** The input methods (ImKeyboards.slot) whose keyboard it is. */
            val usedBy: List<String> = emptyList(),
            /** The panel it is for (a number board's, the symbols'; the app's too); null for letters. */
            val panel: PanelLayouts.Panel? = null,
            /** Whether [panel] uses it; [unread]: chosen, not drawn (its file refused as it is read: the app's drawn meanwhile). */
            val panelUsed: Boolean = false,
            val unread: Boolean = false,
            /** The app's, used as the layout chosen for [panel] is no card of it (§4.4). */
            val missing: Boolean = false,
        ) : Item
    }

    private lateinit var importLauncher: ActivityResultLauncher<String>
    private lateinit var exportLauncher: ActivityResultLauncher<String>
    // the id of the layout whose file the saver is asked for: kept with the page, which may be made anew meanwhile
    private var beingExported: String? = null
    private var reloading: Job? = null

    private val adapter = Adapter()

    @Keep
    private val onThemeChangeListener = ThemeManager.OnThemeChangeListener {
        lifecycleScope.launch { adapter.notifyItemRangeChanged(0, adapter.itemCount) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        beingExported = savedInstanceState?.getString(BEING_EXPORTED)
        // the picker shows every file (.layout has no MIME type of its own): what is in it decides (§2.1)
        importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importLayout(uri)
        }
        exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument(MIME)) { uri ->
            val id = beingExported ?: return@registerForActivityResult
            beingExported = null
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext()
            lifecycleScope.withLoadingDialog(ctx) {
                ctx.showingFileErrors("cannot export layout $id") {
                    withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStreamOrThrow(uri).use { CustomLayouts.export(id, it) } }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(BEING_EXPORTED, beingExported)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        ThemeManager.addOnChangedListener(onThemeChangeListener)
        val list = Grid(ctx).apply {
            adapter = this@LayoutListFragment.adapter
            clipToPadding = false
            applyNavBarInsetsBottomPadding()
        }
        val fab = FloatingActionButton(ctx).apply {
            setImageDrawable(ctx.getDrawable(R.drawable.ic_baseline_plus_24))
            contentDescription = ctx.getString(R.string.layout_add)
            setOnClickListener { addLayout() }
            // the page reaches under the navigation bar (edge to edge): the button kept above it, as BaseDynamicListUi's
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom + ctx.dp(FAB_MARGIN_DP)
                }
                insets
            }
        }
        reload()
        return FrameLayout(ctx).apply {
            addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(fab, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                val m = ctx.dp(FAB_MARGIN_DP)
                setMargins(m, m, m, m)
            })
        }
    }

    override fun onDestroyView() {
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        super.onDestroyView()
    }

    // the cards read off the main thread: the app's few boards and each file of the user's read once (CustomLayouts.list);
    // a reload asked for again (an import, then a delete) is the one shown, not the slower of the two
    private fun reload() {
        val ctx = requireContext().applicationContext
        reloading?.cancel()
        reloading = lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { items(ctx) }
            adapter.items = items
            adapter.notifyDataSetChanged()
        }
    }

    private fun items(ctx: Context): List<Item> {
        // opened as the app starts after an upgrade or a restore: the packs' boards listed and chosen first, not missed
        ThemeLayoutsToList.awaitStart()
        val prefs = AppPrefs.getInstance().keyboard
        // each panel's choice: the app's ("") used where none of the user's is
        val panels = prefs.panelLayouts()
        val chosen = prefs.imKeyboards()
        val own = CustomLayouts.list(context = ctx).map { e ->
            val scene = e.layout?.let { scene(e.id, it, e.fragments) }
            val panel = PanelLayouts.listedUnder(e.family, e.id, panels)
            val used = panel != null && panels[panel] == e.id
            val unread = panel != null && used && PanelLayouts.unread(panel, e.id, CustomKeyboards.choice(e.id))
            Item.Card(e.id, e.name, e, scene, e.cells, ImKeyboards.usedBy(chosen, e.id), panel, used, unread)
        }
        // the app's: the panel's where none of the user's is chosen, or the one chosen is no card of the panel's (its file
        // gone, a backup restored without it; another family's now): said so (L5 review S1)
        fun builtIn(id: String): Item.Card? {
            val scene = SceneLayouts.parseBuiltIn(ctx, id) ?: return null
            val cells = runCatching { LayoutThumbnail.cells(scene.layout, scene.fragments) }.getOrDefault(emptyList())
            val panel = PanelLayouts.Panel.of(scene.layout.family)
            val missing = panel != null && PanelLayouts.missing(panel, panels, own.associate { it.id to it.panel })
            val used = panel != null && (panels[panel].isNullOrEmpty() || missing)
            return Item.Card(id, scene.layout.name, null, scene, cells, panel = panel, panelUsed = used, missing = missing)
        }
        fun section(of: (String?) -> Boolean) = own.filter { of(it.panel?.family ?: it.entry?.family) }
        val out = ArrayList<Item>()
        out += Item.Header(R.string.layout_section_letters)
        out += LETTERS.mapNotNull(::builtIn)
        // one that does not read is listed here, to be deleted
        out += section { it == null || it in LETTER_FAMILIES }
        out += Item.Header(R.string.layout_section_number)
        out += listOfNotNull(builtIn("number"))
        out += section { it == "number" }
        out += Item.Header(R.string.layout_section_symbols)
        out += listOfNotNull(builtIn("symbols"))
        out += section { it == "symbols" }
        return out
    }

    // a layout of one's own as the keyboard will draw it: the app's keys' long press and swipes on the app's names
    // (KeyData.Own), its base of its own family's (CustomLayoutSource.base)
    private fun scene(id: String, layout: Layout, fragments: Map<String, org.fcitx.fcitx5.android.scene.layout.Fragment>): SceneLayout {
        val base = runCatching { CustomKeyboards.baseOf(layout) }.getOrNull()
        val held = runCatching { KeyData.of(id, layout, fragments, KeyData.Mode.Own, base) }.getOrNull()
        return if (held != null) SceneLayout(id, held.layout, held.fragments, base ?: id) else SceneLayout(id, layout, fragments, base ?: id)
    }

    private fun addLayout() {
        val ctx = requireContext()
        LayoutAddMenu.show(ctx) { importLauncher.launch("*/*") }
    }

    private fun importLayout(uri: android.net.Uri) {
        val ctx = requireContext()
        // the theme in use for the blank keys' warning: made here, SceneThemes keeps its own on the main thread
        val active = ThemeManager.activeTheme
        val sceneTheme = SceneThemes.of(active, ThemeManager.shownLook(active))
        lifecycleScope.withLoadingDialog(ctx) {
            // what goes wrong is said, never the app's crash: the file's provider (an IOException, a SecurityException of
            // a grant gone) or a file made to throw the reader (CustomLayouts catches what it knows to)
            val opened = runCatching {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStreamOrThrow(uri).use {
                        CustomLayouts.open(it, ThemePackFiles.minAppVersion(BuildConfig.VERSION_NAME), sceneTheme)
                    }
                }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                Timber.w(e, "cannot import layout")
                ctx.importErrorDialog(e)
                return@withLoadingDialog
            }
            when (opened) {
                CustomLayouts.Opened.TooBig -> ctx.importErrorDialog(R.string.layout_import_too_big)
                CustomLayouts.Opened.NotText -> ctx.importErrorDialog(R.string.layout_import_not_text)
                is CustomLayouts.Opened.Judged -> when (val v = opened.verdict) {
                    is LayoutImport.Verdict.Refused -> dialog(ctx, ctx.getString(R.string.layout_import_refused), LayoutTexts.refused(ctx, v), null)
                        .setPositiveButton(android.R.string.ok, null).show()
                    is LayoutImport.Verdict.TooMany -> ctx.importErrorDialog(R.string.layout_import_too_many, v.max)
                    is LayoutImport.Verdict.Ok -> {
                        // its fragments read and its keys' data held off the main thread
                        val layout = v.layout
                        val preview = withContext(Dispatchers.IO) {
                            runCatching { CustomLayouts.fragments(layout, ctx)?.let { scene(layout.id, layout, it) } }.getOrNull()
                        }
                        confirm(ctx, opened.text, v, preview)
                    }
                }
            }
        }
    }

    // §4.2 step 3, 4: the layout drawn in the theme in use ([preview]), what it is; taken on 导入
    private fun confirm(ctx: Context, text: String, ok: LayoutImport.Verdict.Ok, preview: SceneLayout?) {
        val layout = ok.layout
        val name = LayoutTexts.shown(layout.name, layout.id)
        dialog(ctx, ctx.getString(R.string.layout_import_title, name), LayoutTexts.confirm(ctx, ok), preview)
            .setPositiveButton(R.string.theme_pack_import) { _, _ ->
                lifecycleScope.withLoadingDialog(ctx) {
                    try {
                        val read = withContext(Dispatchers.IO) {
                            CustomLayouts.save(layout.id, text)
                            CustomKeyboards.choice(layout.id)
                        }
                        ctx.toast(ctx.getString(R.string.layout_imported, name))
                        reload()
                        // §4.2 step 5: on what now, where it may be (one: asked of it; more: ticked); later, the keyboard options
                        read?.let { askUse(ctx, it, ask = true) }
                    } catch (e: IOException) {
                        ctx.importErrorDialog(e)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun select(card: Item.Card) {
        (parentFragment as? ThemeFragment)?.previewLayout(card.scene)
        // one that does not read is only to be deleted (its long press)
        if (card.entry != null && card.entry.layout == null) return
        // the number keyboard's, the symbols': 数字键盘用这个, the app's one too
        card.panel?.let { panel ->
            val view = view ?: return
            Snackbar.make(view, card.name, Snackbar.LENGTH_LONG)
                .setAction(if (panel == PanelLayouts.Panel.NUMBER) R.string.layout_use_number else R.string.layout_use_symbols) {
                    context?.let { usePanel(it, panel, card.entry?.id.orEmpty(), card.name) }
                }
                .show()
            return
        }
        // the user's of letters: 用在… under the preview, the input methods it may be typed on
        val entry = card.entry ?: return
        if (entry.family !in LETTER_FAMILIES) return
        val view = view ?: return
        Snackbar.make(view, card.name, Snackbar.LENGTH_LONG)
            .setAction(R.string.layout_use) {
                val ctx = context ?: return@setAction
                lifecycleScope.launch {
                    val (read, problems) = withContext(Dispatchers.IO) { CustomKeyboards.choice(entry.id) to CustomKeyboards.problems(entry.id) }
                    when {
                        read == null -> Unit
                        // refused under this version's rules (one let in by an older build, restored): why, not 「没有输入法能用」
                        !read.drawn -> ctx.toast(ctx.getString(R.string.layout_use_refused, read.name, problems.firstOrNull()?.let { LayoutTexts.finding(ctx, it) }.orEmpty()))
                        else -> askUse(ctx, read, ask = false)
                    }
                }
            }
            .show()
    }

    /**
     * [panel] set to layout [id] of the user's ("": the app's), [name] (§4.3: a single choice), said so; one refused under
     * this version's rules is not set, why said.
     */
    private fun usePanel(ctx: Context, panel: PanelLayouts.Panel, id: String, name: String) = lifecycleScope.launch {
        if (id.isNotEmpty()) {
            val (read, problems) = withContext(Dispatchers.IO) { CustomKeyboards.choice(id) to CustomKeyboards.problems(id) }
            if (read == null) {
                ctx.toast(ctx.getString(R.string.layout_info_unreadable))
                return@launch
            }
            if (PanelLayouts.drawn(panel, read) == null) {
                ctx.toast(ctx.getString(R.string.layout_use_refused, read.name, problems.firstOrNull()?.let { LayoutTexts.finding(ctx, it) }.orEmpty()))
                return@launch
            }
        }
        AppPrefs.getInstance().keyboard.panelLayout(panel).setValue(id)
        ctx.toast(ctx.getString(R.string.layout_panel_uses, panelName(ctx, panel), name))
        reload()
    }

    /**
     * 用在… of [layout] (§4.3): the input methods it may be typed on, each ticked where its keyboard is it; set as ticked on
     * OK, one unticked back on its base (§4.4). [ask]: just imported (§4.2 step 5), one input method asked of by name, none
     * ticked. Nothing where none may use it (a refused one, one 双拼 alone cannot type on and so on).
     */
    private fun askUse(ctx: Context, layout: CustomChoice, ask: Boolean) {
        // a number board, a symbols one: 现在就用作数字键盘吗？ (§4.2 step 5)
        PanelLayouts.Panel.of(layout.family)?.let { panel ->
            if (!ask || PanelLayouts.drawn(panel, layout) == null) return
            // one replacing the panel's choice (or a file of it come back) is used as it is: said, not asked of
            if (AppPrefs.getInstance().keyboard.panelLayout(panel).getValue() == layout.id) {
                ctx.toast(ctx.getString(R.string.layout_panel_uses, panelName(ctx, panel), layout.name))
                return
            }
            AlertDialog.Builder(ctx)
                .setMessage(ctx.getString(R.string.layout_use_now_panel, panelName(ctx, panel)))
                .setPositiveButton(R.string.layout_use_yes) { _, _ -> usePanel(ctx, panel, layout.id, layout.name) }
                .setNegativeButton(R.string.layout_use_no, null)
                .show()
            return
        }
        val scheme = EngineState.shuangpinScheme
        val slots = ImKeyboards.usableBy(layout, scheme)
        if (slots.isEmpty()) {
            if (!ask) ctx.toast(ctx.getString(R.string.layout_use_none, layout.name))
            return
        }
        val prefs = AppPrefs.getInstance().keyboard
        val names = slots.map { imName(ctx, it) }
        fun done(ticked: List<String>) {
            val chosen = prefs.imKeyboards()
            for (slot in slots) {
                val k = ImKeyboards.ticked(chosen, slot, layout, slot in ticked, scheme) ?: continue
                prefs.setImKeyboard(ImKeyboards.imOf(slot), k, layout.id)
            }
            reload()
        }
        if (ask && slots.size == 1) {
            AlertDialog.Builder(ctx)
                .setMessage(ctx.getString(R.string.layout_use_now, names.single()))
                .setPositiveButton(R.string.layout_use_yes) { _, _ -> done(slots) }
                .setNegativeButton(R.string.layout_use_no, null)
                .show()
            return
        }
        val used = ImKeyboards.usedBy(prefs.imKeyboards(), layout.id)
        val checked = BooleanArray(slots.size) { !ask && slots[it] in used }
        AlertDialog.Builder(ctx)
            .setTitle(if (ask) ctx.getString(R.string.layout_use_now_many) else ctx.getString(R.string.layout_use_title, layout.name))
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton(android.R.string.ok) { _, _ -> done(slots.filterIndexed { i, _ -> checked[i] }) }
            .setNegativeButton(if (ask) R.string.layout_use_no else android.R.string.cancel, null)
            .show()
    }

    private fun actions(card: Item.Card) {
        val entry = card.entry ?: return
        val ctx = requireContext()
        // one that does not read: deleted, nothing else
        val items = if (entry.layout == null) {
            arrayOf(ctx.getString(R.string.theme_info), ctx.getString(R.string.delete))
        } else {
            arrayOf(ctx.getString(R.string.theme_info), ctx.getString(R.string.export), ctx.getString(R.string.delete))
        }
        AlertDialog.Builder(ctx)
            .setTitle(card.name)
            .setItems(items) { _, i ->
                when (items[i]) {
                    ctx.getString(R.string.theme_info) -> info(card, entry)
                    ctx.getString(R.string.export) -> {
                        beingExported = entry.id
                        exportLauncher.launch(CustomLayoutFiles.exportName(entry.name))
                    }
                    else -> delete(entry)
                }
            }
            .show()
    }

    private fun info(card: Item.Card, entry: CustomLayouts.Entry) {
        val ctx = requireContext()
        val layout = entry.layout
        if (layout == null) {
            dialog(ctx, entry.id, ctx.getString(R.string.layout_info_unreadable), null).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        lifecycleScope.withLoadingDialog(ctx) {
            // as it was taken (an import's or a restore's): the check of a theme pack's made one (§3.2), what it can be
            // used for; its text read again (the list keeps none)
            val report = withContext(Dispatchers.IO) {
                CustomLayouts.text(entry.file)?.let { text ->
                    runCatching { LayoutCheck.check(text, CustomLayouts.sources, LayoutImport.appVersion(null), strict = false) }.getOrNull()
                }
            }
            dialog(ctx, entry.name, LayoutTexts.info(ctx, layout, report), card.scene).setPositiveButton(android.R.string.ok, null).show()
        }
    }

    private fun delete(entry: CustomLayouts.Entry) = lifecycleScope.launch {
        val ctx = requireContext()
        val prefs = AppPrefs.getInstance().keyboard
        val scheme = EngineState.shuangpinScheme
        // §4.4: the input methods using it back on its base where they have it, else the full keyboard, said first; the
        // files read off the main thread
        val (chosen, read) = withContext(Dispatchers.IO) { prefs.imKeyboards() to CustomKeyboards.choice(entry.id) }
        val after = ImKeyboards.afterDelete(chosen, entry.id, read, scheme)
        // the number keyboard, the symbol panel using it: back on the app's
        val panels = PanelLayouts.usedBy(prefs.panelLayouts(), entry.id)
        val separator = ctx.getString(R.string.layout_list_separator)
        val message = if (panels.isNotEmpty()) {
            ctx.getString(R.string.delete_layout_in_use_panel, entry.name, panels.joinToString(separator) { panelName(ctx, it) })
        } else if (after.isEmpty()) {
            ctx.getString(R.string.delete_layout_msg, entry.name)
        } else {
            val users = after.keys.joinToString(separator) { imName(ctx, it) }
            val back = after.entries.joinToString(separator) { (slot, k) ->
                ctx.getString(R.string.layout_goes_back, imName(ctx, slot), KeyboardLabels.builtInName(ctx, ImKeyboards.imOf(slot), k))
            }
            ctx.getString(R.string.delete_layout_in_use, entry.name, users, back)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.delete_layout)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    val gone = withContext(Dispatchers.IO) { CustomLayouts.delete(entry.id) }
                    if (!gone) ctx.toast(ctx.getString(R.string.layout_not_deleted, entry.name))
                    // the preferences as it is gone, asked again (a keyboard chosen meanwhile kept): the input view made anew
                    if (gone) {
                        CustomKeyboards.forget(entry.id)
                        ImKeyboards.afterDelete(prefs.imKeyboards(), entry.id, null, scheme).keys.forEach { slot ->
                            after[slot]?.let { prefs.setImKeyboard(ImKeyboards.imOf(slot), it) }
                        }
                        PanelLayouts.usedBy(prefs.panelLayouts(), entry.id).forEach { prefs.panelLayout(it).setValue("") }
                    }
                    (parentFragment as? ThemeFragment)?.previewLayout(null)
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // an input method's name as the layouts say it (LayoutTexts), by ImKeyboards.slot
    private fun imName(ctx: Context, slot: String): String = ctx.getString(
        when (slot) {
            InputMethodCatalog.PINYIN -> R.string.layout_im_pinyin
            InputMethodCatalog.SHUANGPIN -> R.string.layout_im_shuangpin
            InputMethodCatalog.ENGLISH -> R.string.layout_im_english
            else -> R.string.layout_im_table
        },
    )

    // 用在：… under [card]: the panel it is used for (读不出 / the one chosen gone, the app's meanwhile), the input methods
    private fun usedBy(ctx: Context, card: Item.Card): String? {
        if (card.panel != null) {
            if (!card.panelUsed) return null
            val text = when {
                card.unread -> R.string.layout_used_by_unread
                card.missing -> R.string.layout_used_by_missing
                else -> R.string.layout_used_by
            }
            return ctx.getString(text, panelName(ctx, card.panel))
        }
        if (card.usedBy.isEmpty()) return null
        return ctx.getString(R.string.layout_used_by, card.usedBy.joinToString(ctx.getString(R.string.layout_list_separator)) { imName(ctx, it) })
    }

    // a panel's name as a sentence says it (English's not its section's title: 「the number keyboard」)
    private fun panelName(ctx: Context, panel: PanelLayouts.Panel): String =
        ctx.getString(if (panel == PanelLayouts.Panel.NUMBER) R.string.layout_panel_number else R.string.layout_panel_symbols)

    // [title] and [text], [preview] drawn above it in the theme in use; the text scrolls
    private fun dialog(ctx: Context, title: String, text: String, preview: SceneLayout?): AlertDialog.Builder {
        val padding = ctx.dp(DIALOG_PADDING_DP)
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            preview?.let { addView(LayoutBoards.view(ctx, ThemeManager.activeTheme, it), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(PREVIEW_DP))) }
            addView(TextView(ctx).apply {
                this.text = text
                setTextIsSelectable(true)
                setPadding(0, padding / 2, 0, 0)
            })
        }
        return AlertDialog.Builder(ctx).setTitle(title).setView(ScrollView(ctx).apply { addView(body) })
    }

    /** The cards in as many columns as fit, a section's title across them all. */
    private inner class Grid(context: Context) : RecyclerView(context) {
        private val itemWidth = context.dp(CARD_WIDTH_DP)
        private val minMargin = context.dp(CARD_MARGIN_DP)
        private val grid = GridLayoutManager(context, 1).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (this@LayoutListFragment.adapter.items.getOrNull(position) is Item.Header) spanCount else 1
            }
        }

        init {
            layoutManager = grid
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0) grid.spanCount = max(1, (w - minMargin) / (itemWidth + minMargin))
        }
    }

    private inner class Holder(view: View, val ui: ThemeThumbnailUi?, val usedBy: TextView? = null) : RecyclerView.ViewHolder(view)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var items: List<Item> = emptyList()

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (items[position] is Item.Header) HEADER else CARD

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            if (viewType == HEADER) {
                val title = TextView(ctx).apply {
                    textSize = HEADER_SP
                    setTextColor(ctx.styledColor(android.R.attr.colorAccent))
                    val h = ctx.dp(CARD_MARGIN_DP)
                    setPadding(h, h, h, h / 2)
                    isFocusable = false
                    isAccessibilityHeading = true
                }
                return Holder(title.apply { layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT) }, null)
            }
            val ui = ThemeThumbnailUi(ctx)
            val row = ThemeNames.rowHeight(ctx)
            // 「用在：拼音、英文」 under the name, a line of its own (the name's row is one line): every card as high
            val usedBy = TextView(ctx).apply {
                textSize = USED_BY_SP
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_HORIZONTAL
                setTextColor(ctx.styledColor(android.R.attr.textColorSecondary))
            }
            val cell = FrameLayout(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, ctx.dp(CARD_HEIGHT_DP) + 2 * row + ctx.dp(CARD_MARGIN_DP))
                addView(ui.root, FrameLayout.LayoutParams(ctx.dp(CARD_WIDTH_DP), ctx.dp(CARD_HEIGHT_DP) + row, Gravity.CENTER_HORIZONTAL or Gravity.TOP))
                addView(usedBy, FrameLayout.LayoutParams(ctx.dp(CARD_WIDTH_DP), row, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
                    topMargin = ctx.dp(CARD_HEIGHT_DP) + row
                })
            }
            return Holder(cell, ui, usedBy)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val item = items[position]) {
                is Item.Header -> (holder.itemView as TextView).setText(item.title)
                is Item.Card -> holder.ui!!.apply {
                    val ctx = root.context
                    val theme = ThemeManager.activeTheme
                    setTheme(theme)
                    // the theme's look over the layout's keys; nothing of the theme's own marks
                    dynamicIcon.visibility = View.GONE
                    keys.background = ThemeThumbnailKeys(ThemeManager.shownLook(theme), ctx.resources.displayMetrics.density, ctx.dp(THUMBNAIL_BAR_DP).toFloat(), item.cells)
                    name.text = if (item.entry != null && item.entry.layout == null) ctx.getString(R.string.layout_unreadable, item.name) else item.name
                    holder.usedBy?.text = usedBy(ctx, item)
                    root.setOnClickListener { select(item) }
                    root.setOnLongClickListener {
                        if (item.entry == null) return@setOnLongClickListener false
                        actions(item)
                        true
                    }
                }
            }
        }
    }

    private companion object {
        const val HEADER = 0
        const val CARD = 1
        const val BEING_EXPORTED = "being_exported"
        // not application/xml: the system file saver would add .xml to the name (UAT ST-150)
        const val MIME = "application/octet-stream"

        // the boards of the letters' section, as the keyboard options list them
        val LETTERS = listOf("qwerty", "k18", "k14", "nine")
        val LETTER_FAMILIES = setOf("letters", "grouped", "nine")

        // as a theme's card (ResponsiveThemeListView, ThemeThumbnailUi)
        const val CARD_WIDTH_DP = 128
        const val CARD_HEIGHT_DP = 92
        const val CARD_MARGIN_DP = 16
        const val THUMBNAIL_BAR_DP = 14
        const val HEADER_SP = 14f
        const val USED_BY_SP = 11f
        const val FAB_MARGIN_DP = 16
        const val DIALOG_PADDING_DP = 24
        const val PREVIEW_DP = 160
    }
}
