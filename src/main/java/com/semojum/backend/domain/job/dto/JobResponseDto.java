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