package com.semojum.backend.domain.result.repository;

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

    Optional<TextElement> findByPageResultAndElementId(PageResult pageResult, String elementId);
}
