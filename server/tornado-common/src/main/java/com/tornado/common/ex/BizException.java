package com.tornado.common.ex;

import lombok.Getter;

/** 业务异常，携带错误码 */
@Getter
public class BizException extends RuntimeException {
    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public static BizException of(ErrorCode ec) {
        return new BizException(ec);
    }
}
