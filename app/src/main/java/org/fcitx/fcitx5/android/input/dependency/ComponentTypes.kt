/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 youmo-ime Contributors
 */
package org.fcitx.fcitx5.android.input.dependency

import org.mechdancer.dependency.IUniqueComponent
import org.mechdancer.dependency.UniqueComponent
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * Found once per class: the library walks the class's supertypes with kotlin-reflect for every instance, each time
 * an input view is made. (A map, not ClassValue: that is API 34.)
 */
object ComponentTypes {
    private val types = ConcurrentHashMap<Class<*>, KClass<out IUniqueComponent<*>>>()

    fun of(component: IUniqueComponent<*>): KClass<out IUniqueComponent<*>> =
        types.getOrPut(component.javaClass) { component.defaultType() }
}

abstract class TypedComponent<T : TypedComponent<T>> : UniqueComponent<T>() {
    final override val type: KClass<out IUniqueComponent<*>> get() = ComponentTypes.of(this)
}
