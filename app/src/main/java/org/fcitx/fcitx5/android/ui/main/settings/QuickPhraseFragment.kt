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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.HostDaemon
import org.fcitx.fcitx5.android.data.quickphrase.QuickPhraseManager
import org.fcitx.fcitx5.android.host.QuickPhraseBook
import org.fcitx.fcitx5.android.host.QuickPhraseEntry
import org.fcitx.fcitx5.android.host.QuickPhraseFiles
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.materialTextInput
import org.fcitx.fcitx5.android.utils.parcelable
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
import java.util.concurrent.ConcurrentHashMap
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

    // the built-in tables' switches as the files have them, each turned at once: rows bound again
    // (filter) read this, never a copy made as the page opened
    private val on = HashMap<String, Boolean>()

    // the switches whose writes are still to come, how many each: counted down as each is written
    private val switching = ConcurrentHashMap<String, Int>()

    private lateinit var adapter: Adapter
    private lateinit var list: RecyclerView
    private var undo: Snackbar? = null

    // the keyboard's ask (＋添加, a card's 编辑, the clipboard's 存为) answered once, not again on a rotation
    private var asked = false

    // the editor open, put back as it was after a rotation, a theme change or the process's death
    private var editor: Editor? = null
    private var editorWas: Bundle? = null

    // the keyboard's ask taken (takeRequest) while the editor restored is not yet shown again: asked once
    // it is; kept with it over another rotation before that
    private var pending: SettingsRoute.QuickPhrase? = null

    // 放弃修改？ over the editor: gone with the page, the editor shown again without it
    private var discard: AlertDialog? = null

    private lateinit var importLauncher: ActivityResultLauncher<String>
    private lateinit var exportLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        asked = savedInstanceState?.getBoolean(ASKED) == true
        editorWas = savedInstanceState?.getBundle(EDITOR)
        pending = savedInstanceState?.parcelable(PENDING)
        importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { it?.let(::importFile) }
        exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { it?.let(::exportFile) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(ASKED, asked)
        // the editor's, or one restored and not yet shown again (the page still reading its files)
        (editor?.saved() ?: editorWas)?.let { outState.putBundle(EDITOR, it) }
        pending?.let { outState.putParcelable(PENDING, it) }
    }

    private fun builtIns() = QuickPhraseManager.BUILT_IN.associateWith { QuickPhraseManager.builtInOn(it) }

    override suspend fun initialize(): View {
        all = try {
            // after any change still being saved (made on the page before a rotation)
            withContext(changes) { QuickPhraseManager.load() }
        } catch (e: IOException) {
            Timber.w(e, "quick phrases")
            requireContext().toast(e)
            emptyList()
        }
        on.putAll(withContext(changes) { builtIns() })
        viewModel.toolbarButton.value = ButtonMode.NONE
        val ctx = requireContext()
        adapter = Adapter()
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
        val was = editorWas
        editorWas = null
        val typedWas = was?.entry(TYPED)
        // OK tapped before, and its entry there now
        // and the entry it changed gone: one the same added meanwhile (by the keyboard) refused the save
        val savedWas = typedWas?.takeIf { t ->
            was.getBoolean(SAVING) && all.any { it.sameAs(t) } && was.entry(OLD).let { old -> old == null || old.sameAs(t) || old !in all }
        }
        if (savedWas != null) {
            // OK was tapped and the old page's save finished meanwhile: saved, nothing to show again
            asked = true
            ctx.toast(R.string.quick_phrase_saved)
        } else if (was != null) {
            asked = true
            edit(was.entry(OLD), was.entry(FILLED), typedWas)
        } else if (args.edit && !asked) {
            asked = true
            val entry = QuickPhraseEntry(args.key, args.content)
            edit(entry.takeIf { args.existing && it in all }, entry)
        }
        pending?.let { request ->
            pending = null
            // the restored editor up now: asked over it as takeRequest does, else (saved meanwhile) opened
            if (!takeRequest(request) && request.edit) {
                val entry = QuickPhraseEntry(request.key, request.content)
                edit(entry.takeIf { request.existing && it in all }, entry)
            }
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

    private class Read(val mine: List<QuickPhraseEntry>, val on: Map<String, Boolean>)

    /**
     * [op] saved, then the entries and the switches read back, failed or not (a row swiped away
     * comes back, a switch turns back), and the keyboard told: the files are the one truth, the
     * keyboard writing them too. [after] with what [op] gave, [failed] if it threw, while the page is there;
     * [settled] last on the main thread whatever happened, its read back applied (or the page gone).
     */
    private fun <T> change(what: String, op: () -> T, failed: () -> Unit = {}, settled: () -> Unit = {}, after: (T) -> Unit = {}) {
        // outlives the page: a change made just before leaving it is still saved
        FcitxApplication.getInstance().coroutineScope.launch(Dispatchers.Main.immediate) {
            try {
                changed(what, op, failed, after)
            } finally {
                settled()
            }
        }
    }

    private suspend fun <T> changed(what: String, op: () -> T, failed: () -> Unit, after: (T) -> Unit) {
        // one at a time, in the order made: a switch turned twice quickly is read back as last turned
        val (done, read) = withContext(changes) {
            val done = try {
                Result.success(op())
            } catch (e: IOException) {
                Result.failure(e)
            }
            val read = try {
                Read(QuickPhraseManager.load(), builtIns())
            } catch (e: IOException) {
                Timber.w(e, "quick phrases")
                null
            }
            done to read
        }
        done.exceptionOrNull()?.let {
            Timber.w(it, what)
            appContext.toast(it)
        }
        // not the page's connection: it is gone once the page is, the change made just before leaving it
        if (done.isSuccess) HostDaemon.runIfRunning { reloadConfig() }
        if (view == null) return
        read?.let {
            all = it.mine
            // a switch whose write's read back is still to come here (this one's too) keeps what it
            // was turned to
            on.putAll(it.on.filterKeys { name -> !switching.containsKey(name) })
        }
        // before the rows are bound again: a switch whose write failed, unread, turns back
        done.onFailure { failed() }
        filter()
        done.onSuccess(after)
    }

    /** The editor up: what it changes ([entry], null for a new one), what it was filled with. */
    private inner class Editor(
        val dialog: AlertDialog,
        val entry: QuickPhraseEntry?,
        val filled: QuickPhraseEntry,
        val key: EditText,
        val content: EditText,
    ) {
        val typed get() = QuickPhraseEntry(key.str, content.str)

        /** OK tapped, its save not answered yet. */
        var saving = false

        // what was typed kept within a Bundle's means (hundreds of thousands of characters pasted
        // would not fit the transaction), one past the limit: put back, still told too long at OK
        fun saved() = Bundle().apply {
            putEntry(OLD, entry)
            putEntry(FILLED, filled)
            putEntry(TYPED, typed.run { QuickPhraseEntry(key.take(KEPT), content.take(KEPT)) })
            putBoolean(SAVING, saving)
        }

        /** Back or 取消: at once with nothing changed, asked first otherwise. */
        fun leave() {
            if (typed == filled) dialog.cancel() else confirmDiscard(dialog)
        }
    }

    private fun edit(entry: QuickPhraseEntry?, filled: QuickPhraseEntry? = entry, typed: QuickPhraseEntry? = filled) {
        val ctx = requireContext()
        val keyLabel = getString(R.string.quick_phrase_key)
        val contentLabel = getString(R.string.quick_phrase_content)
        val (keyLayout, keyField) = ctx.materialTextInput { hint = keyLabel }
        keyField.apply {
            isSingleLine = true
            // a key ends at its first space in the file: none typed. No length filter: one would cut a
            // key filled in (30 code points may be 60 units); its length is checked at OK
            filters = arrayOf(
                InputFilter { source, start, end, _, _, _ ->
                    val typedNow = source.subSequence(start, end)
                    if (typedNow.none { it.isWhitespace() }) null else typedNow.filterNot { it.isWhitespace() }
                },
            )
            imeOptions = EditorInfo.IME_ACTION_NEXT
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val (contentLayout, contentField) = ctx.materialTextInput { hint = contentLabel }
        contentField.apply {
            isSingleLine = false
            maxLines = 10
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            // no length filter either: it would cut what is pasted or filled in unseen; too long is told at OK
        }
        typed?.let {
            keyField.setText(it.key)
            contentField.setText(it.content)
        }
        val layout = ctx.verticalLayout {
            setPadding(dp(20), dp(10), dp(20), 0)
            add(keyLayout, lParams(matchParent))
            add(contentLayout, lParams(matchParent))
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (entry == null) R.string.quick_phrase_new else R.string.quick_phrase_change)
            .setView(layout)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .apply { if (entry != null) setNeutralButton(R.string.delete) { _, _ -> delete(entry) } }
            .show()
        val ed = Editor(dialog, entry, filled ?: QuickPhraseEntry("", ""), keyField, contentField)
        editor = ed
        dialog.setOnDismissListener { if (editor === ed) editor = null }
        dialog.setCanceledOnTouchOutside(false)
        // Back with something changed asks first: what was typed is not lost to a slip
        dialog.onBackPressedDispatcher.addCallback(dialog) { ed.leave() }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { ed.leave() }
        if (typed != null && typed.key.isEmpty()) keyField.requestFocus()
        dialog.onPositiveButtonClick { save(ed) }
    }

    /** OK: checked here, then saved; the editor stays up until the file says it is saved. */
    private fun save(ed: Editor): Boolean {
        val key = ed.key.str
        if (!QuickPhraseFiles.validKey(key)) {
            ed.key.error = if (key.isEmpty()) {
                getString(R.string._cannot_be_empty, getString(R.string.quick_phrase_key))
            } else {
                getString(R.string.quick_phrase_key_invalid, QuickPhraseBook.MAX_KEY)
            }
            ed.key.requestFocus()
            return false
        }
        val content = ed.content.str
        if (content.isEmpty() || content.length > QuickPhraseBook.MAX_CONTENT) {
            ed.content.error = if (content.isEmpty()) {
                getString(R.string._cannot_be_empty, getString(R.string.quick_phrase_content))
            } else {
                getString(R.string.quick_phrase_content_too_long, QuickPhraseBook.MAX_CONTENT)
            }
            ed.content.requestFocus()
            return false
        }
        val new = QuickPhraseEntry(key, content)
        // the list's at once; the file's (the keyboard may have added it meanwhile) as it is saved
        if (all.any { it != ed.entry && it.sameAs(new) }) {
            ed.content.error = getString(R.string.quick_phrase_duplicate)
            return false
        }
        val buttons = listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL).mapNotNull(ed.dialog::getButton)
        buttons.forEach { it.isEnabled = false }
        ed.saving = true
        val failed = {
            ed.saving = false
            buttons.forEach { it.isEnabled = true }
        }
        change("quick phrase save", { QuickPhraseManager.put(ed.entry, new) }, failed) { put ->
            if (put == QuickPhraseManager.Put.Saved) {
                ed.dialog.dismiss()
            } else {
                failed()
                ed.content.error = getString(R.string.quick_phrase_duplicate)
            }
        }
        return false
    }

    private fun confirmDiscard(editorDialog: AlertDialog, then: () -> Unit = {}) {
        discard?.dismiss()
        discard = AlertDialog.Builder(requireContext())
            .setMessage(R.string.quick_phrase_discard_confirm)
            .setNegativeButton(R.string.quick_phrase_keep_editing, null)
            .setPositiveButton(R.string.quick_phrase_discard) { _, _ ->
                editorDialog.cancel()
                then()
            }
            .setOnDismissListener { if (discard === it) discard = null }
            .show()
    }

    /**
     * The keyboard asks for this page again ([request]) while it is up: taken here if its editor has
     * something changed, which is not dropped unasked (放弃修改？, then [request]'s editor; kept
     * editing, the request is let go). False otherwise: the page is opened anew as usual.
     */
    fun takeRequest(request: SettingsRoute.QuickPhrase): Boolean {
        val ed = editor ?: run {
            // restored after a rotation or the process's death, the page still reading its files:
            // what was typed is not dropped by the page opened anew; asked once it is shown again
            val was = editorWas ?: return false
            if (was.entry(TYPED) == was.entry(FILLED)) return false
            pending = request
            return true
        }
        if (ed.typed == ed.filled) return false
        if (request.edit) {
            confirmDiscard(ed.dialog) {
                val entry = QuickPhraseEntry(request.key, request.content)
                edit(entry.takeIf { request.existing && it in all }, entry)
            }
        }
        return true
    }

    private fun delete(entry: QuickPhraseEntry) {
        change("quick phrase delete", { QuickPhraseManager.remove(entry) }) { removed ->
            // gone already (the keyboard deleted it meanwhile): nothing deleted here, nothing to undo
            if (removed == null) return@change
            undo = Snackbar.make(list, R.string.quick_phrase_deleted, Snackbar.LENGTH_LONG)
                // put back where it was, its time of use too; one the same added meanwhile is kept as it is
                .setAction(R.string.undo) { change("quick phrase undo", { QuickPhraseManager.restore(removed) }) }
                .also { it.show() }
        }
    }

    private fun swipeToDelete() {
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun getSwipeDirs(rv: RecyclerView, holder: RecyclerView.ViewHolder) =
                if (holder is EntryHolder) super.getSwipeDirs(rv, holder) else 0

            override fun onMove(rv: RecyclerView, a: RecyclerView.ViewHolder, b: RecyclerView.ViewHolder) = false

            override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
                val at = holder.bindingAdapterPosition
                // gone from the list meanwhile: the row put back, not left swiped away blank
                val entry = shown.getOrNull(at - HEAD) ?: return run { if (at >= 0) adapter.notifyItemChanged(at) }
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
                    val r = QuickPhraseManager.import(bytes)
                    val said = quickPhraseImportToast(appContext, r)
                    // not the page's context: the import outlives it
                    appContext.mainExecutor.execute { appContext.toast(said) }
                })
            }
        }
    }

    private fun exportFile(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.launch {
            ctx.showingFileErrors("quick phrases export") {
                // after the changes still being saved, as they are written
                withContext(changes) {
                    val text = QuickPhraseManager.export()
                    ctx.contentResolver.openOutputStreamOrThrow(uri).use { it.write(text.toByteArray()) }
                }
            }
        }
    }

    private class EntryHolder(view: View, val key: TextView, val content: TextView) : RecyclerView.ViewHolder(view)

    // the hint and the search first, the entries, then the built-in tables' switches
    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

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
                    s.setOnCheckedChangeListener { _, checked ->
                        // before the change's list is bound again (filter): the row keeps what was turned
                        on[name] = checked
                        switching.merge(name, 1) { a, b -> a + b }
                        change(
                            "quick phrase table", { QuickPhraseManager.setBuiltIn(name, checked) },
                            failed = { on[name] = !checked },
                            // counted down on the main thread once this write's read back is applied, not
                            // as it is written: two turns written before the first's read back reached the
                            // main thread had it bind the switch to the first turn, then flip to the second
                            settled = { switching.computeIfPresent(name) { _, n -> (n - 1).takeIf { it > 0 } } },
                        )
                    }
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
        discard?.dismiss()
        // its state is saved (onSaveInstanceState comes first) and shown again by the page made anew
        editor?.let {
            editorWas = it.saved()
            editor = null
            it.dialog.dismiss()
        }
        super.onDestroyView()
    }

    companion object {
        private const val ASKED = "asked"
        private const val EDITOR = "editor"
        private const val PENDING = "pending"
        private const val OLD = "old"
        private const val FILLED = "filled"
        private const val TYPED = "typed"
        private const val SAVING = "saving"

        // a field's text kept over a rotation: one past the content's limit, the key's far below
        private const val KEPT = QuickPhraseBook.MAX_CONTENT + 1

        // every page's changes, one after another (they outlive the page)
        @OptIn(ExperimentalCoroutinesApi::class)
        @VisibleForTesting
        internal val changes = Dispatchers.IO.limitedParallelism(1)

        private fun Bundle.putEntry(name: String, entry: QuickPhraseEntry?) {
            if (entry != null) putStringArray(name, arrayOf(entry.key, entry.content))
        }

        private fun Bundle.entry(name: String): QuickPhraseEntry? =
            getStringArray(name)?.takeIf { it.size == 2 }?.let { QuickPhraseEntry(it[0], it[1]) }

        private const val HEADER = 0
        private const val ENTRY = 1
        private const val SECTION = 2
        private const val SWITCH = 3
        private const val HEAD = 1
    }
}

/** What [QuickPhraseFragment]'s import says of [r]: every skipped line counted, those with a bad key too. */
internal fun quickPhraseImportToast(ctx: Context, r: QuickPhraseManager.Imported): String {
    if (r.added + r.duplicates + r.tooLong == 0) {
        // not a .mb file (a picture picked, say): said so, not "0 imported", though a line or
        // two with a bad key are still said
        return if (r.badKey == 0) ctx.getString(R.string.quick_phrase_import_none)
        else ctx.getString(R.string.quick_phrase_import_bad_key, ctx.getString(R.string.quick_phrase_import_done, 0, 0), r.badKey)
    }
    val done = if (r.tooLong == 0) {
        ctx.getString(R.string.quick_phrase_import_done, r.added, r.duplicates)
    } else {
        ctx.getString(R.string.quick_phrase_import_done_too_long, r.added, r.duplicates, r.tooLong, QuickPhraseBook.MAX_CONTENT)
    }
    return if (r.badKey == 0) done else ctx.getString(R.string.quick_phrase_import_bad_key, done, r.badKey)
}
