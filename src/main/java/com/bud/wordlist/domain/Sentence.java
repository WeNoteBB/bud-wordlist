package com.bud.wordlist.domain;

/** 句表行。hash 为规范化原句的 SHA-256（小写 hex），全表唯一。 */
public record Sentence(
        Long id,
        String hash,
        String sentence,
        String sentenceTranslation,
        String analysis,
        int status,
        int source,
        int retryCount,
        java.time.LocalDateTime createdAt,
        java.time.LocalDateTime updatedAt) {
}
