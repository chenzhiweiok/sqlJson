package com.demo.relation.service;

/** 业务异常：由 Web 层统一转成 {code,msg}，不暴露堆栈。 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        this(400, message);
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }
}
