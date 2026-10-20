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
    private final com.semojum.backend.domain.auth.repository.UserRepository userRepository;

    public JobResponseDto.SaveResult save(String userId, String jobId, JobRequestDto.SaveJob request) {
        // 0. 받은 .smj 파일이면 내 계정에 사본을 만들고 거기에 저장한다 (2026-10-21).
        //    메모 협업은 파일 전달이라, 받은 사람의 저장은 남의 작업을 가리킨다. 파일이 있는 누구나 된다
        String copiedFrom = null;
        Job target = jobRepository.findById(jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
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
        int saved = 0;
        int lastSavedPage = 0;
        for (JobRequestDto.SaveJobPage page : request.pages()) {
            try {
                pageContentSaver.savePage(job, page);
                saved++;
                lastSavedPage = Math.max(lastSavedPage, page.pageNo());
            } catch (Exception e) {
                failed.add(page.pageNo());
                log.warn("쪽 저장 실패: jobId={}, pageNo={}, error={}", jobId, page.pageNo(), e.toString());
            }
        }

        // 3. 텍스트 작업(TXT · 새 문서)은 쪽 나누기를 앱이 정한다 — 쪽 수를 저장된 마지막 쪽 번호로 맞춘다.
        //    본문이 최종 상태라 앱이 쪽을 줄였으면 쪽 수도 준다. PDF 작업은 변환이 정한 쪽 수 그대로
        if ("b".equals(job.getMode()) && lastSavedPage > 0) {
            job.updateTotalPages(lastSavedPage);
        }

        // 4. 내용이 바뀌었으면 카드 날짜·복구 지점은 쪽 저장이 이미 갱신했다. 여기선 확정만.
        jobRepository.save(job);

        log.info("작업 저장: jobId={}, 저장 {}쪽, 실패 {}쪽{}", jobId, saved, failed.size(),
                failed.isEmpty() ? "" : " " + failed);

        return new JobResponseDto.SaveResult(jobId, saved, failed, LocalDateTime.now(), copiedFrom);
    }
}
