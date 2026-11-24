/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import kotlin.math.abs
import kotlin.math.max
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.bar.BarIconDrawables
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.PackIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.set
import org.fcitx.fcitx5.android.input.keys.ScrollListMenu
import org.fcitx.fcitx5.android.input.keys.ScrollListStore
import org.fcitx.fcitx5.android.input.learning.UndoChip
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.SlotNode
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.keys.ScrollDefaults
import org.fcitx.fcitx5.android.scene.keys.ScrollItem
import org.fcitx.fcitx5.android.scene.keys.ScrollLists
import org.fcitx.fcitx5.android.scene.layout.Direction
import org.fcitx.fcitx5.android.scene.layout.Slot
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.topOfParent

/**
 * A scroll slot's items, a native view over the scene's slot (which draws the face): the nine keys' side column and
 * every `scroll`. Its touches come from the keyboard, a finger at a time ([BaseKeyboard]).
 */
internal class ScrollColumn(
    context: Context,
    theme: Theme,
    val vertical: Boolean,
    /** Items in view at once. */
    val show: Int,
    private val labels: () -> List<Label>,
    private val pick: (Int) -> Unit,
    /** TalkBack's actions on an item: the long press menu's. */
    private val actions: (Int) -> List<Pair<String, () -> Unit>> = { emptyList() },
) {
    /** [icon] 0 for text; [said] what TalkBack reads where it is not the text. */
    data class Label(val text: String, @DrawableRes val icon: Int = 0, val said: String? = null)

    private val altColor = theme.altKeyTextColor
    private val pressColor = theme.keyPressHighlightColor
    private val icons = PackIcons.of(theme)

    private val items = LinearLayout(context).apply { orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }

    private var itemSize = 0

    private fun sized(w: Int, h: Int, padding: Int) {
        itemSize = ((if (vertical) h else w) - padding) / show
        for (i in 0 until items.childCount) items.getChildAt(i).layoutParams = itemParams()
    }

    private fun itemParams(): LinearLayout.LayoutParams {
        val size = itemSize.takeIf { it > 0 } ?: view.dp(FIRST_SIZE_DP)
        return if (vertical) {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, size)
        } else {
            LinearLayout.LayoutParams(size, LinearLayout.LayoutParams.MATCH_PARENT)
        }
    }

    val view: FrameLayout = if (vertical) {
        object : ScrollView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                sized(w, h, paddingTop + paddingBottom)
                post { items.requestLayout() }
            }
        }.apply { isVerticalScrollBarEnabled = false }
    } else {
        object : HorizontalScrollView(context) {
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                sized(w, h, paddingLeft + paddingRight)
                post { items.requestLayout() }
            }
        }.apply { isHorizontalScrollBarEnabled = false }
    }.apply {
        overScrollMode = View.OVER_SCROLL_NEVER
        // its face is the scene's slot: the column draws only its items
        val keys = ThemeManager.shownLook(theme).keys
        val landscape = KeyboardArea.landscape(context)
        val h = dp(if (landscape) keys.gap.hLand else keys.gap.h)
        val v = dp(if (landscape) keys.gap.vLand else keys.gap.v)
        setPadding(h, v, h, v)
        addView(items)
        isInvisible = true
    }

    private var shown: List<Label> = emptyList()

    /**
     * The labels read again, a press on a changed list cancelled. [anew]: another list, from its start; [typing]: the
     * syllables, from their start when they change. A list edited in place keeps its place, the layout pass clamping it.
     * [sameItems]: only how they read changed (the marks), so a press keeps its item.
     */
    fun refresh(anew: Boolean = false, typing: Boolean = false, sameItems: Boolean = false) {
        val now = labels()
        val changed = now != shown
        if (anew || changed && !sameItems) cancelPress()
        val was = shown
        shown = now
        while (items.childCount < now.size) items.addView(item(items.childCount))
        for (i in 0 until items.childCount) {
            val v = items.getChildAt(i) as TextView
            v.isVisible = i < now.size
            if (v.isVisible) label(v, i, now[i], anew || now[i] != was.getOrNull(i), anew)
        }
        if (anew || typing && changed) toStart()
    }

    fun toStart() = view.scrollTo(0, 0)

    // a press on the list replaced is cancelled: its pick would take what now sits there
    private fun cancelPress() {
        MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also {
            items.dispatchTouchEvent(it)
            it.recycle()
        }
        // and so is a click released but not run yet (View posts it on ACTION_UP)
        items.cancelPendingInputEvents()
    }

    val size: Int get() = shown.size

    /** Of the item under ([x], [y]) in the view; -1 for none. */
    fun itemAt(x: Float, y: Float): Int {
        val at = if (vertical) y - view.paddingTop + view.scrollY else x - view.paddingLeft + view.scrollX
        if (itemSize <= 0 || at < 0) return -1
        return (at / itemSize).toInt().takeIf { it < shown.size } ?: -1
    }

    /** Item [i]'s view: each item is one child, in order. */
    fun itemView(i: Int): View? = items.getChildAt(i)

    private val actionIds = HashMap<Int, List<Int>>()
    private val actionNames = HashMap<Int, List<String>>()

    // set again only when changed: each scene remade refreshes, and TalkBack hears of every action replaced
    private fun label(v: TextView, i: Int, l: Label, looks: Boolean, anew: Boolean) {
        if (looks) {
            v.text = l.text
            v.foreground = icon(v, i, l.icon)
            v.contentDescription = l.said
        }
        val now = actions(i)
        val names = now.map { it.first }
        if (!anew && actionNames[i] == names) return
        actionIds[i]?.forEach { ViewCompat.removeAccessibilityAction(v, it) }
        actionIds[i] = now.map { (name, run) -> ViewCompat.addAccessibilityAction(v, name) { _, _ -> run(); true } }
        actionNames[i] = names
    }

    // a theme's icon of the command's name, as the keys and the bar draw it; none till it is read, never the app's first
    private fun icon(v: TextView, i: Int, @DrawableRes res: Int): Drawable? {
        if (res == 0) return null
        val ready = { if (shown.getOrNull(i)?.icon == res) v.foreground = icon(v, i, res) }
        return BarIconDrawables.of(v.context, icons, SceneIcons.nameOf(res), res, altColor, v.dp(ICON_DP), ready)
    }

    // one line: a syllable longer than the column (zhuang) shrinks to fit, not wrapped onto a cut-off line
    private fun item(i: Int) = AutoScaleTextView(view.context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, ITEM_TEXT_DP)
        scaleMode = AutoScaleTextView.Mode.Proportional
        isSingleLine = true
        gravity = Gravity.CENTER
        foregroundGravity = Gravity.CENTER
        setTextColor(altColor)
        layoutParams = itemParams()
        // activated: the item a menu is open on, its press gone with the finger
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(pressColor))
            addState(intArrayOf(android.R.attr.state_activated), ColorDrawable(pressColor))
        }
        isClickable = true
        setOnClickListener {
            InputFeedbacks.hapticFeedback(this)
            InputFeedbacks.soundEffect(SoundKey.LETTER)
            pick(i)
        }
    }

    /** Placed in [slot] (the keyboard's coordinates) in the keyboard's own layout pass: never a frame elsewhere. */
    fun place(slot: Rect?) {
        view.isInvisible = slot == null
        if (slot == null) return
        // set only when the slot moved: else the constraint pass (0×0) and this pass would fight, each
        // onSizeChanged posting another layout, never done
        val lp = view.layoutParams as? ConstraintLayout.LayoutParams
        val moved = lp == null || lp.leftMargin != slot.left || lp.topMargin != slot.top
        if (moved || lp.width != slot.width() || lp.height != slot.height()) {
            view.layoutParams = with(view.parent as ConstraintLayout) {
                lParams(slot.width(), slot.height()) {
                    topOfParent(slot.top)
                    leftOfParent(slot.left)
                }
            }
        }
        val exactly = { size: Int -> View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY) }
        if (view.width != slot.width() || view.height != slot.height() || view.isLayoutRequested) {
            view.measure(exactly(slot.width()), exactly(slot.height()))
        }
        view.layout(slot.left, slot.top, slot.right, slot.bottom)
    }

    companion object {
        internal const val ITEM_TEXT_DP = 16f

        private const val FIRST_SIZE_DP = 40

        private const val ICON_DP = 24

        /** A field's own list (`url`, `email`), as the scroll lists' rule reads the field. */
        fun fieldOf(address: AddressKeys): String? = when (address) {
            AddressKeys.URL -> ScrollDefaults.URL
            AddressKeys.EMAIL -> ScrollDefaults.EMAIL
            AddressKeys.NONE -> null
        }

        // the rule reads the input method by name; only English types differently here
        private fun ime(english: Boolean): String? = if (english) "english" else null

        /** The nine keys' marks as the input method types them, unless [english] or marked as they are. */
        fun text(list: String, item: ScrollItem.Text, english: Boolean, marks: Map<String, String>): String =
            ScrollLists.label(list, item, ime(english), marks) ?: item.text

        /** A command drawn as its icon, read as its name. */
        fun label(context: Context, list: String, item: ScrollItem, english: Boolean, marks: Map<String, String>): Label = when (item) {
            is ScrollItem.Text -> Label(text(list, item, english, marks))
            is ScrollItem.Command -> KeyCommand.ofKey(item.key)?.let { c -> KeyShortcutLooks.of(c).let { Label("", it.icon, context.getString(it.label)) } } ?: Label(item.key)
        }

        /** What a tap on [item] of [list] types; null for a command not run in a [password] field. */
        fun typed(list: String, item: ScrollItem, english: Boolean, marks: Map<String, String>, password: Boolean = false): KeyAction? =
            when (val t = ScrollLists.typed(list, item, ime(english), marks)) {
                is ScrollLists.Typed.Command ->
                    KeyCommand.ofKey(t.key)?.takeUnless { password && it.readsField }?.let { KeyAction.ShortcutAction(KeyShortcut.Command(it)) }
                is ScrollLists.Typed.Keypad -> SceneKeyDef.keypad(t.sym)?.let { KeyAction.SymAction(KeySym(it), NumLockState) }
                // English puts a word's space behind a mark
                is ScrollLists.Typed.English -> KeyAction.HostKeyAction(t.text)
                is ScrollLists.Typed.Commit -> KeyAction.CommitAction(t.text, punctuation = t.punctuation)
            }
    }
}

/**
 * A keyboard's columns, one for each `scroll` and `nine-side` slot of its scene by place, placed in its layout pass.
 * A finger landing in one is that column's alone ([target]): it scrolls it, taps or long presses an item.
 */
internal class ScrollColumns(
    private val keyboard: BaseKeyboard,
    private val theme: Theme,
    private val canvas: () -> KeyboardCanvasView?,
    private val marks: () -> Map<String, String>,
    private val act: (KeyAction) -> Unit,
    /** The notice above the keys, with its undo. */
    private val say: (String, UndoChip.Undo) -> Unit,
) {
    private class Column(val kind: String, val view: ScrollColumn) {
        var slot: SlotNode? = null
        var shown: ScrollLists.Shown = ScrollLists.Shown.Category(ScrollDefaults.NINE)
        var items: List<ScrollItem> = emptyList()
    }

    private val context: Context get() = keyboard.context

    private val columns = mutableListOf<Column>()

    private val pref = AppPrefs.getInstance().internal.scrollLists

    private var store = ScrollListStore.parse(pref.getValue())

    // a long press's change and settings' shown at once, as the key lists are
    @Keep
    private val listener = ManagedPreference.OnChangeListener<String> { _, v ->
        store = ScrollListStore.parse(v)
        update()
    }

    init {
        pref.registerOnChangeListener(listener)
    }

    /** The nine keys' side column, there before any scene. */
    fun addSide() {
        add(ScrollLists.NINE_SIDE, vertical = true, show = Slot.DEFAULT_SHOW, at = columns.size)
    }

    private fun add(kind: String, vertical: Boolean, show: Int, at: Int) {
        lateinit var c: Column
        val view = ScrollColumn(context, theme, vertical, show, { labels(c) }, { pick(c, it) }, { actions(c, it) })
        c = Column(kind, view)
        columns.add(at, c)
        keyboard.addView(view.view, ConstraintLayout.LayoutParams(0, 0))
    }

    /** Over the keys: the column takes its touches, the keys' face under it is the slot's. */
    fun toFront() = columns.forEach { it.view.view.bringToFront() }

    private var placing = false

    // the nine keys' column shows as many as it always did, whatever the slot says
    private fun Column.fits(s: SlotNode, vertical: Boolean) =
        kind == s.kind && view.vertical == vertical && (kind == ScrollLists.NINE_SIDE || view.show == s.show)

    // by place: a slot of another kind or direction gets a column of its own; the nine keys' own stays, hidden
    private fun sync(slots: List<SlotNode>) {
        var added = false
        slots.forEachIndexed { i, s ->
            val vertical = s.direction == Direction.Vertical
            val c = columns.getOrNull(i)
            if (c == null || !c.fits(s, vertical)) {
                if (c != null) keyboard.removeView(columns.removeAt(i).view.view)
                add(s.kind, vertical, s.show, i)
                added = true
            }
            columns[i].slot = s
        }
        while (columns.size > slots.size && columns.last().kind != ScrollLists.NINE_SIDE) {
            keyboard.removeView(columns.removeAt(columns.lastIndex).view.view)
        }
        columns.drop(slots.size).forEach { it.slot = null }
        if (menuOn != null && menuOn !in columns) dismissMenu()
        // a scene remade for Shift or a key typed (English's nine keys, each key) reads the same: its items, menus and
        // TalkBack's names are not made again
        if (added || reading() != read) update()
    }

    // what [update] reads that a scene remade may change; the rest tells it itself (address, fieldPad, marks, the lists)
    private data class Read(val input: Input, val side: Input, val lists: List<String?>, val readOnly: Boolean)

    private var read: Read? = null

    private fun reading() = Read(keyboard.scrollInput(), keyboard.sideInput(), columns.map { it.slot?.list }, readOnly)

    @VisibleForTesting
    internal var updates = 0
        private set

    private val expected = Rect()

    fun built(scene: Scene?) {
        if (placing) return
        val c = canvas() ?: return
        // a scene remade for Shift or Return alone lays nothing out
        sync(scene?.slots.orEmpty().filter { it.kind in KINDS })
        val misplaced = columns.any { col ->
            val v = col.view.view
            val s = col.slot ?: return@any v.isVisible
            expected.set(s.bounds)
            expected.offset(c.left, c.top)
            !v.isVisible || v.left != expected.left || v.top != expected.top || v.right != expected.right || v.bottom != expected.bottom
        }
        if (misplaced) keyboard.requestLayout()
    }

    /** In the same pass as the canvas: never a frame elsewhere. */
    fun place() {
        val c = canvas() ?: return
        // reading the scene may make it, whose built() must not sync in the middle of this layout pass
        placing = true
        val slots = try {
            if (columns.isEmpty() && c.scene?.slots.orEmpty().none { it.kind in KINDS }) return
            c.slots(KINDS)
        } finally {
            placing = false
        }
        if (slots.indices.any { columns.getOrNull(it)?.slot !== slots[it].first }) sync(slots.map { it.first })
        columns.forEachIndexed { i, col -> col.view.place(slots.getOrNull(i)?.second?.apply { offset(c.left, c.top) }) }
    }

    /** What each column shows read again: another list from its start, the same one edited where it was. */
    fun update() {
        updates++
        val now = reading().also { read = it }
        val field = ScrollColumn.fieldOf(keyboard.address)
        for (c in columns) {
            val columnInput = if (c.kind == ScrollLists.NINE_SIDE) now.side else now.input
            val was = c.shown
            val itemsWere = c.items
            c.shown = ScrollLists.shown(
                kind = c.kind,
                list = c.slot?.list,
                field = field,
                fieldPad = keyboard.fieldPad,
                pad = columnInput.pad,
                ime = columnInput.ime,
                composing = columnInput.composing,
            )
            c.items = (c.shown as? ScrollLists.Shown.Category)?.let { store[it.id] }.orEmpty()
            if (menuOn === c && (c.shown != was || c.items != itemsWere)) dismissMenu()
            val sameItems = c.shown is ScrollLists.Shown.Category && c.shown == was && c.items == itemsWere
            c.view.refresh(anew = c.shown != was, typing = c.shown == ScrollLists.Shown.Syllables, sameItems = sameItems)
        }
    }

    /** Another field: every column from its start, a menu open put away. */
    fun toStart() {
        dismissMenu()
        columns.forEach { it.view.toStart() }
    }

    private fun labels(c: Column): List<ScrollColumn.Label> = when (val s = c.shown) {
        ScrollLists.Shown.Syllables -> keyboard.columnSaid().let { said -> keyboard.columnSyllables().mapIndexed { i, it -> ScrollColumn.Label(it, said = said.getOrNull(i)) } }
        is ScrollLists.Shown.Category -> c.items.map { ScrollColumn.label(context, s.id, it, keyboard.columnEnglish, marks()) }
    }

    private fun pick(c: Column, i: Int) {
        val action = when (val s = c.shown) {
            ScrollLists.Shown.Syllables -> keyboard.pickSyllable(i)
            is ScrollLists.Shown.Category -> c.items.getOrNull(i)?.let { ScrollColumn.typed(s.id, it, keyboard.columnEnglish, marks(), keyboard.inPasswordField) }
        }
        action?.let(act)
    }

    // syllables are the typing's, no list's
    private fun menuFor(c: Column, i: Int): ScrollListMenu? {
        val s = c.shown as? ScrollLists.Shown.Category ?: return null
        return c.items.getOrNull(i)?.let { ScrollListMenu(s.id, i, it) }
    }

    // a newer version's lists are left as they are; before the first unlock a change would be lost to the
    // credential-encrypted copy synced over it
    private val readOnly: Boolean get() = store.newer || FcitxApplication.getInstance().isDirectBootMode

    // read once: every item's TalkBack names are made again on each refresh
    private val opNames by lazy(LazyThreadSafetyMode.NONE) { ScrollListMenu.Op.entries.associateWith { context.getString(A11Y.getValue(it)) } }

    // the item at [i] when run, as the view it is on shows it now
    private fun actions(c: Column, i: Int): List<Pair<String, () -> Unit>> {
        val entries = menuFor(c, i)?.entries(store, readOnly) ?: return emptyList()
        return entries.filter { it.enabled }.map { e -> opNames.getValue(e.op) to { menuFor(c, i)?.let { run(c, it, e.op) } ?: Unit } }
    }

    // as the column shows it: a mark converted, a command by its name
    private fun named(menu: ScrollListMenu): String =
        ScrollColumn.label(context, menu.list, menu.item, keyboard.columnEnglish, marks()).let { it.said ?: it.text }

    private fun run(c: Column, menu: ScrollListMenu, op: ScrollListMenu.Op) {
        if (op == ScrollListMenu.Op.Edit) {
            if (!readOnly) AppUtil.launchMainToScrollList(context, menu.list)
            return
        }
        val change = menu.change(store, (c.shown as? ScrollLists.Shown.Category)?.id, op, readOnly) ?: return
        val said = when (op) {
            ScrollListMenu.Op.Remove -> context.getString(R.string.scroll_list_removed, named(menu))
            else -> context.getString(R.string.scroll_list_moved_to_front)
        }
        pref.setValue(change.store.serialize())
        say(said, UndoChip.Undo({ change.undo.undone(store, readOnly)?.let { pref.setValue(it.serialize()) } }))
    }

    private var popupMenu: ListPopupWindow? = null
    private var menuOn: Column? = null
    private var menuAnchor: View? = null
    private var menuClosed = 0L

    /** True where one was up. */
    fun dismissMenu(): Boolean {
        val open = popupMenu ?: return false
        closed()
        open.dismiss()
        return true
    }

    /** A touch going down closes the menu: true where it was up or went as the touch went down, the touch then only closing it. */
    fun takes(downTime: Long): Boolean = dismissMenu() || downTime <= menuClosed

    private fun closed() {
        menuAnchor?.isActivated = false
        popupMenu = null
        menuOn = null
        menuAnchor = null
        menuClosed = SystemClock.uptimeMillis()
    }

    @VisibleForTesting
    internal val shownMenu: ListPopupWindow? get() = popupMenu

    private fun open(c: Column, menu: ScrollListMenu, entries: List<ScrollListMenu.Entry>, anchor: View) {
        val ctx = anchor.context
        val tint = ctx.styledColor(android.R.attr.colorControlNormal)
        val rows = entries.map { e ->
            val label = when (e.op) {
                ScrollListMenu.Op.ToFront -> ctx.getString(R.string.scroll_list_to_front)
                ScrollListMenu.Op.Edit -> ctx.getString(R.string.scroll_list_edit)
                ScrollListMenu.Op.Remove -> ctx.getString(R.string.scroll_list_remove, named(menu))
            }
            label to ICONS.getValue(e.op)
        }
        val adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1, rows.map { it.first }) {
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = entries[position].enabled
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    val icon = ContextCompat.getDrawable(ctx, rows[position].second)?.mutate()?.also { it.setTint(tint) }
                    setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
                    compoundDrawablePadding = ctx.dp(MENU_ICON_GAP_DP)
                    isEnabled = entries[position].enabled
                }
        }
        // not a PopupMenu: one takes the window focus from the editor, and some finish composing when they lose it
        // (an English word is composed in the field); not focusable, its back and outside touches come to the keyboard
        val popup = ListPopupWindow(ctx, null, android.R.attr.popupMenuStyle)
        popup.isModal = false
        popup.anchorView = anchor
        popup.setAdapter(adapter)
        popup.setContentWidth(max(widest(adapter, FrameLayout(ctx)), ctx.dp(MENU_MIN_WIDTH_DP)))
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            run(c, menu, entries[position].op)
        }
        dismissMenu()
        popup.setOnDismissListener { if (popup === popupMenu) closed() }
        popupMenu = popup
        menuOn = c
        menuAnchor = anchor
        anchor.isActivated = true
        popup.show()
    }

    private fun widest(adapter: ArrayAdapter<String>, parent: ViewGroup): Int {
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        return (0 until adapter.count).maxOf { i -> adapter.getView(i, null, parent).also { it.measure(unspecified, unspecified) }.measuredWidth }
    }

    private fun hit(c: Column, x: Float, y: Float): Boolean {
        val v = c.view.view
        return v.isVisible && x >= v.left && x < v.right && y >= v.top && y < v.bottom
    }

    fun at(x: Float, y: Float): Boolean = columns.any { hit(it, x, y) }

    /** Null off every column; a second finger on one taken does nothing, or its events would reset the first's drag. */
    fun target(x: Float, y: Float, taken: Collection<TouchTarget>): TouchTarget? {
        val i = columns.indexOfFirst { hit(it, x, y) }
        if (i < 0) return null
        val c = columns[i]
        val first = taken.firstOrNull { it is ColumnTarget && it.column === c } as ColumnTarget?
        // two fingers on one column are no tap: the first's press goes too, unless it already scrolls
        first?.crowded()
        return if (first != null) Busy else ColumnTarget(c, i)
    }

    private object Busy : TouchTarget {
        override val ref = KeyRef(REF)
        override val enabled = false
        override fun send(event: MotionEvent, action: Int, pointerIndex: Int) = Unit
        override fun cancel() = Unit
    }

    private inner class ColumnTarget(val column: Column, place: Int) : TouchTarget {
        override val ref = KeyRef("$REF$place")
        override val enabled = true

        private var downTime = 0L
        private var downX = 0f
        private var downY = 0f
        private var scrolled = 0
        private var item = -1
        private var pressed: ScrollItem? = null
        // the menu stays after the finger lifts, picked by a tap: its moves and lift until then do nothing
        private var menuShown = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val longPress = Runnable { showMenu() }
        private var gone = false

        /** Another finger landed on the column. */
        fun crowded() {
            // a column moved has taken the press from its item: the drag goes on, no tap to void
            if (menuShown || column.view.view.let { it.scrollX + it.scrollY } != scrolled) return
            cancel()
            gone = true
        }

        override fun send(event: MotionEvent, action: Int, pointerIndex: Int) {
            if (gone || menuShown) return
            val v = column.view.view
            val x = event.getX(pointerIndex)
            val y = event.getY(pointerIndex)
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = event.eventTime
                    downX = x
                    downY = y
                    scrolled = v.scrollX + v.scrollY
                    item = column.view.itemAt(x - v.left, y - v.top)
                    pressed = column.items.getOrNull(item)
                    if (menuFor(column, item) != null) keyboard.postDelayed(longPress, AppPrefs.getInstance().keyboard.longPressDelay.getValue().toLong())
                }
                MotionEvent.ACTION_UP -> keyboard.removeCallbacks(longPress)
            }
            forward(event.eventTime, action, x - v.left, y - v.top)
            // held still: a drag stopped would pick the item it started on
            if (action == MotionEvent.ACTION_MOVE && (beyondSlop(x, y) || v.scrollX + v.scrollY != scrolled)) {
                keyboard.removeCallbacks(longPress)
            }
        }

        private fun beyondSlop(x: Float, y: Float) = abs(x - downX) > slop || abs(y - downY) > slop

        private fun forward(time: Long, action: Int, x: Float, y: Float) {
            val e = MotionEvent.obtain(downTime, time, action, x, y, 0)
            column.view.view.dispatchTouchEvent(e)
            e.recycle()
        }

        private fun showMenu() {
            // the list changed under the finger: the place held now has another item
            val held = pressed ?: return
            if (column.items.getOrNull(item) != held) return
            val menu = menuFor(column, item) ?: return
            val entries = menu.entries(store, readOnly) ?: return
            val anchor = column.view.itemView(item) ?: return
            forward(SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f)
            InputFeedbacks.hapticFeedback(keyboard, longPress = true)
            menuShown = true
            open(column, menu, entries, anchor)
        }

        override fun cancel() {
            keyboard.removeCallbacks(longPress)
            if (!menuShown) forward(SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f)
        }
    }

    companion object {
        private const val SCROLL = "scroll"
        private val KINDS = setOf(SCROLL, ScrollLists.NINE_SIDE)

        /** A finger's target: a column's place, no key's. */
        private const val REF = "scroll:"

        // the platform's popup menu row: its least width, the gap between icon and title
        private const val MENU_MIN_WIDTH_DP = 196

        private const val MENU_ICON_GAP_DP = 16

        private val ICONS = mapOf(
            ScrollListMenu.Op.ToFront to R.drawable.ic_baseline_arrow_upward_24,
            ScrollListMenu.Op.Edit to R.drawable.ic_baseline_edit_24,
            ScrollListMenu.Op.Remove to R.drawable.ic_baseline_delete_24,
        )

        private val A11Y = mapOf(
            ScrollListMenu.Op.ToFront to R.string.a11y_scroll_list_to_front,
            ScrollListMenu.Op.Edit to R.string.a11y_scroll_list_edit,
            ScrollListMenu.Op.Remove to R.string.a11y_scroll_list_remove,
        )
    }
}
