package com.bud.wordlist.web.dto;

public record DeleteWordRequest(long userId, String level, long wordId) {
}