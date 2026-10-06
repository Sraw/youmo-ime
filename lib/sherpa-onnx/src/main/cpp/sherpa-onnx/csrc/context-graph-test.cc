// sherpa-onnx/csrc/context-graph-test.cc
//
// Copyright (c)  2023  Xiaomi Corporation

#include "sherpa-onnx/csrc/context-graph.h"

#include <chrono>
#include <cmath>
#include <map>
#include <random>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "gtest/gtest.h"
#include "sherpa-onnx/csrc/macros.h"

namespace sherpa_onnx {

static void TestHelper(const std::map<std::string, float> &queries, float score,
                       bool strict_mode) {
  std::vector<std::string> contexts_str(
      {"S", "HE", "SHE", "SHELL", "HIS", "HERS", "HELLO", "THIS", "THEM"});
  std::vector<std::vector<int32_t>> contexts;
  std::vector<float> scores;
  for (int32_t i = 0; i < contexts_str.size(); ++i) {
    contexts.emplace_back(contexts_str[i].begin(), contexts_str[i].end());
    scores.push_back(std::round(score / contexts_str[i].size() * 100) / 100);
  }
  auto context_graph = ContextGraph(contexts, 1, scores);

  for (const auto &iter : queries) {
    float total_scores = 0;
    auto state = context_graph.Root();
    for (auto q : iter.first) {
      auto res = context_graph.ForwardOneStep(state, q, strict_mode);
      total_scores += std::get<0>(res);
      state = std::get<1>(res);
    }
    auto res = context_graph.Finalize(state);
    EXPECT_EQ(res.second->token, -1);
    total_scores += res.first;
    EXPECT_EQ(total_scores, iter.second);
  }
}

TEST(ContextGraph, TestBasic) {
  auto queries = std::map<std::string, float>{
      {"HEHERSHE", 14}, {"HERSHE", 12}, {"HISHE", 9},
      {"SHED", 6},      {"SHELF", 6},   {"HELL", 2},
      {"HELLO", 7},     {"DHRHISQ", 4}, {"THEN", 2}};
  TestHelper(queries, 0, true);
}

TEST(ContextGraph, TestBasicNonStrict) {
  auto queries = std::map<std::string, float>{
      {"HEHERSHE", 7}, {"HERSHE", 5}, {"HISHE", 5},   {"SHED", 3}, {"SHELF", 3},
      {"HELL", 2},     {"HELLO", 2},  {"DHRHISQ", 3}, {"THEN", 2}};
  TestHelper(queries, 0, false);
}

TEST(ContextGraph, TestCustomize) {
  auto queries = std::map<std::string, float>{
      {"HEHERSHE", 35.84}, {"HERSHE", 30.84},  {"HISHE", 24.18},
      {"SHED", 18.34},     {"SHELF", 18.34},   {"HELL", 5},
      {"HELLO", 13},       {"DHRHISQ", 10.84}, {"THEN", 5}};
  TestHelper(queries, 5, true);
}

TEST(ContextGraph, TestCustomizeNonStrict) {
  auto queries = std::map<std::string, float>{
      {"HEHERSHE", 20}, {"HERSHE", 15},    {"HISHE", 10.84},
      {"SHED", 10},     {"SHELF", 10},     {"HELL", 5},
      {"HELLO", 5},     {"DHRHISQ", 5.84}, {"THEN", 5}};
  TestHelper(queries, 5, false);
}

TEST(ContextGraph, Benchmark) {
  std::random_device rd;
  std::mt19937 mt(rd());
  std::uniform_int_distribution<int32_t> char_dist(0, 25);
  std::uniform_int_distribution<int32_t> len_dist(3, 8);
  for (int32_t num = 10; num <= 10000; num *= 10) {
    std::vector<std::vector<int32_t>> contexts;
    for (int32_t i = 0; i < num; ++i) {
      std::vector<int32_t> tmp;
      int32_t word_len = len_dist(mt);
      for (int32_t j = 0; j < word_len; ++j) {
        tmp.push_back(char_dist(mt));
      }
      contexts.push_back(std::move(tmp));
    }
    auto start = std::chrono::high_resolution_clock::now();
    auto context_graph = ContextGraph(contexts, 1);
    auto stop = std::chrono::high_resolution_clock::now();
    auto duration =
        std::chrono::duration_cast<std::chrono::microseconds>(stop - start);
    SHERPA_ONNX_LOGE("Construct context graph for %d item takes %d us.", num,
                     static_cast<int32_t>(duration.count()));
  }
}

// youmo: CompletingTokens is exactly the tokens on which ForwardOneStep, in
// strict mode as the blocked phrases are followed, reaches a matched phrase:
// checked from every state a random walk passes, over the whole alphabet, with
// phrases that are suffixes and prefixes of one another
TEST(ContextGraph, CompletingTokensAreThoseThatMatch) {
  std::mt19937 rng(7);
  std::uniform_int_distribution<int32_t> letter(0, 4);
  for (int32_t round = 0; round != 50; ++round) {
    std::vector<std::vector<int32_t>> phrases;
    for (int32_t p = 0; p != 6; ++p) {
      std::vector<int32_t> phrase(1 + rng() % 4);
      for (auto &t : phrase) t = letter(rng);
      phrases.push_back(phrase);
    }
    ContextGraph graph(phrases, 0.0f);
    auto state = graph.Root();
    for (int32_t step = 0; step != 200; ++step) {
      std::set<int32_t> expected;
      for (int32_t t = 0; t != 5; ++t) {
        if (std::get<2>(graph.ForwardOneStep(state, t, true)) != nullptr) {
          expected.insert(t);
        }
      }
      const auto &got = graph.CompletingTokens(state);
      EXPECT_EQ(std::set<int32_t>(got.begin(), got.end()), expected);
      EXPECT_EQ(got.size(), expected.size());  // each token once
      state = std::get<1>(graph.ForwardOneStep(state, letter(rng), true));
    }
  }
}

TEST(ContextGraph, CompletingTokensOfASuffix) {
  // "CAB" and "AB": after "XA" (no C), B still completes "AB"; after "CA", B
  // completes "CAB"; after "C" nothing completes
  std::vector<std::vector<int32_t>> phrases = {{'C', 'A', 'B'}, {'A', 'B'}};
  ContextGraph graph(phrases, 0.0f);
  auto walk = [&](const std::string &s) {
    auto state = graph.Root();
    for (auto c : s) state = std::get<1>(graph.ForwardOneStep(state, c, true));
    return graph.CompletingTokens(state);
  };
  EXPECT_EQ(walk("XA"), std::vector<int32_t>{'B'});
  EXPECT_EQ(walk("CA"), std::vector<int32_t>{'B'});
  EXPECT_TRUE(walk("C").empty());
  EXPECT_TRUE(walk("").empty());
}

}  // namespace sherpa_onnx
