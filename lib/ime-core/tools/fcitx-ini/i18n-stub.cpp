// gettext left out: the descriptions as written, untranslated
#include <fcitx-utils/i18n.h>
namespace fcitx {
std::string translate(const std::string &s) { return s; }
const char *translate(const char *s) { return s; }
std::string translateDomain(const char *, const std::string &s) { return s; }
const char *translateDomain(const char *, const char *s) { return s; }
}
