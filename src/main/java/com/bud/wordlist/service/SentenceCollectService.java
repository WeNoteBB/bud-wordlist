package com.bud.wordlist.service;

import com.bud.wordlist.common.BizException;
import com.bud.wordlist.common.ErrorCode;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.domain.Sentence;
import com.bud.wordlist.repository.SentenceRepository;
import com.bud.wordlist.repository.UserSentenceRepository;
import com.bud.wordlist.web.dto.CollectSentenceRequest;
import com.bud.wordlist.web.dto.SentenceView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class SentenceCollectService {

    private final SentenceRepository sentenceRepository;
    private final UserSentenceRepository userSentenceRepository;
    private final WordNormalizer normalizer;
    private final GenerationDispatcher dispatcher;

    public SentenceCollectService(SentenceRepository sentenceRepository,
                                  UserSentenceRepository userSentenceRepository,
                                  WordNormalizer normalizer,
                                  GenerationDispatcher dispatcher) {
        this.sentenceRepository = sentenceRepository;
        this.userSentenceRepository = userSentenceRepository;
        this.normalizer = normalizer;
        this.dispatcher = dispatcher;
    }

    public record CollectResult(SentenceView view, boolean newlyCollected) {
    }

    @Transactional
    public CollectResult collect(CollectSentenceRequest req) {
        if (req.userId() <= 0) {
            throw new BizException(ErrorCode.INVALID_USER, "userId 非法");
        }
        Level level = Level.fromCode(req.level())
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_LEVEL, "级别不合法: " + req.level()));
        if (!normalizer.isValidSentence(req.sentence())) {
            throw new BizException(ErrorCode.INVALID_SENTENCE, "句子不合法");
        }
        String hash = normalizer.sha256Hex(normalizer.sentence(req.sentence()));

        long sentenceId = findOrCreate(level, hash, req.sentence(), req.sentenceTranslation());
        boolean newlyCollected = userSentenceRepository.insert(
                req.userId(), level, sentenceId, req.sourceType(), req.sourceId(), req.sourceTitle());

        SentenceView view = buildView(level, sentenceId, req.sourceTitle());
        return new CollectResult(view, newlyCollected);
    }

    private long findOrCreate(Level level, String hash, String sentence, String translation) {
        Optional<Sentence> existing = sentenceRepository.findByHash(level, hash);
        if (existing.isPresent()) {
            return existing.get().id();
        }
        try {
            long id;
            if (translation != null && !translation.isBlank()) {
                id = sentenceRepository.insertSentence(level, hash, sentence.trim(), translation);
            } else {
                id = sentenceRepository.insertSentenceForAnalysis(level, hash, sentence.trim());
                dispatcher.dispatchSentence(level, id, sentence.trim());
            }
            return id;
        } catch (DuplicateKeyException e) {
            return sentenceRepository.findByHash(level, hash)
                    .orElseThrow(() -> new IllegalStateException("并发插入后查不到记录"))
                    .id();
        }
    }

    private SentenceView buildView(Level level, long sentenceId, String sourceTitle) {
        Sentence s = sentenceRepository.findById(level, sentenceId).orElseThrow();
        return new SentenceView(s.id(), s.hash(), s.sentence(), s.sentenceTranslation(),
                s.analysis(), s.status(), sourceTitle, LocalDateTime.now());
    }
}
