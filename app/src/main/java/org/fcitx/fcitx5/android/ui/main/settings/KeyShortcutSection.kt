/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.keyboard.KeyShortcutLooks
import org.fcitx.fcitx5.android.input.shortcut.KeyCommand
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcut
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcutPreset
import org.fcitx.fcitx5.android.input.shortcut.KeyShortcuts
import org.fcitx.fcitx5.android.utils.styledColor
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.wrapContent

/**
 * One way's shortcut in a key's dialog (按键字符和快捷键): none, a command picked from a list, or a
 * text typed. [title] says the way, [off] that the setting turns that way off now.
 */
class KeyShortcutSection(private val ctx: Context, title: String, off: Boolean, initial: KeyShortcut) {

    private var command = (initial as? KeyShortcut.Command)?.command ?: KeyCommand.SelectAll

    private val none = RadioButton(ctx).apply { setText(R.string.shortcut_none) }
    private val commandButton = RadioButton(ctx)
    private val textButton = RadioButton(ctx).apply { setText(R.string.shortcut_text) }
    private val radios = listOf(none, commandButton, textButton)

    private val field = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        filters = arrayOf(InputFilter.LengthFilter(KeyShortcut.MAX_TEXT))
        setText((initial as? KeyShortcut.Text)?.text)
        setOnFocusChangeListener { _, focused -> if (focused) pick(textButton) }
    }

    val view: LinearLayout = ctx.verticalLayout {
        add(TextView(ctx).apply {
            text = if (off) title + ctx.getString(R.string.shortcut_section_off) else title
            textSize = 14f
            setTextColor(ctx.styledColor(android.R.attr.colorPrimary))
            setPadding(0, ctx.dp(12), 0, ctx.dp(4))
        }, lParams(matchParent))
        add(none, lParams(matchParent))
        add(ctx.horizontalLayout {
            gravity = Gravity.CENTER_VERTICAL
            add(commandButton, lParams(0, wrapContent) { weight = 1f })
            add(MaterialButton(ctx, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                setText(R.string.shortcut_change)
                setOnClickListener { chooseCommand() }
            }, lParams())
        }, lParams(matchParent))
        add(textButton, lParams(matchParent))
        add(field, lParams(matchParent) { marginStart = ctx.dp(32) })
    }

    init {
        radios.forEach { button -> button.setOnClickListener { pick(button) } }
        showCommand()
        pick(
            when (initial) {
                is KeyShortcut.Command -> commandButton
                is KeyShortcut.Text -> textButton
                KeyShortcut.None -> none
            },
        )
    }

    private fun pick(button: RadioButton) = radios.forEach { it.isChecked = it === button }

    private fun showCommand() {
        commandButton.text = ctx.getString(R.string.shortcut_command) + "：" + ctx.getString(KeyShortcutLooks.of(command).label)
    }

    private fun chooseCommand() {
        // voice input only where the build has it (051-c §1.5)
        val commands = KeyCommand.entries
        AlertDialog.Builder(ctx)
            .setTitle(R.string.shortcut_command)
            .setSingleChoiceItems(commands.map { ctx.getString(KeyShortcutLooks.of(it).label) }.toTypedArray(), commands.indexOf(command)) { d, i ->
                command = commands[i]
                showCommand()
                pick(commandButton)
                d.dismiss()
            }
            .show()
    }

    /** What is chosen; null when the text is chosen but empty, said on the field. */
    fun chosen(): KeyShortcut? = when {
        commandButton.isChecked -> KeyShortcut.Command(command)
        textButton.isChecked -> field.text.toString().takeIf { it.isNotEmpty() }?.let(KeyShortcut::Text)
            ?: null.also { field.error = ctx.getString(R.string.invalid_value) }
        else -> KeyShortcut.None
    }

    companion object {
        /** [shortcuts] with [name] as [chosen]: a preset chosen is no edit of the user's. */
        fun KeyShortcuts.withChosen(name: String, chosen: KeyShortcut): KeyShortcuts =
            if (chosen == KeyShortcutPreset.of(name)) without(name) else with(name, chosen)

        /** What a key's corner shows of [shortcut] on the page: a command's name, a text's start. */
        fun shortLabel(ctx: Context, shortcut: KeyShortcut): String? = when (shortcut) {
            is KeyShortcut.Command -> ctx.getString(KeyShortcutLooks.of(shortcut.command).label)
            is KeyShortcut.Text -> shortcut.text
            KeyShortcut.None -> null
        }
    }
}
