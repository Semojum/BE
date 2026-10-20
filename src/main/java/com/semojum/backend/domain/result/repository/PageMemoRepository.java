package com.semojum.backend.domain.result.repository;

import java.util.Collection;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import com.semojum.backend.domain.result.entity.PageMemo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PageMemoRepository extends JpaRepository<PageMemo, Long> {

    List<PageMemo> findByPageIdOrderByElementIdAscLineIndexAsc(UUID pageId);

    /** 저장은 쪽 단위 통째 교체 — 기존 것을 지우고 본문의 것으로 다시 넣는다 */
    void deleteByPageId(UUID pageId);

    List<PageMemo> findByPageIdIn(Collection<UUID> pageIds);

    @Modifying
    @Query("DELETE FROM PageMemo m WHERE m.pageId IN :pageIds")
    int deleteByPageIdIn(@Param("pageIds") Collection<UUID> pageIds);
}
