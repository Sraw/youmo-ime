/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#ifndef FCITX5_ANDROID_ANDROIDENGINE_H
#define FCITX5_ANDROID_ANDROIDENGINE_H

#include <string>
#include <vector>

#include <fcitx/addonfactory.h>
#include <fcitx/addoninstance.h>
#include <fcitx/addonmanager.h>
#include <fcitx/inputmethodengine.h>
#include <fcitx/instance.h>

#include "androidengine_public.h"

namespace fcitx {

/**
 * The input methods of ime-core (lib/ime-core), run on the JVM. This addon only carries events
 * there and shows what comes back: which key does what, the candidates and what they commit are
 * all decided by ime-core's sessions, through the callbacks native-lib sets at startup.
 */
class AndroidEngine final : public InputMethodEngineV3 {
public:
    explicit AndroidEngine(Instance *instance) : instance_(instance) {}

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

private:
    /** Commits the full-width form of a key the session passed on, if it has one; whether it did. */
    bool pushPunctuation(InputContext *ic, const Key &key);

    Instance *instance_;
    FCITX_ADDON_DEPENDENCY_LOADER(punctuation, instance_->addonManager());
    FCITX_ADDON_DEPENDENCY_LOADER(fullwidth, instance_->addonManager());
    FCITX_ADDON_DEPENDENCY_LOADER(chttrans, instance_->addonManager());
    EngineEventCallback eventCallback_;
    EngineCandidatesCallback candidatesCallback_;

    FCITX_ADDON_EXPORT_FUNCTION(AndroidEngine, setEventCallback);
    FCITX_ADDON_EXPORT_FUNCTION(AndroidEngine, setCandidatesCallback);
};

class AndroidEngineFactory : public AddonFactory {
public:
    AddonInstance *create(AddonManager *manager) override {
        return new AndroidEngine(manager->instance());
    }
};

} // namespace fcitx

#endif // FCITX5_ANDROID_ANDROIDENGINE_H
