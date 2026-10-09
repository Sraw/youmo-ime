/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#include <algorithm>
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
class EngineCandidateList : public CandidateList, public BulkCandidateList, public ActionableCandidateList,
                            public PageableCandidateList {
public:
    EngineCandidateList(AndroidEngine *engine, InputContext *ic, std::string im, const EngineSnapshot &snapshot)
            : engine_(engine), ic_(ic), im_(std::move(im)), total_(snapshot.total), actionable_(snapshot.actionable),
              composing_(!snapshot.preedit.empty()) {
        setBulk(this);
        if (actionable_) setActionable(this);
        // pages turn only while composing, as PageUp and PageDown do (Keyboard): a prediction they drop
        if (composing_) setPageable(this);
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

    // what space commits while composing: the first of the page
    int cursorIndex() const override { return composing_ ? 0 : -1; }

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

    bool hasPrev() const override { return first_ > 0; }

    bool hasNext() const override {
        const int after = first_ + shown_;
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

    bool usedNextBefore() const override { return first_ > 0; }

    bool hasAction(const CandidateWord &candidate) const override {
        return actionable_ && dynamic_cast<const EngineCandidateWord *>(&candidate);
    }

    std::vector<CandidateAction> candidateActions(const CandidateWord &candidate) const override {
        if (!hasAction(candidate)) return {};
        engine_->stopRefining();
        const auto &word = static_cast<const EngineCandidateWord &>(candidate);
        asked_ = &word;
        const int offers = engine_->offers(im_, word.index());
        std::vector<CandidateAction> actions;
        auto add = [&](EngineOffer offer, EngineEvent event, const char *text, const char *domain = "fcitx5-chinese-addons") {
            if (!(offers & offer)) return;
            CandidateAction action;
            // the event it sends
            action.setId(static_cast<int>(event));
            action.setText(D_(domain, text));
            actions.push_back(std::move(action));
        };
        // libime's table words it so; its pinyin says "Forget candidate"
        add(OfferForget, EngineEvent::Forget, "Forget word");
        add(OfferPin, EngineEvent::Pin, "Pin to top as custom phrase");
        add(OfferUnpin, EngineEvent::Unpin, "Delete from custom phrase");
        add(OfferBlock, EngineEvent::Block, N_("Never show this word"), "fcitx5-android");
        return actions;
    }

    void triggerAction(const CandidateWord &candidate, int id) override {
        const auto *word = dynamic_cast<const EngineCandidateWord *>(&candidate);
        const auto event = static_cast<EngineEvent>(id);
        const int offer = event == EngineEvent::Forget ? OfferForget
                        : event == EngineEvent::Pin    ? OfferPin
                        : event == EngineEvent::Unpin  ? OfferUnpin
                        : event == EngineEvent::Block  ? OfferBlock
                                                       : 0;
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
    int first_;
    int shown_;
    mutable std::vector<std::unique_ptr<EngineCandidateWord>> words_;
    // as the keys that pick them: 1 to 9, then 0
    std::vector<Text> labels_;
    // the candidate whose actions were last asked of this list
    mutable const EngineCandidateWord *asked_ = nullptr;
};

/** Shows [text] as what is typed. */
void showPreedit(InputContext *ic, const std::string &text) {
    auto &panel = ic->inputPanel();
    // in the app where it can show it, as libime's pinyin does by default (ComposingPinyin)
    if (ic->capabilityFlags().test(CapabilityFlag::Preedit)) {
        Text preedit(text, TextFormatFlag::Underline);
        preedit.setCursor(static_cast<int>(text.size()));
        panel.setClientPreedit(preedit);
    } else {
        panel.setPreedit(Text(text));
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

// the input methods whose pages show config_'s own keys
bool ofConfig(const std::string &im) {
    return im == "engine-pinyin" || im == "engine-shuangpin" || im == "engine-t9";
}

// what the user set of a table they imported, under [Table]: as fcitx's table addon kept it, and
// where EngineBridge.userTable reads it
std::string userTableConf(const std::string &im) { return "table/" + im + ".conf"; }

/** The options of [im], a table the user imported, as the engine reads them (TableConf). */
EngineTableConfig userTable(const std::string &im) {
    // libime table's defaults, for what neither the table's .conf nor the user sets
    auto options = engineTable(false, EngineOrderPolicy::No, -1, -1);
    options.autoSelect.setValue(false);
    RawConfig conf;
    readAsIni(conf, StandardPathsType::PkgData, "inputmethod/" + im + ".conf");
    RawConfig user;
    readAsIni(user, userTableConf(im));
    for (const auto *raw: {&conf, &user}) {
        if (const auto table = raw->get("Table")) options.load(*table, true);
    }
    return options;
}

} // namespace

std::vector<InputMethodEntry> AndroidEngine::listInputMethods() {
    // names as ime-core's Engines knows them
    struct Method {
        const char *name, *label, *text, *icon;
    };
    static const Method methods[] = {
            {"engine-pinyin", "拼", "拼音", "fcitx-pinyin"},
            {"engine-shuangpin", "双", "双拼", "fcitx-shuangpin"},
            {"engine-t9", "九", "九键拼音", "fcitx-pinyin"},
            {"engine-wubi", "五", "五笔", "fcitx-wubi"},
            {"engine-cangjie", "倉", "仓颉", "fcitx-cangjie"},
            {"engine-ziranma", "自", "自然码", "fcitx-ziranma"},
            {"engine-erbi", "二", "二笔", "fcitx-erbi"},
            {"engine-wubipinyin", "五", "五笔拼音", "fcitx-wubi"},
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

Option<EngineTableConfig> *AndroidEngine::table(const std::string &im) {
    // as listInputMethods names them
    static const std::pair<const char *, Option<EngineTableConfig> AndroidEngineConfig::*> tables[] = {
            {"engine-wubi", &AndroidEngineConfig::wubi},
            {"engine-cangjie", &AndroidEngineConfig::cangjie},
            {"engine-ziranma", &AndroidEngineConfig::ziranma},
            {"engine-erbi", &AndroidEngineConfig::erbi},
            {"engine-wubipinyin", &AndroidEngineConfig::wubiPinyin},
    };
    for (const auto &[name, option]: tables) {
        if (im == name) return &(config_.*option);
    }
    return nullptr;
}

const Configuration *AndroidEngine::getConfigForInputMethod(const InputMethodEntry &entry) const {
    const auto &im = entry.uniqueName();
    if (auto *option = const_cast<AndroidEngine *>(this)->table(im)) {
        return &option->value();
    }
    if (!ofConfig(im)) {
        // filled as it is opened, as the pages below are
        static EngineTableConfig userTablePage;
        userTablePage = userTable(im);
        return &userTablePage;
    }
    RawConfig raw;
    config_.save(raw);
    if (im == "engine-shuangpin") {
        shuangpinPage_.load(raw, true);
        return &shuangpinPage_;
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
    } else if (ofConfig(im)) {
        // the page's keys are config_'s own
        config_.load(config, true);
    } else {
        // a table the user imported: in its own file, read as the engine loads the table again
        const auto shown = userTable(im);
        auto options = shown;
        options.load(config, true);
        RawConfig before;
        RawConfig after;
        shown.save(before);
        options.save(after);
        RawConfig user;
        readAsIni(user, userTableConf(im));
        auto &section = user["Table"];
        // what the page changed, written out: fcitx comments out an option's default, which
        // TableConf skips, so the table's .conf would win; one left alone stays as the engine
        // reads it, a value past the page's range (shown as the default) too
        for (const auto &name: after.subItems()) {
            const auto &value = after.get(name)->value();
            if (value != before.get(name)->value()) section.setValueByPath(name, value);
        }
        safeSaveAsIni(user, userTableConf(im));
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
    // shortcuts are the app's
    if (key.states().testAny(KeyStates{KeyState::Ctrl, KeyState::Alt, KeyState::Super, KeyState::Hyper})) return;
    // a punctuation key's marks on offer: Backspace takes the key back, any other key takes the
    // first and goes on as typed
    if (key.sym() == FcitxKey_BackSpace && endPunctuation(event.inputContext(), false)) {
        event.filterAndAccept();
        return;
    }
    endPunctuation(event.inputContext(), true);
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
    if (!snapshot.preedit.empty()) showPreedit(ic, snapshot.preedit);
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
        endPunctuation(event.inputContext(), true);
        send(event.inputContext(), entry.uniqueName(), EngineEvent::Enter);
    }
    reset(entry, event);
}

} // namespace fcitx

FCITX_ADDON_FACTORY(fcitx::AndroidEngineFactory)
