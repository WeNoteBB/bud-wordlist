package com.bud.wordlist.config;

import com.bud.wordlist.generation.SentenceAnalyzer;
import com.bud.wordlist.generation.WordCardGenerator;
import com.bud.wordlist.generation.noop.NoopSentenceAnalyzer;
import com.bud.wordlist.generation.noop.NoopWordCardGenerator;
import com.bud.wordlist.generation.openai.OpenAiCompatibleClient;
import com.bud.wordlist.generation.openai.OpenAiCompatibleSentenceAnalyzer;
import com.bud.wordlist.generation.openai.OpenAiCompatibleWordCardGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 按 wordlist.llm.provider 装配生成器。
 * openai-compatible：DeepSeek/通义/GLM 等，一个实现覆盖多家。
 * none（默认）：降级态，不做生成。
 * （claude 依赖 anthropic-java，本环境未启用，需时再接入。）
 */
@Configuration
public class LlmConfig {

    @Bean
    @ConditionalOnProperty(name = "wordlist.llm.provider", havingValue = "openai-compatible")
    public OpenAiCompatibleClient openAiCompatibleClient(WordlistProperties properties) {
        WordlistProperties.Llm llm = properties.llm();
        return new OpenAiCompatibleClient(
                RestClient.builder().baseUrl(llm.baseUrl()).build(),
                llm.apiKey(),
                llm.model(),
                llm.timeoutSeconds());
    }

    @Bean
    @ConditionalOnProperty(name = "wordlist.llm.provider", havingValue = "openai-compatible")
    public WordCardGenerator openAiWordCardGenerator(OpenAiCompatibleClient client) {
        return new OpenAiCompatibleWordCardGenerator(client);
    }

    @Bean
    @ConditionalOnProperty(name = "wordlist.llm.provider", havingValue = "openai-compatible")
    public SentenceAnalyzer openAiSentenceAnalyzer(OpenAiCompatibleClient client) {
        return new OpenAiCompatibleSentenceAnalyzer(client);
    }

    @Bean
    @ConditionalOnProperty(name = "wordlist.llm.provider", havingValue = "none", matchIfMissing = true)
    public WordCardGenerator noopWordCardGenerator() {
        return new NoopWordCardGenerator();
    }

    @Bean
    @ConditionalOnProperty(name = "wordlist.llm.provider", havingValue = "none", matchIfMissing = true)
    public SentenceAnalyzer noopSentenceAnalyzer() {
        return new NoopSentenceAnalyzer();
    }
}
