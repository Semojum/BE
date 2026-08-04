package com.semojum.backend.domain.job.dto;

import java.util.List;
import java.util.UUID;

/**
 * 마이페이지 작업 목록 조회 조건.
 *
 * <p>범위 규칙 — {@code scope="all"}이면 폴더 무관 전역(최근 작업·전체 보기용),
 * 아니면 {@code folderId}가 있으면 그 폴더 안, 없으면 루트({@code folder_id IS NULL})만 본다.
 * 휴지통 항목({@code deleted_at IS NOT NULL})은 어떤 경우에도 제외된다.
 */
public record JobSearchCondition(
        UUID folderId,
        boolean allScope,
        String search,       // 파일 이름 부분 일치 (대소문자 무시)
        List<String> statuses,
        List<String> modes,
        Boolean favoriteOnly,
        boolean oldestFirst,
        String cursor,       // 이전 페이지 마지막 항목 (Base64 "epochMillis|jobId")
        int size
) {
    public static final int DEFAULT_SIZE = 30;
    public static final int MAX_SIZE = 100;

    public int normalizedSize() {
        if (size <= 0) return DEFAULT_SIZE;
        return Math.min(size, MAX_SIZE);
    }
}
