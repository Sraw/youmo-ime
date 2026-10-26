#pragma once
// Only fs::safeRead, which charselectdata.cpp uses (fcitx-utils/fs.h:54).
#include <sys/types.h>
#include <unistd.h>
#include <cerrno>
#include <cstddef>
namespace fcitx::fs {
inline ssize_t safeRead(int fd, void *data, size_t maxlen) {
    size_t done = 0;
    while (done < maxlen) {
        ssize_t r = ::read(fd, static_cast<char *>(data) + done, maxlen - done);
        if (r < 0 && errno == EINTR) continue;
        if (r <= 0) break;
        done += r;
    }
    return static_cast<ssize_t>(done);
}
} // namespace fcitx::fs
