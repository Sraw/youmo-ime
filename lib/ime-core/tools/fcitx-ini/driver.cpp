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
static Option<EngineTableConfig> *table(AndroidEngineConfig &c, const std::string &g) {
    if (g == "Wubi") return &c.wubi; if (g == "Cangjie") return &c.cangjie; if (g == "Ziranma") return &c.ziranma;
    if (g == "Erbi") return &c.erbi; if (g == "WubiPinyin") return &c.wubiPinyin; return nullptr;
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
            if (auto *t = table(config, group)) { EngineTableConfig v = t->value(); v.load(page, true); t->setValue(v); }
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
            else { EngineTablePage p; p.load(all, true); RawConfig own; table(config, k)->value().save(own); p.load(own, true); p.save(out); }
        }
        if (nodesc) clear(out);
        writeAsIni(out, std::cout);
    }
}
