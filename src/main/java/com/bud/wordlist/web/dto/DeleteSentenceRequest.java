package com.bud.wordlist.web.dto;

public record DeleteSentenceRequest(long userId, String level, long sentenceId) {
}