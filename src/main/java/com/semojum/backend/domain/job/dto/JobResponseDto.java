package com.semojum.backend.domain.job.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class JobResponseDto {

    public record Create(
            String jobId,
            String mode,
            int totalPages,
            String status,
            boolean insertPageNumber,
            String footerText,
            // 이 작업에 적용된 조판 옵션 — 안 보낸 항목이 기본값으로 채워진 최종 형태 (V30)
            LayoutOptions layoutOptions
    ) {}

    /**
     * 이 작업의 업로드 설정 — 조판 옵션 + 꼬리말 (같은 옵션 화면에서 함께 고르는 값들).
     * 에디터가 작업을 열 때 설정을 복원하는 용도. 페이지 조회를 거치지 않고 바로 볼 수 있다.
     */
    public record Options(
            String jobId,
            boolean insertPageNumber,
            String footerText,
            // 위 꼬리말의 점역 결과 (V31) — 화면이 페이지행에 그대로 그릴 수 있게. 미점역이면 null
            String footerBraille,
            LayoutOptions layoutOptions,
            // 그 작업에 적용된 점역 옵션 (V37) — 에디터가 열 때 점역 설정을 복원한다.
            // V3.1 이전 작업은 공장 기본값이 채워져 내려온다
            BrailleOptions brailleOptions
    ) {}

    public record Status(
            String jobId,
            int totalPages,
            int completedPages,
            int pendingPages,
            int runningPages,
            String overallStatus,
            Map<String, String> pages
    ) {}

    /**
     * 재시작 복구용 진행 중 작업 — 어느 작업의 몇 페이지로 돌아갈지 판단할 값만 담는다.
     */
    public record ActiveJob(
            String jobId,
            String mode,
            String status,
            int totalPages,
            Integer progress,
            String originalFileName,
            LocalDateTime lastModifiedAt,
            Integer lastEditedPage
    ) {}

    public record JobDetail(
            String jobId,
            String mode,
            String status,
            int totalPages,
            int[] failedPages,
            String originalFileName,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            int pageNo,
            boolean insertPageNumber,
            // 업로드 때 고른 조판 옵션 — 에디터가 다음에 열 때도 같은 설정으로 열도록 (V30)
            LayoutOptions layoutOptions,
            // 이 면의 페이지행에 들어갈 점역된 꼬리말 (V31) — 없으면 null.
            // 구간 꼬리말이 생겨도 면마다 다른 값을 주면 되므로 모양은 그대로다
            String footerBraille,
            Map<String, Object> result,
            OriginalContent original,
            // 이 쪽의 줄 메모 (V34) — 없으면 빈 배열. 쓰기는 작업 저장 본문이다
            List<Memo> memos
    ) {}

    /**
     * 줄 메모. {@code lineText}는 <b>그 줄의 원문</b>이지 메모 내용이 아니다 —
     * 조판 설정이 바뀌어 {@code lineIndex}가 어긋났을 때 FE가 이것으로 다시 찾는다.
     */
    public record Memo(
            Long memoId,
            String panel,
            String elementId,
            int lineIndex,
            String lineText,
            String body,
            LocalDateTime createdAt,
            String author,             // 작성자 표시 이름 (V42). 예전 메모는 null
            boolean resolved,          // 해결 여부
            LocalDateTime resolvedAt,  // 해결 시각. 미해결이면 null
            List<MemoReply> replies    // 답글 — 없으면 빈 배열
    ) {}

    public record MemoReply(Long replyId, String author, String body, LocalDateTime createdAt) {}

    // 페이지별 원본. URL은 하나이고 무엇인지는 type이 알려준다 (2026-08-31 단일화).
    //   type="image" : a·c 정상 — 서버가 미리 렌더한 JPEG. FE는 <img>로 바로 그린다
    //   type="pdf"   : a·c인데 이미지가 없을 때(렌더 실패·page-image 비활성) — FE는 pdf.js로 그린다
    //   type="text"  : b — url 없이 lines(원문 줄 배열)
    // 사실상 항상 image이지만, 렌더가 실패해도 원본 패널이 비지 않도록 pdf 경로를 남겨 둔다.
    //
    // NON_NULL: 그 모드에 해당 없는 필드는 키 자체를 빼 응답을 명확하게 한다
    //   a·c → {type, url}  /  b → {type, lines}
    // (result 안의 repeated 필드는 여전히 항상 배열로 나간다 — PageResultSerializer 담당, 이 설정과 무관)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OriginalContent(
            String type,
            String url,
            List<String> lines
    ) {}

    /**
     * 작업 저장 결과.
     *
     * <p>쪽 단위 트랜잭션이라 일부만 실패할 수 있다. {@code failedPages}가 비어 있으면 전부 성공이고,
     * null이 아니라 빈 배열로 나간다 — FE가 null 검사 없이 순회하도록.
     */
    public record SaveResult(
            String jobId,
            int savedPages,
            List<Integer> failedPages,
            java.time.LocalDateTime savedAt
    ) {}
}
