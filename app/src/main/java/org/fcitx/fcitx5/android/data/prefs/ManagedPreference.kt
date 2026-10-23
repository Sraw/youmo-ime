/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import androidx.core.content.edit
import org.fcitx.fcitx5.android.utils.WeakHashSet
import timber.log.Timber
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

abstract class ManagedPreference<T : Any>(
    val sharedPreferences: SharedPreferences,
    val key: String,
    val defaultValue: T,
) : ReadWriteProperty<Any?, T> {

    interface StringLikeCodec<T : Any> {
        fun encode(x: T): String = x.toString()
        fun decode(raw: String): T?
    }

    fun interface OnChangeListener<in T : Any> {
        fun onChange(key: String, value: T)
    }

    abstract fun setValue(value: T)

    abstract fun getValue(): T

    abstract fun putValueTo(editor: SharedPreferences.Editor)

    /**
     * [read], or the default (written back) when the stored value can't be read: a key whose type
     * changed between versions throws ClassCastException, a codec's decode whatever it throws.
     */
    protected inline fun readOrReset(read: () -> T): T = try {
        read()
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // as the five catches this replaces: a stored value of another type or format, not an Error
        Timber.w(e, "preference %s unreadable, reset", key)
        setValue(defaultValue)
        defaultValue
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = getValue()

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = setValue(value)

    private lateinit var listeners: MutableSet<OnChangeListener<T>>

    /**
     * **WARN:** No anonymous listeners, please **KEEP** the reference!
     *
     * You may need to reference the listener once outside of it's container's constructor,
     * to prevent R8 from removing the field;
     * or simply mark the listener with [@Keep][androidx.annotation.Keep] .
     */
    fun registerOnChangeListener(listener: OnChangeListener<T>) {
        if (!::listeners.isInitialized) {
            listeners = WeakHashSet()
        }
        listeners.add(listener)
    }

    fun unregisterOnChangeListener(listener: OnChangeListener<T>) {
        if (!::listeners.isInitialized || listeners.isEmpty()) return
        listeners.remove(listener)
    }

    fun fireChange() {
        if (!::listeners.isInitialized || listeners.isEmpty()) return
        val newValue = getValue()
        // a copy, as ManagedPreferenceProvider.fireChange goes through its listeners
        listeners.toList().forEach { it.onChange(key, newValue) }
    }

    class PBool(sharedPreferences: SharedPreferences, key: String, defaultValue: Boolean) :
        ManagedPreference<Boolean>(sharedPreferences, key, defaultValue) {

        override fun setValue(value: Boolean) {
            sharedPreferences.edit { putBoolean(key, value) }
        }

        override fun getValue(): Boolean = readOrReset {
            sharedPreferences.getBoolean(key, defaultValue)
        }

        override fun putValueTo(editor: SharedPreferences.Editor) {
            editor.putBoolean(key, getValue())
        }
    }

    class PString(sharedPreferences: SharedPreferences, key: String, defaultValue: String) :
        ManagedPreference<String>(sharedPreferences, key, defaultValue) {

        override fun setValue(value: String) {
            sharedPreferences.edit { putString(key, value) }
        }

        override fun getValue(): String = readOrReset {
            sharedPreferences.getString(key, defaultValue)!!
        }

        override fun putValueTo(editor: SharedPreferences.Editor) {
            editor.putString(key, getValue())
        }
    }

    class PStringLike<T : Any>(
        sharedPreferences: SharedPreferences,
        key: String,
        defaultValue: T,
        private val codec: StringLikeCodec<T>
    ) : ManagedPreference<T>(sharedPreferences, key, defaultValue) {

        override fun setValue(value: T) {
            sharedPreferences.edit { putString(key, codec.encode(value)) }
        }

        override fun getValue(): T = readOrReset {
            sharedPreferences.getString(key, null)?.let {
                codec.decode(it)
            } ?: defaultValue
        }

        override fun putValueTo(editor: SharedPreferences.Editor) {
            editor.putString(key, codec.encode(getValue()))
        }
    }


    class PInt(sharedPreferences: SharedPreferences, key: String, defaultValue: Int) :
        ManagedPreference<Int>(sharedPreferences, key, defaultValue) {

        override fun setValue(value: Int) {
            sharedPreferences.edit { putInt(key, value) }
        }

        override fun getValue(): Int = readOrReset {
            sharedPreferences.getInt(key, defaultValue)
        }

        override fun putValueTo(editor: SharedPreferences.Editor) {
            editor.putInt(key, getValue())
        }
    }

    class PFloat(sharedPreferences: SharedPreferences, key: String, defaultValue: Float) :
        ManagedPreference<Float>(sharedPreferences, key, defaultValue) {
        override fun setValue(value: Float) {
            sharedPreferences.edit { putFloat(key, value) }
        }

        override fun getValue(): Float = readOrReset {
            sharedPreferences.getFloat(key, defaultValue)
        }

        override fun putValueTo(editor: SharedPreferences.Editor) {
            editor.putFloat(key, getValue())
        }
    }

}

