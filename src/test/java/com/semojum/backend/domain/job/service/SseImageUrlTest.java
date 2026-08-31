package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.global.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * SSE page_done의 원본 이미지 URL — 있으면 싣고, 없거나 실패하면 키 자체를 넣지 않는다.
 * (변환 중 화면은 FE가 로컬 파일로 그리므로 없어도 렌더는 된다 — PDF URL은 싣지 않는다)
 */
class SseImageUrlTest {

    PageRepository pageRepository;
    S3Service s3Service;
    SseService sseService;
    Map<String, Object> event;

    @BeforeEach
    void setUp() {
        pageRepository = Mockito.mock(PageRepository.class);
        s3Service = Mockito.mock(S3Service.class);
        sseService = new SseService(null, null, null, null, null, null, null, pageRepository, s3Service);
        event = new LinkedHashMap<>();
    }

    private Page pageWithImage(String imagePath) {
        Page page = Page.builder().pageNo(1).pdfPath("s3://b/job-1/pages/page-1.pdf").build();
        if (imagePath != null) page.updateImagePath(imagePath);
        return page;
    }

    @Test
    void 이미지가_있으면_presigned_URL을_싣는다() {
        when(pageRepository.findByJob_IdAndPageNo("job-1", 1))
                .thenReturn(Optional.of(pageWithImage("s3://b/job-1/pages/page-1.jpg")));
        when(s3Service.getPresignedUrl(eq("s3://b/job-1/pages/page-1.jpg"), any(Duration.class)))
                .thenReturn("https://signed/page-1.jpg");

        sseService.addImageUrl(event, "job-1", 1);

        assertEquals("https://signed/page-1.jpg", event.get("imageUrl"));
    }

    /** mode b·렌더 전·렌더 실패 — 키를 아예 넣지 않아 FE가 기존 로컬 렌더로 진행한다 */
    @Test
    void 이미지가_없으면_키를_넣지_않는다() {
        when(pageRepository.findByJob_IdAndPageNo("job-1", 1))
                .thenReturn(Optional.of(pageWithImage(null)));

        sseService.addImageUrl(event, "job-1", 1);

        assertFalse(event.containsKey("imageUrl"));
    }

    /** URL 생성이 실패해도 예외가 밖으로 나가면 page_done 자체가 전송되지 않는다 */
    @Test
    void URL_생성이_실패해도_예외를_던지지_않는다() {
        when(pageRepository.findByJob_IdAndPageNo("job-1", 1))
                .thenReturn(Optional.of(pageWithImage("s3://b/job-1/pages/page-1.jpg")));
        when(s3Service.getPresignedUrl(any(), any(Duration.class)))
                .thenThrow(new RuntimeException("S3 오류"));

        sseService.addImageUrl(event, "job-1", 1);

        assertFalse(event.containsKey("imageUrl"));
    }
}
