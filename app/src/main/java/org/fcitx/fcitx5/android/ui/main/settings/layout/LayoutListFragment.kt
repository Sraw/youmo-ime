/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.layout

import android.content.Context
import android.os.Bundle
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.layout.CustomLayouts
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePackFiles
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
import java.io.IOException
import kotlin.math.max

/**
 * 外观 → 布局 (061-custom-layout §4.3): the layouts by what they are (字母键盘, 数字键盘, 符号面板), the app's first, then
 * the user's own, each a card of its keys in little in the theme in use. A card tapped shows in the preview above (no
 * setting changed); one of the user's long pressed: 信息, 导出, 删除. **+**: import a `.layout` file, or open the layout
 * maker in the browser. Which keyboards use a layout is the keyboard options' (L3), not this page's.
 */
class LayoutListFragment : Fragment() {

    /** A row of the list: a section's title, or a layout's card. */
    sealed interface Item {
        class Header(val title: Int) : Item

        /** A layout: the app's ([entry] null) or the user's; [scene] what the preview draws, null where it does not read. */
        class Card(val id: String, val name: String, val entry: CustomLayouts.Entry?, val scene: SceneLayout?, val cells: List<LayoutThumbnail.Cell>) : Item
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
        fun builtIn(id: String): Item.Card? {
            val scene = SceneLayouts.parseBuiltIn(ctx, id) ?: return null
            val cells = runCatching { LayoutThumbnail.cells(scene.layout, scene.fragments) }.getOrDefault(emptyList())
            return Item.Card(id, scene.layout.name, null, scene, cells)
        }
        val own = CustomLayouts.list(context = ctx).map { e ->
            val scene = e.layout?.let { scene(e.id, it, e.fragments) }
            Item.Card(e.id, e.name, e, scene, e.cells)
        }
        fun section(of: (String?) -> Boolean) = own.filter { of(it.entry?.family) }
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

    // a layout of one's own as the keyboard will draw it: the app's keys' long press and swipes on the app's names (KeyData.Own)
    private fun scene(id: String, layout: Layout, fragments: Map<String, org.fcitx.fcitx5.android.scene.layout.Fragment>): SceneLayout {
        val held = runCatching { KeyData.of(id, layout, fragments, KeyData.Mode.Own, layout.basedOn) }.getOrNull()
        return if (held != null) SceneLayout(id, held.layout, held.fragments) else SceneLayout(id, layout, fragments)
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
                        withContext(Dispatchers.IO) { CustomLayouts.save(layout.id, text) }
                        ctx.toast(ctx.getString(R.string.layout_imported, name))
                        reload()
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

    private fun delete(entry: CustomLayouts.Entry) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.delete_layout)
            .setMessage(ctx.getString(R.string.delete_layout_msg, entry.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    val gone = withContext(Dispatchers.IO) { CustomLayouts.delete(entry.id) }
                    if (!gone) ctx.toast(ctx.getString(R.string.layout_not_deleted, entry.name))
                    (parentFragment as? ThemeFragment)?.previewLayout(null)
                    reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

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

    private inner class Holder(view: View, val ui: ThemeThumbnailUi?) : RecyclerView.ViewHolder(view)

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
            val cell = FrameLayout(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, ctx.dp(CARD_HEIGHT_DP) + ThemeNames.rowHeight(ctx) + ctx.dp(CARD_MARGIN_DP))
                addView(ui.root, FrameLayout.LayoutParams(ctx.dp(CARD_WIDTH_DP), ctx.dp(CARD_HEIGHT_DP) + ThemeNames.rowHeight(ctx), Gravity.CENTER_HORIZONTAL or Gravity.TOP))
            }
            return Holder(cell, ui)
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
        const val FAB_MARGIN_DP = 16
        const val DIALOG_PADDING_DP = 24
        const val PREVIEW_DP = 160
    }
}
