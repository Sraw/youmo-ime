/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/**
 * A toggle a module puts in the status area, fcitx's `Action` (`fcitx/action.h`): what the keyboard's
 * status panel shows ([icon], [shortText]) and what a tap does ([activate]). [name] is the one the
 * app matches on for its own label (`StatusAreaEntry.label`), as fcitx's text is in fcitx's language.
 */
abstract class StatusAction(val name: String) {
    /** Given by [StatusArea.register], from 1, as `UserInterfaceManager::registerAction` numbers them. */
    var id: Int = 0
        internal set

    internal var area: StatusArea? = null

    abstract fun icon(ic: InputContext): String
    abstract fun shortText(ic: InputContext): String
    abstract fun activate(ic: InputContext)

    /** `Action::update`: the status area of [ic] shows this action anew. */
    fun updateOn(ic: InputContext) {
        area?.changed(ic)
    }
}

/** One action as the status panel shows it on a context, the app's `Action` DTO's fields. */
data class StatusEntry(val id: Int, val name: String, val icon: String, val shortText: String)

/**
 * The status area: the actions each context's input method shows (`fcitx/statusarea.cpp`). The
 * modules [register] theirs once; the host sets a context's list each time it activates an input
 * method ([setInputMethodActions]: `Instance::activateInputMethod` clears the InputMethod group,
 * `instance.cpp:2491`, then `AndroidEngine::activate` adds chttrans, punctuation, fullwidth for a
 * Chinese input method, `androidengine.cpp:500-509`). A module acts on a context only while its
 * action is in that context's list ([contains], fcitx's `Action::isParent` "in white list").
 * [onChanged] is told whenever a context's list or an action on it changes, to send the panel again.
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

    /** Clears [ic]'s list and puts in the registered actions of [names], in that order; unknown names are left out. */
    fun setInputMethodActions(ic: InputContext, names: List<String>) {
        val list = ic.propertyFor(actions)
        list.clear()
        names.mapNotNullTo(list) { registered[it] }
        onChanged(ic)
    }

    fun contains(ic: InputContext, action: StatusAction): Boolean = action in ic.propertyFor(actions)

    fun entries(ic: InputContext): List<StatusEntry> =
        ic.propertyFor(actions).map { StatusEntry(it.id, it.name, it.icon(ic), it.shortText(ic)) }

    /** A tap on the entry [id] (`native-lib.cpp:344-350`): any registered action, as fcitx looks it up by id. */
    fun activate(ic: InputContext, id: Int) {
        registered.values.firstOrNull { it.id == id }?.activate(ic)
    }

    internal fun changed(ic: InputContext) = onChanged(ic)
}

/**
 * The tips the modules show (`INotifications::showTip`), by the tip id fcitx gives them. The app turns
 * each into its text, the "summary: body" `androidnotification.cpp:84` makes, from its own string
 * resources (`Tip`'s doc says which fcitx strings).
 */
enum class Tip(val id: String) {
    /** "Punctuation" / "Full width punctuation is enabled." (`punctuation.cpp:265-271`) */
    PunctuationEnabled("fcitx-punc-toggle"),

    /** "Punctuation" / "Full width punctuation is disabled." */
    PunctuationDisabled("fcitx-punc-toggle"),

    /** "Switch to Traditional Chinese" / "Traditional Chinese is enabled." (`chttrans.cpp:115-124`) */
    TraditionalEnabled("fcitx-chttrans-toggle"),

    /** "Switch to Simplified Chinese" / "Simplified Chinese is enabled." */
    SimplifiedEnabled("fcitx-chttrans-toggle"),
}
