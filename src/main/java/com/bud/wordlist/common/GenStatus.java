package com.bud.wordlist.common;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public enum GenStatus {
    GENERATING(0), DONE(1), FAILED(2);

    private final int code;

    GenStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    private static final Map<Integer, GenStatus> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(GenStatus::code, Function.identity()));

    public static Optional<GenStatus> fromCode(int code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }
}
