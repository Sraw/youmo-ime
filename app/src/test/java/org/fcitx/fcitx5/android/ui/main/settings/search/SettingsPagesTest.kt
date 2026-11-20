/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.search

import android.os.Looper
import android.view.View
import android.widget.CompoundButton
import android.widget.TextView
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import java.io.File
import java.time.Duration
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.Uat
import org.fcitx.fcitx5.android.core.FakeHostApi
import org.fcitx.fcitx5.android.core.phoneStorage
import org.fcitx.fcitx5.android.data.pinyin.RareCharsManager
import org.fcitx.fcitx5.android.engine.data.TestCharLevels
import org.fcitx.fcitx5.android.input.KeyboardHarness
import org.fcitx.fcitx5.android.uat.RarePageUat
import org.fcitx.fcitx5.android.uat.SettingsHarness
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.search.SettingsPages.Source
import org.fcitx.fcitx5.android.ui.search.SettingsSearch
import org.fcitx.fcitx5.android.ui.search.SettingsSearch.Kind
import org.fcitx.fcitx5.android.until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The search kept in step with the pages: every page registered, every preference and tappable control found, every setting the docs name found. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = FcitxApplication::class, qualifiers = "zh-rCN-w393dp-h851dp-port-440dpi")
class SettingsPagesTest {

    private val api = FakeHostApi()

    @Before
    fun phone() {
        phoneStorage()
        // a few characters per group, its cards shown
        RareCharsManager.useLevels(TestCharLevels.levels)
        api.rareGridAnswer = RarePageUat.grid
    }

    private var st: SettingsHarness? = null

    @After
    fun close() {
        RareCharsManager.useLevels(null)
        st?.close()
    }

    private fun harness() = st ?: SettingsHarness(api).also { st = it }

    // a page added says how it is searched, or why not
    @Test
    fun everyRouteIsRegistered() {
        val routes = SettingsRoute::class.java.declaredClasses.filter { SettingsRoute::class.java.isAssignableFrom(it) && it != SettingsRoute::class.java }
        val registered = SettingsPages.PAGES.map { it.first }
        assertEquals("registered twice", registered.size, registered.toSet().size)
        assertEquals(routes.map { it.simpleName }.sorted(), registered.map { it.simpleName }.sorted())
    }

    // each list-built preference found by its key, each choice worth one by its value
    @Test
    fun everyTitledPreferenceHasAnEntry() {
        val ctx = harness().activity
        val ids = SettingsIndex.build(ctx).map { it.id }.toSet()
        val missing = mutableListOf<String>()
        for ((_, source) in SettingsPages.PAGES) {
            if (source !is Source.Managed) continue
            val screen = PreferenceManager(ctx).apply { preferenceDataStore = Discarding }.createPreferenceScreen(ctx)
            source.build(screen)
            for (p in titled(screen)) {
                if (p.key == null || SearchHighlight.PREF + p.key !in ids) missing += "${p.title} (${p.key})"
                missing += choices(p).filter { (label, value) -> SettingsSearch.isWorthAValue(label) && "value:${p.key}=$value" !in ids }
                    .map { (label, value) -> "$label (${p.key}=$value)" }
            }
        }
        assertTrue(missing.joinToString("\n"), missing.isEmpty())
    }

    private fun choices(p: Preference): List<Pair<String, String>> =
        (p as? ListPreference)?.run { entries.zip(entryValues).map { (l, v) -> l.toString() to v.toString() } }.orEmpty()

    private fun titled(group: PreferenceGroup): List<Preference> = (0 until group.preferenceCount).map(group::getPreference).flatMap {
        if (it is PreferenceGroup) titled(it) else listOfNotNull(it.takeIf { p -> p.title != null })
    }

    private fun idle() {
        repeat(SETTLE_STEPS) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SETTLE_MS)) }
    }

    private fun views(st: SettingsHarness): List<View> = KeyboardHarness.all(st.page.requireView()).filter { it.isShown }.toList()

    // a tappable control's words (a key cap or card has none); disabled ones too, as no user data often disables them
    private fun tappable(st: SettingsHarness): List<String> = views(st)
        .filter { it is TextView && (it.hasOnClickListeners() || it is CompoundButton) }
        .map { (it as TextView).text.toString().trim() }.filter { it.isNotEmpty() }

    private fun anchors(st: SettingsHarness): List<String> = views(st).mapNotNull { (it.tag as? String)?.takeIf { t -> t.startsWith(SearchHighlight.VIEW) } }

    private fun menu(st: SettingsHarness): List<String> {
        st.activity.invalidateMenu()
        st.idle()
        val menu = shadowOf(st.activity).optionsMenu ?: return emptyList()
        return (0 until menu.size()).map(menu::getItem).filter { it.isVisible }.map { it.title.toString() }
    }

    // rows a tap opens or changes, shown or not
    private fun rows(st: SettingsHarness): List<Preference> = st.preferences().filter { it.title != null && it.isSelectable && it !is PreferenceGroup }

    /**
     * Each hand-drawn page, opened with no user data, says everything tappable it shows (or why not), and all it says is
     * there. 按键字符和快捷键 also as 多选 shows it.
     */
    @Test
    @Uat("ST-168")
    fun everyTappableTextOnAHandPageIsSearched() {
        val st = harness()
        val ctx = st.activity
        val index = SettingsIndex.build(ctx)
        val pages = index.filter { it.kind == Kind.PAGE }.map { it.title }.toSet()
        val problems = mutableListOf<String>()
        for ((route, source) in SettingsPages.PAGES) {
            if (source !is Source.Hand) continue
            val name = route.simpleName
            SearchHighlight.Hooks.readied = null
            st.open(source.open)
            idle()
            // content read on another thread first: the looper's idle time is not that thread's
            if (st.page is ReadyLater) {
                until("$name ready", step = { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SETTLE_MS)) }) {
                    SearchHighlight.Hooks.readied === st.page
                }
                idle()
            }
            val entries = source.page.searchEntries(ctx)
            val not = source.page.notSearched.map(ctx::getString).toSet()
            problems += unsaid(st, name, entries, not + pages)
            problems += absent(st, name, entries)
            if (route == SettingsRoute.PopupOverrides::class.java) {
                (views(st).first { it.tag == "view:keys/multi" }).performClick()
                idle()
                problems += unsaid(st, "$name (多选)", entries, not + pages)
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** Tappable but unsaid. */
    private fun unsaid(st: SettingsHarness, name: String, entries: List<PageEntry>, said: Set<String>): List<String> {
        val titles = entries.map { it.title }.toSet() + said
        val ids = entries.map { it.id }.toSet()
        return (tappable(st) + menu(st)).filter { it !in titles }.map { "$name: \"$it\" is not searched" } +
            anchors(st).filter { it !in ids }.map { "$name: $it is not searched" } +
            rows(st).filter { p -> p.key?.let { SearchHighlight.PREF + it in ids } != true && p.title.toString() !in said }
                .map { "$name: row \"${it.title}\" (${it.key}) is not searched" }
    }

    /** Said but not there. */
    private fun absent(st: SettingsHarness, name: String, entries: List<PageEntry>): List<String> = entries.filter { it.where == 0 }.mapNotNull { e ->
        if (e.id.startsWith(SearchHighlight.PREF)) {
            val key = e.id.removePrefix(SearchHighlight.PREF)
            val pages = listOf(st.page) + st.page.childFragmentManager.fragments
            "$name: no row $key".takeIf { pages.filterIsInstance<PreferenceFragmentCompat>().none { it.findPreference<Preference>(key) != null } }
        } else {
            var got: View? = null
            val anchors = st.page as? SearchAnchors ?: return@mapNotNull "$name: $e has no anchors"
            anchors.reveal(e.id) { got = it }
            idle()
            "$name: ${e.id} not revealed".takeIf { got?.isShown != true }
        }
    }

    // the themes' first tab: each built-in one's card revealed
    @Test
    fun everyBuiltInThemeIsRevealed() {
        val st = harness()
        st.open(SettingsRoute.Theme)
        idle()
        val theme = st.page as SearchAnchors
        for (e in (SettingsPages.PAGES.first { it.first == SettingsRoute.Theme::class.java }.second as Source.Managed).also!!.searchEntries(st.activity)) {
            var got: View? = null
            theme.reveal(e.id) { got = it }
            idle()
            assertTrue(e.id, got?.isShown == true)
        }
    }

    /** Every bold setting name in the docs' tables is found by that name, as a title or another name; [UNSEARCHED]: why not. */
    @Test
    fun everySettingInTheDocsIsFound() {
        val index = SettingsIndex.build(harness().activity)
        val names = index.map { it.title }.toSet() + index.flatMap { it.keywords }
        val missing = listOf("settings.md", "words.md").flatMap { doc ->
            File("../docs/$doc").readLines().filter { it.startsWith("|") && !it.startsWith("|-") && !it.startsWith("| -") }
                .mapNotNull { Regex("""^\|\s*\*\*(.+?)\*\*""").find(it)?.groupValues?.get(1) }
                .filter { it !in names && it !in UNSEARCHED }.map { "$doc: $it" }
        }
        assertTrue(missing.joinToString("\n"), missing.isEmpty())
    }

    private object Discarding : PreferenceDataStore() {
        override fun putString(key: String?, value: String?) = Unit
        override fun putStringSet(key: String?, values: MutableSet<String>?) = Unit
        override fun putInt(key: String?, value: Int) = Unit
        override fun putLong(key: String?, value: Long) = Unit
        override fun putFloat(key: String?, value: Float) = Unit
        override fun putBoolean(key: String?, value: Boolean) = Unit
    }

    private companion object {
        const val SETTLE_STEPS = 10
        const val SETTLE_MS = 100L

        // named in the docs, found otherwise: why
        val UNSEARCHED = mapOf(
            // 开发者's count, found as 开发者
            "语音改字：问过 N 次，记住 M 次" to "a count, not a setting",
            // a choice of the import's question, found as 基础词库
            "基础词库（推荐）" to "a dialog's choice",
        ).keys
    }
}
