#pragma once
#include <fcntl.h>
#include <unistd.h>
#include <utility>
namespace fcitx {
class UnixFD {
public:
    UnixFD() = default;
    explicit UnixFD(int fd) : fd_(fd) {}
    UnixFD(UnixFD &&o) noexcept : fd_(std::exchange(o.fd_, -1)) {}
    UnixFD &operator=(UnixFD &&o) noexcept { std::swap(fd_, o.fd_); return *this; }
    ~UnixFD() { if (fd_ >= 0) ::close(fd_); }
    bool isValid() const { return fd_ >= 0; }
    int fd() const { return fd_; }
private:
    int fd_ = -1;
};
} // namespace fcitx
