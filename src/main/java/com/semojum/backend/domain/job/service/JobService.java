package com.semojum.backend.domain.job.service;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.s3.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private final S3Service s3Service;
    private final RedisTemplate<String, String> redisTemplate;
    private final com.semojum.backend.domain.job.scheduler.JobDispatcher jobDispatcher;
    private final com.semojum.backend.global.thumbnail.ThumbnailService thumbnailService;
    private final com.semojum.backend.global.hwp.HwpToPdfConverter hwpToPdfConverter;
    private final FooterBrailleService footerBrailleService;

    private static final int LINES_PER_PAGE = 30;

    @Transactional
    public JobResponseDto.Create createJob(String userId, MultipartFile file, String mode,
                                           boolean insertPageNumber, String footerText,
                                           LayoutOptions requestedOptions,
                                           com.semojum.backend.global.util.ClientInfoResolver.ClientInfo clientInfo)
            throws Exception {

        // 조판 옵션 — 빠진 값은 기본값으로 채워 항상 완전한 형태로 저장한다(읽는 쪽에 null 분기를 안 남긴다)
        LayoutOptions layoutOptions = validateLayoutOptions(
                requestedOptions == null ? LayoutOptions.legacy(insertPageNumber) : requestedOptions.withDefaults());

        // 꼬리말(묵자) 정리 — 빈 값은 null, 200자 초과는 거절 (TranslateText 입력 상한)
        footerText = footerText == null || footerText.isBlank() ? null : footerText.trim();
        if (footerText != null && footerText.length() > 200) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }

        // UUID로 유저 조회
        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 기관 관리자는 점역(에디터) 기능 사용 불가 — T2 관리 화면 전용 (기획 확정 2026-08-19)
        if (user.getRole() == com.semojum.backend.domain.auth.enums.Role.ROLE_ORG_ADMIN) {
            log.warn("기관 관리자 계정의 Job 생성 시도 거부: user={}", user.getLoginId());
            throw new CustomException(ErrorCode.COMMON_FORBIDDEN);
        }

        log.info("Job 생성 시작: mode={}, file={} ({}KB), user={}",
                mode, file.getOriginalFilename(), file.getSize() / 1024, user.getLoginId());

        // 2. 모드 검증
        if (!List.of("a", "b", "c").contains(mode)) {
            throw new CustomException(ErrorCode.JOB_INVALID_MODE);
        }

        // 3. 파일 타입 검증
        String originalFilename = file.getOriginalFilename();
        String ext = originalFilename != null && originalFilename.contains(".")
                ? originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase()
                : "";
        // mode a는 HWP도 허용(2026-08-24) — 업로드 시 PDF로 변환해 기존 PDF 파이프라인에 태운다.
        // mode b는 TXT 전용으로 축소(HWP는 a로 이관 — 텍스트 추출 대신 렌더링 보존 방식)
        if (mode.equals("a")) {
            if (!List.of("pdf", "hwp").contains(ext)) {
                throw new CustomException(ErrorCode.JOB_INVALID_FILE);
            }
        } else if (mode.equals("c")) {
            if (!ext.equals("pdf")) {
                throw new CustomException(ErrorCode.JOB_INVALID_FILE);
            }
        } else if (mode.equals("b")) {
            if (!ext.equals("txt")) {
                throw new CustomException(ErrorCode.JOB_INVALID_FILE);
            }
        }

        // 4. job_id 발급
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
        String jobId = "job_" + timestamp + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        List<Page> pages = new ArrayList<>();
        List<String> tasks = new ArrayList<>();

        if (mode.equals("b")) {
            // 5-b. txt → 30줄 단위 청크로 분리하여 S3 업로드
            // (HWP는 2026-08-24부터 mode a에서 PDF 변환으로 처리 — 텍스트 추출 경로 폐기)
            String fullText = new String(file.getBytes(), StandardCharsets.UTF_8);
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
                    .originalFileName(resolveFileName(user.getId(), file.getOriginalFilename()))
                    .insertPageNumber(insertPageNumber)
                    .footerText(footerText)
                    .layoutOptions(layoutOptions)
                    .build();
            // 꼬리말 점역 (V31) — 화면·SSE·다운로드가 같은 값을 쓰도록 여기서 한 번만 한다.
            // AI 호출 실패는 삼키고(썸네일과 같은 취급), 판면에 안 들어갈 길이면 COMMON4000
            job.updateFooterBraille(
                    footerBrailleService.translateForUpload(footerText, layoutOptions, totalPages));
            if (clientInfo != null) {
                job.recordClientInfo(clientInfo.ip(), clientInfo.os(), clientInfo.browser(), clientInfo.userAgent());
            }
            jobRepository.saveAndFlush(job);

            try {
                byte[] thumbnailBytes = thumbnailService.generateTextThumbnail(chunks.get(0));
                String thumbnailGcsPath = jobId + "/thumbnail.png";
                String thumbnailFullPath = s3Service.uploadFile(thumbnailGcsPath, thumbnailBytes, "image/png");
                job.updateThumbnailUrl(s3Service.getPublicUrl(thumbnailFullPath));
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
                String fullPath = s3Service.uploadFile(gcsPath, chunkBytes, "text/plain");

                // Page 엔티티 생성
                Page page = Page.builder()
                        .job(job)
                        .pageNo(pageNo)
                        .pdfPath(fullPath)
                        .build();
                pages.add(page);

                // 공정 스케줄러 태스크 (Job 저장 후 일괄 등록 — userId는 재시도 시 링 재등록용)
                String task = String.format(
                        "{\"jobId\":\"%s\",\"pageNo\":%d,\"gcsPath\":\"%s\",\"mode\":\"%s\",\"totalPages\":%d,\"userId\":\"%s\",\"advancedAi\":%b}",
                        jobId, pageNo, fullPath, mode, totalPages, user.getId(), layoutOptions.advancedAi()
                );
                tasks.add(task);

                // Redis 상태 초기화
                redisTemplate.opsForHash().put("job:" + jobId + ":pages", "page:" + pageNo, "PENDING");
            }

            // 8-b. Page 일괄 저장 후 스케줄러 등록 (DB에 Page가 있어야 워커가 결과를 저장할 수 있음)
            pageRepository.saveAll(pages);
            jobDispatcher.enqueueJob(user.getId().toString(), jobId, tasks);

            log.info("Job 생성 완료: jobId={}, totalPages={} — 큐 적재됨", jobId, totalPages);
            return new JobResponseDto.Create(jobId, mode, totalPages, "PENDING", insertPageNumber, footerText, layoutOptions);

        } else {
            // 5. PDF 페이지별 분리 및 GCS 업로드.
            // mode a의 HWP는 업로드 시점에 PDF로 변환(2026-08-24) — 이후는 PDF와 완전히 동일하게 처리
            byte[] pdfBytes;
            if (ext.equals("hwp")) {
                long convertStart = System.currentTimeMillis();
                pdfBytes = hwpToPdfConverter.convert(file.getBytes());
                log.info("HWP→PDF 변환 완료: {}KB → {}KB ({}ms)",
                        file.getSize() / 1024, pdfBytes.length / 1024,
                        System.currentTimeMillis() - convertStart);
            } else {
                pdfBytes = file.getBytes();
            }

            try (PdfReader reader = new PdfReader(new java.io.ByteArrayInputStream(pdfBytes));
                 PdfDocument pdfDoc = new PdfDocument(reader)) {

                int totalPages = pdfDoc.getNumberOfPages();

                // 6. Job 엔티티 생성 및 저장
                Job job = Job.builder()
                        .id(jobId)
                        .user(user)
                        .mode(mode)
                        .totalPages(totalPages)
                        .originalFileName(resolveFileName(user.getId(), file.getOriginalFilename()))
                        .insertPageNumber(insertPageNumber)
                        .footerText(footerText)
                        .layoutOptions(layoutOptions)
                        .build();
                // 꼬리말 점역 (V31) — 화면·SSE·다운로드가 같은 값을 쓰도록 여기서 한 번만 한다.
                // AI 호출 실패는 삼키고(썸네일과 같은 취급), 판면에 안 들어갈 길이면 COMMON4000
                job.updateFooterBraille(
                        footerBrailleService.translateForUpload(footerText, layoutOptions, totalPages));
                if (clientInfo != null) {
                    job.recordClientInfo(clientInfo.ip(), clientInfo.os(), clientInfo.browser(), clientInfo.userAgent());
                }
                jobRepository.saveAndFlush(job);

                try {
                    byte[] thumbnailBytes = thumbnailService.generatePdfThumbnail(pdfBytes);
                    String thumbnailGcsPath = jobId + "/thumbnail.png";
                    String thumbnailFullPath = s3Service.uploadFile(thumbnailGcsPath, thumbnailBytes, "image/png");
                    job.updateThumbnailUrl(s3Service.getPublicUrl(thumbnailFullPath));
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
                    String fullPath = s3Service.uploadFile(gcsPath, pageOut.toByteArray(), "application/pdf");

                    // Page 엔티티 생성
                    Page page = Page.builder()
                            .job(job)
                            .pageNo(i)
                            .pdfPath(fullPath)
                            .build();
                    pages.add(page);

                    // 공정 스케줄러 태스크 (Job 저장 후 일괄 등록 — userId는 재시도 시 링 재등록용)
                    String task = String.format(
                            "{\"jobId\":\"%s\",\"pageNo\":%d,\"gcsPath\":\"%s\",\"mode\":\"%s\",\"totalPages\":%d,\"userId\":\"%s\",\"advancedAi\":%b}",
                            jobId, i, fullPath, mode, totalPages, user.getId(), layoutOptions.advancedAi()
                    );
                    tasks.add(task);

                    // Redis 상태 초기화
                    redisTemplate.opsForHash().put("job:" + jobId + ":pages", "page:" + i, "PENDING");
                }

                // 8. Page 일괄 저장 후 스케줄러 등록 (DB에 Page가 있어야 워커가 결과를 저장할 수 있음)
                pageRepository.saveAll(pages);
                jobDispatcher.enqueueJob(user.getId().toString(), jobId, tasks);

                log.info("Job 생성 완료: jobId={}, totalPages={} — 큐 적재됨", jobId, totalPages);
                return new JobResponseDto.Create(jobId, mode, totalPages, "PENDING", insertPageNumber, footerText, layoutOptions);
            }
        }
    }

    /**
     * 조판 옵션 검증 — 값이 이상하면 조판이 조용히 깨지는 대신 업로드에서 막는다.
     * 칸·줄 수 하한은 BrailleAssist가 던지는 조건(cols ≥ 8)에 맞추고, 상한은 실물 규격에서 나올 수 없는 값으로 둔다.
     */
    private LayoutOptions validateLayoutOptions(LayoutOptions o) {
        boolean valid = o.cellsPerLine() >= 8 && o.cellsPerLine() <= 100
                && o.linesPerPage() >= 4 && o.linesPerPage() <= 100
                && o.coverPages() >= 0 && o.sourcePageStart() >= 0 && o.braillePageStart() >= 1
                && List.of("odd", "every", "none").contains(o.pageNumberLine())
                && List.of("center", "right").contains(o.footerAlign())
                && List.of("all", "page").contains(o.editScope());
        if (!valid) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        return o;
    }

    /**
     * 이 작업의 업로드 설정 조회 — 조판 옵션 + 꼬리말.
     *
     * <p>옵션 없이 만들어진 기존 작업도 기본값이 채워진 완전한 형태를 준다(resolveLayoutOptions).
     * 페이지 조회와 달리 <b>변환 결과가 없어도</b> 볼 수 있다 — 설정은 업로드 시점에 확정되므로
     * 변환 중·실패한 작업에서도 필요하다.
     */
    @Transactional(readOnly = true)
    public JobResponseDto.Options getJobOptions(String userId, String jobId) {
        // 타인 작업이면 403 (페이지 조회와 같은 기준)
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));
        return new JobResponseDto.Options(
                job.getId(), job.isInsertPageNumber(), job.getFooterText(),
                footerBrailleService.resolve(job), job.resolveLayoutOptions());
    }

    // job 상태 조회 (Redis Hash에서 페이지별 상태 조회)
    public JobResponseDto.Status getJobStatus(String jobId) {
        Map<Object, Object> redisData = redisTemplate.opsForHash().entries("job:" + jobId + ":pages");

        if (redisData.isEmpty()) {
            throw new CustomException(ErrorCode.JOB_NOT_FOUND);
        }

        // 상태를 폴링한다 = 사용자가 보고 있다 → FG 리스 갱신 (공정 스케줄러 우선순위)
        jobDispatcher.touchForeground(jobId);

        int totalPages = Integer.parseInt((String) redisData.get("total_pages"));
        // 끝난 쪽 수 — BLOCKED(실패)도 포함한다. 진행률의 분자라서 실패 쪽을 빼면
        // 실패가 섞인 작업은 분자가 total에 영원히 못 닿아 화면이 "진행 중"에 멈춘다.
        // 이름이 내용과 어긋나지만 이미 나간 응답 필드라 의미를 흔들지 않는다.
        int completedPages = 0;
        // 그중 결과가 나온 쪽(COMPLETED·NEEDS_REVIEW) — 종료 판정에만 쓴다
        int succeededPages = 0;
        int pendingPages = 0;
        int runningPages = 0;
        Map<String, String> pages = new HashMap<>();

        for (Map.Entry<Object, Object> entry : redisData.entrySet()) {
            String key = (String) entry.getKey();
            String value = (String) entry.getValue();

            if (key.equals("total_pages")) continue;

            pages.put(key, value);

            switch (value) {
                case "COMPLETED", "NEEDS_REVIEW" -> { completedPages++; succeededPages++; }
                case "BLOCKED" -> completedPages++;   // 끝났지만 성공은 아니다
                case "RUNNING" -> runningPages++;
                case "PENDING" -> pendingPages++;
            }
        }

        // 전체 job 상태 계산 — 판정 규칙은 ResultService.evaluateJobTermination과 같아야 한다.
        // 전 쪽이 terminal일 때 성공 0건이면 FAILED다. 예전엔 BLOCKED를 성공과 묶어 세는 바람에
        // 취소로 전 쪽이 BLOCKED된 작업을 COMPLETED라고 답했고, 같은 작업을 DB로 읽는
        // 마이페이지·운영자 화면은 FAILED라 서로 어긋났다(2026-09-02 실측).
        String overallStatus;
        if (completedPages == totalPages) {
            overallStatus = succeededPages > 0 ? "COMPLETED" : "FAILED";
        } else if (runningPages > 0 || completedPages > 0) {
            overallStatus = "IN_PROGRESS";
        } else {
            overallStatus = "PENDING";
        }

        return new JobResponseDto.Status(jobId, totalPages, completedPages, pendingPages, runningPages, overallStatus, pages);
    }

    // V3: 파일 이름은 하나만 사용. 활성 목록에 같은 이름이 있으면 "(2)", "(3)"… 자동 부여
    private String resolveFileName(java.util.UUID userId, String originalFileName) {
        String base = originalFileName != null ? originalFileName : "이름 없는 작업";
        if (base.length() > 100) base = base.substring(0, 100);
        if (!jobRepository.existsActiveFileName(userId, base)) {
            return base;
        }
        for (int n = 2; n <= 999; n++) {
            String candidate = base + " (" + n + ")";
            if (candidate.length() > 100) {
                candidate = base.substring(0, Math.max(1, 100 - (" (" + n + ")").length())) + " (" + n + ")";
            }
            if (!jobRepository.existsActiveFileName(userId, candidate)) {
                return candidate;
            }
        }
        return base + " (" + System.currentTimeMillis() + ")";
    }

}