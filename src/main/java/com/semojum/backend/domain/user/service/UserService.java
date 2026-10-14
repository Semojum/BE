package com.semojum.backend.domain.user.service;

import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.result.entity.*;
import com.semojum.backend.domain.result.repository.*;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.s3.S3Service;
import com.semojum.backend.global.util.RelativeDateFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final JobRepository jobRepository;
    private final PageRepository pageRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final S3Service s3Service;
    private final PageResultRepository pageResultRepository;
    private final com.semojum.backend.domain.result.service.PageResultSerializer pageResultSerializer;
    private final com.semojum.backend.domain.job.service.FooterBrailleService footerBrailleService;
    private final com.semojum.backend.domain.result.repository.PageMemoRepository pageMemoRepository;

    /** 진행 중 작업의 완료 페이지 비율(0~100). 그 외에는 null. */
    private Integer progressOf(Job job) {
        if (!job.isInProgress()) return null;
        try {
            Map<Object, Object> pages = redisTemplate.opsForHash().entries("job:" + job.getId() + ":pages");
            if (pages.isEmpty()) return 0;
            int done = 0, total = 0;
            for (Map.Entry<Object, Object> e : pages.entrySet()) {
                if ("total_pages".equals(e.getKey())) continue;
                total++;
                if (Set.of("COMPLETED", "NEEDS_REVIEW", "BLOCKED").contains(String.valueOf(e.getValue()))) done++;
            }
            return total == 0 ? 0 : (int) Math.round(done * 100.0 / total);
        } catch (Exception e) {
            // 진행률은 부가 정보 — Redis 장애가 목록 조회 전체를 막지 않게 한다
            log.warn("진행률 조회 실패: jobId={}", job.getId(), e);
            return null;
        }
    }

    // 앱 재시작·네트워크 재연결 시 복구용: 아직 진행 중인 Job 목록.
    // FE는 이 목록으로 각 Job의 status를 조회하고 SSE를 다시 연결한다(탭 2개 이상 대응).
    @Transactional(readOnly = true)
    public List<JobResponseDto.ActiveJob> getActiveJobs(String userId) {
        UUID uid = UUID.fromString(userId);
        List<Job> jobs = jobRepository.findActiveByUserIdAndStatusIn(uid, List.of("PENDING", "IN_PROGRESS"));
        List<JobResponseDto.ActiveJob> result = new ArrayList<>();
        for (Job job : jobs) {
            result.add(new JobResponseDto.ActiveJob(
                    job.getId(),
                    job.getMode(),
                    job.getStatus(),
                    job.getTotalPages(),
                    progressOf(job),
                    job.getOriginalFileName(),
                    job.getLastModifiedAt(),
                    job.getLastEditedPage()
            ));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public JobResponseDto.JobDetail getJobPage(String userId, String jobId, int pageNo) {
        // 본인 Job인지 검증 (타인 jobId 직접 입력 시 403 반환)
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));

        PageResult pageResult = pageResultRepository.findByJobIdAndPageNumber(jobId, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));

        // 페이지별 원본 정보 구성 (a/c: 원본 PDF 공개 URL, b: 원본 텍스트 줄 배열)
        Page page = pageRepository.findByJobAndPageNo(job, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        JobResponseDto.OriginalContent original = buildOriginal(job.getMode(), page);

        return new JobResponseDto.JobDetail(
                jobId,
                job.getMode(),
                job.getStatus(),
                job.getTotalPages(),
                job.getFailedPages(),
                job.getOriginalFileName(),
                job.getStartedAt(),
                job.getFinishedAt(),
                pageNo,
                job.isInsertPageNumber(),
                job.resolveLayoutOptions(),
                footerBrailleService.resolve(job),
                pageResultSerializer.buildResult(pageResult),
                original,
                memosOf(page.getId())
        );
    }

    /** 그 쪽의 줄 메모. 없으면 빈 배열 — FE가 null 검사 없이 순회하도록 */
    private List<JobResponseDto.Memo> memosOf(UUID pageId) {
        return pageMemoRepository.findByPageIdOrderByElementIdAscLineIndexAsc(pageId).stream()
                .map(m -> new JobResponseDto.Memo(
                        m.getId(), m.getPanel(), m.getElementId(), m.getLineIndex(),
                        m.getLineText(), m.getBody(), m.getCreatedAt()))
                .toList();
    }

    // 운영자 열람(T1-5 미리보기) — 소유자 검증 없이 페이지 결과+원본 조회.
    // 접속·열람 범위는 운영자 전용(AdminController에서 키/ROLE_ADMIN 검증) — 편집·재변환은 없다.
    @Transactional(readOnly = true)
    public JobResponseDto.JobDetail getJobPageAsAdmin(String jobId, int pageNo) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        PageResult pageResult = pageResultRepository.findByJobIdAndPageNumber(jobId, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        Page page = pageRepository.findByJobAndPageNo(job, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        return new JobResponseDto.JobDetail(
                jobId, job.getMode(), job.getStatus(), job.getTotalPages(), job.getFailedPages(),
                job.getOriginalFileName(), job.getStartedAt(), job.getFinishedAt(), pageNo,
                job.isInsertPageNumber(), job.resolveLayoutOptions(),
                footerBrailleService.resolve(job),
                pageResultSerializer.buildResult(pageResult),
                buildOriginal(job.getMode(), page),
                memosOf(page.getId()));
    }

    // 원본 PDF presigned URL 수명 — FE는 페이지 진입 직후 1회 fetch하므로 짧아도 되지만,
    // 느린 회선에서 대용량 페이지를 받는 경우까지 감안해 15분. 만료 후엔 페이지 조회를 다시 호출하면 된다.
    private static final java.time.Duration ORIGINAL_URL_TTL = java.time.Duration.ofMinutes(15);

    // 모드별 원본 구성. pdfPath(s3:// 전체경로)를 가공 없이 그대로 S3Service에 넘긴다.
    private JobResponseDto.OriginalContent buildOriginal(String mode, Page page) {
        if ("b".equals(mode)) {
            // V3.1 텍스트 작업은 원본 파일을 받지 않는다(쪽도 첫 저장 때 생긴다) — 경로가 비면 원본이 없다
            if (page.getPdfPath() == null || page.getPdfPath().isBlank()) return null;
            // mode b: S3의 .txt를 읽어 줄 단위 배열로. split("\n", -1)로 빈 줄 보존(trim/필터 금지).
            String text = new String(s3Service.downloadFile(page.getPdfPath()), StandardCharsets.UTF_8);
            List<String> lines = Arrays.asList(text.split("\n", -1));
            return new JobResponseDto.OriginalContent("text", null, lines);
        }
        // mode a, c: 미리 렌더한 이미지가 원칙(FE가 <img>로 바로 그린다 — 실측 pdf.js 1.8~2.9s → 6~9ms).
        // 렌더 실패·page-image 비활성으로 이미지가 없을 때만 원본 PDF로 내려 화면이 비지 않게 한다.
        if (page.getImagePath() != null) {
            return new JobResponseDto.OriginalContent(
                    "image", s3Service.getPresignedUrl(page.getImagePath(), ORIGINAL_URL_TTL), null);
        }
        return new JobResponseDto.OriginalContent(
                "pdf", s3Service.getPresignedUrl(page.getPdfPath(), ORIGINAL_URL_TTL), null);
    }

    // 모드에 따라 FE에 전달할 result 필드 구성 (a: 텍스트추출, b: 점자변환, c: 이미지→점자)
}
