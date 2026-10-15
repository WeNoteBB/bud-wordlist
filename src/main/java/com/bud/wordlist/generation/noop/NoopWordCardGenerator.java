package com.bud.wordlist.generation.noop;

import com.bud.wordlist.generation.WordCard;
import com.bud.wordlist.generation.WordCardGenerator;

/** 降级态：不做生成。调用方不会真正触发它（补偿任务关闭）。 */
public class NoopWordCardGenerator implements WordCardGenerator {
    @Override
    public WordCard generate(String word, String level) {
        return null;
    }
}
