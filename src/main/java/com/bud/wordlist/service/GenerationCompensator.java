package com.bud.wordlist.service;

import com.bud.wordlist.common.Level;
import com.bud.wordlist.config.WordlistProperties;
import com.bud.wordlist.domain.Sentence;
import com.bud.wordlist.domain.Word;
import com.bud.wordlist.repository.SentenceRepository;
import com.bud.wordlist.repository.WordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 补偿定时任务：捞起疑似丢失(status=0 超时)或待重试(status=2)的记录，
 * 先做带条件原子更新，成功才重新投递。provider=none 时整任务关闭。
 */
@Component
public class GenerationCompensator {

    private static final Logger log = LoggerFactory.getLogger(GenerationCompensator.class);

    private final WordRepository wordRepository;
    private final SentenceRepository sentenceRepository;
    private final GenerationDispatcher dispatcher;
    private final WordlistProperties properties;

    public GenerationCompensator(WordRepository wordRepository,
                                 SentenceRepository sentenceRepository,
                                 GenerationDispatcher dispatcher,
                                 WordlistProperties properties) {
        this.wordRepository = wordRepository;
        this.sentenceRepository = sentenceRepository;
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Scheduled(cron = "${wordlist.async.compensate-cron:0 */5 * * * ?}")
    public void compensate() {
        if (!properties.async().compensateEnabled()) {
            return;
        }
        int maxAttempts = properties.llm().maxAttempts();
        int stale = properties.async().staleThresholdSeconds();
        for (Level level : Level.values()) {
            compensateWords(level, maxAttempts, stale);
            compensateSentences(level, maxAttempts, stale);
        }
    }

    private void compensateWords(Level level, int maxAttempts, int stale) {
        List<Word> candidates = wordRepository.findStaleOrFailed(level, stale, maxAttempts);
        for (Word w : candidates) {
            int affected = wordRepository.resetToGeneratingIfRetryable(level, w.id(), maxAttempts);
            if (affected == 1) {
                dispatcher.dispatchWord(level, w.id(), w.word());
            }
        }
    }

    private void compensateSentences(Level level, int maxAttempts, int stale) {
        List<Sentence> candidates = sentenceRepository.findStaleOrFailed(level, stale, maxAttempts);
        for (Sentence s : candidates) {
            int affected = sentenceRepository.resetToGeneratingIfRetryable(level, s.id(), maxAttempts);
            if (affected == 1) {
                dispatcher.dispatchSentence(level, s.id(), s.sentence());
            }
        }
    }
}
