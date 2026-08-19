package com.semojum.backend.domain.app.repository;

import com.semojum.backend.domain.app.entity.AppVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppVersionRepository extends JpaRepository<AppVersion, Long> {

    // 최신 행 = 현재 버전 정보
    Optional<AppVersion> findTopByOrderByIdDesc();

    List<AppVersion> findAllByOrderByIdDesc();
}
