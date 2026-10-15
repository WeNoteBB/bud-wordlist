package com.bud.wordlist.domain;

import java.time.LocalDateTime;

public record UserSentenceRow(
        Long id,
        long userId,
        String level,
        long sentenceId,
        Integer sourceType,
        Long sourceId,
        String sourceTitle,
        LocalDateTime createdAt) {
}
