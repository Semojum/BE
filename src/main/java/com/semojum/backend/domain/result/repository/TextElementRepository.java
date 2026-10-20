package com.semojum.backend.domain.result.repository;

import java.util.Collection;
import org.springframework.data.jpa.repository.Modifying;
import com.semojum.backend.domain.result.entity.PageResult;
import com.semojum.backend.domain.result.entity.TextElement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TextElementRepository extends JpaRepository<TextElement, UUID> {
    // 삭제된 블록 제외 + reading_order 순 정렬 (블록 추가/삭제/순서변경이 응답 순서에 반영되도록)
    @Query("SELECT t FROM TextElement t WHERE t.pageResult = :pageResult AND t.isDeleted = false ORDER BY t.readingOrder")
    List<TextElement> findByPageResult(@Param("pageResult") PageResult pageResult);

    // AI 초안 — AI가 준 블록 전부(사용자가 지운 것 포함, 사용자 추가 블록 제외). 수정 이력의 AI 초안 칸용

    Optional<TextElement> findByPageResultAndElementId(PageResult pageResult, String elementId);

    // 쪽 삭제용 — 삭제 표시된 블록까지 전부 (텍스트 작업의 쪽 줄이기, 2026-10-21)
    @Query("SELECT t.id FROM TextElement t WHERE t.pageResult.id IN :pageResultIds")
    List<UUID> findIdsByPageResultIdIn(@Param("pageResultIds") Collection<UUID> pageResultIds);

    @Modifying
    @Query("DELETE FROM TextElement t WHERE t.pageResult.id IN :pageResultIds")
    int deleteByPageResultIdIn(@Param("pageResultIds") Collection<UUID> pageResultIds);
}
