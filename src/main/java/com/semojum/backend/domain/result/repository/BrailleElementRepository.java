package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.BrailleElement;
import com.semojum.backend.domain.result.entity.PageResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BrailleElementRepository extends JpaRepository<BrailleElement, UUID> {
    List<BrailleElement> findByPageResult(PageResult pageResult);
}
