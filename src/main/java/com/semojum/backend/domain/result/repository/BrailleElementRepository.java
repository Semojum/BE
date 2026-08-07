package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.BrailleElement;
import com.semojum.backend.domain.result.entity.PageResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BrailleElementRepository extends JpaRepository<BrailleElement, UUID> {
    // 삭제된 블록 제외 + reading_order 순 정렬 (블록 추가/삭제/순서변경이 응답 순서에 반영되도록)
    @Query("SELECT b FROM BrailleElement b WHERE b.pageResult = :pageResult AND b.isDeleted = false ORDER BY b.readingOrder")
    List<BrailleElement> findByPageResult(@Param("pageResult") PageResult pageResult);

    Optional<BrailleElement> findByPageResultAndElementId(PageResult pageResult, String elementId);
}
