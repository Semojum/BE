package com.semojum.backend.domain.job.service;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.gcs.GcsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final PageRepository pageRepository;
    private final UserRepository userRepository;
    private final GcsService gcsService;
    private final RedisTemplate<String, String> redisTemplate;

    @Transactional
    public JobResponseDto.Create createJob(String email, MultipartFile file, String mode) throws IOException {

        // 1. 유저 조회
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 2. 모드 검증
        if (!List.of("a", "b", "c").contains(mode)) {
            throw new CustomException(ErrorCode.JOB_INVALID_MODE);
        }

        // 3. 파일 타입 검증
        String originalFilename = file.getOriginalFilename();
        String ext = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase()
                : "";
        if (List.of("a", "c").contains(mode)) {
            if (!ext.equals("pdf")) {
                throw new CustomException(ErrorCode.JOB_INVALID_FILE);
            }
        } else if (mode.equals("b")) {
            if (!List.of("txt", "hwp").contains(ext)) {
                throw new CustomException(ErrorCode.JOB_INVALID_FILE);
            }
        }

        // 4. job_id 발급
        String jobId = "job_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        // 5. PDF 페이지별 분리 및 GCS 업로드
        byte[] pdfBytes = file.getBytes();
        List<Page> pages = new ArrayList<>();

        try (PdfReader reader = new PdfReader(new java.io.ByteArrayInputStream(pdfBytes));
             PdfDocument pdfDoc = new PdfDocument(reader)) {

            int totalPages = pdfDoc.getNumberOfPages();

            // 6. Job 엔티티 생성 및 저장
            Job job = Job.builder()
                    .id(jobId)
                    .user(user)
                    .mode(mode)
                    .totalPages(totalPages)
                    .build();
            jobRepository.saveAndFlush(job);

            // 7. 페이지별 처리
            for (int i = 1; i <= totalPages; i++) {
                // 페이지 PDF 추출
                ByteArrayOutputStream pageOut = new ByteArrayOutputStream();
                try (PdfDocument pageDoc = new PdfDocument(new PdfWriter(pageOut))) {
                    pdfDoc.copyPagesTo(i, i, pageDoc);
                }

                // GCS 업로드
                String gcsPath = jobId + "/pages/page-" + i + ".pdf";
                String fullPath = gcsService.uploadFile(gcsPath, pageOut.toByteArray(), "application/pdf");

                // Page 엔티티 생성
                Page page = Page.builder()
                        .job(job)
                        .pageNo(i)
                        .pdfPath(fullPath)
                        .build();
                pages.add(page);

                // Redis task_queue에 등록
                String task = String.format(
                        "{\"jobId\":\"%s\",\"pageNo\":%d,\"gcsPath\":\"%s\",\"mode\":\"%s\"}",
                        jobId, i, fullPath, mode
                );
                redisTemplate.opsForList().leftPush("task_queue", task);

                // Redis 상태 초기화
                redisTemplate.opsForHash().put("job:" + jobId + ":pages", "page:" + i, "PENDING");
            }

            // 8. Page 일괄 저장
            pageRepository.saveAll(pages);

            return new JobResponseDto.Create(jobId, mode, totalPages, "PENDING");
        }
    }
}