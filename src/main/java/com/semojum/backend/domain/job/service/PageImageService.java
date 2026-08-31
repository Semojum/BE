package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.global.pdf.PdfPageRenderer;
import com.semojum.backend.global.s3.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원본 페이지를 미리보기용 이미지(JPEG)로 렌더해 S3에 올리고 경로를 Page에 기록한다.
 *
 * <p><b>왜 이미지인가</b>: FE 좌측 원본 패널이 PDF를 pdf.js로 직접 그리는데, 같은 화면을 그리는 비용이
 * 스캔본에서 1,807~2,853ms였다(2026-08-31 실측 — JPEG 2000은 브라우저 네이티브 디코더가 없어
 * WASM으로 소프트웨어 디코딩된다). 서버가 미리 구운 JPEG를 {@code <img>}로 그리면 6~9ms다.
 *
 * <p><b>실패는 삼킨다</b>: 이미지가 없으면 조회 API가 원본 PDF로 폴백하므로(느릴 뿐 화면은 나온다)
 * 렌더 실패가 변환 자체를 막지 않는다 — 썸네일과 같은 원칙.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageImageService {

    private final PdfPageRenderer pdfPageRenderer;
    private final S3Service s3Service;
    private final PageRepository pageRepository;

    // 에디터 통상 확대 배율에서 선명한 수준. 올리면 용량·렌더 시간이 함께 늘어난다
    @Value("${page-image.dpi:150}")
    private int dpi;

    @Value("${page-image.enabled:true}")
    private boolean enabled;

    /** mode a·c 전용 (b는 원본이 텍스트라 이미지가 없다). 실패해도 예외를 밖으로 던지지 않는다. */
    @Transactional
    public void generateAndStore(String jobId, int pageNo, byte[] pdfBytes) {
        if (!enabled) return;
        try {
            byte[] jpeg = pdfPageRenderer.renderFirstPage(pdfBytes, PdfPageRenderer.Format.JPEG, dpi);
            String path = s3Service.uploadFile(jobId + "/pages/page-" + pageNo + ".jpg", jpeg, "image/jpeg");
            pageRepository.findByJob_IdAndPageNo(jobId, pageNo)
                    .ifPresent(page -> page.updateImagePath(path));
            log.info("페이지 이미지 생성: jobId={}, pageNo={}, {}KB", jobId, pageNo, jpeg.length / 1024);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("페이지 이미지 생성 중단: jobId={}, pageNo={}", jobId, pageNo);
        } catch (Exception e) {
            // 원본 PDF 폴백으로 화면은 나온다 — 변환을 막지 않는다
            log.warn("페이지 이미지 생성 실패(원본 PDF 폴백): jobId={}, pageNo={}, error={}",
                    jobId, pageNo, e.getMessage());
        }
    }
}
