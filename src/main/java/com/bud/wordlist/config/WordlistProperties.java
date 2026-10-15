package com.bud.wordlist.config;

import com.bud.wordlist.common.Level;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "wordlist")
public record WordlistProperties(Copy copy, Llm llm, Async async) {

    public record Copy(boolean enabled, Map<Level, List<Level>> chain) {
        public Copy {
            chain = chain == null ? Map.of() : Map.copyOf(chain);
        }
    }

    public record Llm(String provider,
                      String model,
                      String baseUrl,
                      String apiKey,
                      int timeoutSeconds,
                      int maxAttempts) {
        public Llm {
            if (provider == null) {
                provider = "none";
            }
            if (maxAttempts <= 0) {
                maxAttempts = 3;
            }
            if (timeoutSeconds <= 0) {
                timeoutSeconds = 30;
            }
        }
    }

    public record Async(int corePoolSize,
                        int maxPoolSize,
                        int queueCapacity,
                        boolean compensateEnabled,
                        String compensateCron,
                        int staleThresholdSeconds) {
        public Async {
            if (compensateCron == null || compensateCron.isBlank()) {
                compensateCron = "0 */5 * * * ?";
            }
            if (staleThresholdSeconds <= 0) {
                staleThresholdSeconds = 120;
            }
        }
    }
}
