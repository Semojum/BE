package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.global.pdf.PdfPageRenderer;
import com.semojum.backend.global.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 미리보기 이미지 생성 — 성공하면 경로가 Page에 남고, 실패해도 변환을 막지 않아야 한다.
 * (이미지가 없으면 조회 API가 원본 PDF로 폴백하므로 화면은 나온다)
 */
class PageImageServiceTest {

    PdfPageRenderer renderer;
    S3Service s3Service;
    PageRepository pageRepository;
    PageImageService service;
    Page page;

    @BeforeEach
    void setUp() {
        renderer = Mockito.mock(PdfPageRenderer.class);
        s3Service = Mockito.mock(S3Service.class);
        pageRepository = Mockito.mock(PageRepository.class);
        service = new PageImageService(renderer, s3Service, pageRepository);
        ReflectionTestUtils.setField(service, "dpi", 150);
        ReflectionTestUtils.setField(service, "enabled", true);

        page = Page.builder().pageNo(1).pdfPath("s3://b/job-1/pages/page-1.pdf").build();
        when(pageRepository.findByJob_IdAndPageNo("job-1", 1)).thenReturn(Optional.of(page));
    }

    @Test
    void 렌더에_성공하면_이미지_경로가_남는다() throws Exception {
        when(renderer.renderFirstPage(any(), eq(PdfPageRenderer.Format.JPEG), anyInt()))
                .thenReturn(new byte[]{1, 2, 3});
        when(s3Service.uploadFile(eq("job-1/pages/page-1.jpg"), any(), eq("image/jpeg")))
                .thenReturn("s3://b/job-1/pages/page-1.jpg");

        service.generateAndStore("job-1", 1, new byte[]{9});

        assertEquals("s3://b/job-1/pages/page-1.jpg", page.getImagePath());
    }

    /** 렌더가 실패해도 예외가 밖으로 나가면 안 된다 — 워커의 변환 흐름이 끊긴다 */
    @Test
    void 렌더에_실패해도_예외를_던지지_않고_경로는_비워둔다() throws Exception {
        when(renderer.renderFirstPage(any(), any(), anyInt()))
                .thenThrow(new IOException("pdftoppm exit=1"));

        service.generateAndStore("job-1", 1, new byte[]{9});

        assertNull(page.getImagePath());
        verify(s3Service, never()).uploadFile(any(), any(), any());
    }

    /** 스위치를 끄면 렌더 자체를 하지 않는다 (문제 시 즉시 회귀 수단) */
    @Test
    void 비활성화되면_아무것도_하지_않는다() throws Exception {
        ReflectionTestUtils.setField(service, "enabled", false);

        service.generateAndStore("job-1", 1, new byte[]{9});

        verify(renderer, never()).renderFirstPage(any(), any(), anyInt());
        assertNull(page.getImagePath());
    }
}
