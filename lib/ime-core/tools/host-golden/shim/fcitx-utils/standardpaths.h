#pragma once
// fcitx's StandardPaths reduced to what the quick phrase and charselect loaders call, over the
// directories in $GOLDEN_PKGDATA (colon separated, the user's first, as fcitx scans PkgData).
// open() and locate() follow fcitx-utils/standardpaths.cpp:166-229: the first directory holding a
// name wins; locate() maps file names to paths, sorted by name (std::map).
#include <cstdlib>
#include <filesystem>
#include <functional>
#include <map>
#include <sstream>
#include <string>
#include <vector>
#include "fcitx-utils/unixfd.h"
namespace fcitx {
enum class StandardPathsType { PkgData };
namespace pathfilter {
static inline auto extension(const std::string &ext) {
    return [ext](const std::filesystem::path &path) { return path.extension() == ext; };
}
} // namespace pathfilter
class StandardPaths {
public:
    static const StandardPaths &global() { static StandardPaths p; return p; }
    UnixFD open(StandardPathsType, const std::filesystem::path &path) const {
        for (const auto &d : dirs_) {
            auto full = d / path;
            if (std::filesystem::exists(full)) return openPath(full);
        }
        return UnixFD();
    }
    std::map<std::filesystem::path, std::filesystem::path>
    locate(StandardPathsType, const std::filesystem::path &path,
           const std::function<bool(const std::filesystem::path &)> &callback) const {
        std::map<std::filesystem::path, std::filesystem::path> ret;
        for (const auto &d : dirs_) {
            std::error_code ec;
            for (auto it = std::filesystem::directory_iterator(d / path, ec);
                 it != std::filesystem::directory_iterator(); it.increment(ec)) {
                if (ec) break;
                if (ret.contains(it->path().filename())) continue;
                if (callback(it->path())) ret[it->path().filename()] = it->path();
            }
        }
        return ret;
    }
    static UnixFD openPath(const std::filesystem::path &p) { return UnixFD(::open(p.c_str(), O_RDONLY)); }
private:
    StandardPaths() {
        const char *env = std::getenv("GOLDEN_PKGDATA");
        std::stringstream ss(env ? env : "");
        std::string item;
        while (std::getline(ss, item, ':')) if (!item.empty()) dirs_.emplace_back(item);
    }
    std::vector<std::filesystem::path> dirs_;
};
} // namespace fcitx
