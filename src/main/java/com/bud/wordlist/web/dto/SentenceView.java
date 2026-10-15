package com.bud.wordlist.web.dto;

import java.time.LocalDateTime;

public record SentenceView(
        long sentenceId,
        String hash,
        String sentence,
        String sentenceTranslation,
        String analysis,
        int status,
        String sourceTitle,
        LocalDateTime collectedAt) {
}
