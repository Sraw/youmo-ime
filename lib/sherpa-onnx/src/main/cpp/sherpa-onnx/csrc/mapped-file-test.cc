// sherpa-onnx/csrc/mapped-file-test.cc
//
// youmo: a model file that cannot be mapped throws (the app's voice input then
// says so), not _Exit (the keyboard's process gone).
#include "sherpa-onnx/csrc/mapped-file.h"

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
  EXPECT_THROW(MapFile(testing::TempDir() + "no-such-model.ort"), std::runtime_error);
  // empty: nothing to map
  std::string path = testing::TempDir() + "mapped-file-empty.ort";
  FILE *f = fopen(path.c_str(), "wb");
  ASSERT_NE(f, nullptr);
  fclose(f);
  EXPECT_THROW(MapFile(path), std::runtime_error);
  remove(path.c_str());
}

}  // namespace sherpa_onnx
