/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen

abstract class ManagedPreferenceCategory(
    @StringRes val title: Int,
    protected val sharedPreferences: SharedPreferences
) : ManagedPreferenceProvider() {

    /** Index of the first preference in a section -> the section's title. */
    private val sections = mutableMapOf<Int, Int>()

    /** Preferences registered after this call, up to the next [section], are grouped under [title]. */
    protected fun section(@StringRes title: Int) {
        // two sections at the same index would silently drop the first title
        require(managedPreferencesUi.size !in sections) {
            "empty section before index ${managedPreferencesUi.size}"
        }
        sections[managedPreferencesUi.size] = title
    }

    /** Kept without a UI of their own: a [levels] sets them. */
    protected fun <T> hidden(block: () -> T): T {
        hidingUi = true
        try {
            return block()
        } finally {
            hidingUi = false
        }
    }

    /** The user picks how big or strong, not a number of dp for each of [keys]. */
    protected fun levels(
        @StringRes title: Int,
        key: String,
        keys: List<Pair<String, Int>>,
        levels: List<ManagedPreferenceUi.Levels.Level>,
        // 跟随主题, first in the list
        follow: ManagedPreference.PBool? = null,
        enableUiOn: (() -> Boolean)? = null,
    ) {
        ManagedPreferenceUi.Levels(title, key, sharedPreferences, keys, levels, enableUiOn, follow?.let { it.key to it.defaultValue }).registerUi()
    }

    /** Stored nowhere. */
    protected fun note(@StringRes text: Int, key: String) {
        ManagedPreferenceUi.Note(text, key).registerUi()
    }

    protected fun switch(
        @StringRes
        title: Int,
        key: String,
        defaultValue: Boolean,
        @StringRes
        summary: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ): ManagedPreference.PBool {
        val pref = ManagedPreference.PBool(sharedPreferences, key, defaultValue)
        val ui = ManagedPreferenceUi.Switch(title, key, defaultValue, summary, enableUiOn)
        pref.register()
        ui.registerUi()
        return pref
    }

    protected fun <T : Any> list(
        @StringRes
        title: Int,
        key: String,
        defaultValue: T,
        codec: ManagedPreference.StringLikeCodec<T>,
        entryValues: List<T>,
        @StringRes
        entryLabels: List<Int>,
        apart: ManagedPreferenceUi.Apart<T>? = null,
        confirm: ManagedPreferenceUi.Confirm<T>? = null,
        enableUiOn: (() -> Boolean)? = null,
    ): ManagedPreference.PStringLike<T> {
        val pref = ManagedPreference.PStringLike(sharedPreferences, key, defaultValue, codec)
        val ui = ManagedPreferenceUi.StringList(
            title, key, defaultValue, codec, entryValues, entryLabels, enableUiOn, apart, confirm
        )
        pref.register()
        ui.registerUi()
        return pref
    }

    protected inline fun <reified T> enumList(
        @StringRes
        title: Int,
        key: String,
        defaultValue: T,
        entryValues: List<T> = enumValues<T>().toList(),
        apart: ManagedPreferenceUi.Apart<T>? = null,
        confirm: ManagedPreferenceUi.Confirm<T>? = null,
        noinline enableUiOn: (() -> Boolean)? = null,
    ): ManagedPreference.PStringLike<T> where T : Enum<T>, T : ManagedPreferenceEnum {
        val names = ManagedPreference.enumCodec<T>()
        val codec = object : ManagedPreference.StringLikeCodec<T> {
            // one the list does not offer (another build's, a backup's): read as the default, left stored
            override fun decode(raw: String): T? = names.decode(raw)?.takeIf { it in entryValues }
        }
        val entryLabels = entryValues.map { it.stringRes }
        return list(title, key, defaultValue, codec, entryValues, entryLabels, apart, confirm, enableUiOn)
    }

    protected fun int(
        @StringRes
        title: Int,
        key: String,
        defaultValue: Int,
        min: Int = 0,
        max: Int = Int.MAX_VALUE,
        unit: String = "",
        step: Int = 1,
        @StringRes
        defaultLabel: Int? = null,
        special: ManagedPreferenceUi.EditTextInt.Special? = null,
        // played on slider release, with the value picked
        preview: ((Int) -> Unit)? = null,
        enableUiOn: (() -> Boolean)? = null
    ): ManagedPreference.PInt {
        val pref = ManagedPreference.PInt(sharedPreferences, key, defaultValue)
        // Int can overflow when min < 0 && max == Int.MAX_VALUE
        val ui = if (special != null || (max.toLong() - min.toLong()) / step.toLong() >= 240L)
            ManagedPreferenceUi.EditTextInt(
                title, key, defaultValue, min, max, unit, special, enableUiOn
            )
        else
            ManagedPreferenceUi.SeekBarInt(
                title, key, defaultValue, min, max, unit, step, defaultLabel, enableUiOn, preview
            )
        pref.register()
        ui.registerUi()
        return pref
    }

    protected fun twinInt(
        @StringRes
        title: Int,
        @StringRes
        label: Int,
        key: String,
        defaultValue: Int,
        @StringRes
        secondaryLabel: Int,
        secondaryKey: String,
        secondaryDefaultValue: Int,
        min: Int,
        max: Int,
        unit: String = "",
        step: Int = 1,
        @StringRes
        defaultLabel: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ): Pair<ManagedPreference.PInt, ManagedPreference.PInt> {
        val primary = ManagedPreference.PInt(
            sharedPreferences,
            key, defaultValue,
        )
        val secondary = ManagedPreference.PInt(
            sharedPreferences,
            secondaryKey, secondaryDefaultValue
        )
        val ui = ManagedPreferenceUi.TwinSeekBarInt(
            title,
            label, key, defaultValue,
            secondaryLabel, secondaryKey, secondaryDefaultValue,
            min, max, unit, step, defaultLabel, enableUiOn
        )
        primary.register()
        secondary.register()
        ui.registerUi()
        return primary to secondary
    }

    override fun createUi(screen: PreferenceScreen) = createUi(screen, ALL)

    /** One [section] without its title, for a category shown over two pages; [ALL]: every section under its title. */
    fun createUi(group: PreferenceGroup, @StringRes section: Int) {
        // a section with nothing after it would silently drop its title
        require(managedPreferencesUi.size !in sections) { "empty section at the end" }
        require(section == ALL || section in sections.values) { "no section $section" }
        // one before the first section would be on no section-made page
        require(section == ALL || managedPreferencesUi.isEmpty() || 0 in sections) { "preferences before the first section" }
        val ctx = group.context
        var into: PreferenceGroup? = if (section == ALL) group else null
        managedPreferencesUi.forEachIndexed { index, it ->
            sections[index]?.let { title ->
                into = when (section) {
                    ALL -> addSection(group, title)
                    title -> group
                    else -> null
                }
            }
            into?.addPreference(it.createUi(ctx).apply {
                isEnabled = it.isEnabled()
            })
        }
    }

    companion object {
        /** Key of the [PreferenceCategory] created for [section], for fragments that append to it. */
        fun sectionKey(@StringRes title: Int) = "section_$title"

        /** Keyed and laid out as [createUi] makes a section's. */
        fun addSection(group: PreferenceGroup, @StringRes title: Int): PreferenceCategory =
            PreferenceCategory(group.context).apply {
                key = sectionKey(title)
                setTitle(title)
                isIconSpaceReserved = false
            }.also(group::addPreference)

        const val ALL = 0
    }
}