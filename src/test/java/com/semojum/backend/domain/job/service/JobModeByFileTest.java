package com.semojum.backend.domain.job.service;

import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 3패널 통합(2026-09-30) — 모드는 사용자가 고르지 않고 파일 종류가 정한다 */
class JobModeByFileTest {

    @Test
    void PDF_HWP_HWPX는_텍스트와_점자를_한번에_주는_c() {
        assertEquals("c", JobService.modeForExtension("pdf"));
        assertEquals("c", JobService.modeForExtension("hwp"));
        assertEquals("c", JobService.modeForExtension("hwpx"));
    }

    @Test
    void TXT는_점자만_주는_b() {
        assertEquals("b", JobService.modeForExtension("txt"));
    }

    @Test
    void 그_밖의_파일은_JOB4002() {
        for (String ext : new String[]{"docx", "jpg", ""}) {
            assertEquals(ErrorCode.JOB_INVALID_FILE,
                    assertThrows(CustomException.class, () -> JobService.modeForExtension(ext)).getErrorCode(), ext);
        }
    }
}
