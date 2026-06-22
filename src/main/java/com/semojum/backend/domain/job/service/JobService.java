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
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.reader.HWPReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final com.semojum.backend.global.thumbnail.ThumbnailService thumbnailService;

    private static final int LINES_PER_PAGE = 30;

    @Transactional
    public JobResponseDto.Create createJob(String userId, MultipartFile file, String mode) throws Exception {

        // UUID로 유저 조회
        User user = userRepository.findById(UUID.fromString(userId))
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

        List<Page> pages = new ArrayList<>();

        if (mode.equals("b")) {
            // 5-b. txt/hwp → 텍스트 추출 후 30줄 단위로 분리하여 GCS 업로드

            // 텍스트 추출
            String fullText;
            if (ext.equals("hwp")) {
                fullText = extractTextFromHwp(file);
            } else {
                fullText = new String(file.getBytes(), StandardCharsets.UTF_8);
            }

            // 30줄 단위로 분리
            String[] lines = fullText.split("\n");
            List<String> chunks = new ArrayList<>();
            for (int i = 0; i < lines.length; i += LINES_PER_PAGE) {
                int end = Math.min(i + LINES_PER_PAGE, lines.length);
                chunks.add(String.join("\n", Arrays.copyOfRange(lines, i, end)));
            }
            if (chunks.isEmpty()) chunks.add(fullText);

            int totalPages = chunks.size();

            // 6-b. Job 엔티티 생성 및 저장
            Job job = Job.builder()
                    .id(jobId)
                    .user(user)
                    .mode(mode)
                    .totalPages(totalPages)
                    .originalFileName(file.getOriginalFilename())
                    .build();
            jobRepository.saveAndFlush(job);

            try {
                byte[] thumbnailBytes = thumbnailService.generateTextThumbnail(chunks.get(0));
                String thumbnailGcsPath = jobId + "/thumbnail.png";
                String thumbnailFullPath = gcsService.uploadFile(thumbnailGcsPath, thumbnailBytes, "image/png");
                job.updateThumbnailUrl(gcsService.getPublicUrl(thumbnailFullPath));
                jobRepository.save(job);
            } catch (Exception e) {
                log.warn("썸네일 생성 실패: jobId={}", jobId, e);
            }

            // total_pages Redis에 저장
            redisTemplate.opsForHash().put("job:" + jobId + ":pages", "total_pages", String.valueOf(totalPages));

            // 7-b. 청크별 처리
            for (int i = 0; i < totalPages; i++) {
                int pageNo = i + 1;

                // GCS 업로드 (txt로 통일)
                String gcsPath = jobId + "/pages/page-" + pageNo + ".txt";
                byte[] chunkBytes = chunks.get(i).getBytes(StandardCharsets.UTF_8);
                String fullPath = gcsService.uploadFile(gcsPath, chunkBytes, "text/plain");

                // Page 엔티티 생성
                Page page = Page.builder()
                        .job(job)
                        .pageNo(pageNo)
                        .pdfPath(fullPath)
                        .build();
                pages.add(page);

                // Redis task_queue에 등록
                String task = String.format(
                        "{\"jobId\":\"%s\",\"pageNo\":%d,\"gcsPath\":\"%s\",\"mode\":\"%s\",\"totalPages\":%d}",
                        jobId, pageNo, fullPath, mode, totalPages
                );
                redisTemplate.opsForList().leftPush("task_queue", task);

                // Redis 상태 초기화
                redisTemplate.opsForHash().put("job:" + jobId + ":pages", "page:" + pageNo, "PENDING");
            }

            // 8-b. Page 일괄 저장
            pageRepository.saveAll(pages);

            return new JobResponseDto.Create(jobId, mode, totalPages, "PENDING");

        } else {
            // 5. PDF 페이지별 분리 및 GCS 업로드
            byte[] pdfBytes = file.getBytes();

            try (PdfReader reader = new PdfReader(new java.io.ByteArrayInputStream(pdfBytes));
                 PdfDocument pdfDoc = new PdfDocument(reader)) {

                int totalPages = pdfDoc.getNumberOfPages();

                // 6. Job 엔티티 생성 및 저장
                Job job = Job.builder()
                        .id(jobId)
                        .user(user)
                        .mode(mode)
                        .totalPages(totalPages)
                        .originalFileName(file.getOriginalFilename())
                        .build();
                jobRepository.saveAndFlush(job);

                try {
                    byte[] thumbnailBytes = thumbnailService.generatePdfThumbnail(pdfBytes);
                    String thumbnailGcsPath = jobId + "/thumbnail.png";
                    String thumbnailFullPath = gcsService.uploadFile(thumbnailGcsPath, thumbnailBytes, "image/png");
                    job.updateThumbnailUrl(gcsService.getPublicUrl(thumbnailFullPath));
                    jobRepository.save(job);
                } catch (Exception e) {
                    log.warn("썸네일 생성 실패: jobId={}", jobId, e);
                }

                // total_pages Redis에 저장
                redisTemplate.opsForHash().put("job:" + jobId + ":pages", "total_pages", String.valueOf(totalPages));

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
                            "{\"jobId\":\"%s\",\"pageNo\":%d,\"gcsPath\":\"%s\",\"mode\":\"%s\",\"totalPages\":%d}",
                            jobId, i, fullPath, mode, totalPages
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

    // job 상태 조회 (Redis Hash에서 페이지별 상태 조회)
    public JobResponseDto.Status getJobStatus(String jobId) {
        Map<Object, Object> redisData = redisTemplate.opsForHash().entries("job:" + jobId + ":pages");

        if (redisData.isEmpty()) {
            throw new CustomException(ErrorCode.JOB_NOT_FOUND);
        }

        int totalPages = Integer.parseInt((String) redisData.get("total_pages"));
        int completedPages = 0;
        int pendingPages = 0;
        int runningPages = 0;
        Map<String, String> pages = new HashMap<>();

        for (Map.Entry<Object, Object> entry : redisData.entrySet()) {
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();

            if (key.equals("total_pages")) continue;

            pages.put(key, value);

            switch (value) {
                case "COMPLETED", "NEEDS_REVIEW", "BLOCKED" -> completedPages++;
                case "RUNNING" -> runningPages++;
                case "PENDING" -> pendingPages++;
            }
        }

        // 전체 job 상태 계산
        String overallStatus;
        if (completedPages == totalPages) {
            overallStatus = "COMPLETED";
        } else if (runningPages > 0 || completedPages > 0) {
            overallStatus = "IN_PROGRESS";
        } else {
            overallStatus = "PENDING";
        }

        return new JobResponseDto.Status(jobId, totalPages, completedPages, pendingPages, runningPages, overallStatus, pages);
    }

    // HWP 텍스트 추출
    private String extractTextFromHwp(MultipartFile file) throws Exception {
        HWPFile hwpFile = HWPReader.fromInputStream(file.getInputStream());
        StringBuilder sb = new StringBuilder();
        for (Section section : hwpFile.getBodyText().getSectionList()) {
            for (int i = 0; i < section.getParagraphCount(); i++) {
                Paragraph paragraph = section.getParagraph(i);
                if (paragraph.getNormalString() != null) {
                    sb.append(paragraph.getNormalString()).append("\n");
                }
            }
        }
        return sb.toString();
    }
}