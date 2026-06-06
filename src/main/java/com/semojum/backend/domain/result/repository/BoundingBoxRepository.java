package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.BoundingBox;
import com.semojum.backend.domain.result.entity.PageResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BoundingBoxRepository extends JpaRepository<BoundingBox, UUID> {
    List<BoundingBox> findByPageResult(PageResult pageResult);
}
