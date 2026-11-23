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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import kotlin.math.abs
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
import org.fcitx.fcitx5.android.input.keys.ScrollListStore
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupFocus
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.SlotNode
import org.fcitx.fcitx5.android.scene.engine.Input
import org.fcitx.fcitx5.android.scene.engine.Popups
import org.fcitx.fcitx5.android.scene.keys.ScrollDefaults
import org.fcitx.fcitx5.android.scene.keys.ScrollItem
import org.fcitx.fcitx5.android.scene.keys.ScrollLists
import org.fcitx.fcitx5.android.scene.layout.Direction
import org.fcitx.fcitx5.android.scene.layout.Slot
import org.fcitx.fcitx5.android.scene.theme.SoundKey
import org.fcitx.fcitx5.android.utils.AppUtil
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

    /** Item [i]'s box in the view as scrolled now. */
    fun itemBounds(i: Int, out: Rect) {
        val child = items.getChildAt(i) ?: return out.setEmpty()
        out.set(child.left, child.top, child.right, child.bottom)
        out.offset(view.paddingLeft - view.scrollX, view.paddingTop - view.scrollY)
    }

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
        background = StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(pressColor)) }
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
@Suppress("LongParameterList")
internal class ScrollColumns(
    private val keyboard: BaseKeyboard,
    private val theme: Theme,
    private val canvas: () -> KeyboardCanvasView?,
    private val marks: () -> Map<String, String>,
    private val act: (KeyAction) -> Unit,
    private val popup: (PopupAction) -> Unit,
    private val focus: (KeyRef, Float, Float) -> Boolean,
    private val trigger: (KeyRef) -> Boolean,
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
            val sameItems = c.shown is ScrollLists.Shown.Category && c.shown == was && c.items == itemsWere
            c.view.refresh(anew = c.shown != was, typing = c.shown == ScrollLists.Shown.Syllables, sameItems = sameItems)
        }
    }

    /** Another field: every column from its start. */
    fun toStart() = columns.forEach { it.view.toStart() }

    private fun labels(c: Column): List<ScrollColumn.Label> = when (val s = c.shown) {
        ScrollLists.Shown.Syllables -> keyboard.columnSyllables().map { ScrollColumn.Label(it) }
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
    private fun menu(c: Column, i: Int): Pair<List<KeyDef.Popup.Menu.Item>, Set<Int>>? {
        val s = c.shown as? ScrollLists.Shown.Category ?: return null
        if (i !in c.items.indices) return null
        val items = OPS.mapIndexed { j, (op, _, icon) -> KeyDef.Popup.Menu.Item(opLabels[j], icon, KeyAction.ScrollList(s.id, i, op)) }
        val disabled = if (readOnly) items.indices.toSet() else setOfNotNull(0.takeIf { c.items.size <= 1 }, 1.takeIf { i == 0 })
        return items to disabled
    }

    // a newer version's lists are left as they are; before the first unlock a change would be lost to the
    // credential-encrypted copy synced over it
    private val readOnly: Boolean get() = store.newer || FcitxApplication.getInstance().isDirectBootMode

    // read once: every item's menu and TalkBack names are made again on each refresh
    private val opLabels by lazy(LazyThreadSafetyMode.NONE) { OPS.map { context.getString(it.label) } }
    private val opNames by lazy(LazyThreadSafetyMode.NONE) { OPS.map { context.getString(A11Y.getValue(it.op)) } }

    private fun actions(c: Column, i: Int): List<Pair<String, () -> Unit>> {
        val (items, disabled) = menu(c, i) ?: return emptyList()
        return items.indices.filter { it !in disabled }.map { j -> opNames[j] to { change(items[j].action as KeyAction.ScrollList) } }
    }

    /** The menu's pick: the list shown changed, or its card opened. */
    fun change(a: KeyAction.ScrollList) {
        if (readOnly) return
        when (a.op) {
            KeyAction.ScrollList.Op.Remove -> if (store[a.list].size > 1) pref.setValue(store.removed(a.list, a.index).serialize())
            KeyAction.ScrollList.Op.ToFront -> if (a.index > 0) pref.setValue(store.toFront(a.list, a.index).serialize())
            KeyAction.ScrollList.Op.Edit -> AppUtil.launchMainToScrollList(context, a.list)
        }
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
        private var menuShown = false
        private var lastX = 0f
        private var menuX = 0f
        private var movedOnMenu = false
        private val cell = Rect()
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val longPress = Runnable { showMenu() }
        private var gone = false

        private val menuCell = Popups.px(Popups.MENU_CELL_DP, context.resources.displayMetrics.density.toDouble()).toFloat()
        private val menuLift = Popups.px(Popups.MENU_LIFT_DP, context.resources.displayMetrics.density.toDouble()).toFloat()

        // the menu's first focus is the cell over the finger: a wobble there picks nothing, only going onto the
        // menu's row or as far sideways as moves its focus does
        private fun onMenu(x: Float, y: Float) =
            y < cell.top - menuLift + menuCell || abs(x - menuX) > menuCell * PopupFocus.STEP * (0.5f + PopupFocus.HYSTERESIS)

        /** Another finger landed on the column. */
        fun crowded() {
            // a column moved has taken the press from its item: the drag goes on, no tap to void
            if (menuShown || column.view.view.let { it.scrollX + it.scrollY } != scrolled) return
            cancel()
            gone = true
        }

        override fun send(event: MotionEvent, action: Int, pointerIndex: Int) {
            if (gone) return
            val v = column.view.view
            val x = event.getX(pointerIndex)
            val y = event.getY(pointerIndex)
            lastX = x
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = event.eventTime
                    downX = x
                    downY = y
                    scrolled = v.scrollX + v.scrollY
                    item = column.view.itemAt(x - v.left, y - v.top)
                    pressed = column.items.getOrNull(item)
                    if (menu(column, item) != null) keyboard.postDelayed(longPress, AppPrefs.getInstance().keyboard.longPressDelay.getValue().toLong())
                }
                MotionEvent.ACTION_MOVE -> if (menuShown) {
                    if (onMenu(x, y)) movedOnMenu = true
                    focus(ref, x - cell.left, y - cell.top)
                    return
                }
                MotionEvent.ACTION_UP -> {
                    keyboard.removeCallbacks(longPress)
                    if (menuShown) {
                        menuShown = false
                        // only an item the finger went onto is picked
                        if (movedOnMenu) trigger(ref) else popup(PopupAction.DismissAction(ref))
                        return
                    }
                }
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
            if (column.items.getOrNull(item) != pressed) return
            val (items, disabled) = menu(column, item) ?: return
            val v = column.view.view
            forward(SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f)
            column.view.itemBounds(item, cell)
            cell.offset(v.left, v.top)
            val at = IntArray(2).also { keyboard.getLocationInWindow(it) }
            val bounds = Rect(cell).apply { offset(at[0], at[1]) }
            InputFeedbacks.hapticFeedback(keyboard, longPress = true)
            menuShown = true
            movedOnMenu = false
            menuX = lastX
            popup(PopupAction.ShowMenuAction(ref, KeyDef.Popup.Menu(items.toTypedArray()), bounds, disabled))
        }

        override fun cancel() {
            keyboard.removeCallbacks(longPress)
            if (!menuShown) forward(SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f)
            menuShown = false
        }
    }

    private data class Op(val op: KeyAction.ScrollList.Op, @StringRes val label: Int, @DrawableRes val icon: Int)

    companion object {
        private const val SCROLL = "scroll"
        private val KINDS = setOf(SCROLL, ScrollLists.NINE_SIDE)

        /** The menu's key: a column's place, no key's. */
        private const val REF = "scroll:"

        private val OPS = listOf(
            Op(KeyAction.ScrollList.Op.Remove, R.string.scroll_list_remove, R.drawable.ic_baseline_delete_24),
            Op(KeyAction.ScrollList.Op.ToFront, R.string.scroll_list_to_front, R.drawable.ic_baseline_arrow_upward_24),
            Op(KeyAction.ScrollList.Op.Edit, R.string.scroll_list_edit, R.drawable.ic_baseline_edit_24),
        )

        private val A11Y = mapOf(
            KeyAction.ScrollList.Op.Remove to R.string.a11y_scroll_list_remove,
            KeyAction.ScrollList.Op.ToFront to R.string.a11y_scroll_list_to_front,
            KeyAction.ScrollList.Op.Edit to R.string.a11y_scroll_list_edit,
        )
    }
}
