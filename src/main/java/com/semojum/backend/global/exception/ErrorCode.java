package com.semojum.backend.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // ========== 공통 ==========
    COMMON_BAD_REQUEST(HttpStatus.BAD_REQUEST, "COMMON4000", "잘못된 요청입니다."),
    COMMON_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON4001", "인증이 필요합니다."),
    COMMON_FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON4003", "권한이 없습니다."),
    COMMON_NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON4004", "요청한 경로를 찾을 수 없습니다."),
    COMMON_METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON4005", "지원하지 않는 요청 방식입니다."),
    COMMON_INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON5000", "서버 에러, 관리자에게 문의 바랍니다."),

    // ========== 인증 (AUTH) ==========
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "AUTH4001", "아이디 또는 비밀번호가 올바르지 않습니다."),
    AUTH_DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "AUTH4002", "이미 사용 중인 로그인 ID입니다."),
    AUTH_INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH4003", "액세스 토큰이 만료되었거나 유효하지 않습니다."),
    AUTH_WRONG_CHANNEL(HttpStatus.FORBIDDEN, "AUTH4005", "이 환경에서는 로그인할 수 없는 계정입니다."),

    // ========== 유저 (USER) ==========
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "USER4001", "존재하지 않는 회원입니다."),

    // ========== 기관 (ORG) ==========
    AUTH_INACTIVE_ACCOUNT(HttpStatus.FORBIDDEN, "AUTH4004", "비활성화된 계정입니다."),
    ORG_NOT_FOUND(HttpStatus.NOT_FOUND, "ORG4001", "존재하지 않는 기관입니다."),
    ORG_CODE_DUPLICATE(HttpStatus.CONFLICT, "ORG4002", "이미 사용 중인 기관 코드입니다."),

    // ========== 작업 (JOB) ==========
    JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "JOB4001", "존재하지 않는 작업입니다."),
    JOB_INVALID_FILE(HttpStatus.BAD_REQUEST, "JOB4002", "mode a/c는 PDF, mode b는 TXT/HWP 파일만 업로드 가능합니다."),
    JOB_INVALID_MODE(HttpStatus.BAD_REQUEST, "JOB4003", "지원하지 않는 모드입니다."),
    ELEMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "JOB4004", "존재하지 않는 요소입니다."),
    // JOB4005(elementType 검증)는 페이지 일괄 저장 도입으로 폐기 — 편집 대상 테이블은 mode가 정한다
    ELEMENT_LIST_MISMATCH(HttpStatus.BAD_REQUEST, "JOB4006", "요소 목록이 현재 페이지의 요소와 일치하지 않습니다."),
    JOB_HWP_PARSE_FAILED(HttpStatus.BAD_REQUEST, "JOB4007", "HWP 파일을 읽을 수 없습니다. 파일이 손상되었거나 지원하지 않는 형식입니다."),
    JOB_HWP_UNSUPPORTED(HttpStatus.BAD_REQUEST, "JOB4008", "암호가 설정되었거나 배포용으로 저장된 HWP 파일은 변환할 수 없습니다."),
    JOB_FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "JOB4009", "업로드 가능한 파일 최대 용량(100MB)을 초과했습니다."),
    JOB_IN_PROGRESS(HttpStatus.CONFLICT, "JOB4010", "변환 중인 작업은 이동·이름변경·삭제·다운로드할 수 없습니다."),
    JOB_BRAILLE_EXISTS(HttpStatus.CONFLICT, "JOB4011", "이미 연결된 점역 문서가 있습니다. 덮어쓰기 여부를 확인해 주세요."),
    JOB_NO_RESULT(HttpStatus.BAD_REQUEST, "JOB4012", "다운로드할 변환 결과가 없습니다."),

    // ========== 폴더 (FOLDER) ==========
    FOLDER_NOT_FOUND(HttpStatus.NOT_FOUND, "FOLDER4001", "존재하지 않는 폴더입니다."),
    FOLDER_NAME_DUPLICATE(HttpStatus.CONFLICT, "FOLDER4002", "같은 위치에 같은 이름의 폴더가 이미 있습니다."),
    FOLDER_DEPTH_EXCEEDED(HttpStatus.BAD_REQUEST, "FOLDER4003", "폴더는 5단계까지만 만들 수 있으며, 자기 자신의 하위로 이동할 수 없습니다."),
    FOLDER_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "FOLDER4004", "폴더는 계정당 최대 200개까지 만들 수 있습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String code, String message) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
    }
}