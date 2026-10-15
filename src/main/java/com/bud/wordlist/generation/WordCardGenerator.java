package com.bud.wordlist.generation;

/** 生成单词卡片，失败抛 GenerationException */
public interface WordCardGenerator {
    WordCard generate(String word, String level);
}
