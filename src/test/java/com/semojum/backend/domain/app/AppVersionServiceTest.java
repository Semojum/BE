package com.semojum.backend.domain.app;

import com.semojum.backend.domain.app.dto.AppVersionDto;
import com.semojum.backend.domain.app.entity.AppVersion;
import com.semojum.backend.domain.app.repository.AppVersionRepository;
import com.semojum.backend.domain.app.service.AppVersionService;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class AppVersionServiceTest {

    private AppVersionRepository repository;
    private AppVersionService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(AppVersionRepository.class);
        service = new AppVersionService(repository);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void 등록_정상() {
        var item = service.register(new AppVersionDto.Register("1.2.0", "1.0.0", "https://dl", "노트", null));
        assertEquals("1.2.0", item.latestVersion());
        assertEquals("1.0.0", item.minSupportedVersion());
    }

    @Test
    void 세자리_semver_아니면_거절() {
        CustomException e = assertThrows(CustomException.class, () ->
                service.register(new AppVersionDto.Register("1.2", "1.0.0", null, null, null)));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 최소_지원이_최신보다_크면_거절() {
        CustomException e = assertThrows(CustomException.class, () ->
                service.register(new AppVersionDto.Register("1.2.0", "2.0.0", null, null, null)));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 미등록이면_null_앱은_검사_생략() {
        when(repository.findTopByOrderByIdDesc()).thenReturn(Optional.empty());
        assertNull(service.getCurrent());
    }

    @Test
    void 최신_행이_현재_버전() {
        when(repository.findTopByOrderByIdDesc()).thenReturn(Optional.of(
                AppVersion.builder().latestVersion("1.3.0").minSupportedVersion("1.1.0").build()));
        var cur = service.getCurrent();
        assertEquals("1.3.0", cur.latestVersion());
    }

    @Test
    void 버전_비교_자릿수() {
        assertTrue(AppVersionService.compare("1.10.0", "1.9.9") > 0);   // 문자열 비교였다면 틀리는 케이스
        assertEquals(0, AppVersionService.compare("2.0.0", "2.0.0"));
    }
}
