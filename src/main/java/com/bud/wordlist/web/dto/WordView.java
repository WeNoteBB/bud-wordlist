package com.bud.wordlist.web.dto;

import java.time.LocalDateTime;

public record WordView(
        long wordId,
        String word,
        String sentenceHash,
        String sentence,
        String sentenceTranslation,
        int status,
        String meaning,
        String phonetic,
        String pos,
        String mnemonic,
        String example,
        String phrase,
        String sourceTitle,
        LocalDateTime collectedAt) {
}
