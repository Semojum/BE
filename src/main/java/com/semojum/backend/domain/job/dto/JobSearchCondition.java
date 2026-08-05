package com.semojum.backend.domain.job.dto;

import java.util.List;
import java.util.UUID;

/**
 * 마이페이지 작업 목록 조회 조건.
 *
 * <p>범위 규칙 — {@code allScope}면 폴더 무관 전역, {@code folderIds}가 있으면 그 폴더들 안
 * (검색 시 하위 폴더 전체를 담는다), {@code folderId}가 있으면 그 폴더 한 층, 아무것도 없으면
 * 루트({@code folder_id IS NULL})만 본다.
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
        int size,
        List<UUID> folderIds // 검색 시 하위 폴더까지 포함한 범위. null이면 folderId/allScope 규칙을 따른다
) {

    /** 기존 호출부 호환 — 하위 폴더 확장 없이 한 층만 본다. */
    public JobSearchCondition(UUID folderId, boolean allScope, String search, List<String> statuses,
                              List<String> modes, Boolean favoriteOnly, boolean oldestFirst,
                              String cursor, int size) {
        this(folderId, allScope, search, statuses, modes, favoriteOnly, oldestFirst, cursor, size, null);
    }

    /** 이 조건을 유지한 채 조회 범위만 하위 폴더 전체로 넓힌다. */
    public JobSearchCondition withFolderScope(List<UUID> scope) {
        return new JobSearchCondition(folderId, allScope, search, statuses, modes,
                favoriteOnly, oldestFirst, cursor, size, scope);
    }

    public static final int DEFAULT_SIZE = 30;
    public static final int MAX_SIZE = 100;

    public int normalizedSize() {
        if (size <= 0) return DEFAULT_SIZE;
        return Math.min(size, MAX_SIZE);
    }
}
