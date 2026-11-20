/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/** fcitx's `Action`; [name] is what the app matches for its own label, as fcitx's text is in fcitx's language. */
abstract class StatusAction(val name: String) {
    /** From 1, as `UserInterfaceManager::registerAction` numbers them. */
    var id: Int = 0
        internal set

    internal var area: StatusArea? = null

    abstract fun icon(ic: InputContext): String
    abstract fun shortText(ic: InputContext): String

    /** Said apart, not read off [icon]'s name. */
    abstract fun active(ic: InputContext): Boolean
    abstract fun activate(ic: InputContext)

    /** `Action::update`. */
    fun updateOn(ic: InputContext) {
        area?.changed(ic)
    }
}

/** The app's `Action` DTO's fields. */
data class StatusEntry(val id: Int, val name: String, val icon: String, val shortText: String, val active: Boolean)

/**
 * fcitx's `StatusArea`: modules [register] once; on each activation [setInputMethodActions] clears the list and
 * `AndroidEngine::activate` adds chttrans, punctuation, fullwidth for Chinese. A module acts only while its action is
 * listed ([contains], fcitx's "in white list"). [onChanged]: send the panel again.
 */
class StatusArea(private val onChanged: (InputContext) -> Unit = {}) {
    private val registered = LinkedHashMap<String, StatusAction>()
    private val actions = IcProperty { ArrayList<StatusAction>() }

    fun register(action: StatusAction) {
        check(action.area == null && action.name !in registered) { "${action.name} registered twice" }
        action.id = registered.size + 1
        action.area = this
        registered[action.name] = action
    }

    /** Unknown names are left out. */
    fun setInputMethodActions(ic: InputContext, names: List<String>) {
        val list = ic.propertyFor(actions)
        list.clear()
        names.mapNotNullTo(list) { registered[it] }
        onChanged(ic)
    }

    fun contains(ic: InputContext, action: StatusAction): Boolean = action in ic.propertyFor(actions)

    fun entries(ic: InputContext): List<StatusEntry> =
        ic.propertyFor(actions).map { StatusEntry(it.id, it.name, it.icon(ic), it.shortText(ic), it.active(ic)) }

    /** Any registered action, as fcitx looks it up by id. */
    fun activate(ic: InputContext, id: Int) {
        registered.values.firstOrNull { it.id == id }?.activate(ic)
    }

    internal fun changed(ic: InputContext) = onChanged(ic)
}

/** `INotifications::showTip` ids; the app renders "summary: body" from its own strings. */
enum class Tip(val id: String) {
    PunctuationEnabled("fcitx-punc-toggle"),

    PunctuationDisabled("fcitx-punc-toggle"),

    TraditionalEnabled("fcitx-chttrans-toggle"),

    SimplifiedEnabled("fcitx-chttrans-toggle"),
}
