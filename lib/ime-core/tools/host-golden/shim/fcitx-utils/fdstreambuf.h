#pragma once
// A read-only streambuf over a file descriptor, standing in for fcitx's IFDStreamBuf.
#include <streambuf>
#include <unistd.h>
namespace fcitx {
class IFDStreamBuf : public std::streambuf {
public:
    explicit IFDStreamBuf(int fd) : fd_(fd) {}
protected:
    int_type underflow() override {
        ssize_t n = ::read(fd_, buf_, sizeof(buf_));
        if (n <= 0) return traits_type::eof();
        setg(buf_, buf_, buf_ + n);
        return traits_type::to_int_type(buf_[0]);
    }
private:
    int fd_;
    char buf_[4096];
};
} // namespace fcitx
