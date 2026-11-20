/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

/** The two temp modes registered as fcitx loads them, a recording engine, a host keeping commits and the last panel. */
internal class TempModeRig(
    table: QuickPhraseTable,
    data: () -> ByteArray?,
    clipboard: String = "",
    tableSource: () -> QuickPhraseTable = { table },
    hooks: QuickPhraseHooks = QuickPhraseHooks.NONE,
    book: (() -> QuickPhraseBook)? = null,
) {
    val log = mutableListOf<String>()

    /** Past the commit filters, each also in [commits]. */
    val asIs = mutableListOf<String>()
    val engineKeys = mutableListOf<Int>()
    var panel: TempPanel = TempPanel.EMPTY
    lateinit var pipeline: KeyPipeline

    private val host = object : TempModeHost {
        override fun commit(ic: InputContext, text: String) = pipeline.commit(ic, text)
        override fun commitAsIs(ic: InputContext, text: String) {
            asIs += text
            pipeline.commitAsIs(ic, text)
        }
        override fun showPanel(ic: InputContext, panel: TempPanel) {
            this@TempModeRig.panel = panel
            pipeline.updatePanel(ic)
        }
    }

    val quickPhrase = QuickPhrase(host, book ?: { QuickPhraseBook(tableSource()) }, hooks = hooks)
    val unicode = UnicodeInput(host, data, { clipboard })

    private val output = object : HostOutput {
        override fun commit(ic: InputContext, text: String, cursor: Int) {
            log += "commit $text"
        }

        override fun handBack(ic: InputContext, key: Key, release: Boolean, timestamp: Int) {
            log += "back ${key.sym.toString(16)}"
        }

        override fun panel(ic: InputContext) = Unit
    }

    private val contexts = InputContexts(ImGroup.of("Default", listOf("pinyin", ImGroup.KEYBOARD_US)), { _, _, _, _ -> })
    val ic = contexts.activate(1).also { contexts.focus(it, true) }

    init {
        pipeline = KeyPipeline(
            object : KeyEngine {
                override fun keyEvent(ic: InputContext, e: KeyEvent) {
                    if (!e.release) engineKeys += e.key.sym
                }
            },
            output,
            TempModeManager(listOf(quickPhrase, unicode)),
        )
    }

    fun press(sym: Int, states: Int = 0): KeyEvent = pipeline.dispatch(ic, KeyEvent(Key(sym, states), release = false))

    fun release(sym: Int, states: Int = 0): KeyEvent = pipeline.dispatch(ic, KeyEvent(Key(sym, states), release = true))

    fun type(s: String) = s.forEach { press(it.code) }

    val preedit get() = panel.preedit.toString()
    val aux get() = panel.auxUp.toString()
    val page get() = panel.candidates?.let { l -> (0 until l.size).map { l.candidate(it).text } }.orEmpty()

    /** As the panel's list picks it; false for none. */
    fun tap(globalIndex: Int): Boolean {
        val c = panel.candidates?.candidates?.getOrNull(globalIndex) ?: return false
        c.select?.invoke(ic)
        return true
    }

    fun commits() = log.filter { it.startsWith("commit ") }.map { it.removePrefix("commit ") }
}
