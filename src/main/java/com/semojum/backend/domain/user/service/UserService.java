package com.semojum.backend.domain.user.service;

import com.semojum.backend.domain.folder.dto.FolderDto;
import com.semojum.backend.domain.folder.entity.Folder;
import com.semojum.backend.domain.folder.service.FolderPaths;
import com.semojum.backend.domain.folder.repository.FolderRepository;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.dto.JobSearchCondition;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobQueryRepositoryImpl;
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
    private final FolderRepository folderRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final S3Service s3Service;
    private final PageResultRepository pageResultRepository;
    private final com.semojum.backend.domain.result.service.PageResultSerializer pageResultSerializer;

    /**
     * 전체보기·검색 화면 — 폴더 구분 없이 <b>전역</b>으로 폴더와 파일을 모두 준다.
     *
     * <p>탐색(폴더를 타고 들어가는 화면)은 {@code GET /api/folders/.../contents}가 맡는다.
     * 응답 구조는 그쪽과 같아, 화면이 폴더·파일을 같은 방식으로 그릴 수 있다.
     */
    @Transactional(readOnly = true)
    public FolderDto.Contents searchEverything(String userId, JobSearchCondition condition,
                                               boolean favoriteOnly, boolean oldestFirst) {
        UUID uid = UUID.fromString(userId);
        JobResponseDto.JobList files = getMyJobs(userId, condition);

        // 상태·모드는 폴더에 없는 속성 → 그 필터가 걸리면 폴더는 결과에서 빠진다
        // 2페이지 이후에는 폴더를 다시 보내지 않는다 — 폴더는 페이지네이션이 없어 매번
        // 전체가 실려 오므로, 클라이언트가 그대로 누적하면 중복 표시된다
        boolean fileOnlyFilter = notEmpty(condition.statuses()) || notEmpty(condition.modes());
        boolean paged = condition.cursor() != null && !condition.cursor().isBlank();
        if (fileOnlyFilter || paged) return new FolderDto.Contents(List.of(), files);

        List<Folder> folders = new ArrayList<>(folderRepository.findActiveForTree(uid, favoriteOnly));
        String keyword = condition.search();
        if (keyword != null && !keyword.isBlank()) {
            String needle = keyword.toLowerCase().strip();
            folders.removeIf(f -> !f.getName().toLowerCase().contains(needle));
        }
        Comparator<Folder> order = Comparator.comparing(Folder::getLastModifiedAt)
                .thenComparing(f -> f.getId().toString());
        if (!oldestFirst) order = order.reversed();
        folders.sort(order);

        Map<UUID, String> paths = allFolderPaths(uid);
        List<FolderDto.Item> items = new ArrayList<>();
        for (Folder f : folders) {
            // 폴더의 "위치"는 상위 경로다 — 최상위면 null
            String parentPath = f.getParentFolderId() != null ? paths.get(f.getParentFolderId()) : null;
            items.add(new FolderDto.Item(f.getId(), f.getName(), f.isFavorite(),
                    f.getCreatedAt(), f.getLastModifiedAt(), parentPath));
        }
        return new FolderDto.Contents(items, files);
    }

    private static boolean notEmpty(List<String> values) {
        return values != null && !values.isEmpty();
    }

    /** 폴더 id → 전체 경로("국어교재/1학기") */
    private Map<UUID, String> allFolderPaths(UUID userId) {
        Map<UUID, Folder> byId = new HashMap<>();
        for (Folder f : folderRepository.findAllActiveByUserId(userId)) byId.put(f.getId(), f);
        return FolderPaths.buildAll(byId);
    }

    /**
     * 조건에 맞는 <b>파일</b> 목록 (커서 페이지네이션).
     *
     * <p>휴지통 항목은 조건에서 제외된다. 폴더까지 함께 필요한 화면은
     * {@link #searchEverything} 또는 폴더 내부 조회를 쓴다.
     */
    @Transactional(readOnly = true)
    public JobResponseDto.JobList getMyJobs(String userId, JobSearchCondition condition) {
        UUID uid = UUID.fromString(userId);
        List<Job> fetched = jobRepository.search(uid, condition);
        JobQueryRepositoryImpl.PageSlice slice =
                JobQueryRepositoryImpl.slice(fetched, condition.normalizedSize());

        Map<UUID, String> folderPaths = buildFolderPaths(uid, slice.items());

        LocalDateTime now = LocalDateTime.now();
        List<JobResponseDto.JobCard> cards = new ArrayList<>();
        for (Job job : slice.items()) {
            cards.add(toCard(job, folderPaths, now));
        }
        return new JobResponseDto.JobList(cards, slice.nextCursor(), slice.hasMore());
    }

    private JobResponseDto.JobCard toCard(Job job, Map<UUID, String> folderPaths, LocalDateTime now) {
        return new JobResponseDto.JobCard(
                job.getId(),
                job.getMode(),
                job.getStatus(),
                progressOf(job),   // 진행 중 작업만 Redis 조회 — 완료 작업은 즉시 null
                job.getOriginalFileName(),
                job.getThumbnailUrl(),
                RelativeDateFormatter.format(job.getLastModifiedAt(), now),
                job.getTotalPages(),
                job.getLastEditedPage(),
                job.isFavorite(),
                job.getFolderId() != null ? job.getFolderId().toString() : null,
                job.getFolderId() != null ? folderPaths.get(job.getFolderId()) : null
        );
    }

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

    /** 카드에 표시할 폴더 경로("상위/하위"). 계정당 폴더 200개 상한이라 한 번에 읽어 메모리에서 계산. */
    private Map<UUID, String> buildFolderPaths(UUID userId, List<Job> jobs) {
        boolean anyInFolder = jobs.stream().anyMatch(j -> j.getFolderId() != null);
        if (!anyInFolder) return Map.of();

        Map<UUID, Folder> byId = new HashMap<>();
        for (Folder f : folderRepository.findAllActiveByUserId(userId)) {
            byId.put(f.getId(), f);
        }
        return FolderPaths.buildAll(byId);
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
                pageResultSerializer.buildResult(pageResult),
                original
        );
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
                job.isInsertPageNumber(), pageResultSerializer.buildResult(pageResult),
                buildOriginal(job.getMode(), page));
    }

    // 원본 PDF presigned URL 수명 — FE는 페이지 진입 직후 1회 fetch하므로 짧아도 되지만,
    // 느린 회선에서 대용량 페이지를 받는 경우까지 감안해 15분. 만료 후엔 페이지 조회를 다시 호출하면 된다.
    private static final java.time.Duration ORIGINAL_URL_TTL = java.time.Duration.ofMinutes(15);

    // 모드별 원본 구성. pdfPath(s3:// 전체경로)를 가공 없이 그대로 S3Service에 넘긴다.
    private JobResponseDto.OriginalContent buildOriginal(String mode, Page page) {
        if ("b".equals(mode)) {
            // mode b: S3의 .txt를 읽어 줄 단위 배열로. split("\n", -1)로 빈 줄 보존(trim/필터 금지).
            String text = new String(s3Service.downloadFile(page.getPdfPath()), StandardCharsets.UTF_8);
            List<String> lines = Arrays.asList(text.split("\n", -1));
            return new JobResponseDto.OriginalContent("text", null, lines);
        }
        // mode a, c: 원본 PDF 만료형 서명 URL — 버킷 공개 읽기 회수 후에도 FE가 직접 받을 수 있는 유일한 경로
        String url = s3Service.getPresignedUrl(page.getPdfPath(), ORIGINAL_URL_TTL);
        return new JobResponseDto.OriginalContent("pdf", url, null);
    }

    // 모드에 따라 FE에 전달할 result 필드 구성 (a: 텍스트추출, b: 점자변환, c: 이미지→점자)
}
