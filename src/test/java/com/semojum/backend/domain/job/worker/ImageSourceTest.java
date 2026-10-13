package com.semojum.backend.domain.job.worker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 워커가 원본 조각의 종류를 경로로 가른다 (V3.1 이미지 업로드).
 *
 * <p>태스크에 종류 표시를 넣지 않은 이유: 큐가 Redis라 배포로 비워지지 않아서, 필드를 늘리면
 * 그 순간 큐에 남아 있던 구 형식 태스크엔 그 값이 없다. 경로는 어느 형식에나 이미 있다.
 */
class ImageSourceTest {

    @Test
    void 이미지_경로를_알아본다() {
        assertTrue(PageWorker.isImageSource("job-1/pages/page-1.jpg"));
        assertTrue(PageWorker.isImageSource("job-1/pages/page-1.jpeg"));
        assertTrue(PageWorker.isImageSource("job-1/pages/page-1.png"));
        assertTrue(PageWorker.isImageSource("job-1/pages/page-1.PNG"));
    }

    /** PDF·TXT를 이미지로 보면 pdf_data 대신 image_data로 나가 AI가 못 읽는다 */
    @Test
    void 문서_경로는_이미지가_아니다() {
        assertFalse(PageWorker.isImageSource("job-1/pages/page-1.pdf"));
        assertFalse(PageWorker.isImageSource("job-1/pages/page-1.txt"));
        assertFalse(PageWorker.isImageSource(null));
    }

    /** .jpg의 MIME은 image/jpeg다 — "image/" + 확장자로 만들면 없는 타입이 나간다 */
    @Test
    void MIME_타입으로_알린다() {
        assertEquals("image/png", PageWorker.imageMimeTypeOf("job-1/pages/page-2.png"));
        assertEquals("image/jpeg", PageWorker.imageMimeTypeOf("job-1/pages/page-2.jpg"));
        assertEquals("image/jpeg", PageWorker.imageMimeTypeOf("job-1/pages/page-2.jpeg"));
    }
}
