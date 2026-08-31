package com.semojum.backend.domain.user.service;

import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.global.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * original은 URL 하나 + type으로 구성된다 (2026-08-31 단일화).
 * 이미지가 원칙이고, 렌더 실패 시에만 PDF로 떨어져 원본 패널이 비지 않는다.
 */
class OriginalContentTest {

    S3Service s3Service;
    UserService userService;

    @BeforeEach
    void setUp() {
        s3Service = Mockito.mock(S3Service.class);
        userService = Mockito.mock(UserService.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(userService, "s3Service", s3Service);
    }

    private JobResponseDto.OriginalContent build(String mode, Page page) {
        return ReflectionTestUtils.invokeMethod(userService, "buildOriginal", mode, page);
    }

    private Page page(String imagePath) {
        Page p = Page.builder().pageNo(1).pdfPath("s3://b/job-1/pages/page-1.pdf").build();
        if (imagePath != null) p.updateImagePath(imagePath);
        return p;
    }

    @Test
    void 이미지가_있으면_type은_image이고_url은_JPEG다() {
        when(s3Service.getPresignedUrl(eq("s3://b/job-1/pages/page-1.jpg"), any(Duration.class)))
                .thenReturn("https://signed/page-1.jpg");

        JobResponseDto.OriginalContent original = build("a", page("s3://b/job-1/pages/page-1.jpg"));

        assertEquals("image", original.type());
        assertEquals("https://signed/page-1.jpg", original.url());
        assertNull(original.lines());
    }

    /** 렌더 실패·page-image 비활성 — 원본 패널이 비지 않도록 PDF로 떨어진다 */
    @Test
    void 이미지가_없으면_type은_pdf로_떨어진다() {
        when(s3Service.getPresignedUrl(eq("s3://b/job-1/pages/page-1.pdf"), any(Duration.class)))
                .thenReturn("https://signed/page-1.pdf");

        JobResponseDto.OriginalContent original = build("a", page(null));

        assertEquals("pdf", original.type());
        assertEquals("https://signed/page-1.pdf", original.url());
    }

    /** mode b는 원본이 텍스트라 URL 자체가 없다 */
    @Test
    void mode_b는_text와_lines로_내려간다() {
        when(s3Service.downloadFile(any())).thenReturn("첫 줄\n둘째 줄".getBytes(StandardCharsets.UTF_8));

        JobResponseDto.OriginalContent original = build("b", page(null));

        assertEquals("text", original.type());
        assertNull(original.url());
        assertEquals(List.of("첫 줄", "둘째 줄"), original.lines());
    }
}
