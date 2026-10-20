package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.enums.Role;
import com.semojum.backend.domain.job.dto.JobRequestDto;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.result.service.PageContentSaver;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 작업 저장 — V3.1의 유일한 저장 경로.
 *
 * <p>FE가 「저장」을 누를 때 한 번만 호출된다. 자동 저장은 없다. 본문은 작업 최종 상태 전체이고,
 * 조판 설정·점역 옵션·세 패널·줄 메모가 함께 들어온다.
 *
 * <p><b>쪽 단위 트랜잭션</b>이다. 한 쪽이 실패해도 나머지는 저장되고, 실패한 쪽 번호만 돌려준다 —
 * 205쪽을 한 트랜잭션으로 묶으면 한 쪽 때문에 하루치 편집이 날아간다. 화면은 그 번호로
 * "다시 시도"를 띄운다(피그마 Case 17).
 *
 * <p>설정 갱신은 {@link JobService#updateJobOptions}에 맡긴다 — 소유자 검증·변환 중 거절·
 * 꼬리말 재점역과 길이 검증이 거기 모여 있다. 쪽을 하나도 못 쓰더라도 설정은 먼저 확정된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobSaveService {

    private final JobRepository jobRepository;
    private final JobService jobService;
    private final PageContentSaver pageContentSaver;
    private final JobCopyService jobCopyService;
    private final com.semojum.backend.domain.result.service.TextPageTrimmer textPageTrimmer;
    private final com.semojum.backend.domain.job.repository.PageRepository pageRepository;
    private final com.semojum.backend.domain.auth.repository.UserRepository userRepository;

    public JobResponseDto.SaveResult save(String userId, String jobId, JobRequestDto.SaveJob request) {
        // 요청 모양 검증 — 아무것도 쓰기 전에 거절한다
        validatePageNos(request);
        validateTotalPages(request);

        // 0. 받은 .smj 파일이면 내 계정에 사본을 만들고 거기에 저장한다 (2026-10-21).
        //    메모 협업은 파일 전달이라, 받은 사람의 저장은 남의 작업을 가리킨다. 파일이 있는 누구나 된다
        String copiedFrom = null;
        Job target = jobRepository.findById(jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        // 쪽 줄이기는 텍스트 작업만 — PDF · 이미지의 쪽은 원본 쪽이라 앱이 바꾸지 않는다
        if (request.totalPages() != null && !"b".equals(target.getMode())) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        if (!target.getUser().getId().equals(UUID.fromString(userId))) {
            User me = userRepository.findById(UUID.fromString(userId))
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
            // 기관 관리자는 에디터를 쓰지 않는다(작업 생성과 같은 규칙)
            if (me.getRole() == Role.ROLE_ORG_ADMIN) throw new CustomException(ErrorCode.COMMON_FORBIDDEN);
            copiedFrom = jobId;
            jobId = jobCopyService.copyFor(me, target).getId();
            log.info("받은 파일 저장 → 사본: source={}, copy={}, user={}", copiedFrom, jobId, me.getLoginId());
        }

        // 1. 설정 먼저 — 소유자·변환 중 검증이 여기서 끝난다(변환 중 JOB4010)
        jobService.updateJobOptions(userId, jobId, new JobRequestDto.UpdateOptions(
                request.insertPageNumber(), request.footerText(),
                request.layoutOptions(), request.brailleOptions()));

        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));

        // 2. 쪽마다 독립 트랜잭션 — 실패한 쪽만 걷어낸다
        List<Integer> failed = new ArrayList<>();
        List<JobResponseDto.PageSaveResult> pageResults = new ArrayList<>();
        int saved = 0;
        for (JobRequestDto.SaveJobPage page : request.pages()) {
            try {
                pageContentSaver.savePage(job, page);
                saved++;
                pageResults.add(new JobResponseDto.PageSaveResult(page.pageNo(), "SAVED", null));
            } catch (Exception e) {
                failed.add(page.pageNo());
                // 공개 에러 코드만 — DB 예외 원문을 앱에 보내지 않는다
                String code = e instanceof CustomException ce ? ce.getErrorCode().getCode()
                        : ErrorCode.COMMON_INTERNAL_ERROR.getCode();
                pageResults.add(new JobResponseDto.PageSaveResult(page.pageNo(), "FAILED", code));
                log.warn("쪽 저장 실패: jobId={}, pageNo={}, code={}, error={}", jobId, page.pageNo(), code, e.toString());
            }
        }

        // 3. 텍스트 작업(TXT · 새 문서)은 쪽 나누기를 앱이 정한다.
        //    쪽 수 = 남아 있는 마지막 쪽 번호. 실패한 쪽은 기존 행이 그대로라 쪽 수가 줄지 않는다(B-02).
        //    줄이는 건 totalPages를 명시했을 때만 — 그 뒤 쪽을 통째로 지운다. PDF 작업은 변환이 정한 쪽 수 그대로
        if ("b".equals(job.getMode())) {
            if (request.totalPages() != null) textPageTrimmer.deletePagesAfter(job, request.totalPages());
            Integer last = pageRepository.findMaxPageNo(job.getId());
            if (last != null) job.updateTotalPages(last);
        }

        // 4. 내용이 바뀌었으면 카드 날짜·복구 지점은 쪽 저장이 이미 갱신했다. 여기선 확정만.
        jobRepository.save(job);

        log.info("작업 저장: jobId={}, 저장 {}쪽, 실패 {}쪽{}", jobId, saved, failed.size(),
                failed.isEmpty() ? "" : " " + failed);

        return new JobResponseDto.SaveResult(jobId, saved, failed, LocalDateTime.now(), copiedFrom, pageResults);
    }

    /**
     * 쪽 번호 — 1 이상, 한 요청에 같은 쪽은 한 번만(FE 점검 B-07). 같은 쪽이 두 번 오면 뒤의 것이 앞의 것을
     * 덮어 앞 내용이 조용히 사라진다.
     */
    private void validatePageNos(JobRequestDto.SaveJob request) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (JobRequestDto.SaveJobPage p : request.pages()) {
            if (p.pageNo() < 1 || !seen.add(p.pageNo())) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
    }

    /** totalPages — 1 이상이고, 보낸 쪽 번호가 그 안이어야 한다. PDF · 이미지 작업은 받지 않는다(원본 쪽이다) */
    private void validateTotalPages(JobRequestDto.SaveJob request) {
        Integer total = request.totalPages();
        if (total == null) return;
        if (total < 1) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        for (JobRequestDto.SaveJobPage p : request.pages()) {
            if (p.pageNo() > total) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
    }
}
