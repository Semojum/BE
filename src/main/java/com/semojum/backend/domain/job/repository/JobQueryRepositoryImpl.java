package com.semojum.backend.domain.job.repository;

import com.semojum.backend.domain.job.dto.JobSearchCondition;
import com.semojum.backend.domain.job.entity.Job;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 목록 조회 동적 쿼리.
 *
 * <p>정렬은 카드 날짜({@code last_modified_at}) 기준이며, 같은 시각이 여러 건일 수 있어
 * {@code id}를 2차 정렬키로 둔다. 커서도 이 (시각, id) 쌍으로 비교해야 페이지 경계에서
 * 항목이 중복되거나 누락되지 않는다.
 */
@Slf4j
public class JobQueryRepositoryImpl implements JobQueryRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<Job> search(UUID userId, JobSearchCondition condition) {
        StringBuilder jpql = new StringBuilder(
                "SELECT j FROM Job j WHERE j.user.id = :userId AND j.deletedAt IS NULL");
        Map<String, Object> params = new HashMap<>();
        params.put("userId", userId);

        // 범위: 전역 / 하위 폴더 전체(검색) / 특정 폴더 / 루트
        if (condition.folderIds() != null) {
            // 검색은 현재 위치 아래 전체를 훑는다 — 빈 목록이면 매칭 결과도 없어야 한다
            if (condition.folderIds().isEmpty()) return List.of();
            jpql.append(" AND j.folderId IN :folderIds");
            params.put("folderIds", condition.folderIds());
        } else if (!condition.allScope()) {
            if (condition.folderId() != null) {
                jpql.append(" AND j.folderId = :folderId");
                params.put("folderId", condition.folderId());
            } else {
                jpql.append(" AND j.folderId IS NULL");
            }
        }

        if (condition.search() != null && !condition.search().isBlank()) {
            jpql.append(" AND LOWER(j.originalFileName) LIKE :search");
            params.put("search", "%" + condition.search().toLowerCase().strip() + "%");
        }
        if (condition.statuses() != null && !condition.statuses().isEmpty()) {
            jpql.append(" AND j.status IN :statuses");
            params.put("statuses", condition.statuses());
        }
        if (condition.modes() != null && !condition.modes().isEmpty()) {
            jpql.append(" AND j.mode IN :modes");
            params.put("modes", condition.modes());
        }
        if (Boolean.TRUE.equals(condition.favoriteOnly())) {
            jpql.append(" AND j.isFavorite = true");
        }

        Cursor cursor = Cursor.decode(condition.cursor());
        if (cursor != null) {
            // (시각, id) 튜플 비교 — 같은 시각 항목이 페이지 경계에서 잘리지 않게 한다
            String cmp = condition.oldestFirst() ? ">" : "<";
            jpql.append(" AND (j.lastModifiedAt ").append(cmp).append(" :cursorAt")
                .append(" OR (j.lastModifiedAt = :cursorAt AND j.id ").append(cmp).append(" :cursorId))");
            params.put("cursorAt", cursor.at());
            params.put("cursorId", cursor.id());
        }

        String direction = condition.oldestFirst() ? "ASC" : "DESC";
        jpql.append(" ORDER BY j.lastModifiedAt ").append(direction)
            .append(", j.id ").append(direction);

        TypedQuery<Job> query = em.createQuery(jpql.toString(), Job.class);
        params.forEach(query::setParameter);
        // 다음 페이지 존재 여부를 알려고 1개 더 가져온다
        query.setMaxResults(condition.normalizedSize() + 1);
        return query.getResultList();
    }

    /** 커서 = (last_modified_at, id). Base64로 감싸 FE가 내부 구조에 의존하지 않게 한다. */
    public record Cursor(LocalDateTime at, String id) {

        public static String encode(Job job) {
            String raw = job.getLastModifiedAt().toString() + "|" + job.getId();
            return java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        /** 손상된 커서는 무시하고 첫 페이지로 되돌린다(에러로 목록 전체를 막지 않음). */
        public static Cursor decode(String encoded) {
            if (encoded == null || encoded.isBlank()) return null;
            try {
                String raw = new String(java.util.Base64.getUrlDecoder().decode(encoded),
                        java.nio.charset.StandardCharsets.UTF_8);
                int sep = raw.indexOf('|');
                if (sep < 0) return null;
                return new Cursor(LocalDateTime.parse(raw.substring(0, sep)), raw.substring(sep + 1));
            } catch (Exception e) {
                log.warn("잘못된 커서 무시: {}", encoded);
                return null;
            }
        }
    }

    /** 조회 결과에서 페이지 크기만큼 자르고 다음 커서를 만든다. */
    public static PageSlice slice(List<Job> fetched, int size) {
        boolean hasMore = fetched.size() > size;
        List<Job> items = hasMore ? new ArrayList<>(fetched.subList(0, size)) : fetched;
        String nextCursor = hasMore ? Cursor.encode(items.get(items.size() - 1)) : null;
        return new PageSlice(items, nextCursor, hasMore);
    }

    public record PageSlice(List<Job> items, String nextCursor, boolean hasMore) {}
}
