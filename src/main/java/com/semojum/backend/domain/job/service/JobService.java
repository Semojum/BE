package com.semojum.backend.domain.job.service;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.job.dto.BrailleOptions;
import com.semojum.backend.domain.job.dto.JobRequestDto;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.hwp.HwpToPdfConverter;
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
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final UploadOptionsResolver uploadOptionsResolver;


    @Transactional
    /**
     * 파일 확장자 → 변환 모드. PDF·HWP·HWPX는 c(텍스트+점자), TXT는 b(점자).
     * HWP·HWPX는 업로드 시 PDF로 바꿔 PDF와 같은 길을 탄다 — 내용물이 HWPX인 .hwp도 변환기가 알아서 가른다.
     */
    static String modeForExtension(String ext) {
        return switch (ext) {
            case "pdf", "hwp", "hwpx" -> "c";
            case "jpg", "jpeg", "png" -> "c";
            case "txt" -> "b";
            default -> throw new CustomException(ErrorCode.JOB_INVALID_FILE);
        };
    }

    /**
     * 이미지 업로드 확장자 (V3.1). 피그마는 JPG·PNG라고만 적었지만 .jpeg는 같은 형식이라 함께 받는다.
     *
     * <p>이미지도 모드는 c다 — 원본 | 텍스트 | 점자 세 패널이 나오는 건 PDF와 같고,
     * 다른 점은 쪽을 쪼갤 필요가 없다는 것뿐이다(한 장이 한 쪽).
     */
    static final java.util.Set<String> IMAGE_EXTS = java.util.Set.of("jpg", "jpeg", "png");

    static boolean isImage(String ext) {
        return IMAGE_EXTS.contains(ext);
    }

    /** 파일 이름의 확장자 (소문자, 점 없음). 확장자가 없으면 "" — modeForExtension이 JOB4002로 거절한다 */
    static String extensionOf(String fileName) {
        return fileName != null && fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase()
                : "";
    }

    /**
     * 이미지의 S3 Content-Type. 확장자로만 정한다 — 검증은 modeForExtension이 이미 했다.
     *
     * <p>AI에 보내는 proto {@code image_mime_type}과 같은 값이다({@code PageWorker.imageMimeTypeOf}).
     *
     * <p>⚠️ {@code image/jpg}는 없는 타입이다 — 확장자만 .jpg로 줄여 쓰고 MIME은 {@code image/jpeg}다.
     * {@code "image/" + ext}로 만들면 엄격한 디코더가 거절한다.
     */
    static String imageMime(String ext) {
        return ext.equals("png") ? "image/png" : "image/jpeg";
    }

    /**
     * 여러 파일을 한 작업으로 받을 수 있는 건 이미지뿐이다 — 한 장이 한 쪽이라 N장이 N쪽이 된다
     * (피그마 C11 「이미지 올리기 확인」).
     *
     * <p>PDF·HWP·HWPX·TXT는 한 파일이 문서 하나여서 여러 개를 이어붙일 기준이 없고,
     * 이미지와 섞이면 쪽 순서를 정할 수 없다. 둘 다 COMMON4000.
     */
    static void validateUploadFiles(List<String> exts) {
        if (exts.isEmpty()) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        if (exts.size() > 1 && !exts.stream().allMatch(JobService::isImage)) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
    }

    public JobResponseDto.Create createJob(String userId, List<MultipartFile> files, String mode,
                                           boolean insertPageNumber, String footerText,
                                           LayoutOptions requestedOptions, String formId,
                                           BrailleOptions requestedBrailleOptions,
                                           com.semojum.backend.global.util.ClientInfoResolver.ClientInfo clientInfo)
            throws Exception {

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

        // 조판·점역 옵션 — 양식이 바탕, 개별 항목이 그 위에 덮인다(UploadOptionsResolver).
        // 빠진 값은 기본값으로 채워 항상 완전한 형태로 저장한다(읽는 쪽에 null 분기를 안 남긴다)
        UploadOptionsResolver.Resolved resolved = uploadOptionsResolver.resolve(
                user, formId, requestedOptions, requestedBrailleOptions);
        LayoutOptions layoutOptions = validateLayoutOptions(resolved.layoutOptions());
        BrailleOptions brailleOptions = resolved.brailleOptions();

        // 2. 모드는 파일 종류가 정한다 (3패널 통합, 2026-09-30) — 사용자가 고르지 않는다.
        //    PDF·HWP·HWPX·이미지 → c(원본 | 텍스트 | 점자 한 번에), TXT → b(원문 | 점자).
        //    구 클라이언트가 보내는 mode는 받기만 하고 쓰지 않는다 — a를 보내도 c로 만든다(텍스트 결과는 c에 다 들어 있다)
        List<String> exts = (files == null ? List.<MultipartFile>of() : files).stream()
                .map(f -> extensionOf(f.getOriginalFilename()))
                .toList();
        validateUploadFiles(exts);

        String requestedMode = mode;
        String ext = exts.get(0);
        mode = modeForExtension(ext);
        exts.forEach(JobService::modeForExtension);   // 섞인 목록도 확장자별로 검증한다

        MultipartFile file = files.get(0);
        log.info("Job 생성 시작: mode={}(요청 {}), file={} ({}KB){}, user={}",
                mode, requestedMode, file.getOriginalFilename(), file.getSize() / 1024,
                files.size() > 1 ? " 외 " + (files.size() - 1) + "장" : "", user.getLoginId());

        // 4. job_id 발급
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
        String jobId = "job_" + timestamp + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        List<Page> pages = new ArrayList<>();
        List<String> tasks = new ArrayList<>();

        if (isImage(ext)) {
            return createFromImages(user, jobId, files,
                    insertPageNumber, footerText, layoutOptions, brailleOptions, clientInfo);

        } else if (mode.equals("b")) {
            // TXT 내용은 쓰지 않는다 — 앱이 이미 파일을 쥐고 있고 점역·쪽 나누기를 직접 한다(V3.1).
            // 구 클라이언트 호환으로 받기만 한다. 새 클라이언트는 POST /api/jobs/text로 부르면 올릴 필요도 없다
            return createFromText(user, jobId,
                    resolveFileName(user.getId(), file.getOriginalFilename()),
                    insertPageNumber, footerText, layoutOptions, brailleOptions, clientInfo);

        } else {
            // 5. PDF 페이지별 분리 및 S3 업로드.
            // mode a의 HWP는 업로드 시점에 PDF로 변환(2026-08-24) — 이후는 PDF와 완전히 동일하게 처리
            //
            // ⚠️ 원본을 힙(byte[])에 올리지 않는다(2026-10-16). 업로드는 최대 100MB이고 운영 힙은 768MB라,
            // 큰 파일 몇 건이 겹치면 원본 배열만으로 OOM이 났다(실측: 100MB 4건 동시 → 12건 중 6건 OOM).
            // Spring이 이미 디스크에 받은 업로드를 임시 파일로 옮겨 경로로 읽고, 다 쓰면 finally에서 지운다
            Path upload = Files.createTempFile("upload-", "." + ext);
            HwpToPdfConverter.ConvertedPdf converted = null;
            try {
                file.transferTo(upload);
                Path pdfPath;
                if (ext.equals("hwp") || ext.equals("hwpx")) {
                    long convertStart = System.currentTimeMillis();
                    converted = hwpToPdfConverter.convert(upload);
                    pdfPath = converted.pdf();
                    log.info("HWP→PDF 변환 완료: {}KB → {}KB ({}ms)",
                            file.getSize() / 1024, Files.size(pdfPath) / 1024,
                            System.currentTimeMillis() - convertStart);
                } else {
                    pdfPath = upload;
                }

                // 파일 경로로 연다 — iText가 디스크에서 필요한 부분만 읽는다
                try (PdfReader reader = new PdfReader(pdfPath.toString());
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
                            .brailleOptions(brailleOptions)
                            .build();
                    // 꼬리말은 점역하지 않는다(V3.1 — TranslateText 삭제, 앱이 엔진으로 점역). footer_braille은 null
                    if (clientInfo != null) {
                        job.recordClientInfo(clientInfo.ip(), clientInfo.os(), clientInfo.browser(), clientInfo.userAgent());
                    }
                    jobRepository.saveAndFlush(job);

                    try {
                        byte[] thumbnailBytes = thumbnailService.generatePdfThumbnail(pdfPath);
                        String thumbnailKey = jobId + "/thumbnail.png";
                        String thumbnailFullPath = s3Service.uploadFile(thumbnailKey, thumbnailBytes, "image/png");
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

                        // S3 업로드
                        String pageKey = jobId + "/pages/page-" + i + ".pdf";
                        String fullPath = s3Service.uploadFile(pageKey, pageOut.toByteArray(), "application/pdf");

                        // Page 엔티티 생성
                        Page page = Page.builder()
                                .job(job)
                                .pageNo(i)
                                .pdfPath(fullPath)
                                .build();
                        pages.add(page);

                        // 공정 스케줄러 태스크 (Job 저장 후 일괄 등록 — userId는 재시도 시 링 재등록용)
                        tasks.add(taskJson(jobId, i, fullPath, mode, totalPages,
                                user.getId(), layoutOptions.advancedAi(), brailleOptions));

                        // Redis 상태 초기화
                        redisTemplate.opsForHash().put("job:" + jobId + ":pages", "page:" + i, "PENDING");
                    }

                    // 8. Page 일괄 저장 후 스케줄러 등록 (DB에 Page가 있어야 워커가 결과를 저장할 수 있음)
                    pageRepository.saveAll(pages);
                    jobDispatcher.enqueueJob(user.getId().toString(), jobId, tasks);

                    log.info("Job 생성 완료: jobId={}, totalPages={} — 큐 적재됨", jobId, totalPages);
                    return new JobResponseDto.Create(jobId, mode, totalPages, "PENDING", insertPageNumber, footerText, layoutOptions);
                }
            } finally {
                if (converted != null) converted.close();
                deleteQuietly(upload);
            }
        }
    }

    /** 업로드 임시 파일 정리 — 지우지 못해도 요청은 실패시키지 않는다(OS 임시 폴더 정리에 맡긴다) */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("업로드 임시 파일 삭제 실패: {}", path);
        }
    }



    /**
     * 이미지 업로드 — 한 장이 한 쪽이다 (피그마 C11 「이미지 올리기 확인」).
     *
     * <p>PDF 경로와 다른 점은 셋이다. 쪽을 쪼개지 않고(파일 하나가 쪽 하나), PDF로 변환하지 않고
     * 올라온 바이트를 그대로 S3에 둬서 워커가 그대로 AI에 보내며, 미리보기 렌더도 하지 않는다 —
     * 원본이 이미 이미지라 그 파일이 곧 미리보기다({@code imagePath}를 업로드 시점에 바로 채운다).
     *
     * <p><b>쪽 순서는 받은 순서다.</b> 서버가 파일 이름으로 다시 정렬하면 사용자가 고친 순서가
     * 뒤집힌다 — 이름순은 FE가 기본값으로 정렬해 보내고, 「순서 바꾸기」로 바꾼 결과도 그 순서로 온다.
     */
    private JobResponseDto.Create createFromImages(
            User user, String jobId, List<MultipartFile> files,
            boolean insertPageNumber, String footerText, LayoutOptions layoutOptions,
            BrailleOptions brailleOptions,
            com.semojum.backend.global.util.ClientInfoResolver.ClientInfo clientInfo) throws Exception {

        int totalPages = files.size();
        String mode = modeForExtension(extensionOf(files.get(0).getOriginalFilename()));

        Job job = Job.builder()
                .id(jobId)
                .user(user)
                .mode(mode)
                .totalPages(totalPages)
                // 작업 이름은 첫 장의 파일 이름 — 여러 장이어도 작업은 하나다
                .originalFileName(resolveFileName(user.getId(), files.get(0).getOriginalFilename()))
                .insertPageNumber(insertPageNumber)
                .footerText(footerText)
                .layoutOptions(layoutOptions)
                .brailleOptions(brailleOptions)
                .build();
        // 꼬리말은 점역하지 않는다(V3.1 — TranslateText 삭제, 앱이 엔진으로 점역). footer_braille은 null
        if (clientInfo != null) {
            job.recordClientInfo(clientInfo.ip(), clientInfo.os(), clientInfo.browser(), clientInfo.userAgent());
        }
        jobRepository.saveAndFlush(job);

        // 썸네일은 첫 장 — PDF처럼 렌더할 게 없어 PNG로 다시 인코딩만 한다. 실패는 삼킨다
        try {
            byte[] thumbnailBytes = thumbnailService.generateImageThumbnail(files.get(0).getBytes());
            String thumbnailKey = jobId + "/thumbnail.png";
            String thumbnailFullPath = s3Service.uploadFile(thumbnailKey, thumbnailBytes, "image/png");
            job.updateThumbnailUrl(s3Service.getPublicUrl(thumbnailFullPath));
            jobRepository.save(job);
        } catch (Exception e) {
            log.warn("썸네일 생성 실패: jobId={}", jobId, e);
        }

        redisTemplate.opsForHash().put("job:" + jobId + ":pages", "total_pages", String.valueOf(totalPages));

        List<Page> pages = new ArrayList<>();
        List<String> tasks = new ArrayList<>();

        for (int i = 0; i < totalPages; i++) {
            int pageNo = i + 1;
            // 확장자는 그 자리의 파일에서 직접 뽑는다 — 목록과 따로 들고 다니면 어긋날 수 있다
            String ext = extensionOf(files.get(i).getOriginalFilename());

            String pageKey = jobId + "/pages/page-" + pageNo + "." + ext;
            String fullPath = s3Service.uploadFile(pageKey, files.get(i).getBytes(), imageMime(ext));

            Page page = Page.builder()
                    .job(job)
                    .pageNo(pageNo)
                    .pdfPath(fullPath)
                    .build();
            // 원본이 곧 미리보기 — 워커가 따로 렌더하지 않으므로 여기서 채운다
            page.updateImagePath(fullPath);
            pages.add(page);

            tasks.add(taskJson(jobId, pageNo, fullPath, mode, totalPages,
                    user.getId(), layoutOptions.advancedAi(), brailleOptions));

            redisTemplate.opsForHash().put("job:" + jobId + ":pages", "page:" + pageNo, "PENDING");
        }

        pageRepository.saveAll(pages);
        jobDispatcher.enqueueJob(user.getId().toString(), jobId, tasks);

        log.info("Job 생성 완료(이미지 {}장): jobId={}, totalPages={} — 큐 적재됨", totalPages, jobId, totalPages);
        return new JobResponseDto.Create(jobId, mode, totalPages, "PENDING", insertPageNumber, footerText, layoutOptions);
    }

    /** 이름을 안 주면 「무제」 — 같은 이름이 있으면 resolveFileName이 (2), (3)을 붙인다 */
    static String resolveNewDocumentName(JobRequestDto.NewDocument request) {
        return request.fileName() == null || request.fileName().isBlank()
                ? "무제" : request.fileName().trim();
    }

    /**
     * 새 문서 — 파일 없이 본문 텍스트로 작업을 만든다.
     *
     * <p>앞단 검증은 파일 업로드와 같다(조판 옵션 · 꼬리말 200자 · 기관 관리자 차단).
     * 다른 점은 확장자로 모드를 정하지 않는다는 것뿐이다 — 언제나 텍스트 경로다.
     */
    public JobResponseDto.Create createNewDocument(
            String userId, JobRequestDto.NewDocument request,
            com.semojum.backend.global.util.ClientInfoResolver.ClientInfo clientInfo) {

        boolean insertPageNumber = Boolean.TRUE.equals(request.insertPageNumber());

        String footerText = request.footerText() == null || request.footerText().isBlank()
                ? null : request.footerText().trim();
        if (footerText != null && footerText.length() > 200) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }

        User user = userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        if (user.getRole() == com.semojum.backend.domain.auth.enums.Role.ROLE_ORG_ADMIN) {
            log.warn("기관 관리자 계정의 새 문서 생성 시도 거부: user={}", user.getLoginId());
            throw new CustomException(ErrorCode.COMMON_FORBIDDEN);
        }

        // 업로드와 같은 규칙 — 양식이 바탕, 개별 항목이 그 위에 덮인다
        UploadOptionsResolver.Resolved resolved = uploadOptionsResolver.resolve(
                user, request.formId(), request.layoutOptions(), request.brailleOptions());
        LayoutOptions layoutOptions = validateLayoutOptions(resolved.layoutOptions());

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHHmmss"));
        String jobId = "job_" + timestamp + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        String name = resolveNewDocumentName(request);

        log.info("새 문서 생성 시작: user={}", user.getLoginId());

        return createFromText(user, jobId,
                resolveFileName(user.getId(), name),
                insertPageNumber, footerText, layoutOptions, resolved.brailleOptions(), clientInfo);
    }

    /** TXT 업로드와 「새 문서」가 공유하는 경로 — 둘 다 결과물이 본문 텍스트 하나다 */
    static final String MODE_TEXT = "b";

    /**
     * 공정 스케줄러 태스크 JSON — 워커가 읽는 키 이름이 여기 한 곳에만 있다.
     *
     * <p>PageWorker는 {@code mode}·{@code sourcePath}로 값을 꺼낸다. 키 이름이 틀리면 null이 되고
     * 그 쪽은 전부 BLOCKED로 떨어지는데 컴파일로는 안 잡힌다 — 파일 경로와 텍스트 경로가 같은
     * 포맷 문자열을 따로 들고 있다가 한쪽만 틀린 적이 있어(9d20f81) 한 곳으로 모았다.
     */
    static String taskJson(String jobId, int pageNo, String sourcePath, String mode,
                           int totalPages, UUID userId, boolean advancedAi, BrailleOptions braille) {
        return String.format(
                "{\"jobId\":\"%s\",\"pageNo\":%d,\"sourcePath\":\"%s\",\"mode\":\"%s\",\"totalPages\":%d,\"userId\":\"%s\",\"advancedAi\":%b,\"koreanGrade\":%d,\"englishGrade\":%d,\"splitChoices\":%b}",
                jobId, pageNo, sourcePath, mode, totalPages, userId, advancedAi,
                koreanGradeOf(braille), englishGradeOf(braille), splitChoicesOf(braille));
    }

    /**
     * 점역 옵션 → proto {@code korean_grade}(10). 1 정자 · 2 약자 · 0 서버 기본(약자).
     *
     * <p>업로드 때 정해진 값이 태스크에 실린다 — 변환 중에는 저장이 막혀(JOB4010) 바뀔 수 없으므로
     * 한 작업의 모든 쪽이 같은 값으로 처리된다.
     */
    static int koreanGradeOf(BrailleOptions braille) {
        return braille == null ? 0 : gradeNumber(braille.hangulGrade());
    }

    /** 점역 옵션 → proto {@code english_grade}(11). 1 정자 · 2 약자 · 0 서버 기본(약자) */
    static int englishGradeOf(BrailleOptions braille) {
        return braille == null ? 0 : gradeNumber(braille.englishGrade());
    }

    private static int gradeNumber(String grade) {
        return switch (String.valueOf(grade)) {
            case "grade1" -> 1;
            case "grade2" -> 2;
            default -> 0;
        };
    }

    /** 점역 옵션 → proto {@code split_choices}(12). 「한 줄에 하나씩」이면 true */
    static boolean splitChoicesOf(BrailleOptions braille) {
        return braille != null && "onePerLine".equals(braille.choiceLayout());
    }

    /**
     * 텍스트 작업(TXT 업로드 · 「새 문서」)을 만든다 — <b>작업 기록만</b> 남긴다.
     *
     * <p>V3.1부터 AI는 글을 받아도 점자를 주지 않고(proto에서 점역 결과 삭제), 텍스트 작업에서
     * AI가 해 줄 일이 없다(기획 확정 2026-10-14). 점역·쪽 나누기·조판은 전부 앱이 원문으로 한다.
     * 그래서 쪽 나누기 · S3 업로드 · AI 큐 적재가 없고, 만들자마자 COMPLETED다 — 기다릴 변환이 없다.
     *
     * <p>그래도 작업을 만드는 이유: 최종 저장({@code POST /api/jobs/{jobId}/save})이 jobId를 요구한다.
     * 쪽과 쪽 결과는 <b>첫 저장 때 앱이 보낸 쪽 번호대로 만들어진다</b>({@code PageContentSaver}).
     * 서버가 미리 자르면 앱과 쪽 번호가 어긋날 위험만 생긴다.
     *
     * <p>크레딧은 차감하지 않는다 — 차감은 AI 결과가 올 때만 일어난다.
     */
    private JobResponseDto.Create createFromText(
            com.semojum.backend.domain.auth.entity.User user, String jobId,
            String fileName, boolean insertPageNumber, String footerText,
            LayoutOptions layoutOptions, BrailleOptions brailleOptions,
            com.semojum.backend.global.util.ClientInfoResolver.ClientInfo clientInfo) {

        Job job = Job.builder()
                .id(jobId)
                .user(user)
                .mode(MODE_TEXT)
                .totalPages(0)          // 첫 저장이 앱이 보낸 쪽 수로 채운다
                .originalFileName(fileName)
                .insertPageNumber(insertPageNumber)
                .footerText(footerText)
                .layoutOptions(layoutOptions)
                .brailleOptions(brailleOptions)
                .build();
        if (clientInfo != null) {
            job.recordClientInfo(clientInfo.ip(), clientInfo.os(), clientInfo.browser(), clientInfo.userAgent());
        }
        job.complete(new int[]{});
        jobRepository.save(job);

        log.info("텍스트 작업 생성(변환 없음): jobId={}, name={}", jobId, fileName);
        return new JobResponseDto.Create(jobId, MODE_TEXT, 0, "COMPLETED", insertPageNumber, footerText, layoutOptions);
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
                && List.of("left", "center", "right").contains(o.footerAlign())
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
                footerBrailleService.resolve(job), job.resolveLayoutOptions(), job.resolveBrailleOptions());
    }

    /**
     * 업로드 후 조판 설정 변경 (V32) — 에디터 "이 파일의 조판 설정" 모달.
     *
     * <p>종전엔 조회만 있어 모달이 <b>화면에만</b> 반영됐다 — 40칸으로 다듬어 놓고 내려받으면
     * 32칸 파일이 나오고, 다시 열면 32칸으로 돌아갔다. 이제 저장해 화면·다운로드·재열람이 같은 값을 쓴다.
     *
     * <p><b>보낸 항목만 바뀐다</b>({@link LayoutOptions#merge}). 꼬리말만 {@code ""}로 지울 수 있다.
     *
     * <p>꼬리말 처리가 이 메서드의 핵심이다.
     * <ul>
     *   <li>묵자가 <b>바뀌면</b> 다시 점역한다(AI 1회) — 화면·SSE·다운로드가 같은 값을 쓰는 전제(V31)를 지킨다</li>
     *   <li>묵자가 그대로여도 <b>판면이 좁아지면</b>(칸 수↓·쪽 번호 켜기) 기존 점자가 페이지행에 안 들어갈 수
     *       있다 — 재점역 없이 길이만 다시 검사한다(S-9). 이 검사가 없으면 라이브러리가 말없이 뒤에서 자른다</li>
     *   <li>꼬리말을 지우면 점역 결과도 함께 비운다</li>
     * </ul>
     *
     * <p><b>변환 중에는 거부한다(JOB4010).</b> 조판 옵션 자체는 워커가 안 쓰지만 {@code advancedAi}가
     * gRPC 요청에 실려, 중간에 바뀌면 쪽마다 다른 설정으로 처리된 문서가 나온다.
     * ⚠️ 이미 변환이 끝난 작업에서 {@code advancedAi}를 바꿔도 <b>결과는 달라지지 않는다</b> —
     * 재변환 경로가 없어 기록만 남는다(모달이 그 항목을 보여주므로 거부하지 않고 받아 둔다).
     */
    @Transactional
    public JobResponseDto.Options updateJobOptions(String userId, String jobId,
                                                   JobRequestDto.UpdateOptions request) {
        // 타인 작업이면 403 (조회와 같은 기준 — 존재 여부를 숨긴다)
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));
        if (job.isInProgress()) {
            throw new CustomException(ErrorCode.JOB_IN_PROGRESS);
        }

        LayoutOptions merged = validateLayoutOptions(job.resolveLayoutOptions().merge(request.layoutOptions()));
        boolean insertPageNumber = request.insertPageNumber() != null
                ? request.insertPageNumber() : job.isInsertPageNumber();

        // 꼬리말: 미전송(null)이면 그대로, ""면 삭제, 값이 있으면 교체
        String footerText = job.getFooterText();
        if (request.footerText() != null) {
            String trimmed = request.footerText().trim();
            footerText = trimmed.isEmpty() ? null : trimmed;
            if (footerText != null && footerText.length() > 200) {
                throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
            }
        }

        String footerBraille;
        if (footerText == null) {
            footerBraille = null;                                   // 지웠다 — 점역 결과도 함께 비운다
        } else if (footerText.equals(job.getFooterText()) && job.getFooterBraille() != null) {
            footerBraille = job.getFooterBraille();                 // 묵자 그대로 — 새 판면에 들어가는지만 본다
            footerBrailleService.validateFits(footerBraille, merged, job.getTotalPages());
        } else {
            // 바뀌었거나 점역 값이 없다 — V3.1부터 서버가 점역하지 않으므로 비운다(앱이 점역한다).
            // 예전 점자를 남겨 두면 새 묵자와 어긋난 꼬리말이 화면에 뜬다
            footerBraille = null;
        }

        // 점역 옵션도 같은 규칙 — 보낸 항목만 덮는다. 조판과 달리 판면 검증이 없어 값 검증만 한다
        BrailleOptions braille = job.resolveBrailleOptions().merge(request.brailleOptions()).validated();

        job.updateOptions(insertPageNumber, footerText, merged);
        job.updateBrailleOptions(braille);
        job.updateFooterBraille(footerBraille);
        log.info("조판 설정 변경: jobId={}, 꼬리말={}, 판면={}칸×{}줄",
                jobId, footerText == null ? "(없음)" : footerText, merged.cellsPerLine(), merged.linesPerPage());
        return new JobResponseDto.Options(jobId, insertPageNumber, footerText, footerBraille, merged, braille);
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