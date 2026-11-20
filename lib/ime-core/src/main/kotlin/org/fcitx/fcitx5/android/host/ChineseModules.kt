/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.InputMethodCatalog

/** [converter] and [charSelectData] are read on first need, as fcitx loads OpenCC and `charselectdata` lazily. */
class ModuleInputs(
    val punctuationProfile: PunctuationProfile,
    val punctuationConfig: PunctuationConfig = PunctuationConfig(),
    val chttransEnabled: Set<String> = emptySet(),
    val converter: () -> ChineseConverter? = { null },
    val quickPhraseTable: QuickPhraseTable = QuickPhraseTable.EMPTY,
    /** [quickPhraseTable] alone, or one still loading elsewhere. */
    val quickPhrases: () -> QuickPhraseBook = QuickPhraseBook(quickPhraseTable).let { book -> { book } },
    val quickPhraseHooks: QuickPhraseHooks = QuickPhraseHooks.NONE,
    val charSelectData: () -> ByteArray? = { null },
    /** Unicode lists its characters when opened. */
    val clipboard: () -> String = { "" },
)

/**
 * The modules the app's fcitx loads, in fcitx's registration order: punctuation, fullwidth, chttrans with their
 * status area, then quickphrase and unicode as temp modes. Saved state goes through [HostServices.persistConf]; [conf]
 * gives the file's text to save.
 */
class ChineseModules(private val inputs: ModuleInputs, private val strings: HostStrings = HostStrings.ENGLISH) {

    lateinit var punctuation: Punctuation
        private set
    lateinit var chttrans: Chttrans
        private set
    lateinit var fullWidth: FullWidth
        private set
    lateinit var quickPhrase: QuickPhrase
        private set
    lateinit var unicode: UnicodeInput
        private set
    lateinit var statusArea: StatusArea
        private set

    /** Made once, with the core's services. */
    fun build(services: HostServices): HostModules {
        val tip: (Tip) -> Unit = { services.tip(strings.tip(it)) }
        statusArea = StatusArea(services::updateStatusArea)
        // registration order gives the status ids 1..; they only need to be stable within a run (fcitx loads by name)
        punctuation = Punctuation(
            inputs.punctuationProfile, inputs.punctuationConfig, statusArea, tip,
            save = { services.persistConf(ModuleConfigs.PUNCTUATION) },
            asTyped = services::punctuationAsTyped,
        )
        fullWidth = FullWidth(statusArea) { ic, text -> services.commit(ic, text) }
        chttrans = Chttrans(
            inputs.chttransEnabled, statusArea, services::inputMethod, { InputMethodCatalog.listed(it)?.language },
            inputs.converter, tip,
            // `updateUserInterface(InputPanel)` then `updatePreedit()`
            { ic ->
                services.updatePanel(ic)
                services.updatePreedit(ic)
            },
        ) { services.persistConf(ModuleConfigs.CHTTRANS) }
        val temp = object : TempModeHost {
            override fun commit(ic: InputContext, text: String) = services.commit(ic, text)
            override fun commitAsIs(ic: InputContext, text: String) = services.commitAsIs(ic, text)
            override fun showPanel(ic: InputContext, panel: TempPanel) = services.showPanel(ic, panel)
        }
        quickPhrase = QuickPhrase(temp, inputs.quickPhrases, strings.quickPhrase, inputs.quickPhraseHooks, strings.quickPhraseAdd, strings.quickPhraseHint)
        unicode = UnicodeInput(temp, inputs.charSelectData, inputs.clipboard, strings.unicode)
        return HostModules(
            tempModes = listOf(quickPhrase, unicode),
            postHooks = listOf(fullWidth.keyHook, punctuation.hotkey, chttrans.hotkey),
            resultListeners = listOf(punctuation.keyResult),
            forwardListeners = listOf(punctuation.forwardKey),
            commitFilters = listOf(punctuation.commitFilter, fullWidth.commitFilter, chttrans.commitFilter),
            outputFilters = listOf(chttrans.outputFilter),
            contextListeners = listOf(punctuation.contextListener),
            activationListeners = listOf(
                ActivationListener { ic, _, chinese ->
                    statusArea.setInputMethodActions(ic, if (chinese) CHINESE_ACTIONS else emptyList())
                },
            ),
            punctuation = object : EnginePunctuation {
                override fun candidates(unicode: Int) = punctuation.getPunctuationCandidates(unicode)
                override fun get(unicode: Int) = punctuation.getPunctuation(unicode)
                override fun push(ic: InputContext, unicode: Int) = punctuation.pushPunctuationV2(ic, unicode)
                override fun keys(ic: InputContext) = punctuation.keys(ic)
                override fun keys(chinese: Boolean) = punctuation.keys(chinese)
            },
            fullWidth = { ic -> fullWidth.enabled && fullWidth.inWhiteList(ic) },
            statusArea = statusArea,
            quickPhrase = quickPhrase,
            unicode = unicode,
        )
    }

    /** Null for a file no module keeps. */
    fun conf(path: String, old: String?): String? = when (path) {
        ModuleConfigs.PUNCTUATION -> ModuleConfigs.punctuationText(old, punctuation.config)
        ModuleConfigs.CHTTRANS -> ModuleConfigs.chttransText(old, chttrans.enabledIm)
        else -> null
    }

    companion object {
        // AndroidEngine::activate's order
        private val CHINESE_ACTIONS = listOf("chttrans", "punctuation", "fullwidth")
    }
}
