/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#ifndef FCITX5_ANDROID_ANDROIDENGINE_PUBLIC_H
#define FCITX5_ANDROID_ANDROIDENGINE_PUBLIC_H

#include <functional>
#include <string>
#include <utility>
#include <vector>

#include <fcitx/addoninstance.h>

// What the engine shows after an event: ime-core's Snapshot. [candidates] are the first of
// [total], through the page shown: [shown] of them from [first]. The rest are fetched through
// EngineCandidatesCallback as the list scrolls. [forgets]: whether they offer "Forget word".
struct EngineSnapshot {
    bool handled = false;
    std::string commit;
    std::string preedit;
    std::vector<std::string> candidates;
    std::vector<std::string> hints;
    int first = 0;
    int shown = 0;
    int total = 0;
    bool forgets = false;
};

// What happened, numbered as ime-core's EngineEvent reads them. Which key does what is decided
// there, not here.
enum class EngineEvent : int {
    Char = 0,       // arg: the character's code point
    Backspace = 1,
    Enter = 2,
    Escape = 3,
    PageUp = 4,
    PageDown = 5,
    Pick = 6,       // arg: the index among all candidates
    Reset = 7,
    Other = 8,      // any other key: an arrow, Home, Tab, Delete
    Forget = 9,     // "Forget word" on a candidate; arg: the index among all candidates
};

// Hands an event to the session of input method [im]; called on the fcitx thread. [learning] is
// false where nothing typed may be kept (a password field).
typedef std::function<EngineSnapshot(const std::string &im, EngineEvent event, int arg, bool learning)> EngineEventCallback;
// Candidates [from, from + count) of [im]'s session, each with its hint.
typedef std::function<std::vector<std::pair<std::string, std::string>>(const std::string &im, int from, int count)> EngineCandidatesCallback;
// The addon's config, `key=value` a line, a group's keys after its name (`Fuzzy/L_N=True`); called
// once set, and on each change.
typedef std::function<void(const std::string &settings)> EngineSettingsCallback;

FCITX_ADDON_DECLARE_FUNCTION(AndroidEngine, setEventCallback,
                             void(const EngineEventCallback &))
FCITX_ADDON_DECLARE_FUNCTION(AndroidEngine, setCandidatesCallback,
                             void(const EngineCandidatesCallback &))
FCITX_ADDON_DECLARE_FUNCTION(AndroidEngine, setSettingsCallback,
                             void(const EngineSettingsCallback &))

#endif // FCITX5_ANDROID_ANDROIDENGINE_PUBLIC_H
