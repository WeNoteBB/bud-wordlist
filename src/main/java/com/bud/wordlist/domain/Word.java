package com.bud.wordlist.domain;

/** 词表行。sentenceHash 关联同级别句子表的 hash。 */
public record Word(
        Long id,
        String word,
        String meaning,
        String phonetic,
        String pos,
        String mnemonic,
        String example,
        String phrase,
        String sentenceHash,
        int status,
        int source,
        int retryCount,
        java.time.LocalDateTime createdAt,
        java.time.LocalDateTime updatedAt) {
}
