package com.semojum.backend.global.exception;

import lombok.Getter;

@Getter
public class CustomException extends RuntimeException {

    private final ErrorCode errorCode;

    // 실패 응답의 result에 실을 값 — 대부분 null. 화면이 숫자를 보여줘야 할 때만 쓴다(로그인 남은 횟수 등)
    private final Object result;

    public CustomException(ErrorCode errorCode) {
        this(errorCode, null);
    }

    public CustomException(ErrorCode errorCode, Object result) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.result = result;
    }
}