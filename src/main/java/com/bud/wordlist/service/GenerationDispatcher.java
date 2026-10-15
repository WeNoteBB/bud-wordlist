package com.bud.wordlist.service;

import com.bud.wordlist.common.Level;

/** 把生成任务投递到后台。实现必须立即返回，绝不能阻塞调用线程。 */
public interface GenerationDispatcher {

    void dispatchWord(Level level, long wordId, String word);

    void dispatchSentence(Level level, long sentenceId, String sentence);
}
