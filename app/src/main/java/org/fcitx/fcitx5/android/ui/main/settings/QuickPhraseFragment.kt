/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseManager
import org.fcitx.fcitx5.android.host.QuickPhraseBook
import org.fcitx.fcitx5.android.host.QuickPhraseEntry
import org.fcitx.fcitx5.android.host.QuickPhraseFiles
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.materialTextInput
import org.fcitx.fcitx5.android.utils.onPositiveButtonClick
import org.fcitx.fcitx5.android.utils.str
import org.fcitx.fcitx5.android.utils.styledColor
import org.fcitx.fcitx5.android.utils.toast
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent
import timber.log.Timber
import java.io.IOException
import com.google.android.material.R as MaterialR

/**
 * 快速输入's page: the user's entries in the keyboard's order (the last used first), a search over
 * keys and content, the built-in tables' switches. Each change is saved as it is made
 * ([QuickPhraseManager]), and the keyboard reads them again.
 */
class QuickPhraseFragment : ProgressFragment() {

    private val args by lazyRoute<SettingsRoute.QuickPhrase>()

    private var all = emptyList<QuickPhraseEntry>()
    private var shown = emptyList<QuickPhraseEntry>()
    private var query = ""

    private lateinit var adapter: Adapter
    private lateinit var list: RecyclerView
    private var undo: Snackbar? = null

    // the keyboard's ask (＋添加, a card's 编辑, the clipboard's 存为) answered once, not again on a rotation
    private var asked = false

    private lateinit var importLauncher: ActivityResultLauncher<String>
    private lateinit var exportLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        asked = savedInstanceState?.getBoolean(ASKED) == true
        importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let(::importFile) }
        exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(::exportFile) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(ASKED, asked)
    }

    override suspend fun initialize(): View {
        all = try {
            withContext(Dispatchers.IO) { QuickPhraseManager.load() }
        } catch (e: IOException) {
            Timber.w(e, "quick phrases")
            requireContext().toast(e)
            emptyList()
        }
        val on = withContext(Dispatchers.IO) { QuickPhraseManager.BUILT_IN.associateWith { QuickPhraseManager.builtInOn(it) } }
        viewModel.toolbarButton.value = ButtonMode.NONE
        val ctx = requireContext()
        adapter = Adapter(on)
        filter()
        list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = this@QuickPhraseFragment.adapter
            clipToPadding = false
            setPadding(0, 0, 0, ctx.dp(88))
        }
        swipeToDelete()
        val fab = FloatingActionButton(ctx).apply {
            setImageDrawable(ctx.drawable(R.drawable.ic_baseline_plus_24))
            contentDescription = getString(R.string.quick_phrase_new)
            setOnClickListener { edit(null) }
        }
        addMenu()
        if (args.edit && !asked) {
            asked = true
            val entry = QuickPhraseEntry(args.key, args.content)
            edit(entry.takeIf { args.existing && it in all }, entry)
        }
        return FrameLayout(ctx).apply {
            setBackgroundColor(ctx.styledColor(android.R.attr.colorBackground))
            add(list, lParams(matchParent, matchParent))
            val margin = ctx.dp(16)
            add(fab, lParams(wrapContent, wrapContent, Gravity.END or Gravity.BOTTOM) { setMargins(margin, margin, margin, margin) })
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                fab.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = margin + bottom }
                list.setPadding(0, 0, 0, ctx.dp(88) + bottom)
                insets
            }
        }
    }

    private fun addMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menu.add(R.string.import_file).setOnMenuItemClickListener {
                    importLauncher.launch("*/*")
                    true
                }
                menu.add(R.string.export_file).setOnMenuItemClickListener {
                    exportLauncher.launch("QuickPhrase.mb")
                    true
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem) = false
        }, viewLifecycleOwner, Lifecycle.State.STARTED)
    }

    // the search finds keys and content alike, case aside
    private fun matching(): List<QuickPhraseEntry> {
        val q = query.trim()
        return if (q.isEmpty()) all else all.filter { it.key.contains(q, ignoreCase = true) || it.content.contains(q, ignoreCase = true) }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun filter() {
        shown = matching()
        adapter.notifyDataSetChanged()
    }

    // a change saved, then the list read back and the keyboard told: the file is the one truth, the
    // keyboard writing it too
    private fun change(what: String, op: () -> Unit, after: () -> Unit = {}) {
        // outlives the page: a change made just before leaving it is still saved
        FcitxApplication.getInstance().coroutineScope.launch(Dispatchers.Main.immediate) {
            val read = try {
                withContext(Dispatchers.IO) {
                    op()
                    QuickPhraseManager.load()
                }
            } catch (e: IOException) {
                Timber.w(e, what)
                appContext.toast(e)
                return@launch
            }
            fcitx.runIfReady { reloadConfig() }
            if (view == null) return@launch
            all = read
            filter()
            after()
        }
    }

    private fun edit(entry: QuickPhraseEntry?, filled: QuickPhraseEntry? = entry) {
        val ctx = requireContext()
        val keyLabel = getString(R.string.quick_phrase_key)
        val contentLabel = getString(R.string.quick_phrase_content)
        val (keyLayout, keyField) = ctx.materialTextInput { hint = keyLabel }
        keyField.apply {
            isSingleLine = true
            // a key ends at its first space in the file: none typed
            filters = arrayOf(
                InputFilter { source, start, end, _, _, _ ->
                    val typed = source.subSequence(start, end)
                    if (typed.none { it.isWhitespace() }) null else typed.filterNot { it.isWhitespace() }
                },
                InputFilter.LengthFilter(QuickPhraseBook.MAX_KEY),
            )
            imeOptions = EditorInfo.IME_ACTION_NEXT
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val (contentLayout, contentField) = ctx.materialTextInput { hint = contentLabel }
        contentField.apply {
            isSingleLine = false
            maxLines = 10
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(QuickPhraseBook.MAX_CONTENT))
        }
        filled?.let {
            keyField.setText(it.key)
            contentField.setText(it.content)
        }
        val layout = ctx.verticalLayout {
            setPadding(dp(20), dp(10), dp(20), 0)
            add(keyLayout, lParams(matchParent))
            add(contentLayout, lParams(matchParent))
        }
        AlertDialog.Builder(ctx)
            .setTitle(if (entry == null) R.string.quick_phrase_new else R.string.quick_phrase_change)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .apply { if (entry != null) setNeutralButton(R.string.delete) { _, _ -> delete(entry) } }
            .show()
            .also { if (filled != null && filled.key.isEmpty()) keyField.requestFocus() }
            .onPositiveButtonClick onClick@{
                val key = keyField.str
                if (!QuickPhraseFiles.validKey(key)) {
                    keyField.error = if (key.isEmpty()) getString(R.string._cannot_be_empty, keyLabel) else getString(R.string.quick_phrase_key_invalid)
                    keyField.requestFocus()
                    return@onClick false
                }
                val content = contentField.str
                if (content.isEmpty()) {
                    contentField.error = getString(R.string._cannot_be_empty, contentLabel)
                    contentField.requestFocus()
                    return@onClick false
                }
                val new = QuickPhraseEntry(key, content)
                if (new != entry && new in all) {
                    contentField.error = getString(R.string.quick_phrase_duplicate)
                    return@onClick false
                }
                change("quick phrase save", { QuickPhraseManager.put(entry, new) })
                true
            }
            .setCanceledOnTouchOutside(false)
    }

    private fun delete(entry: QuickPhraseEntry) {
        change("quick phrase delete", { QuickPhraseManager.remove(entry) }) {
            undo = Snackbar.make(list, R.string.quick_phrase_deleted, Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) { change("quick phrase undo", { QuickPhraseManager.put(null, entry, now = 0L) }) }
                .also { it.show() }
        }
    }

    private fun swipeToDelete() {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun getSwipeDirs(rv: RecyclerView, holder: RecyclerView.ViewHolder) =
                if (holder is EntryHolder) super.getSwipeDirs(rv, holder) else 0

            override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder) = false

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                val entry = shown.getOrNull(holder.bindingAdapterPosition - HEAD) ?: return
                delete(entry)
            }
        }).attachToRecyclerView(list)
    }

    private fun importFile(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.launch {
            ctx.showingFileErrors("quick phrases import") {
                val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStreamOrThrow(uri).use { it.readImported() } }
                change("quick phrases import", {
                    val (added, skipped) = QuickPhraseManager.import(bytes)
                    appContext.mainExecutor.execute { ctx.toast(ctx.getString(R.string.quick_phrase_import_done, added, skipped)) }
                })
            }
        }
    }

    private fun exportFile(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.launch {
            ctx.showingFileErrors("quick phrases export") {
                withContext(Dispatchers.IO) {
                    val text = QuickPhraseManager.export()
                    ctx.contentResolver.openOutputStreamOrThrow(uri).use { it.write(text.toByteArray()) }
                }
            }
        }
    }

    private class EntryHolder(view: View, val key: TextView, val content: TextView) : RecyclerView.ViewHolder(view)

    // the hint and the search first, the entries, then the built-in tables' switches
    private inner class Adapter(private val on: Map<String, Boolean>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val tables = QuickPhraseManager.BUILT_IN.zip(
            listOf(R.string.quick_phrase_built_in_emoji, R.string.quick_phrase_built_in_emoji_eac, R.string.quick_phrase_built_in_latex),
        )

        override fun getItemCount() = HEAD + shown.size + 1 + tables.size

        override fun getItemViewType(position: Int) = when {
            position < HEAD -> HEADER
            position < HEAD + shown.size -> ENTRY
            position == HEAD + shown.size -> SECTION
            else -> SWITCH
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            return when (viewType) {
                HEADER -> object : RecyclerView.ViewHolder(header(ctx)) {}
                ENTRY -> entry(ctx)
                SECTION -> object : RecyclerView.ViewHolder(text(ctx, 14f, android.R.attr.colorPrimary).apply {
                    setText(R.string.quick_phrase_built_in)
                    setPadding(dp(16), dp(20), dp(16), dp(8))
                }) {}
                else -> object : RecyclerView.ViewHolder(MaterialSwitch(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(matchParent, wrapContent)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                }) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is EntryHolder -> {
                    val e = shown[position - HEAD]
                    holder.key.text = e.key
                    holder.content.text = e.content
                    holder.itemView.setOnClickListener { edit(e) }
                }
                else -> (holder.itemView as? MaterialSwitch)?.let { s ->
                    val (name, label) = tables[position - HEAD - shown.size - 1]
                    s.setOnCheckedChangeListener(null)
                    s.setText(label)
                    s.isChecked = on[name] != false
                    s.setOnCheckedChangeListener { _, checked -> change("quick phrase table", { QuickPhraseManager.setBuiltIn(name, checked) }) }
                }
            }
        }

        private fun header(ctx: Context) = ctx.verticalLayout {
            layoutParams = ViewGroup.LayoutParams(matchParent, wrapContent)
            setPadding(dp(16), dp(12), dp(16), dp(4))
            add(text(ctx, 14f, MaterialR.attr.colorOnSurfaceVariant).apply { setText(R.string.quick_phrases_page_hint) }, lParams(matchParent))
            val (searchLayout, searchField) = ctx.materialTextInput { hint = getString(R.string.quick_phrase_search) }
            searchField.isSingleLine = true
            searchField.setText(query)
            searchField.doAfterTextChanged {
                query = it?.toString().orEmpty()
                // the header stays, the rows after it change: the field keeps its focus
                val before = shown.size
                shown = matching()
                notifyItemRangeRemoved(HEAD, before)
                notifyItemRangeInserted(HEAD, shown.size)
            }
            add(searchLayout, lParams(matchParent) { topMargin = dp(8) })
        }

        private fun entry(ctx: Context): EntryHolder {
            val key = text(ctx, 16f, MaterialR.attr.colorOnSurface)
            val content = text(ctx, 14f, MaterialR.attr.colorOnSurfaceVariant).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            val row = ctx.verticalLayout {
                layoutParams = ViewGroup.LayoutParams(matchParent, wrapContent)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                isClickable = true
                isFocusable = true
                ctx.theme.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).use { background = it.getDrawable(0) }
                add(key, lParams(matchParent))
                add(content, lParams(matchParent))
            }
            return EntryHolder(row, key, content)
        }

        private fun text(ctx: Context, size: Float, color: Int) = TextView(ctx).apply {
            textSize = size
            setTextColor(ctx.styledColor(color))
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setToolbarTitle(getString(R.string.quick_phrases))
    }

    override fun onDestroyView() {
        undo?.dismiss()
        undo = null
        super.onDestroyView()
    }

    companion object {
        private const val ASKED = "asked"
        private const val HEADER = 0
        private const val ENTRY = 1
        private const val SECTION = 2
        private const val SWITCH = 3
        private const val HEAD = 1
    }
}
