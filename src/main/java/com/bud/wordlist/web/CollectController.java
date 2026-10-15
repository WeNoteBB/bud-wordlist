package com.bud.wordlist.web;

import com.bud.wordlist.common.ApiResponse;
import com.bud.wordlist.common.BizException;
import com.bud.wordlist.common.ErrorCode;
import com.bud.wordlist.common.Level;
import com.bud.wordlist.repository.UserSentenceRepository;
import com.bud.wordlist.repository.UserWordRepository;
import com.bud.wordlist.service.SentenceCollectService;
import com.bud.wordlist.service.WordCollectService;
import com.bud.wordlist.web.dto.CollectSentenceRequest;
import com.bud.wordlist.web.dto.CollectWordRequest;
import com.bud.wordlist.web.dto.DeleteSentenceRequest;
import com.bud.wordlist.web.dto.DeleteWordRequest;
import com.bud.wordlist.web.dto.PageResult;
import com.bud.wordlist.web.dto.SentenceView;
import com.bud.wordlist.web.dto.WordView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/collect")
public class CollectController {

    private static final int MAX_SIZE = 100;

    private final WordCollectService wordCollectService;
    private final SentenceCollectService sentenceCollectService;
    private final UserWordRepository userWordRepository;
    private final UserSentenceRepository userSentenceRepository;

    public CollectController(WordCollectService wordCollectService,
                             SentenceCollectService sentenceCollectService,
                             UserWordRepository userWordRepository,
                             UserSentenceRepository userSentenceRepository) {
        this.wordCollectService = wordCollectService;
        this.sentenceCollectService = sentenceCollectService;
        this.userWordRepository = userWordRepository;
        this.userSentenceRepository = userSentenceRepository;
    }

    @PostMapping("/word")
    public ApiResponse<WordView> collectWord(@RequestBody CollectWordRequest req) {
        WordCollectService.CollectResult r = wordCollectService.collect(req);
        return ApiResponse.ok(r.view());
    }

    @PostMapping("/sentence")
    public ApiResponse<SentenceView> collectSentence(@RequestBody CollectSentenceRequest req) {
        SentenceCollectService.CollectResult r = sentenceCollectService.collect(req);
        return ApiResponse.ok(r.view());
    }

    @DeleteMapping("/word")
    public ApiResponse<Map<String, Boolean>> deleteWord(@RequestBody DeleteWordRequest req) {
        requireLevel(req.level());
        int affected = userWordRepository.delete(req.userId(), req.level(), req.wordId());
        return ApiResponse.ok(Map.of("removed", affected > 0));
    }

    @DeleteMapping("/sentence")
    public ApiResponse<Map<String, Boolean>> deleteSentence(@RequestBody DeleteSentenceRequest req) {
        requireLevel(req.level());
        int affected = userSentenceRepository.delete(req.userId(), req.level(), req.sentenceId());
        return ApiResponse.ok(Map.of("removed", affected > 0));
    }

    @GetMapping("/words")
    public ApiResponse<PageResult<WordView>> words(@RequestParam long userId,
                                                   @RequestParam String level,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        if (userId <= 0) {
            throw new BizException(ErrorCode.INVALID_USER, "userId 非法");
        }
        Level lv = requireLevel(level);
        size = Math.min(Math.max(size, 1), MAX_SIZE);
        page = Math.max(page, 1);
        return ApiResponse.ok(userWordRepository.findPage(userId, lv, page, size));
    }

    @GetMapping("/sentences")
    public ApiResponse<PageResult<SentenceView>> sentences(@RequestParam long userId,
                                                           @RequestParam String level,
                                                           @RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        if (userId <= 0) {
            throw new BizException(ErrorCode.INVALID_USER, "userId 非法");
        }
        Level lv = requireLevel(level);
        size = Math.min(Math.max(size, 1), MAX_SIZE);
        page = Math.max(page, 1);
        return ApiResponse.ok(userSentenceRepository.findPage(userId, lv, page, size));
    }

    private Level requireLevel(String level) {
        return Level.fromCode(level)
                .orElseThrow(() -> new BizException(ErrorCode.INVALID_LEVEL, "级别不合法: " + level));
    }
}
