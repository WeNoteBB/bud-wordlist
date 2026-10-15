package com.bud.wordlist.common;

/** 业务错误码。0 为成功，其余为失败 */
public final class ErrorCode {
    public static final int OK = 0;
    public static final int INVALID_LEVEL = 1001;
    public static final int INVALID_WORD = 1002;
    public static final int INVALID_SENTENCE = 1003;
    public static final int INVALID_USER = 1004;
    public static final int INVALID_PARAM = 1005;
    public static final int NOT_FOUND = 1006;
    public static final int INTERNAL = 9999;

    private ErrorCode() {
    }
}
