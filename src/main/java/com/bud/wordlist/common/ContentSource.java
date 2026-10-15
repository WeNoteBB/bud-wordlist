package com.bud.wordlist.common;

public enum ContentSource {
    AI(1), COPY(2), MANUAL(3);

    private final int code;

    ContentSource(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
