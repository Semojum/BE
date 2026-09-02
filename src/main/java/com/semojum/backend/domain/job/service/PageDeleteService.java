package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageDeleteRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.s3.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.UUID;

/**
 * 원본 페이지 한 장을 지우고 뒤 번호를 당긴다 (FE 요청 X-1, 2026-09-03).
 *
 * <p><b>유저 확정 사항</b>
 * <ul>
 *   <li><b>영구 삭제</b> — 휴지통을 거치지 않고 되돌릴 수 없다</li>
 *   <li><b>크레딧 환불 없음</b> — {@code credit_transactions}는 손대지 않는다. 이미 AI가 처리한
 *       쪽이라 원가가 발생했고, 장부는 일어난 일을 적는 곳이다</li>
 *   <li><b>편집 이력 보존</b> — {@code page_edit_logs}는 남긴다(RLHF 학습 자료)</li>
 * </ul>
 *
 * <p>남기는 두 표는 <b>번호를 당기지 않는다.</b> 그때의 기록이라 뒤늦게 번호를 바꾸면 사실과
 * 달라진다 — 삭제 뒤에는 두 표의 page_no가 현재 쪽 번호와 어긋날 수 있다(의도된 것).
 *
 * <p>S3는 지운 쪽의 객체만 삭제하고 <b>나머지 키는 그대로 둔다</b>. {@code pages.pdf_path}·
 * {@code image_path}가 컬럼으로 저장돼 읽는 쪽이 그 값을 쓰므로, 키를 옮기지 않아도 된다.
 * 옮기면 삭제 한 번에 남은 쪽 수만큼 복사+삭제가 일어난다(205쪽 문서면 400회).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageDeleteService {

    private final JobRepository jobRepository;
    private final PageRepository pageRepository;
    private final PageDeleteRepository pageDeleteRepository;
    private final S3Service s3Service;

    /**
     * @return 삭제 후 남은 총 쪽수
     * @throws CustomException 타인 작업 403 · 변환 중 JOB4010 · 없는 쪽 JOB4001 · 마지막 한 장 COMMON4000
     */
    @Transactional
    public int deletePage(String userId, String jobId, int pageNo) {
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));

        // 변환 중에는 워커가 같은 쪽을 쓰고 있을 수 있다 — 이름변경·이동·다운로드와 같은 기준
        if ("PENDING".equals(job.getStatus()) || "IN_PROGRESS".equals(job.getStatus())) {
            throw new CustomException(ErrorCode.JOB_IN_PROGRESS);
        }
        // 마지막 한 장까지 지우면 결과가 하나도 없는 껍데기가 된다 — 그건 작업 삭제로 해야 한다
        if (job.getTotalPages() <= 1) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }

        Page page = pageRepository.findByJob_IdAndPageNo(jobId, pageNo)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));

        // 1) 변환 결과부터 — FK가 NO ACTION이라 자식 → 부모 순서로 (휴지통 완전 삭제와 같은 순서)
        pageDeleteRepository.deleteRuleTrails(jobId, pageNo);
        pageDeleteRepository.deleteTextElements(jobId, pageNo);
        pageDeleteRepository.deleteBrailleElements(jobId, pageNo);
        pageDeleteRepository.deleteBoundingBoxes(jobId, pageNo);
        pageDeleteRepository.deleteQualityCriticalErrors(jobId, pageNo);
        pageDeleteRepository.deleteQualityReviewFlags(jobId, pageNo);
        pageDeleteRepository.deletePageResult(jobId, pageNo);
        pageDeleteRepository.deletePage(jobId, pageNo);

        // 2) 뒤 쪽 번호 당김 (S3 키는 건드리지 않는다 — 클래스 주석 참조)
        pageDeleteRepository.shiftPagesAfter(jobId, pageNo);
        pageDeleteRepository.shiftPageResultsAfter(jobId, pageNo);

        // 3) Job의 쪽 관련 값 보정
        int remaining = job.getTotalPages() - 1;
        jobRepository.updateTotalPages(jobId, remaining);
        job.applyPageDeleted(pageNo, remaining, shiftFailedPages(job.getFailedPages(), pageNo));

        // 4) S3 — 지운 쪽의 원본 조각과 미리보기 이미지. 실패해도 삭제를 되돌리지 않는다(고아 객체만 남음)
        deleteQuietly(page.getPdfPath());
        deleteQuietly(page.getImagePath());

        log.info("원본 페이지 삭제: jobId={}, pageNo={}, 남은 쪽={}", jobId, pageNo, remaining);
        return remaining;
    }

    /** 실패 쪽 목록도 같이 당긴다 — 지운 쪽은 빼고, 그보다 뒤는 1씩 */
    static int[] shiftFailedPages(int[] failedPages, int deleted) {
        if (failedPages == null) return new int[]{};
        return Arrays.stream(failedPages)
                .filter(p -> p != deleted)
                .map(p -> p > deleted ? p - 1 : p)
                .toArray();
    }

    private void deleteQuietly(String path) {
        if (path == null || path.isBlank()) return;
        try {
            s3Service.deleteObject(path);
        } catch (Exception e) {
            log.warn("삭제한 쪽의 S3 객체 제거 실패(계속): path={}, error={}", path, e.getMessage());
        }
    }
}
