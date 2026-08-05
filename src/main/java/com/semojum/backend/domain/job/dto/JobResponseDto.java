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
            boolean insertPageNumber
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
     * <p>totalPages·failedPages·startedAt·finishedAt·isEdited·insertPageNumber 같은 값은
     * 카드에 표시되지 않아 제외했다. 필요한 화면(에디터·다운로드)에서 상세 조회로 받는다.
     */
    public record JobCard(
            String jobId,
            String mode,             // 배지 색·문구
            String status,           // "변환 중" 판정
            Integer progress,        // 진행 중일 때만 0~100, 그 외 null
            String originalFileName,
            String thumbnailUrl,
            String displayDate,      // "1시간 전" / "어제" / "7. 28." / "2025. 12. 3."
            LocalDateTime lastModifiedAt,  // 정렬·툴팁용 원본 시각
            boolean isFavorite,
            String folderId,
            String folderPath        // 루트면 null
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