/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.keyboard.LevelPick
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.ui.main.settings.DialogSeekBarPreference
import org.fcitx.fcitx5.android.ui.main.settings.EditTextIntPreference
import org.fcitx.fcitx5.android.ui.main.settings.TwinSeekBarPreference

abstract class ManagedPreferenceUi<T : Preference>(
    val key: String,
    private val enableUiOn: (() -> Boolean)? = null
) {

    abstract fun createUi(context: Context): T

    fun isEnabled() = enableUiOn?.invoke() ?: true

    /** Stored nowhere itself: shown as the level nearest the [keys]' values, so an older value stays till the user picks. */
    class Levels(
        @StringRes
        val title: Int,
        key: String,
        private val store: SharedPreferences,
        val keys: List<Pair<String, Int>>,
        private val levels: List<Level>,
        enableUiOn: (() -> Boolean)? = null,
        val follow: Pair<String, Boolean>? = null,
    ) : ManagedPreferenceUi<ListPreference>(key, enableUiOn) {

        class Level(@StringRes val label: Int, vararg val values: Int)

        private val first = if (follow != null) 1 else 0

        fun following(): Boolean = follow?.let { (name, default) -> store.getBoolean(name, default) } ?: false

        fun current(): Int = if (following()) {
            0
        } else {
            first + LevelPick.nearest(keys.map { (name, default) -> store.getInt(name, default) }, levels.map { it.values.toList() })
        }

        fun pick(index: Int, again: Boolean = false): Boolean {
            // the one shown as the page opens is not written over what is there
            if (!again && index == current()) return true
            if (follow != null && index == 0) {
                store.edit {
                    putBoolean(follow.first, true)
                    keys.forEach { (name, _) -> remove(name) }
                }
                return true
            }
            val level = levels.getOrNull(index - first) ?: return false
            store.edit {
                follow?.let { putBoolean(it.first, false) }
                keys.forEachIndexed { k, (name, _) -> putInt(name, level.values[k]) }
            }
            return true
        }

        override fun createUi(context: Context) = object : ListPreference(context) {
            override fun getPersistedString(defaultReturnValue: String?) = current().toString()

            // never written back as it opens: the search index builds these off the main thread while the keyboard may change them
            private var opening = false

            override fun onSetInitialValue(defaultValue: Any?) {
                opening = true
                try {
                    super.onSetInitialValue(defaultValue)
                } finally {
                    opening = false
                }
            }

            override fun persistString(value: String?) = opening || pick(value?.toIntOrNull() ?: -1)

            // the user's pick of the shown level: setValue sees no change
            override fun callChangeListener(newValue: Any?): Boolean {
                if (!super.callChangeListener(newValue)) return false
                if (newValue == value) pick((newValue as? String)?.toIntOrNull() ?: -1, again = true)
                return true
            }
        }.apply {
            key = this@Levels.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            val labels = (if (follow != null) listOf(R.string.follow_theme) else emptyList()) + levels.map { it.label }
            entryValues = labels.indices.map { it.toString() }.toTypedArray()
            entries = labels.map { context.getString(it) }.toTypedArray()
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            // stored nowhere: without a default the list would not read the level at all
            setDefaultValue("0")
            setTitle(this@Levels.title)
            setDialogTitle(this@Levels.title)
        }
    }

    /** Stored nowhere: [pick] writes its keys in one edit. Picking the shown one stores it (a width-derived default becomes set). */
    class Choice(
        @StringRes
        val title: Int,
        key: String,
        private val labels: List<Int>,
        private val current: (Context) -> Int,
        private val pick: (Context, Int) -> Unit,
        private val watched: List<ManagedPreference<String>> = emptyList(),
        enableUiOn: (() -> Boolean)? = null,
    ) : ManagedPreferenceUi<ListPreference>(key, enableUiOn) {

        override fun createUi(context: Context) = object : ListPreference(context) {
            override fun getPersistedString(defaultReturnValue: String?) = current(context).toString()

            // never written back as it opens (see Levels.opening)
            private var opening = false

            override fun onSetInitialValue(defaultValue: Any?) {
                opening = true
                try {
                    super.onSetInitialValue(defaultValue)
                } finally {
                    opening = false
                }
            }

            override fun persistString(value: String?) = opening || persist(value, again = false)

            private fun persist(value: String?, again: Boolean): Boolean {
                val index = value?.toIntOrNull()?.takeIf { it in labels.indices } ?: return false
                if (again || index != current(context)) pick(context, index)
                return true
            }

            // setValue sees no change in the shown one: picked all the same
            override fun callChangeListener(newValue: Any?): Boolean {
                if (!super.callChangeListener(newValue)) return false
                if (newValue == value) persist(newValue as? String, again = true)
                return true
            }

            // kept here: the preferences hold their listeners weakly
            private val shownAnew = ManagedPreference.OnChangeListener<String> { _, _ -> value = current(context).toString() }

            override fun onAttached() {
                super.onAttached()
                watched.forEach { it.registerOnChangeListener(shownAnew) }
            }

            override fun onDetached() {
                watched.forEach { it.unregisterOnChangeListener(shownAnew) }
                super.onDetached()
            }
        }.apply {
            key = this@Choice.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            entryValues = labels.indices.map { it.toString() }.toTypedArray()
            entries = labels.map { context.getString(it) }.toTypedArray()
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            // stored nowhere: without a default the list would not read the one shown at all
            setDefaultValue("0")
            setTitle(this@Choice.title)
            setDialogTitle(this@Choice.title)
        }
    }

    class Note(
        @StringRes
        val text: Int,
        key: String,
    ) : ManagedPreferenceUi<Preference>(key) {
        override fun createUi(context: Context) = Preference(context).apply {
            key = this@Note.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            isSelectable = false
            isPersistent = false
            setSummary(this@Note.text)
        }
    }

    class Switch(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Boolean,
        @StringRes
        val summary: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi<MySwitchPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = MySwitchPreference(context).apply {
            key = this@Switch.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setDefaultValue(defaultValue)
            if (this@Switch.summary != null)
                setSummary(this@Switch.summary)
            setTitle(this@Switch.title)
        }
    }

    class StringList<T : Any>(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: T,
        val codec: ManagedPreference.StringLikeCodec<T>,
        val entryValues: List<T>,
        @StringRes
        val entryLabels: List<Int>,
        enableUiOn: (() -> Boolean)? = null,
        val apart: Apart<T>? = null,
        val confirm: Confirm<T>? = null,
    ) : ManagedPreferenceUi<ListPreference>(key, enableUiOn) {
        override fun createUi(context: Context): ListPreference = object : ListPreference(context), TakenEntry {
            // a stored value the list does not offer shows as the default
            override fun getPersistedString(defaultReturnValue: String?): String? {
                val stored = super.getPersistedString(defaultReturnValue)
                if (stored == null) return null
                if (findIndexOfValue(stored) < 0) return codec.encode(this@StringList.defaultValue)
                val read = apart ?: return stored
                return codec.decode(stored)?.let { codec.encode(read.read(it)) } ?: stored
            }

            override val takenIndex: Int
                get() = apart?.taken?.invoke()?.let { findIndexOfValue(codec.encode(it)) } ?: -1

            override val takenNote: Int get() = apart?.note ?: 0

            // stored as reread, as the keyboard reads them stored. Held here: the preferences hold it weakly
            private val otherChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
                if (changed == key) return@OnSharedPreferenceChangeListener
                val now = getPersistedString(null) ?: return@OnSharedPreferenceChangeListener
                if (now != value) value = now
            }

            override fun onAttached() {
                super.onAttached()
                if (apart != null) sharedPreferences?.registerOnSharedPreferenceChangeListener(otherChanged)
            }

            override fun onDetached() {
                sharedPreferences?.unregisterOnSharedPreferenceChangeListener(otherChanged)
                super.onDetached()
            }

            // setValue sees no change on picking the default, and persistString would see it stored
            override fun callChangeListener(newValue: Any?): Boolean {
                val ask = confirm?.takeIf { newValue != value && newValue == codec.encode(it.value) }
                if (ask != null && newValue is String) {
                    AlertDialog.Builder(context)
                        .setTitle(ask.title)
                        .setMessage(ask.message)
                        .setPositiveButton(android.R.string.ok) { _, _ -> if (super.callChangeListener(newValue)) value = newValue }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    return false
                }
                if (!super.callChangeListener(newValue)) return false
                if (newValue == value && newValue is String) sharedPreferences?.edit { putString(key, newValue) }
                return true
            }
        }.apply {
            key = this@StringList.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            entryValues = this@StringList.entryValues.map { codec.encode(it) }.toTypedArray()
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setDefaultValue(codec.encode(defaultValue))
            setTitle(this@StringList.title)
            entries = this@StringList.entryLabels.map { context.getString(it) }.toTypedArray()
            setDialogTitle(this@StringList.title)
        }
    }

    /** [read] maps a stored value while both share one place (an import) till [AppPrefs.dropRetired] stores it. */
    class Apart<T>(val taken: () -> T?, @StringRes val note: Int, val read: (T) -> T = { it })

    class Confirm<T>(val value: T, @StringRes val title: Int, @StringRes val message: Int)

    interface TakenEntry {
        val takenIndex: Int
        @get:StringRes
        val takenNote: Int
    }

    class EditTextInt(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Int,
        val min: Int,
        val max: Int,
        val unit: String = "",
        val special: Special? = null,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi<EditTextPreference>(key, enableUiOn) {
        /** A named value (「无上限」): a dialog button in place of the default's. */
        data class Special(val value: Int, @StringRes val label: Int, @StringRes val message: Int)

        override fun createUi(context: Context) = EditTextIntPreference(context).apply {
            key = this@EditTextInt.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            summaryProvider = EditTextIntPreference.SimpleSummaryProvider
            setDefaultValue(this@EditTextInt.defaultValue)
            setTitle(this@EditTextInt.title)
            setDialogTitle(this@EditTextInt.title)
            min = this@EditTextInt.min
            max = this@EditTextInt.max
            unit = this@EditTextInt.unit
            this@EditTextInt.special?.let {
                specialValue = it.value
                specialLabel = context.getString(it.label)
                dialogMessage = context.getString(it.message)
            }
        }
    }

    class SeekBarInt(
        @StringRes
        val title: Int,
        key: String,
        val defaultValue: Int,
        val min: Int,
        val max: Int,
        val unit: String = "",
        val step: Int = 1,
        @StringRes
        val defaultLabel: Int? = null,
        enableUiOn: (() -> Boolean)? = null,
        val preview: ((Int) -> Unit)? = null,
    ) : ManagedPreferenceUi<DialogSeekBarPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = DialogSeekBarPreference(context).apply {
            key = this@SeekBarInt.key
            isIconSpaceReserved = false
            isSingleLineTitle = false
            summaryProvider = DialogSeekBarPreference.SimpleSummaryProvider
            this@SeekBarInt.defaultLabel?.let { defaultLabel = context.getString(it) }
            setDefaultValue(this@SeekBarInt.defaultValue)
            setTitle(this@SeekBarInt.title)
            setDialogTitle(this@SeekBarInt.title)
            min = this@SeekBarInt.min
            max = this@SeekBarInt.max
            unit = this@SeekBarInt.unit
            step = this@SeekBarInt.step
            onRelease = this@SeekBarInt.preview
        }
    }

    class TwinSeekBarInt(
        @StringRes
        val title: Int,
        @StringRes
        val label: Int,
        key: String,
        val defaultValue: Int,
        @StringRes
        val secondaryLabel: Int,
        val secondaryKey: String,
        val secondaryDefaultValue: Int,
        val min: Int,
        val max: Int,
        val unit: String = "",
        val step: Int = 1,
        @StringRes
        val defaultLabel: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ) : ManagedPreferenceUi<TwinSeekBarPreference>(key, enableUiOn) {
        override fun createUi(context: Context) = TwinSeekBarPreference(context).apply {
            setTitle(this@TwinSeekBarInt.title)
            setDialogTitle(this@TwinSeekBarInt.title)
            label = context.getString(this@TwinSeekBarInt.label)
            key = this@TwinSeekBarInt.key
            secondaryLabel = context.getString(this@TwinSeekBarInt.secondaryLabel)
            secondaryKey = this@TwinSeekBarInt.secondaryKey
            this@TwinSeekBarInt.defaultLabel?.let { defaultLabel = context.getString(it) }
            setDefaultValue(this@TwinSeekBarInt.defaultValue to this@TwinSeekBarInt.secondaryDefaultValue)
            min = this@TwinSeekBarInt.min
            max = this@TwinSeekBarInt.max
            unit = this@TwinSeekBarInt.unit
            step = this@TwinSeekBarInt.step
            isIconSpaceReserved = false
            isSingleLineTitle = false
            summaryProvider = TwinSeekBarPreference.SimpleSummaryProvider
        }
    }
}