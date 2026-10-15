package com.bud.wordlist.common;

/** 业务异常。由 GlobalExceptionHandler 统一转成 400 + 业务码 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
