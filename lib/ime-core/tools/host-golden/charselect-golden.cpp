// Runs fcitx's own CharSelectData (modules/unicode/charselectdata.cpp, compiled as is) over the
// queries on stdin, one per line, and prints what the Unicode temp mode gets from it:
//   find <query>  ->  "F\t<query>\t<hex code points, comma separated, in find()'s order>"
//   names         ->  "N\t<hex>\t<name>" for every code point 0..10FFFF
// Names come out untranslated (the i18n stub below returns the msgid).
#include <cstdio>
#include <iostream>
#include <string>
#include "charselectdata.h"
#include "fcitx-utils/i18n.h"

namespace fcitx {
const char *translateDomain(const char *, const char *s) { return s; }
std::string translateDomain(const char *, const std::string &s) { return s; }
} // namespace fcitx

int main() {
    CharSelectData data;
    if (!data.load()) {
        std::cerr << "cannot load unicode/charselectdata under $GOLDEN_PKGDATA\n";
        return 1;
    }
    std::string line;
    while (std::getline(std::cin, line)) {
        if (line == "names") {
            for (uint32_t c = 0; c <= 0x10FFFF; c++) {
                std::printf("N\t%X\t%s\n", c, data.name(c).c_str());
            }
            continue;
        }
        if (line.rfind("find ", 0) != 0) continue;
        auto query = line.substr(5);
        std::string out;
        for (auto c : data.find(query)) {
            if (!out.empty()) out += ',';
            char buf[16];
            std::snprintf(buf, sizeof buf, "%X", c);
            out += buf;
        }
        std::printf("F\t%s\t%s\n", query.c_str(), out.c_str());
    }
    return 0;
}
