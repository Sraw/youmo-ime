/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.scene.engine

import org.fcitx.fcitx5.android.scene.Box
import org.fcitx.fcitx5.android.scene.Icons
import org.fcitx.fcitx5.android.scene.KeyEffect
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.MarkFate
import org.fcitx.fcitx5.android.scene.KeyState
import org.fcitx.fcitx5.android.scene.Motion
import org.fcitx.fcitx5.android.scene.Op
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Point
import org.fcitx.fcitx5.android.scene.Problems
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.Shape
import org.fcitx.fcitx5.android.scene.SlotNode
import org.fcitx.fcitx5.android.scene.getOr
import org.fcitx.fcitx5.android.scene.layout.Face
import org.fcitx.fcitx5.android.scene.layout.Fragment
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.layout.OnSplit
import org.fcitx.fcitx5.android.scene.layout.Slot
import org.fcitx.fcitx5.android.scene.rules.KeyMarkYield
import org.fcitx.fcitx5.android.scene.rules.SplitKeyboard
import org.fcitx.fcitx5.android.scene.theme.Cascade
import org.fcitx.fcitx5.android.scene.theme.Cascades
import org.fcitx.fcitx5.android.scene.theme.Effect
import org.fcitx.fcitx5.android.scene.theme.Fill
import org.fcitx.fcitx5.android.scene.theme.KeyLook
import org.fcitx.fcitx5.android.scene.theme.Selector
import org.fcitx.fcitx5.android.scene.theme.Style
import org.fcitx.fcitx5.android.scene.theme.TextLook
import org.fcitx.fcitx5.android.scene.theme.Value
import org.fcitx.fcitx5.android.scene.theme.Theme

/**
 * A layout and a theme in an environment, made a [Scene] (060-render-v2 §6): the board placed in the
 * keyboard (one-handed or split at a side: rules D §2), expanded ([Expand]), placed ([Place]), each
 * key's look cascaded from the theme for its states and pressed, its face's layers placed ([Faces]),
 * its text and marks placed ([Content]) and kept apart ([KeyMarkYield]: the letters together, the
 * rest each on its own), all drawn as ops ([Paint]); a slot's or a blank place's face under it. The
 * same values give the same scene, on the phone, in a browser, in a test.
 */
class SceneBuilder(
    private val theme: Theme,
    private val env: Env,
    private val measurer: TextMeasurer,
    private val icons: IconSizes = IconSizes.MATERIAL,
    private val phases: Phases = Phases.None,
    /** [theme]'s cascades, kept by the caller to make its next scene of the theme with what these worked out. */
    cascades: Cascades = Cascades(theme),
) {
    init {
        require(cascades.theme === theme) { "the cascades of another theme" }
    }

    private val cascade = cascades.of(env.scenes)

    // `face="keep"`: the face theme 3.0 drew whatever 按键样式 said (the lookup key on, 确定)
    private val unflat = cascades.of(env.scenes - "flat")

    // a key's cascade by its face: one kept as 3.0 drew it, unflat
    private fun cascadeFor(face: Face?) = if (face == Face.Keep) unflat else cascade

    // the app's 按键样式 over a 4.0 theme's keys (KeyStyleOver), null where it changes nothing
    private val over = env.keyStyle?.takeUnless { it.none }

    // [values], a key's as the theme's cascade has them, with the app's 按键样式 laid on (KeyStyleOver.on)
    private fun over(values: Map<String, Value>): Map<String, Value> = over?.on(values, env.landscape, env.compact) ?: values
    private val paint = Paint(theme, env.density)
    private val content = Content(env, measurer, icons, themedSign = Icons.SHORT_TEXT in theme.icons)
    private val popups by lazy { PopupBuilder(theme, env, measurer, icons, cascade, paint) }

    /**
     * [layout] with the files its includes name, [fragments] by id; its problems if it cannot be laid
     * out here. Never throws: a fault of the engine's own is a problem too, so a bad file a user
     * imported cannot take the keyboard down.
     */
    // any exception (an Error, out of memory, is not a layout's fault): the caller is the keyboard drawing itself, which has nowhere to send a throw
    @Suppress("TooGenericExceptionCaught")
    fun build(layout: Layout, fragments: Map<String, Fragment> = emptyMap()): Parsed<Scene> = try {
        scene(layout, fragments)
    } catch (e: Exception) {
        Parsed.Failed(0, "the scene could not be built: ${fault(e)}")
    }

    /**
     * A key's preview bubble (rules D §4, [PopupBuilder.preview]): [text] over a key [keyWidth] px
     * across, at [size] px (a shortcut's words fitted), else a character's size. Never throws.
     */
    fun preview(text: String, keyWidth: Double, size: Double? = null): Parsed<Scene> = safely("preview") { popups.preview(text, keyWidth, size) }

    /** How `popup`, `preview` or `menu` ([widget]) comes up and goes (060-render-p3 A2); null: as ever. Never throws. */
    fun popupMotion(widget: String): Parsed<PopupMotion?> = safely("popup motion") { popups.motion(widget) }

    /** The bubble's words' size and the least they shrink to, px (PreviewLabel fits them). Never throws. */
    fun previewWordsSize(): Parsed<Pair<Double, Double>> = safely("preview words size") { popups.wordsSize() }

    /** The space each side of the bubble's text, px. Never throws. */
    fun previewPadding(): Parsed<Double> = safely("preview padding") { popups.padding() }

    /** How wide [text] is drawn in the bubble at [size] px. Never throws. */
    fun previewWidth(text: String, size: Double): Parsed<Double> = safely("preview width") { popups.previewWidth(text, size) }

    /**
     * A long press's keys' size for [labels] [columns] across in [room] px ([PopupBuilder.popupKeys]):
     * at least [minWidth] wide, [height] tall, by default [Popups]'. Never throws.
     */
    fun popupKeys(
        labels: List<String>,
        columns: Int,
        room: Double?,
        minWidth: Double = Popups.px(Popups.KEY_MIN_WIDTH_DP, env.density),
        height: Double = Popups.px(Popups.KEY_HEIGHT_DP, env.density),
    ): Parsed<PopupKeys> = safely("popup keys") { popups.popupKeys(labels, columns, room, minWidth, height) }

    /** A long press's keys ([PopupBuilder.popup]): [rows] of [labels]' indices, the bottom one first, [selected] in focus. Never throws. */
    fun popup(labels: List<String>, rows: List<List<Int>>, keys: PopupKeys, selected: Int): Parsed<Scene> =
        safely("popup") { popups.popup(labels, rows, keys, selected) }

    /** A menu's cells' size ([PopupBuilder.menuKeys]). Never throws. */
    fun menuKeys(cells: List<MenuCell>): Parsed<PopupKeys> = safely("menu keys") { popups.menuKeys(cells) }

    /** A menu ([PopupBuilder.menu]): [cells] in [order] across, [selected] in focus, [disabled] faded. Never throws. */
    fun menu(cells: List<MenuCell>, order: List<Int>, selected: Int, disabled: Set<Int> = emptySet()): Parsed<Scene> =
        safely("menu") { popups.menu(cells, order, selected, disabled) }

    // as build: a fault of the engine's own is a problem, never a throw into the keyboard drawing it
    @Suppress("TooGenericExceptionCaught")
    private fun <T> safely(what: String, make: () -> T): Parsed<T> = try {
        Parsed.Ok(make())
    } catch (e: Exception) {
        Parsed.Failed(0, "the $what could not be built: ${fault(e)}")
    }

    // the only trace a fault leaves (the keyboard logs it and draws nothing): its class, message and stack
    private fun fault(e: Exception): String = e.stackTraceToString()

    private fun scene(layout: Layout, fragments: Map<String, Fragment>): Parsed<Scene> {
        val warnings = Problems()
        if (padless(layout)) warnings.add(0, "a number pad with no pad= given: the full pad")
        val placement = placement(layout)
        val rows = phases.section("expand") { expand(layout, fragments) }.getOr { return Parsed.Failed(it) }
        val board = board(placement)
        val placed = phases.section("place") { Place.rows(rows, layout.columns, layout.onSplit, env, board, warnings) }.getOr { return Parsed.Failed(it) }
        // each key's looks cascaded, its face's layers and its texts placed (the texts measured): each a
        // pass over the keys, so a trace tells the three apart
        val keyed = placed.mapNotNull { p -> (p.item as? ExpandedKey)?.let { p to it } }
        val styled = phases.section("cascade.of") { keyed.map { (_, k) -> styled(layout.id, k) } }
        val shaped = phases.section("faces") { keyed.mapIndexed { i, (p, k) -> shaped(p, k, styled[i]) } }
        var keys = phases.section("content") { keyed.mapIndexed { i, (p, k) -> prepare(p, k, styled[i], shaped[i], warnings) } }
        // the same keys with their edges where they fall, not on whole pixels: what the marks' yields are
        // worked out on (060-render-p2 S4e), so a pixel's rounding never turns a choice; drawn on the above
        var cells: List<Placed> = emptyList()
        fun looseOf(i: Int, short: Map<Int, Int>): KeyContent {
            // a key with no marks has nothing to choose: as drawn
            if (keys[i].content.marks.isEmpty()) return keys[i].content
            val k = keyed[i].second
            val cell = cells[i].cell
            val g = Faces.of(cell, styled[i].looks.getValue(styled[i].states), k.attrs.face ?: Face.Default, k.attrs.margin ?: true, env.flat && k.attrs.face != Face.Keep, env.density)
            return fitted(content.of(k, cell, g.content, Looks(styled[i].parts.getValue(styled[i].states)), g, snap = false, short = short))
        }
        var loose = phases.section("loose") {
            cells = Place.rows(rows, layout.columns, layout.onSplit, env, board, Problems(), snap = false).getOr { return Parsed.Failed(it) }
                .filter { it.item is ExpandedKey }
            keyed.indices.map { looseOf(it, emptyMap()) }
        }
        // a text with no room on its key as it is (left out, or asking the shortcuts all smaller): a step down its
        // ladder at a time (its first character and 「…」, then the short-text icon), so one long text neither vanishes nor
        // shrinks every mark; the key then asks nothing of the marks' classes (Member.shortened)
        val short = HashMap<Int, Map<Int, Int>>()
        for (step in 1..LADDER_STEPS) {
            val more = keyed.indices.associateWith { i -> roomless(i, loose[i], short[i].orEmpty()) }.filterValues { it.isNotEmpty() }
            if (more.isEmpty()) break
            more.forEach { (i, js) -> short[i] = short[i].orEmpty() + js.associateWith { j -> (short[i]?.get(j) ?: 0) + 1 } }
            keys = keys.mapIndexed { i, k -> if (i in more) prepare(k.placed, keyed[i].second, styled[i], shaped[i], Problems(), short.getValue(i)) else k }
            loose = loose.mapIndexed { i, c -> if (i in more) looseOf(i, short.getValue(i)) else c }
        }
        val nodes = phases.section("paint") {
            val settled = settle(keys, loose, short.keys)
            keys.mapIndexed { i, k -> node(k, settled[i]) }
        }
        val slots = placed.mapNotNull { p ->
            val s = (p.item as? ExpandedSlot)?.slot ?: return@mapNotNull null
            val face = surface(layout.id, s.id, s.look, s.classes, s.face, p.cell, slot = true)
            if (s.kind == Pads.KIND) pad(layout.id, s, p.cell, face) else SlotNode(s.kind, s.id, p.cell, face)
        }
        // a key's place the board has no key for (the number pads'): a key that takes no press, `:disabled`
        // and faded as theme 3.0's disabled blank view was (its whole view at android:disabledAlpha)
        val blanks = placed.flatMap { p ->
            val g = (p.item as? ExpandedGap)?.gap ?: return@flatMap emptyList()
            surface(layout.id, g.id, g.look, g.classes, Face.Default, p.cell, slot = false, states = setOf(KeyState.Disabled.xml))
                .map { popups.faded(it, Popups.DISABLED_ALPHA) }
        }
        val whole = Box(0.0, 0.0, env.width, env.height)
        val side = sideRoom(board, whole)?.let { SlotNode(SIDE_BUTTONS, null, it) }
        return Parsed.Ok(Scene(env.width, env.height, base(whole) + gaps(placed) + blanks, nodes, slots + listOfNotNull(side), board, layout.preview, layout.popups, warnings.all))
    }

    // the room between a split row's halves (`split-gap`, 060-render-p3 T7a): its fill and corners, each row's; none set, the keyboard's under it
    private fun gaps(placed: List<Placed>): List<Op> {
        val rooms = placed.filter { it.item === SplitRoom }
        if (rooms.isEmpty()) return emptyList()
        val look = KeyLook(cascade.of(Selector.Target("split-gap")), theme)
        if (look.fill == null) return emptyList()
        return rooms.mapNotNull { paint.fill(Shape.Rect(it.cell, Faces.radii(look.radius, it.cell, env.density)), look.fill) }
    }

    // a side of the keyboard one-handed, or split for a board but the letters; null: the whole of it
    private fun placement(layout: Layout): SplitKeyboard.Placement? = if (env.floating != null) {
        null
    } else {
        SplitKeyboard.placement(env.oneHanded, env.dock, env.split, layout.onSplit != OnSplit.Side, env.widthDp)
    }

    // where the keys go
    private fun board(placement: SplitKeyboard.Placement?): Box {
        val whole = Box(0.0, 0.0, env.width, env.height)
        if (placement == null) return whole
        val w = placement.widthDp * env.density
        return if (placement.side == SplitKeyboard.Dock.LEFT) Box(0.0, 0.0, w, env.height) else Box(env.width - w, 0.0, env.width, env.height)
    }

    // the keyboard less the board, where the app's buttons go (moving it across, back to the whole width)
    private fun sideRoom(board: Box, whole: Box): Box? = when {
        board == whole -> null
        board.left > 0 -> Box(0.0, 0.0, board.left, whole.bottom)
        else -> Box(board.right, 0.0, whole.right, whole.bottom)
    }

    // the keyboard's own fill under the keys
    private fun base(box: Box): List<Op> {
        val look = KeyLook(cascade.of(Selector.Target("keyboard")), theme)
        return listOfNotNull(paint.fill(Shape.Rect(box, Faces.radii(look.radius, box, env.density)), look.fill))
    }

    // a key's face where a slot or a blank place is given a look: never pressed, `keep` block on flat keys too;
    // a slot is not one of its board's every key (`handwriting/*`), but by its own name and look, as theme 3.0 drew the pad
    private fun surface(
        board: String,
        id: String?,
        look: String?,
        classes: List<String>,
        face: Face,
        cell: Box,
        slot: Boolean,
        states: Set<String> = emptySet(),
    ): List<Op> {
        if (look == null || face == Face.None) return emptyList()
        // a slot's or a blank place's face: the theme's alone, the key style is for the keys (the owner's settings name keys)
        val c = cascadeFor(face)
        val l = KeyLook(c.of(Selector.Target("key", board, id, null, setOf(look) + classes, states, slot = slot)), theme)
        return paint.face(Faces.of(cell, l, face, true, env.flat && face != Face.Keep, env.density), l, false)
    }

    // the handwriting pad: its face, the grid over it the theme shows, the ink its strokes are drawn in (060-render-p3 T7b)
    private fun pad(board: String, s: Slot, cell: Box, face: List<Op>): SlotNode {
        val target = Selector.Target("key", board, s.id, null, setOfNotNull(s.look) + s.classes, emptySet(), slot = true)
        // the grid and the ink are the scene's, flat or not; only the face keeps 3.0's
        val v = cascade.of(target)
        val c = cascadeFor(s.face)
        val box = s.look?.takeIf { s.face != Face.None }?.let {
            Faces.of(cell, KeyLook(c.of(target), theme), s.face, true, env.flat && s.face != Face.Keep, env.density).face?.bounds
        } ?: cell
        return SlotNode(s.kind, s.id, cell, face + Pads.grid(v, theme, box, env.density), Pads.ink(v, theme, env.density))
    }

    private class Prepared(
        val placed: Placed,
        val key: ExpandedKey,
        val states: Set<KeyState>,
        val looks: Map<Set<KeyState>, KeyLook>,
        val parts: Map<Set<KeyState>, (Part) -> TextLook>,
        val geometry: Map<Set<KeyState>, FaceGeometry>,
        val content: KeyContent,
    )

    private data class Settled(val glyph: Drawn?, val marks: List<Drawn?>)

    // a key's looks in its states and pressed: its face's and each part's
    private class Styled(
        val states: Set<KeyState>,
        val looks: Map<Set<KeyState>, KeyLook>,
        val parts: Map<Set<KeyState>, (Part) -> TextLook>,
    )

    // what a key's looks are worked out from: the same in the next scene, its looks kept by the cascade. [over]: the
    // key style laid on and the form it reads (landscape, compact), null without one, as the cascade is shared by
    // every scene of the theme (a rotation, a setting changed, the settings' key cells)
    private data class StyledFor(
        val board: String,
        val id: String,
        val role: String?,
        val classes: Set<String>,
        val states: Set<KeyState>,
        val over: Triple<KeyStyleOver, Boolean, Boolean>?,
    ) :
        Cascade.KeptKey<Styled>

    private fun styled(board: String, key: ExpandedKey): Styled {
        val c = cascadeFor(key.attrs.face)
        val states = statesOf(key)
        val classes = setOf(key.kind) + key.attrs.classes.orEmpty()
        return c.kept(StyledFor(board, key.key.id, key.attrs.role, classes, states, over?.let { Triple(it, env.landscape, env.compact) })) { styled(c, board, key, classes, states) }
    }

    private fun styled(c: Cascade, board: String, key: ExpandedKey, classes: Set<String>, states: Set<KeyState>): Styled {
        val looks = HashMap<Set<KeyState>, KeyLook>()
        val parts = HashMap<Set<KeyState>, (Part) -> TextLook>()
        for (s in listOf(states, states + KeyState.Pressed)) {
            // the key's target once a state, its parts the same but the part's name
            val whole = Selector.Target("key", board, key.key.id, key.attrs.role, classes, s.mapTo(HashSet()) { it.xml })
            looks[s] = KeyLook(over(c.of(whole)), theme)
            // a part's look only when a text or an icon of it is placed or drawn
            val byPart = HashMap<Part, TextLook>()
            parts[s] = { part -> byPart.getOrPut(part) { TextLook(c.of(whole.copy(part = part.xml)), theme) } }
        }
        return Styled(states, looks, parts)
    }

    /**
     * The keys of [roles] on [layout] that this scene would draw blank: their words and icon hidden and their
     * face, as [styled] cascades it, not an image, in the states the environment puts them in plus each of
     * [stateSets], each pressed and not. `board/id` and the states for each, for [BlankKeys].
     *
     * [expanded] keeps the keys expanded across the scenes [BlankKeys] asks, by what of the environment expands
     * them there: its board and form (its input and texts the same in each), not its colours or what is typed.
     */
    internal fun blanks(
        layout: Layout,
        fragments: Map<String, Fragment>,
        roles: Set<String>,
        stateSets: List<Set<KeyState>>,
        expanded: MutableMap<List<Any>, List<ExpandedKey>> = HashMap(),
    ): List<Pair<ExpandedKey, Set<KeyState>>> {
        val out = ArrayList<Pair<ExpandedKey, Set<KeyState>>>()
        val form = listOf<Any>(layout.id, env.landscape, env.compact, env.floating != null, env.split, env.isOneHanded)
        for (key in expanded.getOrPut(form) { expandedKeys(layout, fragments) }) {
            if (key.attrs.role !in roles) continue
            val c = cascadeFor(key.attrs.face)
            val classes = setOf(key.kind) + key.attrs.classes.orEmpty()
            val own = statesOf(key)
            for (states in stateSets.map { own + it }.distinct().flatMap { listOf(it, it + KeyState.Pressed) }) {
                val whole = Selector.Target("key", layout.id, key.key.id, key.attrs.role, classes, states.mapTo(HashSet()) { it.xml })
                // what the key shows (its words, its icon) all hidden by the theme; one that shows neither is the layout's
                fun hides(part: Part) = TextLook(c.of(whole.copy(part = part.xml)), theme).hidden
                val shown = (key.label != null && !hides(Part.Label)) || (key.icon != null && !hides(Part.Icon))
                val hidden = !shown && (key.label != null || key.icon != null)
                if (hidden && KeyLook(c.of(whole), theme).fill !is Fill.Image) out += key to states
            }
        }
        return out
    }

    // the keys as the scene has them: a layout that does not expand here draws no keys here (its own problems
    // are the layout check's); expanded as [scene] does: a number pad the app named no pad for, the full one
    private fun expandedKeys(layout: Layout, fragments: Map<String, Fragment>): List<ExpandedKey> {
        val rows = expand(layout, fragments).getOr { return emptyList() }
        return rows.flatMap { it.items }.filterIsInstance<ExpandedKey>()
    }

    // rules C §7: a number pad the app named no pad for (a field it does not know) is the full one
    private fun padless(layout: Layout) = layout.family == "number" && env.input.pad == null

    // [layout]'s rows as this scene expands them, the one rule [scene] and [expandedKeys] share: padless, the full
    // pad; split, the half its placement puts it on
    private fun expand(layout: Layout, fragments: Map<String, Fragment>): Parsed<List<ExpandedRow>> {
        val expanding = if (padless(layout)) env.copy(input = env.input.copy(pad = FULL_PAD)) else env
        val splitSide = placement(layout)?.side?.takeIf { env.split }?.name?.lowercase()
        return Expand.rows(layout, fragments, expanding, splitSide)
    }

    /**
     * Which style each of key [id]'s properties ([part]'s, null the key's own) comes from on [layout] here, as
     * [styled] cascades it, in [states] (theme.xml's names: `pressed`, `locked`…) besides the ones the key is in
     * (the maker's cascade check). Empty where the key is not on it here.
     */
    internal fun sources(layout: Layout, fragments: Map<String, Fragment>, id: String, part: String?, states: Set<String>): Map<String, Style> {
        val key = expandedKeys(layout, fragments).firstOrNull { it.key.id == id } ?: return emptyMap()
        val c = cascadeFor(key.attrs.face)
        val classes = setOf(key.kind) + key.attrs.classes.orEmpty()
        val all = statesOf(key).mapTo(HashSet()) { it.xml } + states
        return c.sources(Selector.Target("key", layout.id, key.key.id, key.attrs.role, classes, all, part))
    }

    // the key's face's layers in each state
    private fun shaped(placed: Placed, key: ExpandedKey, styled: Styled): Map<Set<KeyState>, FaceGeometry> {
        val face = key.attrs.face ?: Face.Default
        val flat = env.flat && face != Face.Keep
        val margin = key.attrs.margin ?: true
        return styled.looks.mapValues { (_, look) -> Faces.of(placed.cell, look, face, margin, flat, env.density) }
    }

    private fun prepare(placed: Placed, key: ExpandedKey, styled: Styled, geometry: Map<Set<KeyState>, FaceGeometry>, warnings: Problems, short: Map<Int, Int> = emptyMap()): Prepared {
        val looksNow = Looks(styled.parts.getValue(styled.states))
        val g = geometry.getValue(styled.states)
        val drawn = fitted(content.of(key, placed.cell, g.content, looksNow, g, short = short), warnings)
        return Prepared(placed, key, styled.states, styled.looks, styled.parts, geometry, drawn)
    }

    // the states a key is in but pressed: the environment's for its id or role, Shift's from the shift
    private fun statesOf(key: ExpandedKey): Set<KeyState> = buildSet {
        env.states[key.key.id]?.let(::addAll)
        key.attrs.role?.let { env.states[it] }?.let(::addAll)
        if (key.attrs.role == "shift") {
            if (env.shift == Shift.Once) add(KeyState.Shifted)
            if (env.shift == Shift.Lock) add(KeyState.Locked)
        }
    }

    // key [i]'s ([c], as worked out, its texts [at] their steps down) text marks to take a step further down: none
    // where its marks fit at their size; else those not at their ladder's foot left out on it alone, or, where none
    // is but the shortcuts are asked smaller, those meeting the glyph or another mark (a built-in left out
    // shortens nothing)
    private fun roomless(i: Int, c: KeyContent, at: Map<Int, Int>): Set<Int> {
        val texts = c.marks.indices.filter { j -> c.marks[j].text && (at[j] ?: 0) < LADDER_STEPS }
        if (texts.isEmpty()) return emptySet()
        val gap = GAP_DP * env.density
        val m = member(c, i, at.isNotEmpty())
        if (KeyMarkYield.fitsWhole(m, gap)) return emptySet()
        val b = KeyMarkYield.board(listOf(m), gap)
        val dropped = texts.filter { b.keys[0].marks[it] == null }
        if (dropped.isNotEmpty()) return dropped.toSet()
        return if (b.shortcut < 1.0) texts.filter { it in KeyMarkYield.crowded(m, gap) }.toSet() else emptySet()
    }

    // rules B §7, 060-render-p2 S4e: each class's glyphs one size (KeyMarkYield.board), their marks kept apart
    // from them; a key of no class (no glyph, a table's roots in lines) as placed, its marks of their classes
    private fun settle(keys: List<Prepared>, loose: List<KeyContent>, shortened: Set<Int>): List<Settled> {
        val gap = GAP_DP * env.density
        // a key with no glyph but marks (a space with no name) too, and one whose glyph stays as placed (a
        // table's roots in lines) as one with none: its marks are of their classes
        val members = keys.indices.filter { keys[it].content.let { c -> (c.yields && c.glyphClass != null) || c.marks.isNotEmpty() } }
        val board = KeyMarkYield.board(members.map { member(loose[it], it, it in shortened) }, gap)
        val out = arrayOfNulls<Settled>(keys.size)
        members.forEachIndexed { n, i ->
            val c = keys[i].content
            val onto = KeyMarkYield.onto(member(c, i), member(loose[i], i, i in shortened), board, board.keys[n])
            val drawn = c.glyphClass?.takeIf { c.yields }?.let { c.glyph?.drawnAt(onto.glyph.toBox(), board.scales.getValue(classOf(c, it, i)), c.glyph.grows(measurer)) }
            val glyph = drawn?.let { within(it, c.room, c.anchor?.first) } ?: c.glyph
            // moved back within its room: its marks placed again where one it kept meets it there
            val s = if (drawn != null && glyph !== drawn) KeyMarkYield.again(member(c, i), glyph!!.ink.toInk(), board, onto, gap) else onto
            out[i] = Settled(glyph, c.marks.mapIndexed { j, m -> s.marks[j]?.let { m.drawn.drawnAt(it.toBox(), board.markScale(mark(m)), m.drawn.grows(measurer)) } })
        }
        keys.forEachIndexed { i, k ->
            if (out[i] == null) out[i] = Settled(k.content.glyph, k.content.marks.map { it.drawn })
        }
        return out.map { it!! }
    }

    // rules B §8a (060-render-p2 S6 review): a label wider than its key's room shrinks, before its marks yield
    // (so they yield to it as drawn). A class's: the class all as much smaller ([KeyMarkYield.board] asks it of
    // each), to [KeyMarkYield.MAX_CLASS_SHRINK] at most with what its marks ask; only what is wider still shrunk
    // alone here, by what the class's most leaves. A key of no class shrunk alone to fit, both ways alike, about
    // its scaling point (a main character's baseline kept), centred on the room across, its ink measured at the
    // size drawn (§8b). One that fits but whose ink stands out of the room (a text centred by its advance, its
    // ink off to one side: （#-_-)┯━┯) moved the least way back into it. What is cut off is not shown, and what
    // is shown is what the key types.
    private fun fitted(c: KeyContent, warnings: Problems? = null): KeyContent {
        val g = c.glyph
        if (g == null || g.texts.isEmpty() || g.ink.width <= 0) return c
        val classed = c.yields && c.glyphClass != null
        val least = if (classed) 1.0 - KeyMarkYield.MAX_CLASS_SHRINK else 1.0
        val py = c.anchor?.second ?: if (classed && c.glyphClass == GlyphClass.Main) c.baseline else g.ink.centerY
        // wider than the room still (the class to take it in): its ink's middle the room's, so the class, smaller
        // about it, leaves it within on both sides
        val placed = within(alone(g, c.room, least, py, c.anchor?.first, warnings), c.room, c.anchor?.first)
        return if (placed === g) c else KeyContent(placed, c.marks, c.room, c.glyphClass, c.baseline, c.yields, c.edge, c.anchor, c.own)
    }

    // [g] shrunk alone about (the room's middle, [py]) as far as its ink measured at [least] (its class's most:
    // what the class's ask sees) is within [room]; as it is where it is so already. Not within it even at
    // [FIT_LEAST] of the first guess (a font's ink that grows as it shrinks): that size, and a warning in [warnings]
    private fun alone(g: Drawn, room: Box, least: Double, py: Double, ax: Double?, warnings: Problems? = null): Drawn {
        val grows = g.grows(measurer)
        // shrunk by [s], its ink as drawn at that size (§8b)
        fun shrunk(s: Double): Drawn {
            val d = grows?.at(s)
            val w = g.ink.width * s + (d?.let { it.left + it.right } ?: 0.0)
            val top = py + (g.ink.top - py) * s - (d?.top ?: 0.0)
            val bottom = py + (g.ink.bottom - py) * s + (d?.bottom ?: 0.0)
            val box = if (ax == null) Box(room.centerX - w / 2, top, room.centerX + w / 2, bottom) else across(room, ax, w).let { Box(it, top, it + w, bottom) }
            return g.drawnAt(box, s, grows)
        }
        fun fits(f: Drawn) = f.ink.width * least + (f.grows(measurer)?.at(least)?.let { it.left + it.right } ?: 0.0) <= room.width
        // measured at the class's most, not scaled (§8b): a font's ink can be wider there than scaled
        // no room at all (insets wider than the face): unshrunk, never by a negative scale, which mirrors it
        if (room.width <= 0 || g.ink.width <= room.width || fits(g)) return g
        val s = minOf(1.0, room.width / (g.ink.width * least))
        val fit = shrunk(s)
        if (fits(fit)) return fit
        // the ink not linear in the size (a font's whole pixels, its hinting: by steps): the largest below that
        // fits at the class's most, measured again each time
        var lo = s * FIT_LEAST
        var hi = s
        repeat(FIT_STEPS) {
            val m = (lo + hi) / 2
            if (fits(shrunk(m))) lo = m else hi = m
        }
        val out = shrunk(lo)
        if (!fits(out)) warnings?.add(0, "label ${g.texts.joinToString("") { it.text }} not within its room at its least size (rules B §8a)")
        return out
    }

    // rules B §8a's last word: a label's ink as drawn at its class's size (§8b: not even about its middle) moved
    // the least way back into its room where it stands out
    // (as wide as the room to a rounding's worth: put in it whole; wider, which the class's ask leaves none: its middle the room's).
    // Out by half a px or less it stays: that is the glyph mapped onto whole pixels (KeyMarkYield.onto), and moved
    // in it would meet a mark kept at the gap and have them all placed again, a pixel's rounding moving a mark
    // px (060-render-p2 S4e, S7a review #4); the room is inside the face's padding, nothing is cut
    private fun within(g: Drawn, room: Box, ax: Double?): Drawn {
        if (g.texts.isEmpty() || (g.ink.left >= room.left - SNAP && g.ink.right <= room.right + SNAP)) return g
        val left = if (g.ink.width > room.width + ROUNDING) across(room, ax, g.ink.width) else room.left.coerceAtLeast(minOf(g.ink.left, room.right - g.ink.width))
        return g.mapTo(Box(left, g.ink.top, left + g.ink.width, g.ink.bottom), 1.0)
    }

    // where a glyph [w] wide that is wider than [room] starts: its middle the room's; one a theme aligns (its
    // anchor at [ax] across), as far along it as [ax] is along the room, so it stays at its corner or its top
    private fun across(room: Box, ax: Double?, w: Double): Double {
        if (ax == null || room.width <= 0) return room.centerX - w / 2
        return ax - ((ax - room.left) / room.width).coerceIn(0.0, 1.0) * w
    }

    // a glyph's class's number on the board: its [GlyphClass]'s, or one of its own ([KeyContent.own]) by its key's [index]
    private fun classOf(c: KeyContent, cls: GlyphClass, index: Int): Int = if (c.own) GlyphClass.entries.size + index else cls.ordinal

    // a key as its class's member: a main character about its baseline, where its row's stand, and never moved off
    // it; a glyph a theme aligns about where it stays ([KeyContent.anchor]), never moved either
    private fun member(c: KeyContent, index: Int, shortened: Boolean = false): KeyMarkYield.Member {
        val cls = c.glyphClass?.takeIf { c.yields }
            ?: return KeyMarkYield.Member(KeyMarkYield.NO_GLYPH, 0.0, 0.0, c.marks.map(::mark), c.room.toInk(), c.edge, glyphClass = -1, shortened = shortened)
        val ink = c.glyph!!.ink.toInk()
        val main = cls == GlyphClass.Main
        val (x, y) = c.anchor ?: (((ink.left + ink.right) / 2) to (if (main) c.baseline else (ink.top + ink.bottom) / 2))
        return KeyMarkYield.Member(
            ink, x, y, c.marks.map(::mark), c.room.toInk(), c.edge,
            moves = !main && c.anchor == null, glyphClass = classOf(c, cls, index), grows = c.glyph.grows(measurer), across = c.glyph.texts.isNotEmpty(),
            shortened = shortened,
        )
    }

    private fun mark(m: MarkDrawn) = KeyMarkYield.Mark(m.drawn.ink.toInk(), m.slot, m.drop, m.drawn.grows(measurer), m.user)

    private fun node(k: Prepared, s: Settled): KeyNode {
        val cell = k.placed.cell
        val normal = k.geometry.getValue(k.states)
        val faces = k.looks.keys.associateWith { states ->
            val looks = k.parts.getValue(states)
            val ops = paint.face(k.geometry.getValue(states), k.looks.getValue(states), KeyState.Pressed in states) +
                listOfNotNull(s.glyph).flatMap { paint.content(it, looks) } +
                s.marks.filterNotNull().flatMap { paint.content(it, looks) }
            // a key that takes no press (展开候选's page that cannot turn, 060-render-p2 S6): faded as a blank is
            if (KeyState.Disabled in states) ops.map { popups.faded(it, Popups.DISABLED_ALPHA) } else ops
        }
        return KeyNode(
            id = k.key.key.id,
            copy = k.placed.copy,
            kind = k.key.kind,
            role = k.key.attrs.role,
            shape = normal.face ?: normal.pressed,
            bounds = cell,
            hit = Shape.Rect(k.placed.hit ?: cell),
            anchor = Point(cell.centerX, cell.centerY),
            up = UP,
            states = k.states,
            faces = faces,
            a11y = k.key.a11y,
            actions = k.key.attrs.actions,
            label = k.key.label,
            alt = k.key.alt,
            altRaw = k.key.altRaw,
            popup = k.key.key.popup,
            menu = k.key.key.menu,
            sound = k.key.attrs.sound ?: "standard",
            shortcut = k.key.attrs.shortcut,
            letters = k.key.key.letters,
            lean = k.key.key.leans,
            face = k.key.attrs.face ?: Face.Default,
            margin = k.key.attrs.margin ?: true,
            motion = motion(k.looks.getValue(k.states), k.looks.getValue(k.states + KeyState.Pressed), (normal.face ?: normal.pressed).bounds),
            // a way left out (no room even at its ladder's foot) said so, else how it was drawn
            fates = k.content.marks.zip(s.marks).mapNotNull { (m, d) -> m.up?.let { up -> up to (if (d == null) MarkFate.Dropped else m.fate!!) } }.toMap(),
        )
    }

    // 060-render-p3 A1: the key's look's transition, the pressed look's (80 ms at most), scale and sink; null where none moves
    private fun motion(normal: KeyLook, pressed: KeyLook, face: Box): Motion? {
        val transition = normal.transition?.takeIf { it.ms > 0 }
        val scale = pressed.pressScale
        val sink = pressed.pressSink * env.density
        val press = pressed.transition?.takeIf { it.ms > 0 && (scale != 1.0 || sink != 0.0) }
            ?.let { it.copy(ms = minOf(it.ms, Motion.MAX_PRESS_MS)) }
        val effect = effect(pressed.effect, face)
        val m = Motion(transition, press, scale, sink, effect)
        return m.takeUnless { it == STILL }
    }

    // A3: a pressed look's effect in px, a ripple's radius of the face's half diagonal
    private fun effect(e: Effect?, face: Box): KeyEffect? = when (e) {
        null -> null
        is Effect.Ripple -> KeyEffect.Ripple(e.file, e.radius / EFFECT_PERCENT * kotlin.math.hypot(face.width, face.height) / 2, e.ms)
        is Effect.Particles -> KeyEffect.Particles(e.file, e.count, e.spread * env.density, e.ms)
    }

    companion object {
        // §8a: a label shrunk alone that does not fit as its size scaled says, searched for between this much of
        // that and it, in so many halvings
        private const val FIT_LEAST = 0.5
        private const val EFFECT_PERCENT = 100.0
        // a key that does not move: no transition, no press, scaled 1, no sink, no effect
        private val STILL = Motion(null, null)
        // a double's rounding over a box's width
        private const val ROUNDING = 1e-6

        // a whole pixel's rounding, px: what within leaves out of the room as it is
        private const val SNAP = 0.5
        private const val FIT_STEPS = 16

        /** KeyMarkYield's: a glyph and a mark this far apart at least. */
        const val GAP_DP = 1.0

        // a text's steps down where it has no room: its first character and 「…」, then the short-text icon (MarkContent.Text.ladder)
        const val LADDER_STEPS = 2

        /** The slot beside a board one-handed or split at a side: the app's buttons there (rules D §2). */
        const val SIDE_BUTTONS = "side-buttons"
        private const val FULL_PAD = "full"
        private val UP = Point(0.0, -1.0)
    }
}
