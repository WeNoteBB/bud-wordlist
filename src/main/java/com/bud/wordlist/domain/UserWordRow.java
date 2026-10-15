package com.bud.wordlist.domain;

import java.time.LocalDateTime;

public record UserWordRow(
        Long id,
        long userId,
        String level,
        long wordId,
        Integer sourceType,
        Long sourceId,
        String sourceTitle,
        LocalDateTime createdAt) {
}
