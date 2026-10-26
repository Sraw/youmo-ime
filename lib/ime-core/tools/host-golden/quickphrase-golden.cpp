// Runs fcitx's own BuiltInQuickPhraseProvider (populate, reloadConfig, load: lines 29-104 of
// modules/quickphrase/quickphraseprovider.cpp, pasted in by build.sh unchanged) over the PkgData
// directories in $GOLDEN_PKGDATA and prints, for each input on stdin (one per line, "\t"-escaped
// as \t, "\\" as \\), what the quick phrase list gets in order:
//   "Q\t<input>\t<count>" then "C\t<key>\t<value>" per candidate (value escaped the same way)
#include <cstdio>
#include <functional>
#include <iostream>
#include <map>
#include <string>
#include <string_view>
#include "fcitx-utils/fdstreambuf.h"
#include "fcitx-utils/macros.h"
#include "fcitx-utils/standardpaths.h"
#include "fcitx-utils/stringutils.h"
#include "fcitx-utils/utf8.h"

namespace fcitx {
class InputContext;
enum class QuickPhraseAction { Commit, TypeToBuffer, DigitSelection, AlphaSelection, NoneSelection, DoNothing, AutoCommit };
using QuickPhraseAddCandidateCallbackV2 = std::function<void(
    const std::string &, const std::string &, const std::string &, QuickPhraseAction action)>;
class BuiltInQuickPhraseProvider {
public:
    bool populate(InputContext *ic, const std::string &userInput,
                  const QuickPhraseAddCandidateCallbackV2 &addCandidate);
    void reloadConfig();
private:
    void load(int fd);
    std::multimap<std::string, std::string> map_;
};
#include "provider-body.inc"
} // namespace fcitx

static std::string escape(const std::string &s) {
    std::string r;
    for (char c : s) {
        if (c == '\\') r += "\\\\";
        else if (c == '\t') r += "\\t";
        else if (c == '\n') r += "\\n";
        else r += c;
    }
    return r;
}

static std::string unescape(const std::string &s) {
    std::string r;
    for (size_t i = 0; i < s.size(); i++) {
        if (s[i] == '\\' && i + 1 < s.size()) {
            i++;
            r += s[i] == 't' ? '\t' : s[i] == 'n' ? '\n' : s[i];
        } else {
            r += s[i];
        }
    }
    return r;
}

int main() {
    fcitx::BuiltInQuickPhraseProvider provider;
    provider.reloadConfig();
    std::string line;
    while (std::getline(std::cin, line)) {
        auto input = unescape(line);
        std::vector<std::pair<std::string, std::string>> got;
        provider.populate(nullptr, input,
                          [&got](const std::string &word, const std::string &, const std::string &comment,
                                 fcitx::QuickPhraseAction) { got.emplace_back(comment, word); });
        std::printf("Q\t%s\t%zu\n", escape(input).c_str(), got.size());
        for (auto &[k, v] : got) std::printf("C\t%s\t%s\n", escape(k).c_str(), escape(v).c_str());
    }
    return 0;
}
