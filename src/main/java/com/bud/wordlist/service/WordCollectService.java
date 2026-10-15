package com.bud.wordlist.service;

import com.bud.wordlist.common.BizException;
import com.bud.wordlist.common.ErrorCode;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.domain.Sentence;
import com.bud.wordlist.domain.Word;
import com.bud.wordlist.repository.SentenceRepository;
import com.bud.wordlist.repository.UserWordRepository;
import com.bud.wordlist.repository.WordRepository;
import com.bud.wordlist.web.dto.CollectWordRequest;
import com.bud.wordlist.web.dto.WordView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class WordCollectService {

    private final WordRepository wordRepository;
    private final SentenceRepository sentenceRepository;
    private final UserWordRepository userWordRepository;
    private final CopyResolver copyResolver;
    private final WordNormalizer normalizer;
    private final GenerationDispatcher dispatcher;

    public WordCollectService(WordRepository wordRepository,
                              SentenceRepository sentenceRepository,
                              UserWordRepository userWordRepository,
                              CopyResolver copyResolver,
                              WordNormalizer normalizer,
                              GenerationDispatcher dispatcher) {
        this.wordRepository = wordRepository;
        this.sentenceRepository = sentenceRepository;
        this.userWordRepository = userWordRepository;
        this.copyResolver = copyResolver;
        this.normalizer = normalizer;
        this.dispatcher = dispatcher;
    }

    public record CollectResult(WordView view, boolean newlyCollected) {
    }

    @Transactional
    public CollectResult collect(CollectWordRequest req) {
        if (req.userId() <= 0) {
            throw new BizException(ErrorCode.INVALID_USER, "userId 非法");
        }
        Level level = Level.fromCode(req.level())
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_LEVEL, "级别不合法: " + req.level()));
        if (!normalizer.isValidWord(req.word())) {
            throw new BizException(ErrorCode.INVALID_WORD, "单词不合法");
        }
        String normalized = normalizer.word(req.word());

        // 句子：按 hash 去重，存在则复用，不存在则存入句子表
        String sentenceHash = ensureSentence(level, req.sentence(), req.sentenceTranslation());

        // 单词：查重 / 跨级别拷贝 / 新建（处理并发重复）
        long wordId = findOrCreateWord(level, normalized, sentenceHash);

        // 若本次带句子，把句子关联到单词（记忆上下文）
        if (sentenceHash != null) {
            wordRepository.updateSentenceHash(level, wordId, sentenceHash);
        }

        boolean newlyCollected = userWordRepository.insert(
                req.userId(), level, wordId, req.sourceType(), req.sourceId(), req.sourceTitle());

        WordView view = buildView(level, wordId, sentenceHash, req.sourceTitle());
        return new CollectResult(view, newlyCollected);
    }

    private long findOrCreateWord(Level level, String normalized, String sentenceHash) {
        Optional<Word> existing = wordRepository.findByWord(level, normalized);
        if (existing.isPresent()) {
            return existing.get().id();
        }
        Optional<Long> copied = tryCopy(level, normalized);
        if (copied.isPresent()) {
            return copied.get();
        }
        try {
            long id = wordRepository.insertWord(level, normalized, sentenceHash);
            dispatcher.dispatchWord(level, id, normalized);
            return id;
        } catch (DuplicateKeyException e) {
            // 并发下已被别的请求建好，重新取 id，不重复投递
            return wordRepository.findByWord(level, normalized)
                    .orElseThrow(() -> new IllegalStateException("并发插入后查不到记录"))
                    .id();
        }
    }

    private Optional<Long> tryCopy(Level level, String word) {
        for (Level source : copyResolver.sourcesFor(level)) {
            Optional<Word> src = wordRepository.findByWord(source, word);
            if (src.isPresent()) {
                try {
                    return Optional.of(wordRepository.insertCopiedWord(level, word, src.get()));
                } catch (DuplicateKeyException e) {
                    return wordRepository.findByWord(level, word)
                            .map(Word::id);
                }
            }
        }
        return Optional.empty();
    }

    /** 句子去重：返回 hash；若未提供句子返回 null。 */
    private String ensureSentence(Level level, String sentence, String translation) {
        if (sentence == null || sentence.isBlank()) {
            return null;
        }
        if (!normalizer.isValidSentence(sentence)) {
            throw new BizException(ErrorCode.INVALID_SENTENCE, "句子不合法");
        }
        String hash = normalizer.sha256Hex(normalizer.sentence(sentence));
        if (sentenceRepository.findByHash(level, hash).isPresent()) {
            return hash; // 句子已存在，不用管
        }
        try {
            sentenceRepository.insertSentence(level, hash, sentence.trim(), translation);
        } catch (DuplicateKeyException e) {
            // 并发下已被其他请求存入，复用
        }
        return hash;
    }

    private WordView buildView(Level level, long wordId, String sentenceHash, String sourceTitle) {
        Word w = wordRepository.findById(level, wordId).orElseThrow();
        String hash = w.sentenceHash() != null ? w.sentenceHash() : sentenceHash;
        String sentence = null;
        String translation = null;
        if (hash != null) {
            Optional<Sentence> s = sentenceRepository.findByHash(level, hash);
            if (s.isPresent()) {
                sentence = s.get().sentence();
                translation = s.get().sentenceTranslation();
            }
        }
        return new WordView(
                w.id(), w.word(), hash, sentence, translation,
                w.status(), w.meaning(), w.phonetic(), w.pos(),
                w.mnemonic(), w.example(), w.phrase(), sourceTitle, LocalDateTime.now());
    }
}
