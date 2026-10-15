package com.bud.wordlist.common;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 级别枚举。表名唯一来源，任何 SQL 的表名都必须取自这里，
 * 禁止把请求参数直接拼进 SQL。
 */
public enum Level {

    KET("ket", "ket_word", "ket_sentence"),
    PET("pet", "pet_word", "pet_sentence"),
    ZHONGKAO("zhongkao", "zhongkao_word", "zhongkao_sentence"),
    IELTS("ielts", "ielts_word", "ielts_sentence");

    private final String code;
    private final String wordTable;
    private final String sentenceTable;

    Level(String code, String wordTable, String sentenceTable) {
        this.code = code;
        this.wordTable = wordTable;
        this.sentenceTable = sentenceTable;
    }

    public String code() {
        return code;
    }

    public String wordTable() {
        return wordTable;
    }

    public String sentenceTable() {
        return sentenceTable;
    }

    private static final Map<String, Level> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Level::code, Function.identity()));

    public static Optional<Level> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_CODE.get(code.trim().toLowerCase(Locale.ROOT)));
    }
}
