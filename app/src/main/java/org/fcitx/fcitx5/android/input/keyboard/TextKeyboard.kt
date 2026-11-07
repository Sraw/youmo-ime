/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.text.SpannableString
import android.util.TypedValue
import android.view.View
import androidx.annotation.Keep
import androidx.annotation.VisibleForTesting
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.allViews
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.EngineState
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.engine.InputMethodCatalog
import org.fcitx.fcitx5.android.engine.glide.Glide
import org.fcitx.fcitx5.android.engine.table.KeyLabels
import org.fcitx.fcitx5.android.engine.table.TableKeyboard
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyAppearance
import org.fcitx.fcitx5.android.input.floating.FloatingKeyboard
import org.fcitx.fcitx5.android.input.keyboard.canvas.AndroidTextMeasurer
import org.fcitx.fcitx5.android.input.keyboard.canvas.KeyboardCanvasView
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneFonts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneIcons
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneEnvs
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneLayouts
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneThemes
import org.fcitx.fcitx5.android.input.keyboard.canvas.SceneTrace
import org.fcitx.fcitx5.android.input.scene.KeyRef
import org.fcitx.fcitx5.android.input.scene.SceneLetters
import org.fcitx.fcitx5.android.input.scene.SceneMarks
import org.fcitx.fcitx5.android.input.scene.TextBoardState
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.input.theme.ThemeLook
import org.fcitx.fcitx5.android.scene.KeyNode
import org.fcitx.fcitx5.android.scene.Parsed
import org.fcitx.fcitx5.android.scene.Scene
import org.fcitx.fcitx5.android.scene.engine.Env
import org.fcitx.fcitx5.android.scene.engine.MarkSettings
import org.fcitx.fcitx5.android.scene.engine.SceneBuilder
import org.fcitx.fcitx5.android.scene.engine.Shift
import org.fcitx.fcitx5.android.scene.engine.TextMeasurer
import org.fcitx.fcitx5.android.scene.engine.Texts
import org.fcitx.fcitx5.android.scene.engine.section
import org.fcitx.fcitx5.android.scene.layout.Layout
import org.fcitx.fcitx5.android.scene.theme.Theme as SceneTheme
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.popup.PopupOverrides
import org.fcitx.fcitx5.android.input.popup.PopupOverridesCache
import org.fcitx.fcitx5.android.input.popup.PopupPreset
import org.fcitx.fcitx5.android.input.theme.KeyNames
import splitties.views.imageResource
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.leftOfParent
import splitties.views.dsl.constraintlayout.rightOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import timber.log.Timber

@SuppressLint("ViewConstructor")
class TextKeyboard private constructor(
    context: Context,
    theme: Theme,
    split: Split?,
) : BaseKeyboard(context, theme, split?.keys ?: Layout, split?.widths, KeyNames.QWERTY, shortcuts = true), AutoCapsKeys {

    // the halves: 双拼's ing is L's long press there (LS-29), a table's code key has a key of its own
    private val isSplit = split != null

    constructor(context: Context, theme: Theme) : this(context, theme, Split.of(context))

    enum class CapsState { None, Once, Lock }

    // what the marks' settings are made from (KeyShortcuts compared as the same instance: the cache's)
    private data class MarksFor(
        val shortcuts: KeyShortcuts,
        val layers: SwipeLayers,
        val spots: KeyMarks.Spots,
        val charShortcuts: Boolean,
        val names: Set<String>,
    )

    companion object {
        const val Name = "Text"

        // kept a process (main thread), as SceneThemes keeps the themes, for a keyboard made again (each
        // theme switch builds the input view): a look's measurer and the last KEPT_SCENES scenes made, so
        // a theme switched back to makes no scene again and measures no text again; what a switch allocates
        // more than the views' decides whether a GC stops its first frame (specs/app.md)
        private const val KEPT_SCENES = 4
        private val measurers = LinkedHashMap<ThemeLook, AndroidTextMeasurer>(KEPT_SCENES, 0.75f, true)
        private val scenesKept = ArrayDeque<Made>()

        // a layout's shortcut names, and the marks' settings as last made from their inputs: as alts(), kept
        // for the scene asked again, the same instances making the env equal to the last at once
        private var namesMade: Pair<Layout, Set<String>>? = null
        private var marksMade: Pair<MarksFor, MarkSettings>? = null

        private fun names(layout: Layout): Set<String> =
            namesMade?.takeIf { it.first === layout }?.second ?: SceneMarks.names(layout).also { namesMade = layout to it }

        private fun marks(of: MarksFor): MarkSettings = marksMade?.takeIf { it.first == of }?.second
            ?: SceneMarks.settings(of.shortcuts, of.layers, of.spots, of.charShortcuts, of.names).also { marksMade = of to it }

        private fun measurer(look: ThemeLook): AndroidTextMeasurer = measurers.getOrPut(look) { AndroidTextMeasurer(SceneFonts(look)) }
            .also { if (measurers.size > KEPT_SCENES) measurers.remove(measurers.keys.first()) }

        /** Whether the letters are split here: as the setting is, the screen as wide as it is now; floating, never. */
        fun splits(context: Context): Boolean {
            if (ThemeManager.prefs.floatingKeyboard.getValue()) return false
            val config = context.resources.configuration
            val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
            return SplitKeyboard.splits(ThemeManager.prefs.splitKeyboard.getValue().mode, landscape, config.screenWidthDp)
        }

        /** The keyboard's width, dp: the screen's less its side padding (InputView's), or floating its own. */
        fun widthDp(context: Context): Int {
            val config = context.resources.configuration
            if (ThemeManager.prefs.floatingKeyboard.getValue()) return FloatingKeyboard.widthDp(config.screenWidthDp)
            val keyboard = AppPrefs.getInstance().keyboard
            val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
            val padding = (if (landscape) keyboard.keyboardSidePaddingLandscape else keyboard.keyboardSidePadding).getValue()
            return config.screenWidthDp - 2 * padding
        }

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                AlphabetKey("Q", "1"),
                AlphabetKey("W", "2"),
                AlphabetKey("E", "3"),
                AlphabetKey("R", "4"),
                AlphabetKey("T", "5"),
                AlphabetKey("Y", "6"),
                AlphabetKey("U", "7"),
                AlphabetKey("I", "8"),
                AlphabetKey("O", "9"),
                AlphabetKey("P", "0")
            ),
            listOf(
                AlphabetKey("A", "@"),
                AlphabetKey("S", "*"),
                AlphabetKey("D", "+"),
                AlphabetKey("F", "-"),
                AlphabetKey("G", "="),
                AlphabetKey("H", "/"),
                AlphabetKey("J", "#"),
                AlphabetKey("K", "("),
                AlphabetKey("L", ")"),
                // where a physical keyboard has it: 微软 双拼 types ing on it, hidden else (semicolonKey);
                // a table's code key the face has nowhere else, as the engine says (TableKeyboard.extraKey)
                SymbolKey(";", 0.1f, viewId = R.id.button_semicolon),
                SymbolKey("*", 0.1f, viewId = R.id.button_star),
            ),
            listOf(
                // a table's 拼 key in Shift's place, one of the two shown as its lookup is (showLookUpKey)
                CapsKey(),
                LookUpKey(on = false),
                LookUpKey(on = true),
                AlphabetKey("Z", "'"),
                AlphabetKey("X", ":"),
                AlphabetKey("C", "\""),
                AlphabetKey("V", "?"),
                AlphabetKey("B", "!"),
                AlphabetKey("N", "~"),
                AlphabetKey("M", "\\"),
                BackspaceKey()
            ),
            listOf(
                // Sogou's row: symbols and numbers each a key of their own, the comma and the
                // full stop either side of the space, the language switch after them; held, 符
                // and 123 open quick phrase and Unicode input, which have no key in this row
                LayoutSwitchKey("符", PickerWindow.Key.Symbol.name, 0.12f, longPress = KeyAction.QuickPhraseAction),
                LayoutSwitchKey("123", NumberKeyboard.Name, 0.12f, longPress = KeyAction.UnicodeAction),
                SymbolKey(",", 0.1f, KeyDef.Appearance.Variant.Alternative, viewId = R.id.button_comma, shortcut = "comma"),
                // an address field's in place of the comma and the full stop (AddressKeys), hidden else
                AddressKey("/", R.id.button_slash),
                AddressKey("@", R.id.button_at),
                SpaceKey(),
                SymbolKey(".", 0.1f, KeyDef.Appearance.Variant.Alternative, viewId = R.id.button_period, shortcut = "period"),
                // as it is: through the engine . would be 。
                AddressKey(".", R.id.button_dot),
                AddressKey(AddressKeys.DOMAINS.first(), R.id.button_domain, 0.14f, AddressKeys.DOMAINS),
                LanguageKey(),
                ReturnKey(),
                // a table's while a code is typed, in Return's place (showConfirmKey)
                ConfirmKey(),
            )
        )

        // the keys either side of Shift's place in [Layout]'s third row that stand in for it
        private const val LOOK_UP_KEYS = 2

        // what a table's code key may have of its own beside the letters, the first wanted first
        private val EXTRA_SLOTS = listOf(';', '*')

        // a table's roots in two lines on a key, of a letter's size
        private const val ROOTS_TEXT_SCALE = 0.55f
        private const val ROOTS_INSET_DP = 6
    }

    /**
     * [Layout] in two halves of five keys (SplitKeyboard), Gboard's: G and V on both, which also
     * puts the keys of the middle under either thumb; Shift and backspace as wide as a letter. The
     * space spans the room between the halves, the bottom row's other keys as wide in letters as
     * they were.
     */
    private class Split(sizes: SplitKeyboard.Sizes) {
        val keys: List<List<KeyDef>>
        val widths: List<List<Float>>

        init {
            val rows = listOf(
                sizes.row(Layout[0].take(SplitKeyboard.HALF_KEYS), Layout[0].takeLast(SplitKeyboard.HALF_KEYS), GapKey),
                // a table's extra key after L, as unsplit (not the 双拼 `;`, showSemicolonKey): the right
                // half six keys while one shows, the room between the halves giving way (it fills what is left)
                Layout[1].let { row ->
                    val letters = row.dropLast(EXTRA_SLOTS.size)
                    val halves = sizes.row(letters.take(SplitKeyboard.HALF_KEYS), letters.takeLast(SplitKeyboard.HALF_KEYS), GapKey)
                    halves.map { (key, width) -> key to if (key === GapKey) 0f else width } +
                        row.takeLast(EXTRA_SLOTS.size).map { it to sizes.width(it.appearance.percentWidth) }
                },
                // Shift Z X C V, V B N M backspace; a table's 拼 keys as wide as Shift, one of them shown in its place
                Layout[2].let { row ->
                    val shift = row.take(1)
                    val lookUp = row.subList(1, 1 + LOOK_UP_KEYS)
                    val halves = sizes.row(shift + row.subList(1 + LOOK_UP_KEYS, LOOK_UP_KEYS + SplitKeyboard.HALF_KEYS), row.takeLast(SplitKeyboard.HALF_KEYS), GapKey)
                    halves.take(1) + lookUp.map { it to halves[0].second } + halves.drop(1)
                },
                Layout[3].map { it to sizes.width(it.appearance.percentWidth) },
            )
            keys = rows.map { row -> row.map { it.first } }
            widths = rows.map { row -> row.map { it.second } }
        }

        companion object {
            /**
             * The letters in two halves here, or null: as the setting is, the screen as wide as it is
             * now; sized by the keyboard's width, the screen's less its side padding (InputView's).
             */
            fun of(context: Context): Split? {
                if (!splits(context)) return null
                return Split(SplitKeyboard.Sizes(widthDp(context)))
            }
        }
    }

    val caps: ImageKeyView by lazy { findViewById(R.id.button_caps) }
    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val lang: ImageKeyView by lazy { findViewById(R.id.button_lang) }
    val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }
    private val confirm: TextKeyView by lazy { findViewById(R.id.button_confirm) }

    /** What the bottom row has either side of the space, as the field is: see [AddressKeys]. */
    override var address: AddressKeys = AddressKeys.NONE
        set(value) {
            field = value
            showAddressKeys()
            canvas?.invalidateScene()
        }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        updateLangSwitchKey(v)
    }

    private val keepLettersUppercase by AppPrefs.getInstance().keyboard.keepLettersUppercase

    // what the user made of the long press: a letter's first is its swipe and its corner too
    private val popupOverridesPref = AppPrefs.getInstance().internal.popupOverrides

    @Keep
    private val popupOverridesListener = ManagedPreference.OnChangeListener<String> { _, _ ->
        updatePunctuationKeys()
        // a swipe changed may type a table's code key no more, or now
        showSemicolonKey()
    }

    // the input method last reported, for the `;` key again when the 双拼 scheme changed meanwhile
    private var imeName = ""

    // what the engine last said of a table input method's keyboard (EngineState.tableKeyboard), null
    // for another, or none said yet of the one shown: its letters' roots, its 拼 key, its code keys
    private var table: TableKeyboard? = null

    // a table's every root on the letters leaves no corner free of them: the shortcuts unmarked, swiped all the same
    override val charShortcutMarks: Boolean
        get() = table?.let { it.labels == KeyLabels.ROOTS && it.roots != null } != true

    // the table's code key the letters had no room for, as last shown (showSemicolonKey): the drawn keys' too
    private var extraKey: Char? = null

    // the keys' views by their theme names (KeyDef.name), in the layout's order, a split row's G the
    // second's: before init, which draws the keys with the canvas
    private val keyViews: Map<String, List<KeyView>> by lazy {
        keyRows.flatMap { it.allViews.filterIsInstance<KeyView>().toList() }.filter { it.themeKey != null }.groupBy { it.themeKey!! }
    }

    // Return as it was last shown: while something is typed, a table shows 确定 in its place
    private var returnAppearance = ReturnKeyAppearance.Enter

    // the letter key whose long press shows its roots (TableKeyboard.rootsOf) in its bubble
    private var rootsShownOn: KeyRef? = null

    private var scope: CoroutineScope? = null

    init {
        // hidden until an input method says it types on it: shown, every one's A row had an 11th key
        // at a cold start, before fcitx reported the first
        showSemicolonKey()
        showAddressKeys()
        updateLangSwitchKey(showLangSwitchKey.getValue())
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
        popupOverridesPref.registerOnChangeListener(popupOverridesListener)
        confirm.mainText.text = context.getString(R.string.key_confirm)
        showLookUpKey()
        showConfirmKey()
        // 060's redraw while it is made: the keys drawn by the scene engine, the views under it doing the work
        if (AppPrefs.getInstance().internal.canvasKeyboard.getValue()) drawKeysWith(canvasView())
    }

    // the keys drawn by [view] from now on: their rows left laid out but not drawn, nor read out (the canvas's nodes are),
    // nor touched: the scene's keys are, each the view's by its theme name and which of its name it is (a split row's G)
    private fun drawKeysWith(view: KeyboardCanvasView) {
        drawKeysOn(view) { v -> v.themeKey?.let { name -> keyViews[name]?.indexOf(v)?.takeIf { it >= 0 }?.let { KeyRef.scene(name, it) } } }
        keyRows.forEach { it.visibility = View.INVISIBLE }
        add(view, lParams(0, 0) {
            topOfParent()
            bottomOfParent()
            leftOfParent()
            rightOfParent()
        })
    }

    private fun canvasView(): KeyboardCanvasView {
        val look = ThemeManager.shownLook(theme)
        val fonts = SceneFonts(look)
        val measurer = measurer(look)
        return KeyboardCanvasView(context, look, keyImages, fonts, object : KeyboardCanvasView.Host {
            override fun scene(width: Int, height: Int) = scene(look, measurer, width, height)
            override fun key(node: KeyNode) = sceneKey(node)
        })
    }

    // what the keys show now, as the scene engine's environment (qwerty.xml's when= and bindings)
    // 060-render-p2 U8: a scene the theme makes none of (the engine refusing it, or what the theme made)
    // is the built-in theme's of the same lightness, logged; none of that either, nothing (the canvas
    // draws nothing and logs it). Never the views again: they go in S8
    private fun scene(look: ThemeLook, measurer: TextMeasurer, width: Int, height: Int): Scene? {
        val layout = SceneLayouts.qwerty(context) ?: return null
        SceneTrace.section("theme") { SceneThemes.of(look, theme.name, theme.isDark) }
            ?.let { scene(it, look, layout, measurer, width, height) }?.let { return it }
        val builtIn = if (theme.isDark) ThemePreset.PixelDark else ThemePreset.PixelLight
        if (builtIn.name == theme.name) return null
        Timber.w("keyboard canvas: no scene of theme %s; the built-in %s's drawn", theme.name, builtIn.name)
        val builtInLook = ThemeManager.shownLook(builtIn)
        return SceneThemes.of(builtInLook, builtIn.name, builtIn.isDark)?.let { scene(it, builtInLook, layout, measurer, width, height) }
    }

    // the scene of [sceneTheme] ([look]'s): the last kept for the same values, or made; null, the engine refused it
    private fun scene(sceneTheme: SceneTheme, look: ThemeLook, layout: Layout, measurer: TextMeasurer, width: Int, height: Int): Scene? {
        val env = SceneTrace.section("env") { env(look, layout, width, height) }
        // the same values make the same scene: one asked again for nothing that shows (the first key typed,
        // the word committed: Return's look unchanged) is the last one, not made again in the frame
        scenesKept.lastOrNull { it.theme === sceneTheme && it.layout === layout && it.measurer === measurer && it.env == env }?.let { return it.scene }
        scenesMade++
        // the theme's looks worked out for the last scene kept for the next: Shift's makes only what it changed
        return when (val built = SceneBuilder(sceneTheme, env, measurer, phases = SceneTrace, cascades = SceneThemes.cascades(sceneTheme)).build(layout, emptyMap())) {
            is Parsed.Ok -> built.value.also {
                scenesKept.addLast(Made(sceneTheme, layout, measurer, env, it))
                if (scenesKept.size > KEPT_SCENES) scenesKept.removeFirst()
            }
            is Parsed.Failed -> null.also { Timber.e("keyboard canvas: the engine refused the scene: %s", built.problems) }
        }
    }

    // what the keys show now, as the scene engine's environment: the screen's and the settings' as every
    // keyboard's (SceneEnvs), this one's state, Shift, texts and marks its own
    private fun env(look: ThemeLook, layout: Layout, width: Int, height: Int): Env {
        val state = TextBoardState(
            semicolonScheme = semicolonKey(imeName, EngineState.shuangpinScheme),
            table = imeName in InputMethodCatalog.TABLES,
            tableExtra = extraKey,
            lookingUp = table?.lookingUp == true,
            composing = returnAppearance.composing,
            address = address,
            langKey = showLangSwitchKey.getValue(),
            holdVoice = holdsToTalk,
            roots = table?.labelled == true,
        )
        val texts = Texts(
            imeName = shownImeName.orEmpty(),
            comma = transformPunctuation(","),
            period = transformPunctuation("."),
            returnLabel = returnAppearance.label(context).toString(),
            returnIcon = SceneIcons.returnName(returnAppearance.icon),
            confirm = context.getString(R.string.key_confirm),
            keepUppercase = keepLettersUppercase,
            punctuation = punctuationMapping,
            // the corners and a table's roots as the views show them (updatePunctuationKeys, updateAlphabetKeys)
            alts = alts(),
            other = SceneLetters.roots(table) { c -> letterShown(c.toString()) },
            // the formats as they are: the engine fills their places (getString with no arguments formats nothing)
            a11yGrouped = context.getString(R.string.a11y_key_grouped),
            a11ySpace = context.getString(R.string.a11y_key_space_with_ime),
        )
        val marks = marks(
            MarksFor(KeyShortcutsCache.current, AppPrefs.getInstance().keyboard.swipeLayers.getValue().layers, markSpots(), charShortcutMarks, names(layout)),
        )
        return SceneEnvs.of(
            this, look, theme.isDark, width, height,
            input = state.input(),
            split = isSplit,
            shift = when (capsState) {
                CapsState.None -> Shift.None
                CapsState.Once -> Shift.Once
                CapsState.Lock -> Shift.Lock
            },
            texts = texts,
            marks = marks,
        )
    }

    private class Made(val theme: SceneTheme, val layout: Layout, val measurer: TextMeasurer, val env: Env, val scene: Scene)

    // the letters' corners as last worked out, and what they were worked out from (cornerOf's inputs):
    // the canvas asks for a scene on every change the views show (a key typed), and these change only as
    // a setting or Shift does; the same map kept, the env compared with the last is equal at once
    private data class AltsFor(
        val shown: Boolean,
        val labelled: Boolean,
        val punctuation: Map<String, String>,
        val overrides: PopupOverrides,
        val caps: CapsState,
    )

    private var altsMade: Pair<AltsFor, Map<String, String>>? = null

    // none with 标点位置 无: the views hid their corners (AltTextKeyView.applyNoAltTextPosition), and a
    // corner the scene does not draw is not said (TalkBack) or given (its node's extras, the harness)
    private fun alts(): Map<String, String> {
        val shown = markSpots().symbol != KeyMarks.At.None
        val now = AltsFor(shown, table?.labelled == true, punctuationMapping, PopupOverridesCache.user.current, capsState)
        altsMade?.takeIf { it.first == now }?.let { return it.second }
        val made = if (shown) SceneLetters.alts { c -> letterDefs[c]?.let(::cornerOf) } else emptyMap()
        return made.also { altsMade = now to it }
    }

    /** The scenes the engine made for this keyboard: a test's count of what was not kept. */
    @VisibleForTesting
    var scenesMade = 0
        private set

    /** The scenes kept let go, the next asked made anew as things are: a measure of what making one costs (KeyboardPerfBaselineTest). */
    @VisibleForTesting
    fun forgetScene() {
        scenesKept.clear()
        canvas?.invalidateScene()
    }

    // the letters' keys' definitions by their letters: their corners' (cornerOf)
    private val letterDefs: Map<Char, KeyDef.Appearance.AltText> by lazy {
        letterKeys.mapNotNull { (c, v) -> (v.def as? KeyDef.Appearance.AltText)?.let { c to it } }.toMap()
    }

    // a letter as its key shows it with no table's roots over it: Shift's case, or kept upper case
    private fun letterShown(letter: String) = if (keepLettersUppercase) letter.uppercase() else transformAlphabet(letter)

    // a letter key's corner: its swipe (the long press's first, built in or the user's, as Shift has it) as
    // the punctuation maps it; the letter itself while a table's roots are over it. The corners are kept
    // by AltsFor: whatever new this reads goes into AltsFor too, or a change to it leaves the corners stale
    private fun cornerOf(def: KeyDef.Appearance.AltText): String {
        val letter = def.displayText.singleOrNull()?.takeIf { it.isLetter() }
        return if (letter != null && table?.labelled == true) letter.lowercase() else transformPunctuation(swipeOverride(def) ?: def.altText)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(Dispatchers.Main.immediate + Job()).also { s ->
            s.launch { EngineState.tableKeyboard.collect(::showTable) }
        }
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        super.onDetachedFromWindow()
    }

    // [state] of the table shown, or of another input method's (none of this one's then)
    private fun showTable(state: TableKeyboard?) {
        // 全部字根 shown as 键名 at 紧凑, read as the keys are made (made anew into and out of it)
        val shown = state?.takeIf { it.im == imeName }?.atHeight(keyboardCompact())
        if (shown == table) return
        // another table's roots as well: 五笔's 键名 to 仓颉's, both NAMES, told before the switch shows
        val relabels = shown?.labels != table?.labels || shown?.roots !== table?.roots
        table = shown
        showLookUpKey()
        if (relabels) {
            keysLaidOut = false
            updateAlphabetKeys()
            updatePunctuationKeys()
            showSwipeMarks()
        }
        showSemicolonKey()
    }

    // a table's 拼 key, dark while it looks up, in Shift's place; Shift elsewhere
    private fun showLookUpKey() {
        val isTable = imeName in InputMethodCatalog.TABLES
        val on = table?.lookingUp == true
        showKey(caps, !isTable)
        findViewById<View>(R.id.button_lookup)?.let { showKey(it, isTable && !on) }
        findViewById<View>(R.id.button_lookup_on)?.let { showKey(it, isTable && on) }
        canvas?.invalidateScene()
    }

    // 确定 in Return's place while a table has a code typed: what Return does then, said
    private fun showConfirmKey() {
        val confirms = returnAppearance.composing && imeName in InputMethodCatalog.TABLES
        showKey(`return`, !confirms)
        showKey(confirm, confirms)
        canvas?.invalidateScene()
    }

    private val textKeys: List<TextKeyView> by lazy {
        allViews.filterIsInstance(TextKeyView::class.java).toList()
    }

    private var capsState: CapsState = CapsState.None

    // the input method reads a glide (InputMethodCatalog.glides)
    private var glides = false

    private fun showSemicolonKey() {
        // the table's code key the face has nowhere else: not on a corner its swipe types, unless the corners show roots
        val extra = table?.extraKey(EXTRA_SLOTS, BOTTOM_ROW_KEYS + if (table?.labelled == true) emptySet() else cornerKeys())
        extraKey = extra
        canvas?.invalidateScene()
        findViewById<View>(R.id.button_semicolon)?.let { showKey(it, (!isSplit && semicolonKey(imeName, EngineState.shuangpinScheme)) || extra == ';') }
        findViewById<View>(R.id.button_star)?.let { showKey(it, extra == '*') }
    }

    // what the letters' swipes type, drawn in their corners
    private fun cornerKeys(): Set<Char> = textKeys.mapNotNullTo(HashSet()) { v ->
        (v.def as? KeyDef.Appearance.AltText)?.let { (swipeOverride(it) ?: it.altText).singleOrNull() }
    }

    private val letterKeys: List<Pair<Char, TextKeyView>> by lazy {
        textKeys.mapNotNull { v ->
            (v.def as? KeyDef.Appearance.Text)?.displayText?.singleOrNull()?.lowercaseChar()?.takeIf { it in 'a'..'z' }?.let { it to v }
        }
    }

    // glide typing (滑行输入) where the input method reads one; a stroke (笔画筛选) in pinyin's and 双拼's
    // the letters where the scene places them while the canvas draws the keys (drawKeysWith): the scene's keys
    private val sceneLetters: List<Pair<Char, KeyPlace>> by lazy { letterKeys.map { (c, v) -> c to (sceneKeyOf(v) ?: v) } }

    override val glide = glideInput(letters = { if (canvas != null) sceneLetters else letterKeys }, enabled = { glides }, filters = { InputMethodCatalog.filtersByStrokes(imeName) }) { path, layout ->
        onAction(KeyAction.GlideAction(Glide(path, layout, shift = capsState == CapsState.Once, capsLock = capsState == CapsState.Lock)))
        // as a letter typed: Shift-once used up in any input method, as a tap uses it up (only English reads it)
        if (capsState == CapsState.Once) switchCapsState()
    }

    private val autoCaps = AutoCaps()

    override val capsLocked: Boolean get() = capsState == CapsState.Lock

    override fun applyAutoCaps(wants: Boolean) {
        val current = when (capsState) {
            CapsState.None -> AutoCaps.Caps.NONE
            CapsState.Once -> AutoCaps.Caps.ONCE
            CapsState.Lock -> AutoCaps.Caps.LOCK
        }
        val next = when (autoCaps.next(current, wants)) {
            AutoCaps.Caps.NONE -> CapsState.None
            AutoCaps.Caps.ONCE -> CapsState.Once
            AutoCaps.Caps.LOCK -> CapsState.Lock
        }
        if (next == capsState) return
        capsState = next
        updateCapsButtonIcon()
        updateAlphabetKeys()
        updatePunctuationKeys()
    }

    private fun transformAlphabet(c: String): String {
        return when (capsState) {
            CapsState.None -> c.lowercase()
            else -> c.uppercase()
        }
    }

    private var punctuationMapping: Map<String, String> = mapOf()
    private fun transformPunctuation(p: String) = punctuationMapping.getOrDefault(p, p)

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        var transformed = action
        when (action) {
            is KeyAction.HostKeyAction -> when (source) {
                KeyActionListener.Source.Keyboard -> {
                    when (capsState) {
                        CapsState.None -> {
                            transformed = action.copy(act = action.act.lowercase())
                        }
                        CapsState.Once -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.Shift)
                            )
                            switchCapsState()
                        }
                        CapsState.Lock -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.CapsLock)
                            )
                        }
                    }
                }
                KeyActionListener.Source.Popup -> {
                    if (capsState == CapsState.Once) {
                        switchCapsState()
                    }
                }
            }
            // a word from the long press (or a letter's swipe, its first) is typed too: Shift-once is used up
            is KeyAction.CommitAction -> if (capsState == CapsState.Once) switchCapsState()
            is KeyAction.CapsAction -> switchCapsState(action.lock)
            else -> {}
        }
        super.onAction(transformed, source)
    }

    override fun onAttach() {
        canvas?.invalidateScene()
        capsState = CapsState.None
        autoCaps.changed()
        updateCapsButtonIcon()
        updateAlphabetKeys()
        updatePunctuationKeys()
        // the scheme may have changed in the settings with 双拼 kept, which reports no input method
        showSemicolonKey()
    }

    override fun onReturnDrawableUpdate(appearance: ReturnKeyAppearance) {
        `return`.showReturnAction(appearance)
        returnAppearance = appearance
        showConfirmKey()
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        updatePunctuationKeys()
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        showInputMethodName(space, ime)
        imeName = ime.uniqueName
        canvas?.invalidateScene()
        // what the engine said last, if of this one: a table's state comes with its first answer
        showTable(EngineState.tableKeyboard.value)
        showLookUpKey()
        showConfirmKey()
        showSemicolonKey()
        glides = InputMethodCatalog.glides(ime.uniqueName)
        if (capsState != CapsState.None) {
            switchCapsState()
        }
    }

    private fun transformPopupPreview(key: KeyRef, c: String) = previewOf(c, key, ::transformAlphabet, ::transformPunctuation)

    override fun onPopupAction(action: PopupAction) {
        val newAction = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = transformPopupPreview(action.key, action.content))
            // the roots stay shown till the key is let go
            is PopupAction.PreviewUpdateAction -> if (action.key == rootsShownOn) return else action.copy(content = transformPopupPreview(action.key, action.content))
            is PopupAction.DismissAction -> action.also { if (it.key == rootsShownOn) rootsShownOn = null }
            // let go: the bubble goes whether or not a press shows one (no long press's keys to take it down)
            is PopupAction.TriggerAction -> if (action.key == rootsShownOn) {
                rootsShownOn = null
                action.outConsumed = true
                super.onPopupAction(PopupAction.DismissAction(action.key))
                return
            } else action
            is PopupAction.ShowKeyboardAction -> {
                // a table's roots, large, in the key's bubble while the keys show them; nothing to pick
                rootsOf(action.keyboard)?.let { roots ->
                    rootsShownOn = action.key
                    super.onPopupAction(PopupAction.PreviewAction(action.key, roots, action.bounds))
                    return
                }
                when (action.keyboard) {
                    is KeyDef.Popup.Keyboard.Preset -> {
                        val label = action.keyboard.label
                        if (label.length == 1 && label[0].isLetter())
                            action.copy(
                                keyboard = action.keyboard.copy(label = transformAlphabet(label))
                            )
                        else action
                    }
                    is KeyDef.Popup.Keyboard.Explicit -> action
                }
            }
            else -> action
        }
        super.onPopupAction(newAction)
    }

    private fun switchCapsState(lock: Boolean = false) {
        autoCaps.changed()
        capsState =
            if (lock) {
                when (capsState) {
                    CapsState.Lock -> CapsState.None
                    else -> CapsState.Lock
                }
            } else {
                when (capsState) {
                    CapsState.None -> CapsState.Once
                    else -> CapsState.None
                }
            }
        updateCapsButtonIcon()
        updateAlphabetKeys()
        // a letter's corner is its swipe, which follows Shift (swipeOverride)
        updatePunctuationKeys()
    }

    private fun updateCapsButtonIcon() {
        caps.img.apply {
            imageResource = when (capsState) {
                CapsState.None -> R.drawable.ic_capslock_none
                CapsState.Once -> R.drawable.ic_capslock_once
                CapsState.Lock -> R.drawable.ic_capslock_lock
            }
        }
        // The icon carries the state, so accessibility has to as well. As a state description
        // TalkBack speaks it when it changes on the focused key -- the user pressing Shift --
        // but not when Shift lets go by itself after a letter, when another key has focus.
        ViewCompat.setStateDescription(
            caps,
            when (capsState) {
                CapsState.None -> null
                CapsState.Once -> context.getString(R.string.a11y_state_shift_once)
                CapsState.Lock -> context.getString(R.string.a11y_state_caps_lock)
            }
        )
        canvas?.invalidateScene()
    }

    private fun updateLangSwitchKey(visible: Boolean) {
        lang.visibility = if (visible) View.VISIBLE else View.GONE
        canvas?.invalidateScene()
    }

    // the letters' sizes and places set for the table shown: a Shift changes their text alone, and
    // setting them again lays out every key
    private var keysLaidOut = false

    private fun updateAlphabetKeys() {
        val layOut = !keysLaidOut
        keysLaidOut = true
        textKeys.forEach {
            // on to the next key, not out: the 双拼 `;` after L is no letter, and Z to M come after it
            if (it.def !is KeyDef.Appearance.AltText) return@forEach
            it.mainText.text = it.def.displayText.let { str ->
                if (str.length != 1 || !str[0].isLetter()) return@forEach
                // a table's roots over the letter, which its corner shows (updatePunctuationKeys); in
                // lines, shrunk to fit the key (AutoScaleTextView lays out a styled text's lines)
                val label = table?.label(str[0].lowercaseChar())
                val lines = label != null && '\n' in label
                val roots = label != null && label.length > 1
                if (layOut) {
                    it.mainText.scaleMode = if (roots) AutoScaleTextView.Mode.Proportional else AutoScaleTextView.Mode.None
                    // held to the key, so they shrink to it: wrapped, it was as wide as they are, over the next keys
                    (it.mainText.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
                        lp.constrainedWidth = roots
                        lp.constrainedHeight = roots
                        it.mainText.layoutParams = lp
                    }
                    // the key's face is narrower than its place by its margins
                    val inset = if (roots) (ROOTS_INSET_DP * resources.displayMetrics.density).toInt() else 0
                    it.mainText.setPadding(inset, 0, inset, 0)
                    // two lines in the height of one
                    it.mainText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, it.def.textSize * keyTextScale(context) * if (lines) ROOTS_TEXT_SCALE else 1f)
                }
                if (lines) SpannableString(label) else label ?: letterShown(str)
            }
        }
    }

    // [keyboard]'s, a letter's long press, as a table's roots in a line, while the keys show them
    private fun rootsOf(keyboard: KeyDef.Popup.Keyboard): String? {
        val letter = (keyboard as? KeyDef.Popup.Keyboard.Preset)?.label?.singleOrNull()?.lowercaseChar() ?: return null
        return table?.rootsOf(letter)?.joinToString(" ")
    }

    private fun updatePunctuationKeys() {
        // a corner emptied or filled (a long press turned off, Shift's list): the marks placed for it move
        var cornersChanged = false
        textKeys.forEach {
            if (it is AltTextKeyView) {
                val had = it.altText.text.isNotEmpty()
                // a table's roots over the letter: the letter in the corner, its swipe as it was
                it.altText.text = cornerOf(it.def as KeyDef.Appearance.AltText)
                if (had != it.altText.text.isNotEmpty()) cornersChanged = true
            } else {
                it.def as KeyDef.Appearance.Text
                // an address's keys commit as they are, and say so: . not 。
                if (it.id in ADDRESS_KEYS) return@forEach
                it.mainText.text = it.def.displayText.let { str ->
                    // 确定's text is the keyboard's own (ConfirmKey)
                    if (str.isEmpty() || str[0].run { isLetter() || isWhitespace() }) return@forEach
                    transformPunctuation(str)
                }
            }
        }
        if (cornersChanged && isAttachedToWindow) showSwipeMarks()
        canvas?.invalidateScene()
    }


    // a letter's swipe is the first of its long press, as built in (q: 1, then Q) and as the user
    // changed it: one thing, drawn in its corner too (updatePunctuationKeys); none when the user
    // turned its long press off
    override fun swipeText(def: KeyDef): SwipeChoice =
        SwipeChoice.of((def.appearance as? KeyDef.Appearance.AltText)?.let(::swipeOverride))

    private fun swipeOverride(def: KeyDef.Appearance.AltText): String? {
        val letter = def.displayText.takeIf { it.length == 1 && it[0].isLetter() }?.lowercase() ?: return null
        // under Shift the long press shows the upper-case label's list (onPopupAction): so does the swipe
        return PopupOverridesCache.user.current.swipeOf(letter, transformAlphabet(letter))
    }

}

/** What [label]'s long press offers: the user's list, else the built-in one; null where it is off. */
internal fun PopupOverrides.longPressOf(label: String): Array<String>? = resolve(label, PopupPreset[label])

// here, not in the companion: previewOf's JVM test would otherwise load TextKeyboard, a View;
// the 双拼 `;` is a key of the scheme, shown as it is too (; not ；)
private val ADDRESS_KEYS = setOf(R.id.button_slash, R.id.button_at, R.id.button_dot, R.id.button_domain, R.id.button_semicolon, R.id.button_star)

// the same keys as the scene names them (qwerty.xml's ids, the keys' theme names)
private val ADDRESS_NAMES = setOf("slash", "at", "dot", "domain", "semicolon", "star")

// the bottom row's own keys a table's code may have: the comma and the full stop
private val BOTTOM_ROW_KEYS = setOf(',', '.')


/**
 * What the preview bubble of the key [key] shows for [content]: a letter as Shift has it
 * ([letter]), punctuation as the input method maps it ([punctuation]); an address's keys what they
 * commit, . not 。, as their labels show (TextKeyboard.updatePunctuationKeys).
 */
internal fun previewOf(content: String, key: KeyRef, letter: (String) -> String, punctuation: (String) -> String): String = when {
    content.length != 1 || key.viewId in ADDRESS_KEYS || key.id in ADDRESS_NAMES -> content
    content[0].isLetter() -> letter(content)
    else -> punctuation(content)
}

/**
 * The first of [label]'s long press ([longPressOf]) as the user changed it, [label] being [letter] as
 * Shift shows it: null where the user changed neither (the built-in swipe), "" where that long press is off.
 */
internal fun PopupOverrides.swipeOf(letter: String, label: String): String? {
    if (this[letter] == null && this[label] == null) return null
    return longPressOf(label)?.firstOrNull().orEmpty()
}