/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#include <algorithm>
#include <memory>
#include <stdexcept>

#include <fcitx/candidatelist.h>
#include <fcitx/inputcontext.h>
#include <fcitx/inputpanel.h>
#include <fcitx/statusarea.h>
#include <fcitx/userinterfacemanager.h>
#include <fcitx-utils/utf8.h>
#include <punctuation_public.h>

#include "androidengine.h"

namespace fcitx {

namespace {

class EngineCandidateWord : public CandidateWord {
public:
    EngineCandidateWord(AndroidEngine *engine, std::string im, int index, const std::string &text, const std::string &hint)
            : CandidateWord(Text(text)), engine_(engine), im_(std::move(im)), index_(index) {
        if (!hint.empty()) setComment(Text(hint));
    }

    void select(InputContext *inputContext) const override {
        engine_->send(inputContext, im_, EngineEvent::Pick, index_);
    }

private:
    AndroidEngine *engine_;
    std::string im_;
    int index_;
};

/**
 * All the candidates of a snapshot, as a list that scrolls: those it came with, then the rest
 * fetched from the session a chunk at a time as the keyboard asks for them. A new snapshot
 * replaces the list, so what is fetched is always of the input shown. As a page it is the one the
 * session shows: candidate(i) is the (first + i)th of all.
 */
class EngineCandidateList : public CandidateList, public BulkCandidateList {
public:
    EngineCandidateList(AndroidEngine *engine, std::string im, const EngineSnapshot &snapshot)
            : engine_(engine), im_(std::move(im)), total_(snapshot.total) {
        setBulk(this);
        for (size_t i = 0; i < snapshot.candidates.size(); i++) {
            const auto &hint = i < snapshot.hints.size() ? snapshot.hints[i] : std::string();
            words_.push_back(std::make_unique<EngineCandidateWord>(engine_, im_, static_cast<int>(i), snapshot.candidates[i], hint));
        }
        const int size = static_cast<int>(words_.size());
        first_ = std::clamp(snapshot.first, 0, size);
        shown_ = std::clamp(snapshot.shown, 0, size - first_);
        for (int i = 0; i < shown_; i++) labels_.emplace_back(std::to_string((i + 1) % 10));
    }

    const Text &label(int idx) const override {
        check(idx);
        return labels_[idx];
    }

    const CandidateWord &candidate(int idx) const override {
        check(idx);
        return *words_[first_ + idx];
    }

    int size() const override { return shown_; }

    int cursorIndex() const override { return -1; }

    CandidateLayoutHint layoutHint() const override { return CandidateLayoutHint::NotSet; }

    const CandidateWord &candidateFromAll(int idx) const override {
        // a total of -1: the session has not counted them, so ask until it runs out
        if (idx < 0 || (total_ >= 0 && idx >= total_)) throw std::invalid_argument("invalid index");
        while (static_cast<int>(words_.size()) <= idx) {
            const int from = static_cast<int>(words_.size());
            const int count = std::max(Chunk, idx + 1 - from);
            auto more = engine_->fetch(im_, from, count);
            if (static_cast<int>(more.size()) < count) total_ = from + static_cast<int>(more.size());
            if (more.empty()) throw std::invalid_argument("invalid index");
            for (auto &[text, hint]: more) {
                words_.push_back(std::make_unique<EngineCandidateWord>(engine_, im_, static_cast<int>(words_.size()), text, hint));
            }
        }
        return *words_[idx];
    }

    int totalSize() const override { return total_; }

private:
    static constexpr int Chunk = 32;

    void check(int idx) const {
        if (idx < 0 || idx >= shown_) throw std::invalid_argument("invalid index");
    }

    AndroidEngine *engine_;
    std::string im_;
    mutable int total_;
    int first_;
    int shown_;
    mutable std::vector<std::unique_ptr<EngineCandidateWord>> words_;
    // as the keys that pick them: 1 to 9, then 0
    std::vector<Text> labels_;
};

} // namespace

std::vector<InputMethodEntry> AndroidEngine::listInputMethods() {
    // names as ime-core's Engines knows them
    struct Method {
        const char *name, *label, *text, *icon;
    };
    static const Method methods[] = {
            {"engine-pinyin", "拼", "拼音", "fcitx-pinyin"},
            {"engine-shuangpin", "双", "双拼", "fcitx-shuangpin"},
            {"engine-wubi", "五", "五笔", "fcitx-wubi"},
            {"engine-cangjie", "倉", "仓颉", "fcitx-cangjie"},
            {"engine-ziranma", "自", "自然码", "fcitx-ziranma"},
            {"engine-erbi", "二", "二笔", "fcitx-erbi"},
    };
    std::vector<InputMethodEntry> result;
    for (const auto &m: methods) {
        result.emplace_back(std::move(
                InputMethodEntry(m.name, m.text, "zh_CN", "androidengine")
                        .setLabel(m.label)
                        .setIcon(m.icon)
                        .setConfigurable(true)));
    }
    return result;
}

void AndroidEngine::reloadConfig() {
    readAsIni(config_, ConfPath);
    pushSettings();
}

void AndroidEngine::setConfig(const RawConfig &config) {
    config_.load(config, true);
    safeSaveAsIni(config_, ConfPath);
    pushSettings();
}

void AndroidEngine::pushSettings() const {
    if (!settingsCallback_) return;
    RawConfig raw;
    config_.save(raw);
    std::string flat;
    std::function<void(const RawConfig &, const std::string &)> walk = [&](const RawConfig &node, const std::string &path) {
        for (const auto &name: node.subItems()) {
            const auto item = node.get(name);
            const auto key = path.empty() ? name : path + "/" + name;
            if (item->hasSubItems()) {
                walk(*item, key);
            } else {
                flat += key + "=" + item->value() + "\n";
            }
        }
    };
    walk(raw, "");
    settingsCallback_(flat);
}

void AndroidEngine::activate(const InputMethodEntry &entry, InputContextEvent &event) {
    // loaded now, so their toggles are there to add
    punctuation();
    fullwidth();
    chttrans();
    // in the status area, these addons act on this input context: full-width punctuation and
    // letters, traditional characters
    for (const auto *name: {"chttrans", "punctuation", "fullwidth"}) {
        if (auto *action = instance_->userInterfaceManager().lookupAction(name)) {
            event.inputContext()->statusArea().addAction(StatusGroup::InputMethod, action);
        }
    }
    // starts the session afresh, and loads its data now rather than on the first key
    send(event.inputContext(), entry.uniqueName(), EngineEvent::Reset);
}

bool AndroidEngine::pushPunctuation(InputContext *ic, const Key &key) {
    if (!punctuation() || key.isKeyPad()) return false;
    const auto unicode = Key::keySymToUnicode(key.sym());
    auto [text, after] = punctuation()->call<IPunctuation::pushPunctuationV2>("zh_CN", ic, unicode);
    if (text.empty()) return false;
    // a pair (“”) goes in whole, the cursor between
    const auto length = utf8::lengthValidated(text);
    if (!after.empty() && ic->capabilityFlags().test(CapabilityFlag::CommitStringWithCursor) &&
        length != 0 && length != utf8::INVALID_LENGTH) {
        ic->commitStringWithCursor(text + after, length);
    } else {
        ic->commitString(text + after);
        const auto back = utf8::lengthValidated(after);
        if (back != utf8::INVALID_LENGTH) {
            for (size_t i = 0; i < back; i++) ic->forwardKey(Key(FcitxKey_Left));
        }
    }
    return true;
}

void AndroidEngine::keyEvent(const InputMethodEntry &entry, KeyEvent &event) {
    if (event.isRelease()) return;
    const auto &key = event.key();
    if (key.isModifier()) return;
    // shortcuts are the app's
    if (key.states().testAny(KeyStates{KeyState::Ctrl, KeyState::Alt, KeyState::Super, KeyState::Hyper})) return;
    EngineEvent what;
    int arg = 0;
    switch (key.sym()) {
        case FcitxKey_BackSpace: what = EngineEvent::Backspace; break;
        case FcitxKey_Return:
        case FcitxKey_KP_Enter: what = EngineEvent::Enter; break;
        case FcitxKey_Escape: what = EngineEvent::Escape; break;
        case FcitxKey_Page_Up: what = EngineEvent::PageUp; break;
        case FcitxKey_Page_Down: what = EngineEvent::PageDown; break;
        default: {
            const auto unicode = Key::keySymToUnicode(key.sym());
            // a control character (Tab, a line feed, Delete) is a key like an arrow: the session
            // keeps it while composing, the app gets it otherwise
            if (unicode >= 0x20 && unicode != 0x7f) {
                what = EngineEvent::Char;
                arg = static_cast<int>(unicode);
            } else {
                what = EngineEvent::Other;
            }
        }
    }
    if (send(event.inputContext(), entry.uniqueName(), what, arg) ||
        (what == EngineEvent::Char && pushPunctuation(event.inputContext(), key))) {
        event.filterAndAccept();
    }
}

bool AndroidEngine::send(InputContext *ic, std::string im, EngineEvent event, int arg) {
    if (!eventCallback_) return false;
    const bool learning = !ic->capabilityFlags().testAny(CapabilityFlag::PasswordOrSensitive);
    const auto snapshot = eventCallback_(im, event, arg, learning);
    if (!snapshot.commit.empty()) ic->commitString(snapshot.commit);
    auto &panel = ic->inputPanel();
    panel.reset();
    if (!snapshot.preedit.empty()) {
        // in the app where it can show it, as libime's pinyin does by default (ComposingPinyin)
        if (ic->capabilityFlags().test(CapabilityFlag::Preedit)) {
            Text preedit(snapshot.preedit, TextFormatFlag::Underline);
            preedit.setCursor(static_cast<int>(snapshot.preedit.size()));
            panel.setClientPreedit(preedit);
        } else {
            panel.setPreedit(Text(snapshot.preedit));
        }
    }
    if (!snapshot.candidates.empty()) panel.setCandidateList(std::make_unique<EngineCandidateList>(this, std::move(im), snapshot));
    ic->updatePreedit();
    ic->updateUserInterface(UserInterfaceComponent::InputPanel);
    return snapshot.handled;
}

void AndroidEngine::reset(const InputMethodEntry &entry, InputContextEvent &event) {
    send(event.inputContext(), entry.uniqueName(), EngineEvent::Reset);
}

void AndroidEngine::deactivate(const InputMethodEntry &entry, InputContextEvent &event) {
    // switching to another input method keeps what was typed, as libime's pinyin does; losing
    // focus drops it
    if (event.type() == EventType::InputContextSwitchInputMethod) {
        send(event.inputContext(), entry.uniqueName(), EngineEvent::Enter);
    }
    reset(entry, event);
}

} // namespace fcitx

FCITX_ADDON_FACTORY(fcitx::AndroidEngineFactory)
