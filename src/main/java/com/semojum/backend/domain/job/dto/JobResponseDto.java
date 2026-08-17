package com.semojum.backend.domain.job.dto;

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
            String footerText
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
            Map<String, Object> result,
            OriginalContent original
    ) {}

    // 페이지별 원본 (a/c: type="pdf"+url, b: type="text"+lines). 안 쓰는 필드는 null.
    public record OriginalContent(
            String type,
            String url,
            List<String> lines
    ) {}
}