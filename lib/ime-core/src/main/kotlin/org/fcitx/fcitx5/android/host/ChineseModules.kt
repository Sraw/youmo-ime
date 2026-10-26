/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.host

import org.fcitx.fcitx5.android.engine.host.Engines

/**
 * What the modules start from: their files as the host read them, and where their data comes from.
 * [converter] and [charSelectData] are read the first time they are needed (traditional turned on,
 * Unicode opened), as fcitx loads OpenCC and `charselectdata` lazily.
 */
class ModuleInputs(
    val punctuationProfile: PunctuationProfile,
    val punctuationConfig: PunctuationConfig = PunctuationConfig(),
    val chttransEnabled: Set<String> = emptySet(),
    val converter: () -> ChineseConverter? = { null },
    val quickPhraseTable: QuickPhraseTable = QuickPhraseTable.EMPTY,
    /** Where quick input gets its table at its first lookup: [quickPhraseTable], or one still loading elsewhere. */
    val quickPhrases: () -> QuickPhraseTable = { quickPhraseTable },
    val charSelectData: () -> ByteArray? = { null },
    /** The clipboard's latest text, which Unicode lists the characters of when opened. */
    val clipboard: () -> String = { "" },
)

/**
 * The modules the app's fcitx loads, plugged into the host in fcitx's registration order
 * (`specs/host.md`): chinese-addons' punctuation, fullwidth and chttrans with their status area,
 * then quickphrase and unicode as temp modes. A Chinese input method activated puts the three
 * toggles in the status area (`androidengine.cpp:497-510`); any other clears them
 * (`instance.cpp:2491`). Each module's saved state is told through [HostServices.persistConf] as it
 * changes; [conf] gives the file's text for the writer to save.
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

    /** What [HostCore] takes as its `modules`: made once, with the core's services. */
    fun build(services: HostServices): HostModules {
        val tip: (Tip) -> Unit = { services.tip(strings.tip(it)) }
        statusArea = StatusArea(services::updateStatusArea)
        // registration order gives the status ids 1..: punctuation, fullwidth, chttrans here (fcitx
        // loads them in name order, chttrans first); the ids only need to be stable within a run
        punctuation = Punctuation(inputs.punctuationProfile, inputs.punctuationConfig, statusArea, tip) {
            services.persistConf(ModuleConfigs.PUNCTUATION)
        }
        fullWidth = FullWidth(statusArea) { ic, text -> services.commit(ic, text) }
        chttrans = Chttrans(
            inputs.chttransEnabled, statusArea, services::inputMethod, { Engines.listed(it)?.language },
            inputs.converter, tip,
            // `updateUserInterface(InputPanel)` then `updatePreedit()` (`chttrans.cpp:214-215`)
            { ic ->
                services.updatePanel(ic)
                services.updatePreedit(ic)
            },
        ) { services.persistConf(ModuleConfigs.CHTTRANS) }
        val temp = object : TempModeHost {
            override fun commit(ic: InputContext, text: String) = services.commit(ic, text)
            override fun showPanel(ic: InputContext, panel: TempPanel) = services.showPanel(ic, panel)
        }
        quickPhrase = QuickPhrase(temp, inputs.quickPhrases, strings.quickPhrase)
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
            },
            statusArea = statusArea,
            quickPhrase = quickPhrase,
            unicode = unicode,
        )
    }

    /** The text of the module file at [path] as it should now be, [old] its current text; null for a file no module keeps. */
    fun conf(path: String, old: String?): String? = when (path) {
        ModuleConfigs.PUNCTUATION -> ModuleConfigs.punctuationText(old, punctuation.config)
        ModuleConfigs.CHTTRANS -> ModuleConfigs.chttransText(old, chttrans.enabledIm)
        else -> null
    }

    companion object {
        // `androidengine.cpp:500-509`'s order
        private val CHINESE_ACTIONS = listOf("chttrans", "punctuation", "fullwidth")
    }
}
