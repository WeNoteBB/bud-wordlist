package com.bud.wordlist.service;

import com.bud.wordlist.common.Level;
import com.bud.wordlist.config.WordlistProperties;
import com.bud.wordlist.generation.GenerationException;
import com.bud.wordlist.generation.RetryPolicy;
import com.bud.wordlist.generation.SentenceAnalyzer;
import com.bud.wordlist.generation.WordCard;
import com.bud.wordlist.generation.WordCardGenerator;
import com.bud.wordlist.repository.SentenceRepository;
import com.bud.wordlist.repository.WordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** 生成任务的投递与写回。dispatch 方法必须立即返回，AI 调用在线程池里跑。 */
@Service
public class AsyncGenerationDispatcher implements GenerationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AsyncGenerationDispatcher.class);

    private final WordRepository wordRepository;
    private final SentenceRepository sentenceRepository;
    private final WordCardGenerator wordCardGenerator;
    private final SentenceAnalyzer sentenceAnalyzer;
    private final Executor executor;
    private final RetryPolicy retryPolicy;

    public AsyncGenerationDispatcher(WordRepository wordRepository,
                                     SentenceRepository sentenceRepository,
                                     WordCardGenerator wordCardGenerator,
                                     SentenceAnalyzer sentenceAnalyzer,
                                     @Qualifier("generationExecutor") Executor executor,
                                     WordlistProperties properties) {
        this.wordRepository = wordRepository;
        this.sentenceRepository = sentenceRepository;
        this.wordCardGenerator = wordCardGenerator;
        this.sentenceAnalyzer = sentenceAnalyzer;
        this.executor = executor;
        this.retryPolicy = new RetryPolicy(properties.llm().maxAttempts(), 1000L);
    }

    @Override
    public void dispatchWord(Level level, long wordId, String word) {
        submit(() -> generateWord(level, wordId, word), "word=" + word);
    }

    @Override
    public void dispatchSentence(Level level, long sentenceId, String sentence) {
        submit(() -> generateSentence(level, sentenceId, sentence), "sentenceId=" + sentenceId);
    }

    private void submit(Runnable task, String label) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("生成队列已满，任务放弃投递，等待补偿任务处理: {}", label);
        }
    }

    private void generateWord(Level level, long wordId, String word) {
        try {
            WordCard card = retryPolicy.execute(() -> wordCardGenerator.generate(word, level.code()));
            if (card == null) {
                // provider=none：不做生成，保持 status=0（预期行为）
                log.debug("生成器返回 null（provider=none），保持 status=0: id={}", wordId);
                return;
            }
            int affected = wordRepository.updateContentIfGenerating(level, wordId, card);
            if (affected == 0) {
                log.debug("写回被跳过（记录已处理）: level={} id={}", level, wordId);
            }
        } catch (GenerationException e) {
            log.error("单词生成最终失败: level={} id={} word={}", level, wordId, word, e);
            wordRepository.markFailed(level, wordId);
        } catch (RuntimeException e) {
            log.error("单词生成出现未预期异常: level={} id={} word={}", level, wordId, word, e);
            wordRepository.markFailed(level, wordId);
        }
    }

    private void generateSentence(Level level, long sentenceId, String sentence) {
        try {
            String analysis = retryPolicy.execute(() -> sentenceAnalyzer.analyze(sentence, level.code()));
            if (analysis == null) {
                // provider=none：不做生成，保持 status=0
                log.debug("分析器返回 null（provider=none），保持 status=0: id={}", sentenceId);
                return;
            }
            sentenceRepository.updateAnalysisIfGenerating(level, sentenceId, analysis);
        } catch (GenerationException e) {
            log.error("句子分析最终失败: level={} id={}", level, sentenceId, e);
            sentenceRepository.markFailed(level, sentenceId);
        } catch (RuntimeException e) {
            log.error("句子分析出现未预期异常: level={} id={}", level, sentenceId, e);
            sentenceRepository.markFailed(level, sentenceId);
        }
    }
}
