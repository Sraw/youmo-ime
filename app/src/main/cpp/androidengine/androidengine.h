/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#ifndef FCITX5_ANDROID_ANDROIDENGINE_H
#define FCITX5_ANDROID_ANDROIDENGINE_H

#include <memory>
#include <string>
#include <vector>

#include <fcitx-config/configuration.h>
#include <fcitx-config/enum.h>
#include <fcitx-config/iniparser.h>
#include <fcitx-utils/event.h>
#include <fcitx-utils/i18n.h>
#include <fcitx/addonfactory.h>
#include <fcitx/addoninstance.h>
#include <fcitx/addonmanager.h>
#include <fcitx/inputmethodengine.h>
#include <fcitx/instance.h>

#include "androidengine_public.h"

namespace fcitx {

// The keys, names and texts of libime pinyin's config, so its translations serve here and a user's
// old settings carry over as they are (ime-core's LibimeMigration copies them). Only what the
// engine does: ime-core's EngineSettings reads these.
#undef FCITX_GETTEXT_DOMAIN
#define FCITX_GETTEXT_DOMAIN "fcitx5-chinese-addons"

enum class EngineShuangpinProfile { Ziranma, MS, Ziguang, ABC, Zhongwenzhixing, PinyinJiajia, Xiaohe, GB };

FCITX_CONFIG_ENUM_NAME_WITH_I18N(EngineShuangpinProfile, N_("Ziranma"), N_("MS"), N_("Ziguang"), N_("ABC"),
                                 N_("Zhongwenzhixing"), N_("PinyinJiajia"), N_("Xiaohe"), N_("GB Standard"))

FCITX_CONFIGURATION(
        EngineFuzzyConfig,
        // here also a key slipped onto its neighbour
        Option<bool> commonTypo{this, "NG_GN", D_("fcitx5-android", "Typos and slipped keys"), true};
        Option<bool> v{this, "V_U", _("u <-> v"), false};
        Option<bool> an{this, "AN_ANG", _("an <-> ang"), false};
        Option<bool> en{this, "EN_ENG", _("en <-> eng"), false};
        Option<bool> ian{this, "IAN_IANG", _("ian <-> iang"), false};
        Option<bool> in{this, "IN_ING", _("in <-> ing"), false};
        Option<bool> ou{this, "U_OU", _("u <-> ou"), false};
        Option<bool> uan{this, "UAN_UANG", _("uan <-> uang"), false};
        Option<bool> c{this, "C_CH", _("c <-> ch"), false};
        Option<bool> f{this, "F_H", _("f <-> h"), false};
        Option<bool> l{this, "L_N", _("l <-> n"), false};
        Option<bool> r{this, "L_R", _("l <-> r"), false};
        Option<bool> s{this, "S_SH", _("s <-> sh"), false};
        Option<bool> z{this, "Z_ZH", _("z <-> zh"), false};)

enum class EngineOrderPolicy { No, Freq, Fast };

FCITX_CONFIG_ENUM_NAME_WITH_I18N(EngineOrderPolicy, N_("No"), N_("Freq"), N_("Fast"))

// libime table's keys and texts; the engine orders by use alike for Freq and Fast
FCITX_CONFIGURATION(
        EngineTableConfig,
        Option<bool> autoSelect{this, "AutoSelect", _("Auto select candidate"), true};
        Option<bool> hint{this, "Hint", _("Display Hint for word"), true};
        OptionWithAnnotation<EngineOrderPolicy, EngineOrderPolicyI18NAnnotation> orderPolicy{
                this, "OrderPolicy", _("Order policy"), EngineOrderPolicy::No};
        // -1 as libime has it: the longest code; never by count, only once picked
        Option<int, IntConstrain> autoPhraseLength{this, "AutoPhraseLength", _("Auto phrase length"), 0,
                                                   IntConstrain(-1, 8)};
        Option<int, IntConstrain> saveAutoPhraseAfter{
                this, "SaveAutoPhraseAfter", _("Save auto phrase after being typed for ... times"), 0,
                IntConstrain(-1, 10)};)

// each table's own defaults, those of ime-core's TableOptions presets (fcitx's .conf files); a
// value read that is out of range falls back to EngineTableConfig's, not to these
inline EngineTableConfig engineTable(bool hint, EngineOrderPolicy order, int autoPhraseLength, int saveAfter) {
    EngineTableConfig config;
    config.hint.setValue(hint);
    config.orderPolicy.setValue(order);
    config.autoPhraseLength.setValue(autoPhraseLength);
    config.saveAutoPhraseAfter.setValue(saveAfter);
    return config;
}

FCITX_CONFIGURATION(
        AndroidEngineConfig,
        OptionWithAnnotation<EngineShuangpinProfile, EngineShuangpinProfileI18NAnnotation> shuangpinProfile{
                this, "ShuangpinProfile", _("Shuangpin Profile"), EngineShuangpinProfile::Ziranma};
        Option<int, IntConstrain> pageSize{this, "PageSize", _("Candidates Per Page"), 7, IntConstrain(3, 10)};
        Option<bool> prediction{this, "Prediction", _("Enable Prediction"), true};
        Option<bool> sentenceModel{this, "SentenceModel", D_("fcitx5-android", "Weigh readings as whole sentences"), true};
        Option<bool> latinWords{this, "LatinWords", D_("fcitx5-android", "Type English words in pinyin"), true};
        Option<EngineFuzzyConfig> fuzzy{this, "Fuzzy", _("Fuzzy Pinyin")};
        // named as the input methods are listed (androidengine.cpp)
        Option<EngineTableConfig> wubi{this, "Wubi", "五笔", engineTable(true, EngineOrderPolicy::Freq, 4, 3)};
        Option<EngineTableConfig> cangjie{this, "Cangjie", "仓颉", engineTable(false, EngineOrderPolicy::No, -1, -1)};
        Option<EngineTableConfig> ziranma{this, "Ziranma", "自然码", engineTable(true, EngineOrderPolicy::Fast, -1, -1)};
        Option<EngineTableConfig> erbi{this, "Erbi", "二笔", engineTable(false, EngineOrderPolicy::Freq, 4, -1)};
        Option<EngineTableConfig> wubiPinyin{this, "WubiPinyin", "五笔拼音", engineTable(true, EngineOrderPolicy::Freq, 4, 3)};
        // opened by name in the app (ConfigDescriptor), the uri only says whose they are
        ExternalOption dictmanager{this, "DictManager", _("Manage Dictionaries"),
                                   "fcitx://config/addon/androidengine/dictmanager"};
        ExternalOption customphrase{this, "CustomPhrase", _("Manage Custom Phrase"),
                                    "fcitx://config/addon/androidengine/customphrase"};
        ExternalOption tablemanager{this, "TableManager", _("Manage Table-based Input Methods"),
                                    "fcitx://config/addon/androidengine/tablemanager"};)

// What each input method's own page shows of AndroidEngineConfig, under the same keys: a page
// with every table's options on it read as if pinyin had them all
FCITX_CONFIGURATION(
        EnginePinyinPage,
        Option<int, IntConstrain> pageSize{this, "PageSize", _("Candidates Per Page"), 7, IntConstrain(3, 10)};
        Option<bool> prediction{this, "Prediction", _("Enable Prediction"), true};
        Option<bool> sentenceModel{this, "SentenceModel", D_("fcitx5-android", "Weigh readings as whole sentences"), true};
        Option<bool> latinWords{this, "LatinWords", D_("fcitx5-android", "Type English words in pinyin"), true};
        Option<EngineFuzzyConfig> fuzzy{this, "Fuzzy", _("Fuzzy Pinyin")};)

FCITX_CONFIGURATION(
        EngineShuangpinPage,
        OptionWithAnnotation<EngineShuangpinProfile, EngineShuangpinProfileI18NAnnotation> shuangpinProfile{
                this, "ShuangpinProfile", _("Shuangpin Profile"), EngineShuangpinProfile::Ziranma};
        Option<int, IntConstrain> pageSize{this, "PageSize", _("Candidates Per Page"), 7, IntConstrain(3, 10)};
        Option<bool> prediction{this, "Prediction", _("Enable Prediction"), true};
        Option<bool> sentenceModel{this, "SentenceModel", D_("fcitx5-android", "Weigh readings as whole sentences"), true};
        Option<EngineFuzzyConfig> fuzzy{this, "Fuzzy", _("Fuzzy Pinyin")};)

#undef FCITX_GETTEXT_DOMAIN
#define FCITX_GETTEXT_DOMAIN "fcitx5-android"

/**
 * The input methods of ime-core (lib/ime-core), run on the JVM. This addon only carries events
 * there and shows what comes back: which key does what, the candidates and what they commit are
 * all decided by ime-core's sessions, through the callbacks native-lib sets at startup.
 */
class AndroidEngine final : public InputMethodEngineV3 {
public:
    explicit AndroidEngine(Instance *instance) : instance_(instance) { reloadConfig(); }

    static const inline std::string ConfPath = "conf/androidengine.conf";

    void reloadConfig() override;

    const Configuration *getConfig() const override { return &config_; }

    void setConfig(const RawConfig &config) override;

    /** Only [entry]'s own options: pinyin's, shuangpin's or its table's. */
    const Configuration *getConfigForInputMethod(const InputMethodEntry &entry) const override;

    void setConfigForInputMethod(const InputMethodEntry &entry, const RawConfig &config) override;

    std::vector<InputMethodEntry> listInputMethods() override;

    void activate(const InputMethodEntry &entry, InputContextEvent &event) override;

    void keyEvent(const InputMethodEntry &entry, KeyEvent &event) override;

    void reset(const InputMethodEntry &entry, InputContextEvent &event) override;

    void deactivate(const InputMethodEntry &entry, InputContextEvent &event) override;

    /**
     * Sends [event] to [im]'s session and shows the result; whether the session took it. [im] is
     * a copy: a pick's comes from the candidate list this replaces.
     */
    bool send(InputContext *ic, std::string im, EngineEvent event, int arg = 0);

    std::vector<std::pair<std::string, std::string>> fetch(const std::string &im, int from, int count) const {
        return candidatesCallback_ ? candidatesCallback_(im, from, count) : std::vector<std::pair<std::string, std::string>>{};
    }

    /**
     * Stops what refining is left, till the next event: a candidate pressed is acted on by its
     * index, which a reordered list would give to another.
     */
    void stopRefining() { refine_.reset(); }

    /** EngineOffer bits: what a long press on the [index]th of [im]'s candidates offers. */
    int offers(const std::string &im, int index) const {
        return offersCallback_ ? offersCallback_(im, index) : 0;
    }

    void setEventCallback(const EngineEventCallback &callback) { eventCallback_ = callback; }

    void setCandidatesCallback(const EngineCandidatesCallback &callback) { candidatesCallback_ = callback; }

    void setOffersCallback(const EngineOffersCallback &callback) { offersCallback_ = callback; }

    void setSettingsCallback(const EngineSettingsCallback &callback) {
        settingsCallback_ = callback;
        pushSettings();
    }

private:
    /** Shows [snapshot], [im]'s: what it commits, its preedit and candidates. */
    void show(InputContext *ic, std::string im, const EngineSnapshot &snapshot);

    /** Sends Refine to [im]'s session after [delay] µs, unless another event comes first. */
    void refineLater(InputContext *ic, const std::string &im, uint64_t delay);

    /**
     * Commits the full-width form of a key the session passed on, if it has one; one with several
     * offers them all as candidates, the first going in unless another is picked. Whether it did.
     */
    bool pushPunctuation(InputContext *ic, const Key &key);

    /** Hands the config to the engine, flattened as EngineSettingsCallback says. */
    void pushSettings() const;

    Instance *instance_;
    FCITX_ADDON_DEPENDENCY_LOADER(punctuation, instance_->addonManager());
    FCITX_ADDON_DEPENDENCY_LOADER(fullwidth, instance_->addonManager());
    FCITX_ADDON_DEPENDENCY_LOADER(chttrans, instance_->addonManager());
    EngineEventCallback eventCallback_;
    EngineCandidatesCallback candidatesCallback_;
    EngineOffersCallback offersCallback_;
    EngineSettingsCallback settingsCallback_;
    AndroidEngineConfig config_;
    // filled from config_ as a page is opened
    mutable EnginePinyinPage pinyinPage_;
    mutable EngineShuangpinPage shuangpinPage_;

    /** The table options of [im], one of the table input methods; null for pinyin and shuangpin. */
    Option<EngineTableConfig> *table(const std::string &im);
    // the pause a snapshot that refines waits for; any event ends it
    std::unique_ptr<EventSourceTime> refine_;

    FCITX_ADDON_EXPORT_FUNCTION(AndroidEngine, setEventCallback);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidEngine, setCandidatesCallback);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidEngine, setOffersCallback);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidEngine, setSettingsCallback);
};

class AndroidEngineFactory : public AddonFactory {
public:
    AddonInstance *create(AddonManager *manager) override {
        return new AndroidEngine(manager->instance());
    }
};

} // namespace fcitx

#endif // FCITX5_ANDROID_ANDROIDENGINE_H
