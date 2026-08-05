package com.semojum.backend.domain.result.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** proto V3.0.0의 image_resolution("2480x3505") 파싱 검증 */
class ImageResolutionParseTest {

    @Test
    void 정상_해상도_파싱() {
        int[] result = ResultService.parseImageResolution("2480x3505");
        assertArrayEquals(new int[]{2480, 3505}, result);
    }

    @Test
    void 공백_섞여도_파싱() {
        int[] result = ResultService.parseImageResolution("2480 x 3505");
        assertArrayEquals(new int[]{2480, 3505}, result);
    }

    @Test
    void 빈문자열은_null_모드b() {
        assertNull(ResultService.parseImageResolution(""));
    }

    @Test
    void 형식_불일치는_null() {
        assertNull(ResultService.parseImageResolution("2480"));
        assertNull(ResultService.parseImageResolution("2480x3505x99"));
        assertNull(ResultService.parseImageResolution("axb"));
    }

    @Test
    void 비양수는_null() {
        assertNull(ResultService.parseImageResolution("0x3505"));
        assertNull(ResultService.parseImageResolution("2480x-1"));
    }
}
