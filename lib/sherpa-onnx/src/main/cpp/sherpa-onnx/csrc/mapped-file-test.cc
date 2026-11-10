// sherpa-onnx/csrc/mapped-file-test.cc
//
// youmo: a model file that cannot be mapped throws (the app's voice input then
// says so), not _Exit (the keyboard's process gone).
#include "sherpa-onnx/csrc/mapped-file.h"

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>

#include "gtest/gtest.h"

namespace sherpa_onnx {

TEST(MappedFile, AFileIsMappedAsItIs) {
  std::string path = testing::TempDir() + "mapped-file-test.ort";
  FILE *f = fopen(path.c_str(), "wb");
  ASSERT_NE(f, nullptr);
  fputs("model", f);
  fclose(f);
  auto file = MapFile(path);
  ASSERT_EQ(file->size, 5u);
  EXPECT_EQ(memcmp(file->data, "model", 5), 0);
  remove(path.c_str());
}

TEST(MappedFile, OneThatCannotBeMappedThrows) {
  std::string missing = testing::TempDir() + "no-such-model.ort";
  try {
    MapFile(missing);
    ADD_FAILURE() << "a missing file mapped";
  } catch (const std::runtime_error &e) {
    // the path and why (strerror's ENOENT text) are in the message
    std::string what = e.what();
    EXPECT_NE(what.find(missing), std::string::npos) << what;
    EXPECT_NE(what.find(strerror(ENOENT)), std::string::npos) << what;
  }
  // empty: nothing to map
  std::string path = testing::TempDir() + "mapped-file-empty.ort";
  FILE *f = fopen(path.c_str(), "wb");
  ASSERT_NE(f, nullptr);
  fclose(f);
  try {
    MapFile(path);
    ADD_FAILURE() << "an empty file mapped";
  } catch (const std::runtime_error &e) {
    std::string what = e.what();
    ASSERT_GE(what.size(), 7u);
    EXPECT_EQ(what.substr(what.size() - 7), ": empty") << what;
  }
  remove(path.c_str());
}

}  // namespace sherpa_onnx
