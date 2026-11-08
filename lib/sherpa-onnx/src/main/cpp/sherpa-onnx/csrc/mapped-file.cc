// sherpa-onnx/csrc/mapped-file.cc
//
// youmo: see mapped-file.h
#include "sherpa-onnx/csrc/mapped-file.h"

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <stdexcept>

namespace sherpa_onnx {

MappedFile::~MappedFile() {
  if (data) munmap(data, size);
}

std::unique_ptr<MappedFile> MapFile(const std::string &filename) {
  auto file = std::make_unique<MappedFile>();
  int fd = open(filename.c_str(), O_RDONLY | O_CLOEXEC);
  struct stat st {};
  if (fd >= 0 && fstat(fd, &st) == 0 && st.st_size > 0) {
    file->size = static_cast<size_t>(st.st_size);
    void *data = mmap(nullptr, file->size, PROT_READ, MAP_PRIVATE, fd, 0);
    if (data != MAP_FAILED) file->data = data;
  }
  if (fd >= 0) close(fd);
  if (!file->data) throw std::runtime_error("cannot map '" + filename + "'");
  return file;
}

}  // namespace sherpa_onnx
