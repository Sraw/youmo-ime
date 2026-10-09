// sherpa-onnx/csrc/utils-test.cc
//
// youmo: EncodeBase leaves a line with a token not in the table out whole,
// and every line after it keeps its own ids, score, phrase and threshold

#include "sherpa-onnx/csrc/utils.h"

#include <sstream>
#include <string>
#include <vector>

#include "gtest/gtest.h"
#include "sherpa-onnx/csrc/symbol-table.h"

namespace sherpa_onnx {

static const char kTokens[] = "<blk> 0\n开 1\n饭 2\n";

// 龘 is not in the table: shortened to 开, its line would boost another word
TEST(EncodeHotwords, ALineWithAMissingTokenIsLeftOutWhole) {
  SymbolTable table(std::string(kTokens), false);
  std::istringstream is("开饭 :2.0\n开龘 :3.0\n饭 :4.0\n");
  std::vector<std::vector<int32_t>> ids;
  std::vector<float> scores;
  EXPECT_FALSE(EncodeHotwords(is, "cjkchar", table, nullptr, &ids, &scores));
  EXPECT_EQ(ids, (std::vector<std::vector<int32_t>>{{1, 2}, {2}}));
  EXPECT_EQ(scores, (std::vector<float>{2.0f, 4.0f}));
}

TEST(EncodeKeywords, ALineWithAMissingTokenIsLeftOutWhole) {
  SymbolTable table(std::string(kTokens), false);
  std::istringstream is(
      "开 饭 :2.0 #0.3 @开饭\n开 龘 :3.0 #0.4 @开龘\n饭 :4.0 #0.5 @饭\n");
  std::vector<std::vector<int32_t>> ids;
  std::vector<std::string> phrases;
  std::vector<float> scores;
  std::vector<float> thresholds;
  EXPECT_FALSE(
      EncodeKeywords(is, table, &ids, &phrases, &scores, &thresholds));
  EXPECT_EQ(ids, (std::vector<std::vector<int32_t>>{{1, 2}, {2}}));
  EXPECT_EQ(phrases, (std::vector<std::string>{"开饭", "饭"}));
  EXPECT_EQ(scores, (std::vector<float>{2.0f, 4.0f}));
  EXPECT_EQ(thresholds, (std::vector<float>{0.3f, 0.5f}));
}

}  // namespace sherpa_onnx
