package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.PageResult;
import com.semojum.backend.domain.result.entity.TextElement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TextElementRepository extends JpaRepository<TextElement, UUID> {
    List<TextElement> findByPageResult(PageResult pageResult);
}
