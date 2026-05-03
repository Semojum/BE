package com.semojum.backend.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // ========== 공통 ==========
    COMMON_BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON4000", "잘못된 요청입니다."),
    COMMON_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON4001", "인증이 필요합니다."),
    COMMON_FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON4003", "권한이 없습니다."),
    COMMON_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON5000", "서버 에러, 관리자에게 문의 바랍니다."),

    // ========== 인증 (AUTH) ==========
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH4001", "이메일 또는 비밀번호가 올바르지 않습니다."),
    AUTH_DUPLICATE_EMAIL(HttpStatus.CONFLICT, "AUTH4002", "이미 사용 중인 이메일입니다."),
    AUTH_INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH4003", "액세스 토큰이 만료되었거나 유효하지 않습니다."),

    // ========== 유저 (USER) ==========
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER4001", "존재하지 않는 회원입니다."),

    // ========== 작업 (JOB) ==========
    JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "JOB4001", "존재하지 않는 작업입니다."),
    JOB_INVALID_FILE(HttpStatus.BAD_REQUEST, "JOB4002", "지원하지 않는 파일 형식입니다."),
    JOB_INVALID_MODE(HttpStatus.BAD_REQUEST, "JOB4003", "지원하지 않는 모드입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }
}