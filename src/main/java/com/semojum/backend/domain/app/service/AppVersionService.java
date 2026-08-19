package com.semojum.backend.domain.app.service;

import com.semojum.backend.domain.app.dto.AppVersionDto;
import com.semojum.backend.domain.app.entity.AppVersion;
import com.semojum.backend.domain.app.repository.AppVersionRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AppVersionService {

    // 세 자리 semver만 허용 (1.2.0) — 앱·BE가 같은 규칙으로 비교하기 위한 강제
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    private final AppVersionRepository appVersionRepository;

    // 무인증 조회 — 앱 시작 시 호출. 등록된 버전이 없으면 null(앱은 검사 생략)
    @Transactional(readOnly = true)
    public AppVersionDto.Response getCurrent() {
        return appVersionRepository.findTopByOrderByIdDesc()
                .map(v -> new AppVersionDto.Response(v.getLatestVersion(), v.getMinSupportedVersion(),
                        v.getDownloadUrl(), v.getReleaseNotes(), v.getCreatedAt()))
                .orElse(null);
    }

    @Transactional
    public AppVersionDto.HistoryItem register(AppVersionDto.Register request) {
        if (!SEMVER.matcher(request.latestVersion()).matches()
                || !SEMVER.matcher(request.minSupportedVersion()).matches()) {
            log.warn("앱 버전 형식 오류: latest={}, min={}", request.latestVersion(), request.minSupportedVersion());
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        if (compare(request.minSupportedVersion(), request.latestVersion()) > 0) {
            log.warn("최소 지원 버전이 최신 버전보다 큼: min={} > latest={}",
                    request.minSupportedVersion(), request.latestVersion());
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        AppVersion saved = appVersionRepository.save(AppVersion.builder()
                .latestVersion(request.latestVersion())
                .minSupportedVersion(request.minSupportedVersion())
                .downloadUrl(request.downloadUrl())
                .releaseNotes(request.releaseNotes())
                .note(request.note())
                .build());
        log.info("앱 버전 등록: latest={}, min={}", request.latestVersion(), request.minSupportedVersion());
        AppVersion v = appVersionRepository.findById(saved.getId()).orElse(saved);
        return toHistoryItem(v);
    }

    @Transactional(readOnly = true)
    public List<AppVersionDto.HistoryItem> listHistory() {
        return appVersionRepository.findAllByOrderByIdDesc().stream().map(this::toHistoryItem).toList();
    }

    private AppVersionDto.HistoryItem toHistoryItem(AppVersion v) {
        return new AppVersionDto.HistoryItem(v.getId(), v.getLatestVersion(), v.getMinSupportedVersion(),
                v.getDownloadUrl(), v.getReleaseNotes(), v.getNote(), v.getCreatedAt());
    }

    // 세 자리 semver 숫자 비교 (테스트 접근용 public)
    public static int compare(String a, String b) {
        String[] pa = a.split("\\."), pb = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int d = Integer.compare(Integer.parseInt(pa[i]), Integer.parseInt(pb[i]));
            if (d != 0) return d;
        }
        return 0;
    }
}
