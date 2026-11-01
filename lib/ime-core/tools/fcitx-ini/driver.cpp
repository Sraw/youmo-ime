// fcitx's own config code run on the host over androidengine's option table, the oracle the Kotlin
// EngineOptions and IniFile are held to (gen.py writes its outputs as golden files). build.sh puts
// androidengine.h's option table where the marker is.
#include <iostream>
#include <sstream>
#include <fcitx-config/configuration.h>
#include <fcitx-config/enum.h>
#include <fcitx-config/iniparser.h>
#include <fcitx-config/rawconfig.h>
#include <fcitx-utils/i18n.h>
// @OPTIONS@
} // namespace fcitx
using namespace fcitx;

static void clear(RawConfig &c) { c.setComment(""); for (auto &n : c.subItems()) clear(*c.get(n)); }
// [f] over table group [g]'s option and the page type that shows it; false for no table
template <typename F> static bool table(AndroidEngineConfig &c, const std::string &g, F f) {
    if (g == "Wubi") return f(c.wubi, EngineLetterTablePage{}), true;
    if (g == "Cangjie") return f(c.cangjie, EngineNamedTablePage{}), true;
    if (g == "Ziranma") return f(c.ziranma, EngineTablePage{}), true;
    if (g == "Erbi") return f(c.erbi, EngineTablePage{}), true;
    if (g == "WubiPinyin") return f(c.wubiPinyin, EngineLetterTablePage{}), true;
    return false;
}
// stdin: an INI file, read as the addon reads it at start (readAsIni: a full load).
// then each "page <group> <ini>": a page's partial save (setConfigForInputMethod); <group> a table's or "-".
// then "save": the file as safeSaveAsIni writes it; or "show <pinyin|shuangpin|english|handwriting|Group>": that page
// (getConfigForInputMethod); "nodesc" first: description comments left out.
int main(int argc, char **argv) {
    std::stringstream in; in << std::cin.rdbuf();
    RawConfig raw; readFromIni(raw, in);
    AndroidEngineConfig config; config.load(raw);
    bool nodesc = false;
    for (int i = 1; i < argc; i++) {
        std::string a = argv[i];
        if (a == "nodesc") { nodesc = true; continue; }
        if (a == "page") {
            std::string group = argv[++i]; std::stringstream p(argv[++i]);
            RawConfig page; readFromIni(page, p);
            table(config, group, [&](auto &t, auto) { auto v = t.value(); v.load(page, true); t.setValue(v); });
            config.load(page, true);
            continue;
        }
        RawConfig out;
        if (a == "save") config.save(out);
        else if (a == "show") {
            std::string k = argv[++i]; RawConfig all; config.save(all);
            if (k == "pinyin") { EnginePinyinPage p; p.load(all, true); p.save(out); }
            else if (k == "shuangpin") { EngineShuangpinPage p; p.load(all, true); p.save(out); }
            else if (k == "english") { EngineEnglishPage p; p.load(all, true); p.save(out); }
            else if (k == "handwriting") { EngineHandwritingPage p; p.load(all, true); p.save(out); }
            else table(config, k, [&](auto &t, auto p) { p.load(all, true); RawConfig own; t.value().save(own); p.load(own, true); p.save(out); });
        }
        if (nodesc) clear(out);
        writeAsIni(out, std::cout);
    }
}
