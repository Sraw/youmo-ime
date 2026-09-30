/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#include <algorithm>
#include <memory>
#include <stdexcept>

#include <fcitx/candidateaction.h>
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

// µs after a key before its snapshot is refined: long enough for a key typed right after it to
// come first, short against the time a user takes to reach for a candidate
constexpr uint64_t PauseBeforeRefine = 30000;

class EngineCandidateWord : public CandidateWord {
public:
    EngineCandidateWord(AndroidEngine *engine, std::string im, int index, const std::string &text, const std::string &hint)
            : CandidateWord(Text(text)), engine_(engine), im_(std::move(im)), index_(index) {
        if (!hint.empty()) setComment(Text(hint));
    }

    void select(InputContext *inputContext) const override {
        engine_->send(inputContext, im_, EngineEvent::Pick, index_);
    }

    int index() const { return index_; }

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
 *
 * A long press on a candidate offers what the session says it may do with it, as libime's pinyin
 * and table did: "Forget word", and pinyin's custom phrases pinned or deleted. It is asked on the
 * press, not sent for each candidate with the snapshot: few are ever pressed.
 */
class EngineCandidateList : public CandidateList, public BulkCandidateList, public ActionableCandidateList {
public:
    EngineCandidateList(AndroidEngine *engine, InputContext *ic, std::string im, const EngineSnapshot &snapshot)
            : engine_(engine), ic_(ic), im_(std::move(im)), total_(snapshot.total), actionable_(snapshot.actionable) {
        setBulk(this);
        if (actionable_) setActionable(this);
        for (size_t i = 0; i < snapshot.candidates.size(); i++) {
            const auto &hint = i < snapshot.hints.size() ? snapshot.hints[i] : std::string();
            words_.push_back(std::make_unique<EngineCandidateWord>(engine_, im_, static_cast<int>(i), snapshot.candidates[i], hint));
        }
        const int size = static_cast<int>(words_.size());
        first_ = std::clamp(snapshot.first, 0, size);
        shown_ = std::clamp(snapshot.shown, 0, size - first_);
        for (int i = 0; i < shown_; i++) {
            const auto at = static_cast<size_t>(i);
            labels_.emplace_back(at < snapshot.labels.size() ? std::string(1, snapshot.labels[at]) : std::to_string((i + 1) % 10));
        }
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

    bool hasAction(const CandidateWord &candidate) const override {
        return actionable_ && dynamic_cast<const EngineCandidateWord *>(&candidate);
    }

    std::vector<CandidateAction> candidateActions(const CandidateWord &candidate) const override {
        if (!hasAction(candidate)) return {};
        engine_->stopRefining();
        const int offers = engine_->offers(im_, static_cast<const EngineCandidateWord &>(candidate).index());
        std::vector<CandidateAction> actions;
        auto add = [&](EngineOffer offer, EngineEvent event, const char *text) {
            if (!(offers & offer)) return;
            CandidateAction action;
            // the event it sends
            action.setId(static_cast<int>(event));
            action.setText(D_("fcitx5-chinese-addons", text));
            actions.push_back(std::move(action));
        };
        // libime's table words it so; its pinyin says "Forget candidate"
        add(OfferForget, EngineEvent::Forget, "Forget word");
        add(OfferPin, EngineEvent::Pin, "Pin to top as custom phrase");
        add(OfferUnpin, EngineEvent::Unpin, "Delete from custom phrase");
        return actions;
    }

    void triggerAction(const CandidateWord &candidate, int id) override {
        const auto *word = dynamic_cast<const EngineCandidateWord *>(&candidate);
        const auto event = static_cast<EngineEvent>(id);
        const int offer = event == EngineEvent::Forget ? OfferForget
                        : event == EngineEvent::Pin    ? OfferPin
                        : event == EngineEvent::Unpin  ? OfferUnpin
                                                       : 0;
        // asked again: only what the session still offers for the candidate at that index
        if (!actionable_ || !word || !offer || !(engine_->offers(im_, word->index()) & offer)) return;
        // the snapshot that comes back replaces this list: nothing of it is touched after
        engine_->send(ic_, im_, event, word->index());
    }

private:
    static constexpr int Chunk = 32;

    void check(int idx) const {
        if (idx < 0 || idx >= shown_) throw std::invalid_argument("invalid index");
    }

    AndroidEngine *engine_;
    // whose panel holds the list, so it outlives it
    InputContext *ic_;
    std::string im_;
    mutable int total_;
    bool actionable_;
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
            {"engine-wubipinyin", "五", "五笔拼音", "fcitx-wubi"},
            {"engine-dianbao", "电", "电报码", "fcitx-dianbaoma"},
            {"engine-bingchan", "冰", "冰蟾全息", "fcitx-bingchan"},
            {"engine-wanfeng", "晚", "晚风", "fcitx-wanfeng"},
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
    // any key ends a pause, shortcuts too: a refine landing after one would redraw a panel
    // someone else may have put up since
    refine_.reset();
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
    refine_.reset();
    const bool learning = !ic->capabilityFlags().testAny(CapabilityFlag::PasswordOrSensitive);
    const auto snapshot = eventCallback_(im, event, arg, learning);
    // a key may be on its way: the pause starts a little after it, the next slice at once
    if (snapshot.refines) refineLater(ic, im, event == EngineEvent::Refine ? 0 : PauseBeforeRefine);
    // a slice of refining that changed nothing leaves the panel as it is, the list scrolled
    if (event != EngineEvent::Refine || snapshot.handled) show(ic, std::move(im), snapshot);
    return snapshot.handled;
}

void AndroidEngine::refineLater(InputContext *ic, const std::string &im, uint64_t delay) {
    // deleted in its own callback by the send it makes, which fcitx allows for: its timer
    // callback runs a copy of the function, and checks the source is still there after it
    refine_ = instance_->eventLoop().addTimeEvent(
            CLOCK_MONOTONIC, now(CLOCK_MONOTONIC) + delay, 0,
            [this, ref = ic->watch(), im](EventSourceTime *, uint64_t) {
                auto *ic = ref.get();
                // still ours to show: the same input method, and our list on the panel, not a
                // clipboard or quick phrase one put up meanwhile
                if (ic && ic->hasFocus() && instance_->inputMethod(ic) == im &&
                    dynamic_cast<EngineCandidateList *>(ic->inputPanel().candidateList().get())) {
                    send(ic, im, EngineEvent::Refine);
                }
                return true;
            });
}

void AndroidEngine::show(InputContext *ic, std::string im, const EngineSnapshot &snapshot) {
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
    if (!snapshot.candidates.empty()) panel.setCandidateList(std::make_unique<EngineCandidateList>(this, ic, std::move(im), snapshot));
    ic->updatePreedit();
    ic->updateUserInterface(UserInterfaceComponent::InputPanel);
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
