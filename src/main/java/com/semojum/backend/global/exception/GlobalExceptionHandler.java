package com.semojum.backend.global.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // @Valid 검증 실패
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        log.warn("요청 검증 실패: {}", message);
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.failure(ErrorCode.COMMON_BAD_REQUEST));
    }

    // 본문을 읽지 못한 경우 — 깨진 JSON, 기대와 다른 타입(객체 자리에 배열) 등.
    // 아래 catch-all이 삼키면 클라이언트 실수가 500 + 스택으로 나가 서버 장애처럼 보인다
    // (grep "WARN|ERROR"가 장애 화면이라는 로깅 원칙도 깨진다). 4xx이므로 스택 없이 WARN.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("요청 본문 파싱 실패: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.failure(ErrorCode.COMMON_BAD_REQUEST));
    }

    // 커스텀 예외 — 사용자 신고("안 돼요") 대응을 위해 에러코드를 로그에 남긴다.
    // 어떤 요청이었는지는 같은 ctx의 REQ 라인이 담당(RequestLogFilter)
    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustom(CustomException e) {
        log.warn("비즈니스 예외: {} — {}", e.getErrorCode().getCode(), e.getErrorCode().getMessage());
        return ResponseEntity
                .status(e.getErrorCode().getHttpStatus())
                .body(ApiResponse.failure(e.getErrorCode()));
    }

    // 업로드 용량 초과 — 핸들링하지 않으면 500으로 나가 원인을 알 수 없다
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity
                .status(ErrorCode.JOB_FILE_TOO_LARGE.getHttpStatus())
                .body(ApiResponse.failure(ErrorCode.JOB_FILE_TOO_LARGE));
    }

    // 매핑되지 않은 경로 — 아래 catch-all이 삼키면 500으로 나가 클라이언트가 오타인지 장애인지 구분할 수 없다
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception e) {
        return ResponseEntity
                .status(ErrorCode.COMMON_NOT_FOUND.getHttpStatus())
                .body(ApiResponse.failure(ErrorCode.COMMON_NOT_FOUND));
    }

    // 경로는 있으나 메서드가 다른 경우 (예: PATCH만 있는 경로에 DELETE)
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity
                .status(ErrorCode.COMMON_METHOD_NOT_ALLOWED.getHttpStatus())
                .body(ApiResponse.failure(ErrorCode.COMMON_METHOD_NOT_ALLOWED));
    }

    // 그 외 예외
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unhandled exception: ", e);
        return ResponseEntity
                .internalServerError()
                .body(ApiResponse.failure(ErrorCode.COMMON_INTERNAL_ERROR));
    }
}