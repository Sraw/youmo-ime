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
 * A component's type as the scope keys it ([IUniqueComponent.defaultType]), found once per class.
 * The library walks the class's supertypes with kotlin-reflect for every instance, each time an
 * input view is made: about a fifth of making one on the 6T (060-render-perf-2 §2, #6). The answer
 * is the class's alone, so one lookup serves every instance after.
 * (A map, not ClassValue: that is API 34.)
 */
object ComponentTypes {
    private val types = ConcurrentHashMap<Class<*>, KClass<out IUniqueComponent<*>>>()

    fun of(component: IUniqueComponent<*>): KClass<out IUniqueComponent<*>> =
        types.getOrPut(component.javaClass) { component.defaultType() }
}

/** [UniqueComponent] with its type from [ComponentTypes]. */
abstract class TypedComponent<T : TypedComponent<T>> : UniqueComponent<T>() {
    final override val type: KClass<out IUniqueComponent<*>> get() = ComponentTypes.of(this)
}
