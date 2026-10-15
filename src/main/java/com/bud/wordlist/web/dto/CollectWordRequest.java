package com.bud.wordlist.web.dto;

/** 收录单词。sentence/sentenceTranslation 可选：把单词放进句子里记忆。 */
public record CollectWordRequest(
        long userId,
        String level,
        String word,
        String sentence,
        String sentenceTranslation,
        Integer sourceType,
        Long sourceId,
        String sourceTitle) {
}
