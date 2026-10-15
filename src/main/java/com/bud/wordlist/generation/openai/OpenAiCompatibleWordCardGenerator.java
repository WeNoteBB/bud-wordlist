package com.bud.wordlist.generation.openai;

import com.bud.wordlist.generation.GenerationException;
import com.bud.wordlist.generation.JsonExtractor;
import com.bud.wordlist.generation.WordCard;
import com.bud.wordlist.generation.WordCardGenerator;
import com.fasterxml.jackson.databind.JsonNode;

public class OpenAiCompatibleWordCardGenerator implements WordCardGenerator {

    private static final String SYSTEM = "你是英语学习助手，输出严格 JSON，不要输出任何多余文字。";

    private final OpenAiCompatibleClient client;

    public OpenAiCompatibleWordCardGenerator(OpenAiCompatibleClient client) {
        this.client = client;
    }

    @Override
    public WordCard generate(String word, String level) {
        String prompt = """
                请为单词 "%s"（级别 %s）生成一张学习卡片，返回 JSON，字段：
                meaning(中文意思), phonetic(音标), pos(词性), mnemonic(助记),
                example(例句), phrase(常用词组)。example 缺失视为失败。
                """.formatted(word, level);
        String content = client.chatJson(SYSTEM, prompt);
        JsonNode node = JsonExtractor.parseObject(content);
        String meaning = text(node, "meaning");
        String example = text(node, "example");
        if (meaning == null || example == null) {
            throw new GenerationException("meaning/example 缺失");
        }
        return new WordCard(
                meaning,
                text(node, "phonetic"),
                text(node, "pos"),
                text(node, "mnemonic"),
                example,
                text(node, "phrase"));
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
