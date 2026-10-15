package com.bud.wordlist.generation.noop;

import com.bud.wordlist.generation.SentenceAnalyzer;

/** 降级态：不做生成。 */
public class NoopSentenceAnalyzer implements SentenceAnalyzer {
    @Override
    public String analyze(String sentence, String level) {
        return null;
    }
}
