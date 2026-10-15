package com.bud.wordlist.generation.openai;

import com.bud.wordlist.generation.GenerationException;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 调用 OpenAI 兼容的 /chat/completions。单个实现覆盖 DeepSeek / 通义 / GLM / Kimi。
 */
public class OpenAiCompatibleClient {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;
    private final int timeoutSeconds;

    public OpenAiCompatibleClient(RestClient restClient, String apiKey, String model, int timeoutSeconds) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 返回模型返回的 content 文本；任何 HTTP/解析失败都抛 GenerationException */
    public String chatJson(String systemPrompt, String userPrompt) {
        Map<String, Object> body = Map.of(
                "model", model,
                "response_format", Map.of("type", "json_object"),
                "messages", java.util.List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)));
        try {
            String json = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            if (json == null) {
                throw new GenerationException("LLM 返回为空");
            }
            return parseContent(json);
        } catch (GenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new GenerationException("LLM 调用失败: " + e.getMessage(), e);
        }
    }

    private String parseContent(String json) {
        // 只做最轻量的解析：取 message.content。更复杂的容错由 JsonExtractor 完成。
        int idx = json.indexOf("\"content\":");
        if (idx < 0) {
            throw new GenerationException("json 缺少 content");
        }
        int start = json.indexOf('"', idx + "\"content\":".length());
        int end = json.indexOf('"', start + 1);
        if (start < 0 || end < 0) {
            throw new GenerationException("content 解析失败");
        }
        String content = json.substring(start + 1, end);
        return unescape(content);
    }

    private String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
    }
}
