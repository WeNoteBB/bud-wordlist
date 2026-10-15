package com.bud.wordlist.generation;

/** 生成句子分析，失败抛 GenerationException */
public interface SentenceAnalyzer {
    String analyze(String sentence, String level);
}
