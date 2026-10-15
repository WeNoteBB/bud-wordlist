package com.bud.wordlist.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncConfig {

    @Bean(name = "generationExecutor")
    public Executor generationExecutor(WordlistProperties properties) {
        WordlistProperties.Async a = properties.async();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(a.corePoolSize());
        executor.setMaxPoolSize(a.maxPoolSize());
        executor.setQueueCapacity(a.queueCapacity());
        executor.setThreadNamePrefix("gen-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
