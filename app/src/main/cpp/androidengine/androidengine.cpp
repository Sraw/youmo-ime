/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#include <algorithm>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <utility>

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

/** What a long press may offer, in the menu's order: the session's bit for it, the event it sends, its text. */
struct OfferAction {
    EngineOffer offer;
    EngineEvent event;
    const char *text, *domain;
};

const OfferAction offerActions[] = {
        // libime's table words it so; its pinyin says "Forget candidate"
        {OfferForget, EngineEvent::Forget, "Forget word", "fcitx5-chinese-addons"},
        {OfferPin, EngineEvent::Pin, "Pin to top as custom phrase", "fcitx5-chinese-addons"},
        {OfferUnpin, EngineEvent::Unpin, "Delete from custom phrase", "fcitx5-chinese-addons"},
        {OfferBlock, EngineEvent::Block, N_("Never show this word"), "fcitx5-android"},
};

/**
 * All the candidates of a snapshot, as a list that scrolls: those it came with, from the page's
 * first on, then the rest fetched from the session as the keyboard asks for them, those after a
 * chunk at a time and those before the page at once. A new snapshot replaces the list, so what is
 * fetched is always of the input shown. As a page it is the one the session shows: candidate(i)
 * is the (first + i)th of all.
 *
 * A long press on a candidate offers what the session says it may do with it, as libime's pinyin
 * and table did: "Forget word", and pinyin's custom phrases pinned or deleted. It is asked on the
 * press, not sent for each candidate with the snapshot: few are ever pressed.
 */
class EngineCandidateList : public CandidateList, public BulkCandidateList, public ActionableCandidateList,
                            public PageableCandidateList {
public:
    EngineCandidateList(AndroidEngine *engine, InputContext *ic, std::string im, const EngineSnapshot &snapshot)
            : engine_(engine), ic_(ic), im_(std::move(im)), total_(snapshot.total), actionable_(snapshot.actionable),
              composing_(!snapshot.preedit.empty()), offset_(std::max(snapshot.first, 0)) {
        setBulk(this);
        if (actionable_) setActionable(this);
        // pages turn only while composing, as PageUp and PageDown do (Keyboard): a prediction they drop
        if (composing_) setPageable(this);
        for (size_t i = 0; i < snapshot.candidates.size(); i++) {
            const auto &hint = i < snapshot.hints.size() ? snapshot.hints[i] : std::string();
            words_.push_back(std::make_unique<EngineCandidateWord>(engine_, im_, offset_ + static_cast<int>(i), snapshot.candidates[i], hint));
        }
        const int size = static_cast<int>(words_.size());
        shown_ = std::clamp(snapshot.shown, 0, size);
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
        return *words_[idx];
    }

    int size() const override { return shown_; }

    // what space commits while composing: the first of the page
    int cursorIndex() const override { return composing_ ? 0 : -1; }

    CandidateLayoutHint layoutHint() const override { return CandidateLayoutHint::NotSet; }

    const CandidateWord &candidateFromAll(int idx) const override {
        // a total of -1: the session has not counted them, so ask until it runs out
        if (idx < 0 || (total_ >= 0 && idx >= total_)) throw std::invalid_argument("invalid index");
        if (idx < offset_) {
            // asked once, whatever came back: a fetch that failed (none) is not made again for each index
            if (!fetchedBefore_) {
                fetchedBefore_ = true;
                auto earlier = engine_->fetch(im_, 0, offset_);
                for (auto &[text, hint]: earlier) {
                    before_.push_back(std::make_unique<EngineCandidateWord>(engine_, im_, static_cast<int>(before_.size()), text, hint));
                }
            }
            if (idx >= static_cast<int>(before_.size())) throw std::invalid_argument("invalid index");
            return *before_[idx];
        }
        while (offset_ + static_cast<int>(words_.size()) <= idx) {
            const int from = offset_ + static_cast<int>(words_.size());
            const int count = std::max(Chunk, idx + 1 - from);
            auto more = engine_->fetch(im_, from, count);
            if (static_cast<int>(more.size()) < count) total_ = from + static_cast<int>(more.size());
            for (auto &[text, hint]: more) {
                words_.push_back(std::make_unique<EngineCandidateWord>(engine_, im_, offset_ + static_cast<int>(words_.size()), text, hint));
            }
            // a short chunk was the last: none past it to ask for
            if (total_ >= 0 && idx >= total_) throw std::invalid_argument("invalid index");
        }
        return *words_[idx - offset_];
    }

    int totalSize() const override { return total_; }

    bool hasPrev() const override { return offset_ > 0; }

    bool hasNext() const override {
        const int after = offset_ + shown_;
        if (total_ >= 0) return after < total_;
        try {
            candidateFromAll(after);
            return true;
        } catch (const std::invalid_argument &) {
            return false;
        }
    }

    // as the keys turn it; the snapshot that comes back replaces this list
    void prev() override { engine_->send(ic_, im_, EngineEvent::PageUp); }

    void next() override { engine_->send(ic_, im_, EngineEvent::PageDown); }

    bool usedNextBefore() const override { return offset_ > 0; }

    bool hasAction(const CandidateWord &candidate) const override {
        return actionable_ && dynamic_cast<const EngineCandidateWord *>(&candidate);
    }

    std::vector<CandidateAction> candidateActions(const CandidateWord &candidate) const override {
        if (!hasAction(candidate)) return {};
        const auto &word = static_cast<const EngineCandidateWord &>(candidate);
        asked_ = &word;
        const int offers = engine_->offers(im_, word.index());
        std::vector<CandidateAction> actions;
        for (const auto &offered: offerActions) {
            if (!(offers & offered.offer)) continue;
            CandidateAction action;
            // the event it sends
            action.setId(static_cast<int>(offered.event));
            action.setText(D_(offered.domain, offered.text));
            actions.push_back(std::move(action));
        }
        // a menu shown holds the list still, its choice acted on by index; none, refining goes on
        if (!actions.empty()) engine_->stopRefining();
        return actions;
    }

    void triggerAction(const CandidateWord &candidate, int id) override {
        const auto *word = dynamic_cast<const EngineCandidateWord *>(&candidate);
        const auto event = static_cast<EngineEvent>(id);
        int offer = 0;
        for (const auto &offered: offerActions) {
            if (offered.event == event) offer = offered.offer;
        }
        // asked again: only what the session still offers for the candidate at that index, and
        // only on the one this list offered it for: a menu of a list since replaced names another
        if (!actionable_ || !word || word != asked_ || !offer || !(engine_->offers(im_, word->index()) & offer)) return;
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
    // what was typed, not a prediction (whose preedit is empty)
    bool composing_;
    // the index among all of words_[0], the page's first
    int offset_;
    int shown_;
    mutable std::vector<std::unique_ptr<EngineCandidateWord>> words_;
    // those before the page, from the first of all: fetched the first time one of them is asked for
    mutable std::vector<std::unique_ptr<EngineCandidateWord>> before_;
    mutable bool fetchedBefore_ = false;
    // as the keys that pick them: 1 to 9, then 0
    std::vector<Text> labels_;
    // the candidate whose actions were last asked of this list
    mutable const EngineCandidateWord *asked_ = nullptr;
};

/**
 * Shows [text] as what is typed, the cursor [cursor] code points into it; -1, or past its end, at
 * its end. ime-core's Snapshot.cursor counts UTF-16 units: the same while English, the one session
 * with a cursor of its own, types letters of the BMP alone.
 */
void showPreedit(InputContext *ic, const std::string &text, int cursor = -1) {
    auto &panel = ic->inputPanel();
    // in the app where it can show it, as libime's pinyin does by default (ComposingPinyin)
    const bool client = ic->capabilityFlags().test(CapabilityFlag::Preedit);
    Text preedit(text, client ? TextFormatFlag::Underline : TextFormatFlag::NoFlag);
    const auto chars = utf8::length(text);
    const auto at = cursor < 0 || static_cast<size_t>(cursor) >= chars ? text.size() : utf8::ncharByteLength(text.begin(), cursor);
    preedit.setCursor(static_cast<int>(at));
    if (client) {
        panel.setClientPreedit(preedit);
    } else {
        panel.setPreedit(preedit);
    }
}

void clearPanel(InputContext *ic) {
    ic->inputPanel().reset();
    ic->updatePreedit();
    ic->updateUserInterface(UserInterfaceComponent::InputPanel);
}

/** Commits a punctuation key's [text], and [after] it the other of a pair. */
void commitPunctuation(InputContext *ic, const std::string &text, const std::string &after) {
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
}

/** One of the marks a punctuation key with several cards offers: picked, it goes in as the key types it. */
class PunctuationCandidateWord : public CandidateWord {
public:
    PunctuationCandidateWord(std::string text, std::string after, bool half, AddonInstance *punctuation = nullptr,
                             uint32_t key = 0)
            : CandidateWord(Text(text + after)), text_(std::move(text)), after_(std::move(after)),
              punctuation_(punctuation), key_(key) {
        // the key's own character, as libime's pinyin and table marked it
        if (half) setComment(Text(D_("fcitx5-chinese-addons", "(Half)")));
    }

    void select(InputContext *inputContext) const override {
        // the half of a pair the key typed: the pair is opened or closed only now it goes in
        if (punctuation_) punctuation_->call<IPunctuation::pushPunctuationV2>("zh_CN", inputContext, key_);
        commitPunctuation(inputContext, text_, after_);
        // last: the list that holds this word goes with the panel
        clearPanel(inputContext);
    }

private:
    std::string text_;
    std::string after_;
    // the addon the key is pushed to, on the first entry of a pair typed a half at a time
    // (AndroidEngine::pushPunctuation); else null
    AddonInstance *punctuation_;
    uint32_t key_;
};

// a punctuation key's marks on the panel (AndroidEngine::pushPunctuation), till one goes in
class PunctuationCandidateList : public CommonCandidateList {};

/**
 * Ends the marks on offer, if the panel shows them: [take] commits the first, what the key typed
 * alone, else none goes in. Whether there were.
 */
bool endPunctuation(InputContext *ic, bool take) {
    // held: the pick clears the panel that holds the list
    const auto list = ic->inputPanel().candidateList();
    const auto *marks = dynamic_cast<const PunctuationCandidateList *>(list.get());
    if (!marks) return false;
    if (take) {
        marks->candidateFromAll(0).select(ic);
    } else {
        clearPanel(ic);
    }
    return true;
}

using TableOption = Option<EngineTableConfig> AndroidEngineConfig::*;

// the page of an input method's own options: one of config_'s keys, or its table's (Method::table)
enum class Page { Pinyin, Shuangpin, English, Table, Handwriting };

constexpr char EnglishName[] = "keyboard-us";

// the input methods, named as ime-core's Engines knows them
struct Method {
    const char *name, *label, *text, *icon, *language;
    Page page;
    // a table's options, in AndroidEngineConfig; null for pinyin, shuangpin and T9, whose options
    // are config_'s own keys
    TableOption table;
};

const Method methods[] = {
        {"engine-pinyin", "拼", "拼音", "fcitx-pinyin", "zh_CN", Page::Pinyin, nullptr},
        {"engine-shuangpin", "双", "双拼", "fcitx-shuangpin", "zh_CN", Page::Shuangpin, nullptr},
        {"engine-t9", "九", "九键拼音", "fcitx-pinyin", "zh_CN", Page::Pinyin, nullptr},
        {"engine-wubi", "五", "五笔", "fcitx-wubi", "zh_CN", Page::Table, &AndroidEngineConfig::wubi},
        {"engine-cangjie", "倉", "仓颉", "fcitx-cangjie", "zh_CN", Page::Table, &AndroidEngineConfig::cangjie},
        {"engine-ziranma", "自", "自然码", "fcitx-ziranma", "zh_CN", Page::Table, &AndroidEngineConfig::ziranma},
        {"engine-erbi", "二", "二笔", "fcitx-erbi", "zh_CN", Page::Table, &AndroidEngineConfig::erbi},
        {"engine-wubipinyin", "五", "五笔拼音", "fcitx-wubi", "zh_CN", Page::Table, &AndroidEngineConfig::wubiPinyin},
        {"engine-handwriting", "手", "手写", "draw-freehand", "zh_CN", Page::Handwriting, nullptr},
        // fcitx's own name for English, which it falls back to: a user's lists keep it
        {EnglishName, "En", "English", "input-keyboard", "en", Page::English, nullptr},
};

// the one of methods named [im]; null for any other
const Method *listed(const std::string &im) {
    for (const auto &m: methods) {
        if (im == m.name) return &m;
    }
    return nullptr;
}

// whether [im] types Chinese: Chinese marks for its punctuation keys, and the toggles of its text
// (traditional, Chinese marks, full width) in the status area; English types the keys' own
bool typesChinese(const std::string &im) {
    const auto *m = listed(im);
    return m && std::strcmp(m->language, "zh_CN") == 0;
}

// where AndroidEngineConfig keeps [im]'s options if it is one of the tables listed; else null
TableOption tableOf(const std::string &im) {
    const auto *m = listed(im);
    return m ? m->table : nullptr;
}

} // namespace

std::vector<InputMethodEntry> AndroidEngine::listInputMethods() {
    std::vector<InputMethodEntry> result;
    for (const auto &m: methods) {
        result.emplace_back(std::move(
                InputMethodEntry(m.name, m.text, m.language, "androidengine")
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

Option<EngineTableConfig> *AndroidEngine::table(const std::string &im) {
    const auto option = tableOf(im);
    return option ? &(config_.*option) : nullptr;
}

const Configuration *AndroidEngine::getConfigForInputMethod(const InputMethodEntry &entry) const {
    const auto &im = entry.uniqueName();
    if (const auto option = tableOf(im)) {
        return &(config_.*option).value();
    }
    const auto *m = listed(im);
    if (!m) return nullptr;
    RawConfig raw;
    config_.save(raw);
    if (m->page == Page::Shuangpin) {
        shuangpinPage_.load(raw, true);
        return &shuangpinPage_;
    }
    if (m->page == Page::English) {
        englishPage_.load(raw, true);
        return &englishPage_;
    }
    if (m->page == Page::Handwriting) {
        handwritingPage_.load(raw, true);
        return &handwritingPage_;
    }
    pinyinPage_.load(raw, true);
    return &pinyinPage_;
}

void AndroidEngine::setConfigForInputMethod(const InputMethodEntry &entry, const RawConfig &config) {
    const auto &im = entry.uniqueName();
    if (auto *option = table(im)) {
        EngineTableConfig value = option->value();
        value.load(config, true);
        option->setValue(value);
    } else if (listed(im)) {
        // the page's keys are config_'s own
        config_.load(config, true);
    } else {
        return;
    }
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
    // in the status area, these addons act on this input context: full-width punctuation and
    // letters, traditional characters. Chinese's alone: a 全角 left on in pinyin, which they share,
    // would make English's words and the keys it passes on (a password's too) full width
    if (typesChinese(entry.uniqueName())) {
        // loaded now, so their toggles are there to add
        punctuation();
        fullwidth();
        chttrans();
        for (const auto *name: {"chttrans", "punctuation", "fullwidth"}) {
            if (auto *action = instance_->userInterfaceManager().lookupAction(name)) {
                event.inputContext()->statusArea().addAction(StatusGroup::InputMethod, action);
            }
        }
    }
    // starts the session afresh, and loads its data now rather than on the first key
    send(event.inputContext(), entry.uniqueName(), EngineEvent::Reset);
}

bool AndroidEngine::pushPunctuation(InputContext *ic, const Key &key) {
    if (!punctuation() || key.isKeyPad()) return false;
    const auto unicode = Key::keySymToUnicode(key.sym());
    const auto marks = punctuation()->call<IPunctuation::getPunctuationCandidates>("zh_CN", unicode);
    auto [text, after] = punctuation()->call<IPunctuation::pushPunctuationV2>("zh_CN", ic, unicode);
    if (text.empty()) return false;
    if (marks.size() < 2) {
        commitPunctuation(ic, text, after);
        return true;
    }
    // a key with several cards offers their marks, as libime's pinyin and table did: first what
    // the key types alone, which goes in unless another is picked (endPunctuation)
    const auto typed = utf8::UCS4ToUTF8(unicode);
    const bool pair = !punctuation()->call<IPunctuation::getPunctuation>("zh_CN", unicode).second.empty();
    // a half typed alone has opened or closed the pair, which another card picked or Backspace
    // must not do: each push turns it over, so a second turns it back and the first entry pushes
    // again as it goes in
    const bool turns = pair && after.empty();
    if (turns) punctuation()->call<IPunctuation::pushPunctuationV2>("zh_CN", ic, unicode);
    auto list = std::make_unique<PunctuationCandidateList>();
    list->setPageSize(*config_.pageSize);
    list->append<PunctuationCandidateWord>(text, after, text + after == typed, turns ? punctuation() : nullptr, unicode);
    // that entry is the whole first card: the marks list a pair's two halves apart
    for (size_t i = pair ? 2 : 1; i < marks.size(); i++) {
        list->append<PunctuationCandidateWord>(marks[i], std::string(), marks[i] == typed);
    }
    list->setGlobalCursorIndex(0);
    ic->inputPanel().reset();
    showPreedit(ic, text + after);
    ic->inputPanel().setCandidateList(std::move(list));
    ic->updatePreedit();
    ic->updateUserInterface(UserInterfaceComponent::InputPanel);
    return true;
}

void AndroidEngine::keyEvent(const InputMethodEntry &entry, KeyEvent &event) {
    if (event.isRelease()) return;
    const auto &key = event.key();
    if (key.isModifier()) return;
    // any key ends a pause, shortcuts too: a refine landing after one would redraw a panel
    // someone else may have put up since
    refine_.reset();
    // shortcuts are the app's: a mark on offer goes in first, as before any other key, so that
    // what the app does (a paste) comes after it
    if (key.states().testAny(KeyStates{KeyState::Ctrl, KeyState::Alt, KeyState::Super, KeyState::Hyper})) {
        endPunctuation(event.inputContext(), true);
        return;
    }
    // a punctuation key's marks on offer: Backspace takes the key back, any other key takes the
    // first and goes on as typed, but for Space and Return below
    if (key.sym() == FcitxKey_BackSpace && endPunctuation(event.inputContext(), false)) {
        event.filterAndAccept();
        return;
    }
    // Space and Return pick the mark shown, as libime's did, and type nothing more: the first is
    // highlighted, and a mark that came with a space after it, or sent the message, would surprise
    const bool picks = key.sym() == FcitxKey_space || key.sym() == FcitxKey_Return || key.sym() == FcitxKey_KP_Enter;
    if (picks && endPunctuation(event.inputContext(), true)) {
        event.filterAndAccept();
        return;
    }
    endPunctuation(event.inputContext(), true);
    const auto &im = entry.uniqueName();
    const bool chinese = typesChinese(im);
    // English where it suggests nothing: the keys type themselves, what was typed going in first,
    // as typed (Enter commits it, and has the session forget the space after a word)
    if (!chinese && !suggestsEnglish(event.inputContext(), event.isVirtual())) {
        send(event.inputContext(), im, EngineEvent::Enter);
        return;
    }
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
                // which key, for a session to act on (libime moves within the input on an arrow)
                arg = static_cast<int>(key.sym());
            }
        }
    }
    // English's punctuation is the key's own, not a Chinese mark
    if (send(event.inputContext(), im, what, arg) ||
        (what == EngineEvent::Char && chinese && pushPunctuation(event.inputContext(), key))) {
        event.filterAndAccept();
    }
}

bool AndroidEngine::suggestsEnglish(InputContext *ic, bool virtualKey) const {
    // a hardware keyboard types as its keys, as the editor asking for no suggestions has it; and
    // an address, where a space after a word, or ". " for two, would break it
    return *config_.englishSuggest && virtualKey &&
           !ic->capabilityFlags().testAny(CapabilityFlags{CapabilityFlag::Password, CapabilityFlag::NoSpellCheck,
                                                          CapabilityFlag::Url, CapabilityFlag::Email});
}

bool AndroidEngine::send(InputContext *ic, std::string im, EngineEvent event, int arg) {
    if (!eventCallback_) return false;
    refine_.reset();
    const bool learning = !ic->capabilityFlags().testAny(CapabilityFlag::PasswordOrSensitive);
    const auto snapshot = eventCallback_(im, event, arg, learning);
    // a key may be on its way: the pause starts a little after it, the next slice at once
    if (snapshot.refines) refineLater(ic, im, event == EngineEvent::Refine ? 0 : PauseBeforeRefine);
    // a slice of refining that changed nothing leaves the panel as it is, the list scrolled; as
    // does a tap no session takes
    if ((event != EngineEvent::Refine && event != EngineEvent::Cursor) || snapshot.handled) show(ic, std::move(im), snapshot);
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
    // a key passed on with nothing on show before or after it, as each of English's where it
    // suggests nothing: no panel to reset, nor the app's to update
    if (!snapshot.handled && snapshot.deleteBefore <= 0 && snapshot.commit.empty() && snapshot.preedit.empty() &&
        snapshot.candidates.empty() && ic->inputPanel().empty()) {
        return;
    }
    // English's space after a word, put behind the punctuation typed next
    if (snapshot.deleteBefore > 0) ic->deleteSurroundingText(-snapshot.deleteBefore, snapshot.deleteBefore);
    if (!snapshot.commit.empty()) ic->commitString(snapshot.commit);
    auto &panel = ic->inputPanel();
    panel.reset();
    if (!snapshot.preedit.empty()) showPreedit(ic, snapshot.preedit, snapshot.cursor);
    if (!snapshot.candidates.empty()) panel.setCandidateList(std::make_unique<EngineCandidateList>(this, ic, std::move(im), snapshot));
    ic->updatePreedit();
    ic->updateUserInterface(UserInterfaceComponent::InputPanel);
}

void AndroidEngine::invokeActionImpl(const InputMethodEntry &entry, InvokeActionEvent &event) {
    // a tap in the composed text: a session keeping a cursor in its input (English) puts it there
    if (event.action() == InvokeActionEvent::Action::LeftClick && event.cursor() >= 0 &&
        send(event.inputContext(), entry.uniqueName(), EngineEvent::Cursor, event.cursor())) {
        event.filter();
        return;
    }
    InputMethodEngineV3::invokeActionImpl(entry, event);
}

void AndroidEngine::reset(const InputMethodEntry &entry, InputContextEvent &event) {
    send(event.inputContext(), entry.uniqueName(), EngineEvent::Reset);
}

void AndroidEngine::deactivate(const InputMethodEntry &entry, InputContextEvent &event) {
    // switching to another input method keeps what was typed, as libime's pinyin does; losing
    // focus drops it
    if (event.type() == EventType::InputContextSwitchInputMethod) {
        endPunctuation(event.inputContext(), true);
        send(event.inputContext(), entry.uniqueName(), EngineEvent::Enter);
    }
    reset(entry, event);
}

} // namespace fcitx

FCITX_ADDON_FACTORY(fcitx::AndroidEngineFactory)
