package com.semojum.backend.domain.result.repository;

import java.util.Collection;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import com.semojum.backend.domain.result.entity.BoundingBox;
import com.semojum.backend.domain.result.entity.PageResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BoundingBoxRepository extends JpaRepository<BoundingBox, UUID> {
    List<BoundingBox> findByPageResult(PageResult pageResult);

    @Modifying
    @Query("DELETE FROM BoundingBox x WHERE x.pageResult.id IN :pageResultIds")
    int deleteByPageResultIdIn(@Param("pageResultIds") Collection<UUID> pageResultIds);
}
