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
            LayoutOptions layoutOptions
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
     * 마이페이지 목록 카드 — 화면에 실제로 그려지는 값만 담는다.
     *
     * <p>failedPages·startedAt·finishedAt·insertPageNumber 같은 값은 카드에 표시되지 않아
     * 제외했다. 필요한 화면(에디터·다운로드)에서 상세 조회로 받는다.
     *
     * <p>진행률(0~100)도 담지 않는다 — 카드는 퍼센트 없이 "변환 중"만 표시하고,
     * 실시간 진행률은 SSE가 담당한다.
     *
     * <p>정렬 기준인 last_modified_at 원본 시각도 담지 않는다 — 화면에는 완성된 문자열
     * displayDate가 쓰이고, 다음 페이지 요청은 불투명한 nextCursor로 한다.
     */
    public record JobCard(
            String jobId,
            String mode,             // 배지 색·문구
            String status,           // "변환 중" 판정
            Integer progress,        // 변환 중일 때만 0~100(완료 페이지 비율), 그 외 null — 카드 "생성 중 N%" 표시용 (2026-08-17 복원)
            String originalFileName,
            String thumbnailUrl,
            String displayDate,      // "1시간 전" / "어제" / "7. 28." / "2025. 12. 3."
            int totalPages,
            Integer lastEditedPage,  // 마지막으로 편집한 페이지. 편집 이력이 없으면 null
            boolean isFavorite,
            String folderId,         // 전체보기·검색 결과의 "폴더로 이동"용. 루트면 null
            String folderPath        // 전체보기·검색 결과의 위치 표시("국어교재/1학기"). 루트면 null
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

    // 커서 페이지네이션 응답
    public record JobList(
            List<JobCard> items,
            String nextCursor,
            boolean hasMore
    ) {}

    public record JobSummary(
            String jobId,
            String mode,
            String status,
            int totalPages,
            int[] failedPages,
            String originalFileName,
            String thumbnailUrl,
            LocalDateTime startedAt,
            LocalDateTime finishedAt
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
            Map<String, Object> result,
            OriginalContent original
    ) {}

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
}