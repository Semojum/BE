package com.semojum.backend.domain.app.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 앱 버전 (V26) — 데스크톱 앱이 시작 시 조회해 강제/권장 업데이트 판단.
 * 갱신 = 새 행 추가(이력 보존, 단가표 패턴) — 최신 행이 현재 값.
 */
@Entity
@Table(name = "app_versions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "latest_version", nullable = false, length = 20)
    private String latestVersion;

    // 이 미만 버전은 강제 업데이트 (앱이 사용 차단 + 업데이트 화면)
    @Column(name = "min_supported_version", nullable = false, length = 20)
    private String minSupportedVersion;

    @Column(name = "download_url", length = 500)
    private String downloadUrl;

    @Column(name = "release_notes", columnDefinition = "text")
    private String releaseNotes;

    @Column(length = 500)
    private String note;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Builder
    public AppVersion(String latestVersion, String minSupportedVersion,
                      String downloadUrl, String releaseNotes, String note) {
        this.latestVersion = latestVersion;
        this.minSupportedVersion = minSupportedVersion;
        this.downloadUrl = downloadUrl;
        this.releaseNotes = releaseNotes;
        this.note = note;
    }
}
