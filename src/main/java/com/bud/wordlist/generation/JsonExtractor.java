package com.bud.wordlist.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 容错 JSON 解析：剥离 markdown 围栏、截取首个 { 到末个 } 的子串。
 */
public final class JsonExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonExtractor() {
    }

    public static JsonNode parseObject(String raw) {
        if (raw == null) {
            throw new GenerationException("json 为空");
        }
        String text = raw;
        int first = text.indexOf('{');
        int last = text.lastIndexOf('}');
        if (first < 0 || last < 0 || last < first) {
            throw new GenerationException("json 中没有对象");
        }
        text = text.substring(first, last + 1);
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new GenerationException("json 解析失败", e);
        }
    }
}
