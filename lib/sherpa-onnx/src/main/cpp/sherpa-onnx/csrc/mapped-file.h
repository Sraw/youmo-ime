// sherpa-onnx/csrc/mapped-file.h
//
// youmo: a model file mapped where it lies (a resource pack's), not read onto
// the heap.
#ifndef SHERPA_ONNX_CSRC_MAPPED_FILE_H_
#define SHERPA_ONNX_CSRC_MAPPED_FILE_H_

#include <cstddef>
#include <memory>
#include <string>

namespace sherpa_onnx {

// A file mapped (MAP_PRIVATE, read only), unmapped when freed.
struct MappedFile {
  void *data = nullptr;
  size_t size = 0;
  MappedFile() = default;
  MappedFile(const MappedFile &) = delete;
  MappedFile &operator=(const MappedFile &) = delete;
  ~MappedFile();
};

// [filename] mapped. One that cannot be (gone, empty, the mapping refused)
// throws std::runtime_error, not SHERPA_ONNX_EXIT: in the app that ended the
// keyboard's process; thrown, the JNI layer hands Java an exception and voice
// input says it cannot load.
std::unique_ptr<MappedFile> MapFile(const std::string &filename);

}  // namespace sherpa_onnx

#endif  // SHERPA_ONNX_CSRC_MAPPED_FILE_H_
