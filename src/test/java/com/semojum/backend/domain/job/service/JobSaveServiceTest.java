package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.dto.JobRequestDto;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.result.service.PageContentSaver;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * 작업 저장 — 쪽 단위 트랜잭션의 <b>부분 성공</b>이 핵심이다.
 *
 * <p>205쪽을 한 트랜잭션으로 묶으면 한 쪽이 실패할 때 하루치 편집이 통째로 날아간다.
 * 그래서 실패한 쪽만 걷어내고 나머지는 저장한 뒤, 그 번호를 돌려줘 화면이 "다시 시도"를 띄우게 한다.
 */
class JobSaveServiceTest {

    JobRepository jobRepository;
    JobService jobService;
    PageContentSaver pageContentSaver;
    JobSaveService service;

    final String USER_ID = UUID.randomUUID().toString();
    Job job;

    @BeforeEach
    void setUp() {
        jobRepository = Mockito.mock(JobRepository.class);
        jobService = Mockito.mock(JobService.class);
        pageContentSaver = Mockito.mock(PageContentSaver.class);
        service = new JobSaveService(jobRepository, jobService, pageContentSaver);

        job = Job.builder().id("job1").mode("c").totalPages(3).originalFileName("교재.pdf").build();
        when(jobRepository.findByIdAndUserId(anyString(), any())).thenReturn(Optional.of(job));
    }

    private JobRequestDto.SaveJobPage page(int no) {
        return new JobRequestDto.SaveJobPage(no, List.of(), List.of(), List.of());
    }

    private JobRequestDto.SaveJob request(int... pageNos) {
        List<JobRequestDto.SaveJobPage> pages = new java.util.ArrayList<>();
        for (int no : pageNos) pages.add(page(no));
        return new JobRequestDto.SaveJob(true, "수특 사회문화 2", null, null, pages);
    }

    @Test
    void 전부_성공하면_실패_목록은_빈_배열이다() {
        JobResponseDto.SaveResult result = service.save(USER_ID, "job1", request(1, 2, 3));

        assertEquals(3, result.savedPages());
        assertEquals(List.of(), result.failedPages(), "null이 아니라 빈 배열 — FE가 null 검사 없이 순회한다");
        assertEquals("job1", result.jobId());
        assertNotNull(result.savedAt());
    }

    /** 한 쪽이 터져도 나머지는 저장된다 — 이게 쪽 단위 트랜잭션을 둔 이유다 */
    @Test
    void 실패한_쪽만_걷어내고_나머지는_저장한다() {
        doThrow(new CustomException(ErrorCode.ELEMENT_NOT_FOUND))
                .when(pageContentSaver).savePage(any(), argThat(p -> p != null && p.pageNo() == 2));

        JobResponseDto.SaveResult result = service.save(USER_ID, "job1", request(1, 2, 3));

        assertEquals(2, result.savedPages());
        assertEquals(List.of(2), result.failedPages());
        verify(pageContentSaver, times(3)).savePage(any(), any());
    }

    /** 앞 쪽이 실패해도 뒤 쪽 저장을 멈추지 않는다 */
    @Test
    void 첫_쪽이_실패해도_끝까지_돈다() {
        doThrow(new RuntimeException("DB 끊김"))
                .when(pageContentSaver).savePage(any(), argThat(p -> p != null && p.pageNo() == 1));

        JobResponseDto.SaveResult result = service.save(USER_ID, "job1", request(1, 2, 3));

        assertEquals(2, result.savedPages());
        assertEquals(List.of(1), result.failedPages());
    }

    @Test
    void 전부_실패하면_저장_0쪽이다() {
        doThrow(new RuntimeException("DB 끊김")).when(pageContentSaver).savePage(any(), any());

        JobResponseDto.SaveResult result = service.save(USER_ID, "job1", request(1, 2));

        assertEquals(0, result.savedPages());
        assertEquals(List.of(1, 2), result.failedPages());
    }

    /**
     * 설정은 쪽보다 먼저 확정된다 — 소유자·변환 중 검증이 거기 모여 있어서,
     * 거절될 요청이면 쪽을 하나도 건드리지 않고 끝나야 한다.
     */
    @Test
    void 변환_중이면_쪽을_하나도_건드리지_않는다() {
        doThrow(new CustomException(ErrorCode.JOB_IN_PROGRESS))
                .when(jobService).updateJobOptions(anyString(), anyString(), any());

        CustomException e = assertThrows(CustomException.class,
                () -> service.save(USER_ID, "job1", request(1, 2, 3)));

        assertEquals(ErrorCode.JOB_IN_PROGRESS, e.getErrorCode());
        verify(pageContentSaver, never()).savePage(any(), any());
    }

    @Test
    void 조판_설정과_꼬리말은_설정_갱신으로_넘긴다() {
        service.save(USER_ID, "job1", request(1));

        verify(jobService).updateJobOptions(eq(USER_ID), eq("job1"),
                argThat(o -> Boolean.TRUE.equals(o.insertPageNumber())
                        && "수특 사회문화 2".equals(o.footerText())));
    }

    // ── 텍스트 작업의 쪽 수 (V3.1) ──────────────────────────────────
    // 텍스트 작업은 쪽 나누기를 앱이 정한다. 생성 땐 0이고 저장된 마지막 쪽 번호가 쪽 수가 된다.

    @Test
    void 텍스트_작업은_저장된_마지막_쪽이_쪽_수가_된다() {
        Job textJob = Job.builder().id("job-t").mode("b").totalPages(0).originalFileName("무제").build();
        when(jobRepository.findByIdAndUserId(anyString(), any())).thenReturn(Optional.of(textJob));

        service.save(USER_ID, "job-t", request(1, 2, 3));

        assertEquals(3, textJob.getTotalPages());
    }

    /** 본문이 최종 상태라 앱이 쪽을 줄였으면 쪽 수도 준다 */
    @Test
    void 텍스트_작업에서_쪽이_줄면_쪽_수도_준다() {
        Job textJob = Job.builder().id("job-t").mode("b").totalPages(5).originalFileName("무제").build();
        when(jobRepository.findByIdAndUserId(anyString(), any())).thenReturn(Optional.of(textJob));

        service.save(USER_ID, "job-t", request(1, 2));

        assertEquals(2, textJob.getTotalPages());
    }

    /** 실패한 쪽은 쪽 수에 넣지 않는다 — 없는 쪽을 가리키게 되면 조회가 404다 */
    @Test
    void 텍스트_작업의_실패한_마지막_쪽은_쪽_수에_넣지_않는다() {
        Job textJob = Job.builder().id("job-t").mode("b").totalPages(0).originalFileName("무제").build();
        when(jobRepository.findByIdAndUserId(anyString(), any())).thenReturn(Optional.of(textJob));
        doThrow(new RuntimeException("쪽 3 실패")).when(pageContentSaver)
                .savePage(any(), argThat(pg -> pg != null && pg.pageNo() == 3));

        service.save(USER_ID, "job-t", request(1, 2, 3));

        assertEquals(2, textJob.getTotalPages());
    }

    /** PDF 작업의 쪽 수는 변환이 정한다 — 저장이 건드리지 않는다 */
    @Test
    void PDF_작업의_쪽_수는_저장이_바꾸지_않는다() {
        service.save(USER_ID, "job1", request(1));

        assertEquals(3, job.getTotalPages());
    }
}
