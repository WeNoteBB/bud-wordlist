package com.bud.wordlist.generation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 重试策略：总尝试次数 maxAttempts（含首次），
 * 退避间隔 baseDelayMs 起按 1s/4s 递增，叠加 ±20% 随机抖动。
 * 只重试可重试错误，其余直接抛出。
 */
public class RetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);

    private final int maxAttempts;
    private final long baseDelayMs;

    public RetryPolicy(int maxAttempts, long baseDelayMs) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseDelayMs = baseDelayMs;
    }

    public <T> T execute(Supplier<T> call) {
        int attempt = 1;
        while (true) {
            try {
                return call.get();
            } catch (Exception e) {
                boolean retryable = isRetryable(e);
                if (!retryable || attempt >= maxAttempts) {
                    if (e instanceof GenerationException ge) {
                        throw ge;
                    }
                    throw new GenerationException("生成失败", e);
                }
                long delay = backoff(attempt);
                log.warn("生成调用失败，第 {} 次重试，{}ms 后重试: {}", attempt, delay, e.getMessage());
                sleep(delay);
                attempt++;
            }
        }
    }

    private long backoff(int attempt) {
        long base = baseDelayMs * (1L << (attempt - 1));
        double jitter = 1.0 + ThreadLocalRandom.current().nextDouble(-0.2, 0.2);
        return Math.max(1, (long) (base * jitter));
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new GenerationException("重试等待被中断", ie);
        }
    }

    /** 只重试：超时、连接失败、429、5xx、JSON 解析失败 */
    private boolean isRetryable(Exception e) {
        if (e instanceof GenerationException ge) {
            return ge.getMessage() != null
                    && (ge.getMessage().contains("timeout")
                    || ge.getMessage().contains("connect")
                    || ge.getMessage().contains("json")
                    || ge.getMessage().contains("429")
                    || ge.getMessage().contains("5"));
        }
        return true;
    }
}
