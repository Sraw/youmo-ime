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

// youmo: the streaming decoder ranks and scores a hypothesis as Finalize()
// ends the offline one's: a phrase begun but not finished gives back its score,
// a finished one keeps it. Non-strict, as the beam search follows hotwords:
// "JD" begins seven phrases, "QK" is one of its own, half as boosted a token;
// "DX" lies inside the seven's path, so a walk through them reaches it by an
// output link
TEST(ContextGraph, FinalizedLogProbGivesBackAnUnfinishedPrefix) {
  std::vector<std::vector<int32_t>> phrases = {{'Q', 'K'}, {'D', 'X'}};
  std::vector<float> scores = {0.5f, 0.75f};
  for (int32_t c = 'A'; c != 'H'; ++c) {
    phrases.push_back({'J', 'D', 'X', c});
    scores.push_back(1.0f);
  }
  ContextGraph graph(phrases, 1.0f, scores);
  auto walk = [&](const std::string &s, float *sum) {
    auto state = graph.Root();
    *sum = 0;
    for (auto c : s) {
      auto res = graph.ForwardOneStep(state, c, false);
      *sum += std::get<0>(res);
      state = std::get<1>(res);
    }
    return state;
  };
  float jd = 0, qk = 0;
  auto jd_state = walk("JD", &jd);
  auto qk_state = walk("QK", &qk);
  EXPECT_FLOAT_EQ(jd, 2.0f);  // a prefix's bonus, ahead of the finished word
  EXPECT_FLOAT_EQ(qk, 1.0f);
  EXPECT_FLOAT_EQ(FinalizedLogProb(-5.0 + jd, jd_state), -5.0);
  EXPECT_FLOAT_EQ(FinalizedLogProb(-5.0 + qk, qk_state), -4.0);
  EXPECT_DOUBLE_EQ(FinalizedLogProb(-5.0, nullptr), -5.0);  // no hotwords

  // "JDX" completes "DX" by the output link and starts again at the root
  float jdx = 0;
  EXPECT_EQ(walk("JDX", &jdx), graph.Root());
  EXPECT_FLOAT_EQ(FinalizedLogProb(jdx, graph.Root()), 1.5f);

  // the trie's own node for `s` (from the root by `next` alone), or none
  auto node = [&](const std::string &s) -> const ContextState * {
    const ContextState *n = graph.Root();
    for (auto c : s) {
      auto it = n->next.find(c);
      if (it == n->next.end()) return nullptr;
      n = it->second.get();
    }
    return n;
  };
  // every walk, finished or not: as offline (the sum and Finalize()), and what
  // is left is the scores of the phrases it completed and nothing else. A
  // non-strict walk completes, at each token, the phrase that is the longest
  // suffix since its last completion to be in the trie, or else the longest
  // phrase that is a suffix of that one (its output link), then starts again
  std::mt19937 rng(11);
  const std::string letters = "JDQKXAB";
  for (int32_t round = 0; round != 500; ++round) {
    std::string s(1 + rng() % 8, ' ');
    for (auto &c : s) c = letters[rng() % letters.size()];
    float sum = 0;
    auto state = walk(s, &sum);
    EXPECT_FLOAT_EQ(FinalizedLogProb(sum, state),
                    sum + graph.Finalize(state).first)
        << s;

    float completed = 0;
    size_t from = 0;
    for (size_t end = 1; end <= s.size(); ++end) {
      std::string deepest;
      for (size_t b = from; b < end && deepest.empty(); ++b) {
        if (node(s.substr(b, end - b))) deepest = s.substr(b, end - b);
      }
      for (size_t b = 0; b < deepest.size(); ++b) {
        auto n = node(deepest.substr(b));
        if (n != nullptr && n->is_end) {
          completed += n->node_score;
          from = end;
          break;
        }
      }
    }
    EXPECT_NEAR(FinalizedLogProb(sum, state), completed, 1e-4) << s;
  }
}

}  // namespace sherpa_onnx
