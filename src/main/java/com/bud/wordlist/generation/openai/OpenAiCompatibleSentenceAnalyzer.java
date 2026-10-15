package com.bud.wordlist.generation.openai;

import com.bud.wordlist.generation.GenerationException;
import com.bud.wordlist.generation.JsonExtractor;
import com.bud.wordlist.generation.SentenceAnalyzer;
import com.fasterxml.jackson.databind.JsonNode;

public class OpenAiCompatibleSentenceAnalyzer implements SentenceAnalyzer {

    private static final String SYSTEM = "你是英语学习助手，输出严格 JSON，不要输出任何多余文字。";

    private final OpenAiCompatibleClient client;

    public OpenAiCompatibleSentenceAnalyzer(OpenAiCompatibleClient client) {
        this.client = client;
    }

    @Override
    public String analyze(String sentence, String level) {
        String prompt = """
                请分析句子 "%s"（级别 %s），返回 JSON：{"analysis": "逐句分析：语法、重点词汇、结构说明"}。
                """.formatted(sentence, level);
        String content = client.chatJson(SYSTEM, prompt);
        JsonNode node = JsonExtractor.parseObject(content);
        JsonNode a = node.get("analysis");
        if (a == null || a.isNull() || a.asText().isBlank()) {
            throw new GenerationException("analysis 缺失");
        }
        return a.asText();
    }
}
