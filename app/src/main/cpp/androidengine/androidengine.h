/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#ifndef FCITX5_ANDROID_ANDROIDENGINE_H
#define FCITX5_ANDROID_ANDROIDENGINE_H

#include <string>
#include <vector>

#include <fcitx-config/configuration.h>
#include <fcitx-config/enum.h>
#include <fcitx-config/iniparser.h>
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
        Option<bool> commonTypo{this, "NG_GN", _("Typos and slipped keys"), true};
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

FCITX_CONFIGURATION(
        AndroidEngineConfig,
        OptionWithAnnotation<EngineShuangpinProfile, EngineShuangpinProfileI18NAnnotation> shuangpinProfile{
                this, "ShuangpinProfile", _("Shuangpin Profile"), EngineShuangpinProfile::Ziranma};
        Option<int, IntConstrain> pageSize{this, "PageSize", _("Candidates Per Page"), 7, IntConstrain(3, 10)};
        Option<bool> prediction{this, "Prediction", _("Enable Prediction"), true};
        Option<bool> sentenceModel{this, "SentenceModel", _("Weigh readings as whole sentences"), true};
        Option<EngineFuzzyConfig> fuzzy{this, "Fuzzy", _("Fuzzy Pinyin")};
        // opened by name in the app (ConfigDescriptor), the uri only says whose they are
        ExternalOption dictmanager{this, "DictManager", _("Manage Dictionaries"),
                                   "fcitx://config/addon/androidengine/dictmanager"};
        ExternalOption customphrase{this, "CustomPhrase", _("Manage Custom Phrase"),
                                    "fcitx://config/addon/androidengine/customphrase"};)

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

    void setEventCallback(const EngineEventCallback &callback) { eventCallback_ = callback; }

    void setCandidatesCallback(const EngineCandidatesCallback &callback) { candidatesCallback_ = callback; }

    void setSettingsCallback(const EngineSettingsCallback &callback) {
        settingsCallback_ = callback;
        pushSettings();
    }

private:
    /** Commits the full-width form of a key the session passed on, if it has one; whether it did. */
    bool pushPunctuation(InputContext *ic, const Key &key);

    /** Hands the config to the engine, flattened as EngineSettingsCallback says. */
    void pushSettings() const;

    Instance *instance_;
    FCITX_ADDON_DEPENDENCY_LOADER(punctuation, instance_->addonManager());
    FCITX_ADDON_DEPENDENCY_LOADER(fullwidth, instance_->addonManager());
    FCITX_ADDON_DEPENDENCY_LOADER(chttrans, instance_->addonManager());
    EngineEventCallback eventCallback_;
    EngineCandidatesCallback candidatesCallback_;
    EngineSettingsCallback settingsCallback_;
    AndroidEngineConfig config_;

    FCITX_ADDON_EXPORT_FUNCTION(AndroidEngine, setEventCallback);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidEngine, setCandidatesCallback);
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
