package com.bud.wordlist.web.dto;

public record CollectSentenceRequest(
        long userId,
        String level,
        String sentence,
        String sentenceTranslation,
        Integer sourceType,
        Long sourceId,
        String sourceTitle) {
}
