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

    // 마이페이지 목록 카드 — 확장 필드(진행률·위치·즐겨찾기·복구 정보) 포함
    public record JobCard(
            String jobId,
            String mode,
            String status,
            int totalPages,
            int[] failedPages,
            String originalFileName,
            String thumbnailUrl,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            LocalDateTime lastModifiedAt,
            Integer lastEditedPage,
            boolean isEdited,
            boolean isFavorite,
            boolean insertPageNumber,
            Integer progress,        // 진행 중일 때만 0~100, 그 외 null
            String folderId,
            String folderPath        // 루트면 null
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