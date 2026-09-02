package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageDeleteRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 원본 페이지 영구 삭제 (FE 요청 X-1).
 *
 * <p>유저 확정: 크레딧 환불 없음 · 편집 이력 보존 · 되돌리기 없음.
 */
class PageDeleteServiceTest {

    JobRepository jobRepository;
    PageRepository pageRepository;
    PageDeleteRepository deleteRepo;
    S3Service s3Service;
    PageDeleteService service;

    final String userId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        jobRepository = Mockito.mock(JobRepository.class);
        pageRepository = Mockito.mock(PageRepository.class);
        deleteRepo = Mockito.mock(PageDeleteRepository.class);
        s3Service = Mockito.mock(S3Service.class);
        service = new PageDeleteService(jobRepository, pageRepository, deleteRepo, s3Service);
    }

    private Job job(String status, int totalPages) {
        Job j = Job.builder()
                .id("job1").user(User.builder().loginId("testorg01").password("pw").build())
                .mode("a").totalPages(totalPages).originalFileName("교재.pdf").build();
        ReflectionTestUtils.setField(j, "status", status);
        return j;
    }

    private void given(Job j, int pageNo) {
        when(jobRepository.findByIdAndUserId(eq("job1"), any(UUID.class))).thenReturn(Optional.of(j));
        Page p = Page.builder().job(j).pageNo(pageNo)
                .pdfPath("job1/pages/page-" + pageNo + ".pdf").build();
        ReflectionTestUtils.setField(p, "imagePath", "job1/pages/page-" + pageNo + ".jpg");
        when(pageRepository.findByJob_IdAndPageNo("job1", pageNo)).thenReturn(Optional.of(p));
    }

    @Test
    void 삭제하면_남은_쪽수를_돌려준다() {
        Job j = job("COMPLETED", 10);
        given(j, 3);

        assertEquals(9, service.deletePage(userId, "job1", 3));
        verify(jobRepository).updateTotalPages("job1", 9);
        assertEquals(9, j.getTotalPages());
    }

    /** FK가 NO ACTION이라 자식 → 부모 순서가 지켜져야 한다 (휴지통 완전 삭제와 같은 순서) */
    @Test
    void 자식_테이블부터_순서대로_지운다() {
        given(job("COMPLETED", 5), 2);

        service.deletePage(userId, "job1", 2);

        InOrder o = inOrder(deleteRepo);
        o.verify(deleteRepo).deleteRuleTrails("job1", 2);
        o.verify(deleteRepo).deleteTextElements("job1", 2);
        o.verify(deleteRepo).deleteBrailleElements("job1", 2);
        o.verify(deleteRepo).deleteBoundingBoxes("job1", 2);
        o.verify(deleteRepo).deleteQualityCriticalErrors("job1", 2);
        o.verify(deleteRepo).deleteQualityReviewFlags("job1", 2);
        o.verify(deleteRepo).deletePageResult("job1", 2);
        o.verify(deleteRepo).deletePage("job1", 2);
    }

    @Test
    void 뒤_쪽_번호를_당긴다() {
        given(job("COMPLETED", 5), 2);

        service.deletePage(userId, "job1", 2);

        verify(deleteRepo).shiftPagesAfter("job1", 2);
        verify(deleteRepo).shiftPageResultsAfter("job1", 2);
    }

    /** 지운 쪽의 조각·미리보기만 지운다. 나머지 키는 그대로 둔다(경로가 컬럼에 저장돼 있어 옮길 필요가 없다) */
    @Test
    void 지운_쪽의_S3_객체만_삭제한다() {
        given(job("COMPLETED", 5), 2);

        service.deletePage(userId, "job1", 2);

        verify(s3Service).deleteObject("job1/pages/page-2.pdf");
        verify(s3Service).deleteObject("job1/pages/page-2.jpg");
        verify(s3Service, times(2)).deleteObject(anyString());   // 그 두 개가 전부
    }

    /** S3가 실패해도 DB 삭제를 되돌리지 않는다 — 고아 객체만 남고 사용자는 목적을 달성한다 */
    @Test
    void S3_삭제_실패는_전체를_막지_않는다() {
        given(job("COMPLETED", 5), 2);
        doThrow(new RuntimeException("S3 오류")).when(s3Service).deleteObject(anyString());

        assertEquals(4, service.deletePage(userId, "job1", 2));
    }

    // ── 가드 ────────────────────────────────────────────────────────

    @Test
    void 타인_작업은_403() {
        when(jobRepository.findByIdAndUserId(anyString(), any(UUID.class))).thenReturn(Optional.empty());

        CustomException e = assertThrows(CustomException.class,
                () -> service.deletePage(userId, "job1", 1));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
    }

    @Test
    void 변환_중이면_JOB4010() {
        for (String status : new String[]{"PENDING", "IN_PROGRESS"}) {
            when(jobRepository.findByIdAndUserId(eq("job1"), any(UUID.class)))
                    .thenReturn(Optional.of(job(status, 5)));

            CustomException e = assertThrows(CustomException.class,
                    () -> service.deletePage(userId, "job1", 2));
            assertEquals(ErrorCode.JOB_IN_PROGRESS, e.getErrorCode(), status);
        }
        verify(deleteRepo, never()).deletePage(anyString(), anyInt());
    }

    /** 마지막 한 장까지 지우면 결과 없는 껍데기가 된다 — 그건 작업 삭제로 해야 한다 */
    @Test
    void 마지막_한_장은_지울_수_없다() {
        when(jobRepository.findByIdAndUserId(eq("job1"), any(UUID.class)))
                .thenReturn(Optional.of(job("COMPLETED", 1)));

        CustomException e = assertThrows(CustomException.class,
                () -> service.deletePage(userId, "job1", 1));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 없는_쪽이면_JOB4001() {
        when(jobRepository.findByIdAndUserId(eq("job1"), any(UUID.class)))
                .thenReturn(Optional.of(job("COMPLETED", 5)));
        when(pageRepository.findByJob_IdAndPageNo("job1", 99)).thenReturn(Optional.empty());

        CustomException e = assertThrows(CustomException.class,
                () -> service.deletePage(userId, "job1", 99));
        assertEquals(ErrorCode.JOB_NOT_FOUND, e.getErrorCode());
    }

    // ── 실패 쪽 목록·마지막 편집 위치 보정 ──────────────────────────

    @Test
    void 실패_쪽_목록도_당겨진다() {
        assertArrayEquals(new int[]{1, 4},
                PageDeleteService.shiftFailedPages(new int[]{1, 3, 5}, 3), "지운 쪽은 빠지고 뒤는 1씩");
        assertArrayEquals(new int[]{1, 2},
                PageDeleteService.shiftFailedPages(new int[]{1, 2}, 5), "앞쪽은 그대로");
        assertArrayEquals(new int[]{}, PageDeleteService.shiftFailedPages(null, 1));
    }

    /** 에디터가 "마지막 편집 위치"로 없는 쪽을 열지 않게 */
    @Test
    void 마지막_편집_위치를_보정한다() {
        Job j = job("COMPLETED", 5);

        j.markContentEdited(4);
        j.applyPageDeleted(2, 4, new int[]{});
        assertEquals(3, j.getLastEditedPage(), "뒤쪽이면 1 당김");

        Job j2 = job("COMPLETED", 5);
        j2.markContentEdited(2);
        j2.applyPageDeleted(2, 4, new int[]{});
        assertEquals(2, j2.getLastEditedPage(), "지운 쪽이면 그 자리(이제 다음 쪽)");

        Job j3 = job("COMPLETED", 2);
        j3.markContentEdited(2);
        j3.applyPageDeleted(2, 1, new int[]{});
        assertEquals(1, j3.getLastEditedPage(), "범위를 넘지 않는다");
    }
}
