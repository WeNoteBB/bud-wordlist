package com.bud.wordlist.web;

import com.bud.wordlist.common.ApiResponse;
import com.bud.wordlist.common.BizException;
import com.bud.wordlist.common.ErrorCode;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.domain.Sentence;
import com.bud.wordlist.domain.Word;
import com.bud.wordlist.repository.SentenceRepository;
import com.bud.wordlist.repository.WordRepository;
import com.bud.wordlist.web.dto.WordView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/word")
public class WordBankController {

    private final WordRepository wordRepository;
    private final SentenceRepository sentenceRepository;

    public WordBankController(WordRepository wordRepository, SentenceRepository sentenceRepository) {
        this.wordRepository = wordRepository;
        this.sentenceRepository = sentenceRepository;
    }

    @GetMapping("/detail")
    public ApiResponse<WordView> detail(@RequestParam String level,
                                        @RequestParam(required = false) Long wordId,
                                        @RequestParam(required = false) String word) {
        Level lv = Level.fromCode(level)
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_LEVEL, "级别不合法: " + level));
        Word w;
        if (wordId != null) {
            w = wordRepository.findById(lv, wordId)
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "单词不存在"));
        } else if (word != null && !word.isBlank()) {
            w = wordRepository.findByWord(lv, word.trim().toLowerCase())
                    .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "单词不存在"));
        } else {
            throw new BizException(ErrorCode.INVALID_PARAM, "wordId 与 word 至少提供一个");
        }

        String hash = w.sentenceHash();
        String sentence = null;
        String translation = null;
        if (hash != null) {
            Optional<Sentence> s = sentenceRepository.findByHash(lv, hash);
            if (s.isPresent()) {
                sentence = s.get().sentence();
                translation = s.get().sentenceTranslation();
            }
        }
        return ApiResponse.ok(new WordView(w.id(), w.word(), hash, sentence, translation,
                w.status(), w.meaning(), w.phonetic(), w.pos(),
                w.mnemonic(), w.example(), w.phrase(), null, w.createdAt()));
    }
}
