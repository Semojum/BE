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
    JobCopyService jobCopyService;
    com.semojum.backend.domain.result.service.TextPageTrimmer trimmer;
    com.semojum.backend.domain.job.repository.PageRepository pageRepository;
    com.semojum.backend.domain.auth.repository.UserRepository userRepository;
    JobSaveService service;

    final String USER_ID = UUID.randomUUID().toString();
    Job job;

    @BeforeEach
    void setUp() {
        jobRepository = Mockito.mock(JobRepository.class);
        jobService = Mockito.mock(JobService.class);
        pageContentSaver = Mockito.mock(PageContentSaver.class);
        jobCopyService = Mockito.mock(JobCopyService.class);
        userRepository = Mockito.mock(com.semojum.backend.domain.auth.repository.UserRepository.class);
        trimmer = Mockito.mock(com.semojum.backend.domain.result.service.TextPageTrimmer.class);
        pageRepository = Mockito.mock(com.semojum.backend.domain.job.repository.PageRepository.class);
        service = new JobSaveService(jobRepository, jobService, pageContentSaver, jobCopyService, trimmer,
                pageRepository, userRepository);

        me = user(USER_ID, "testorg01", com.semojum.backend.domain.auth.enums.Role.ROLE_USER);
        job = Job.builder().id("job1").user(me).mode("c").totalPages(3).originalFileName("교재.pdf").build();
        when(jobRepository.findById("job1")).thenReturn(Optional.of(job));
        when(jobRepository.findByIdAndUserId(anyString(), any())).thenReturn(Optional.of(job));
    }

    com.semojum.backend.domain.auth.entity.User me;

    private com.semojum.backend.domain.auth.entity.User user(String id, String loginId,
                                                             com.semojum.backend.domain.auth.enums.Role role) {
        var u = com.semojum.backend.domain.auth.entity.User.builder().loginId(loginId).password("pw").build();
        org.springframework.test.util.ReflectionTestUtils.setField(u, "id", UUID.fromString(id));
        u.changeRole(role);
        when(userRepository.findById(UUID.fromString(id))).thenReturn(Optional.of(u));
        return u;
    }

    private JobRequestDto.SaveJobPage page(int no) {
        return new JobRequestDto.SaveJobPage(no, List.of(), List.of(), List.of());
    }

    private JobRequestDto.SaveJob request(int... pageNos) {
        List<JobRequestDto.SaveJobPage> pages = new java.util.ArrayList<>();
        for (int no : pageNos) pages.add(page(no));
        return new JobRequestDto.SaveJob(true, "수특 사회문화 2", null, null, pages, null);
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

    private Job textJob(int totalPages) {
        Job t = Job.builder().id("job-t").user(me).mode("b").totalPages(totalPages).originalFileName("무제").build();
        when(jobRepository.findByIdAndUserId(anyString(), any())).thenReturn(Optional.of(t));
        when(jobRepository.findById("job-t")).thenReturn(Optional.of(t));
        return t;
    }

    private JobRequestDto.SaveJob withTotal(Integer total, int... pageNos) {
        JobRequestDto.SaveJob r = request(pageNos);
        return new JobRequestDto.SaveJob(r.insertPageNumber(), r.footerText(), null, null, r.pages(), total);
    }

    /** 쪽 수 = 남아 있는 마지막 쪽 번호 — 새 문서의 첫 저장이 쪽을 만든다 */
    @Test
    void 텍스트_작업은_남아_있는_마지막_쪽이_쪽_수가_된다() {
        Job t = textJob(0);
        when(pageRepository.findMaxPageNo("job-t")).thenReturn(3);

        service.save(USER_ID, "job-t", request(1, 2, 3));

        assertEquals(3, t.getTotalPages());
    }

    /** FE 점검 B-02 — 3쪽 문서의 2 · 3쪽 저장이 실패해도 쪽 수는 그대로다(종전엔 1이 됐다) */
    @Test
    void 일부_쪽이_실패해도_쪽_수가_줄지_않는다() {
        Job t = textJob(3);
        when(pageRepository.findMaxPageNo("job-t")).thenReturn(3);
        doThrow(new RuntimeException("실패")).when(pageContentSaver)
                .savePage(any(), argThat(pg -> pg != null && pg.pageNo() >= 2));

        JobResponseDto.SaveResult result = service.save(USER_ID, "job-t", request(1, 2, 3));

        assertEquals(List.of(2, 3), result.failedPages());
        assertEquals(3, t.getTotalPages());
        verify(trimmer, never()).deletePagesAfter(any(), anyInt());
    }

    /** 쪽을 빼고 보내도 totalPages를 안 보내면 줄이지 않는다 — 실패 쪽만 다시 보낸 요청일 수 있다 */
    @Test
    void totalPages를_안_보내면_쪽을_줄이지_않는다() {
        Job t = textJob(5);
        when(pageRepository.findMaxPageNo("job-t")).thenReturn(5);

        service.save(USER_ID, "job-t", request(1, 2));

        assertEquals(5, t.getTotalPages());
        verify(trimmer, never()).deletePagesAfter(any(), anyInt());
    }

    /** 앱이 쪽을 줄였으면 totalPages로 명시한다 — 그 뒤 쪽을 지운 뒤 쪽 수가 준다 */
    @Test
    void totalPages를_보내면_그_뒤_쪽을_지우고_줄인다() {
        Job t = textJob(5);
        when(pageRepository.findMaxPageNo("job-t")).thenReturn(2);

        service.save(USER_ID, "job-t", withTotal(2, 1, 2));

        verify(trimmer).deletePagesAfter(t, 2);
        assertEquals(2, t.getTotalPages());
    }

    /** 새 문서에서 마지막 쪽 생성이 실패하면 그 쪽은 없다 — 없는 쪽을 가리키면 조회가 404다 */
    @Test
    void 새_쪽_생성이_실패하면_쪽_수에_넣지_않는다() {
        Job t = textJob(0);
        when(pageRepository.findMaxPageNo("job-t")).thenReturn(2);
        doThrow(new RuntimeException("쪽 3 실패")).when(pageContentSaver)
                .savePage(any(), argThat(pg -> pg != null && pg.pageNo() == 3));

        service.save(USER_ID, "job-t", request(1, 2, 3));

        assertEquals(2, t.getTotalPages());
    }

    /** PDF · 이미지의 쪽은 원본 쪽이다 — totalPages를 보내면 아무것도 쓰기 전에 400 */
    @Test
    void PDF_작업에_totalPages를_보내면_400이다() {
        CustomException e = assertThrows(CustomException.class, () -> service.save(USER_ID, "job1", withTotal(1, 1)));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
        verify(jobService, never()).updateJobOptions(any(), any(), any());
    }

    @Test
    void totalPages보다_뒤_쪽을_보내면_400이다() {
        textJob(3);
        for (JobRequestDto.SaveJob bad : List.of(withTotal(2, 1, 3), withTotal(0, 1))) {
            CustomException e = assertThrows(CustomException.class, () -> service.save(USER_ID, "job-t", bad));
            assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
        }
        verify(pageContentSaver, never()).savePage(any(), any());
    }

    /** PDF 작업의 쪽 수는 변환이 정한다 — 저장이 건드리지 않는다 */
    @Test
    void PDF_작업의_쪽_수는_저장이_바꾸지_않는다() {
        service.save(USER_ID, "job1", request(1));

        assertEquals(3, job.getTotalPages());
    }

    // ── 받은 .smj 파일 저장 → 내 계정에 사본 (2026-10-21) ──────────────

    /** 남의 작업을 가리키는 저장 — 사본을 만들어 거기에 저장하고, 앱이 바꿀 수 있게 새 jobId를 준다 */
    @Test
    void 받은_파일을_저장하면_내_사본에_저장한다() {
        String otherId = UUID.randomUUID().toString();
        var receiver = user(otherId, "testorg02", com.semojum.backend.domain.auth.enums.Role.ROLE_USER);
        Job copy = Job.builder().id("job_copy").user(receiver).mode("c").totalPages(3).originalFileName("교재.pdf").build();
        when(jobCopyService.copyFor(receiver, job)).thenReturn(copy);
        when(jobRepository.findByIdAndUserId(eq("job_copy"), any())).thenReturn(Optional.of(copy));

        JobResponseDto.SaveResult result = service.save(otherId, "job1", request(1, 2));

        assertEquals("job_copy", result.jobId(), "이후 저장은 사본으로 — 앱이 파일의 작업 ID를 바꾼다");
        assertEquals("job1", result.copiedFromJobId());
        verify(jobService).updateJobOptions(eq(otherId), eq("job_copy"), any());
        verify(pageContentSaver, times(2)).savePage(eq(copy), any());
        verify(pageContentSaver, never()).savePage(eq(job), any());
    }

    @Test
    void 내_작업이면_사본을_만들지_않는다() {
        JobResponseDto.SaveResult result = service.save(USER_ID, "job1", request(1));

        assertNull(result.copiedFromJobId());
        verify(jobCopyService, never()).copyFor(any(), any());
    }

    /** 기관 관리자는 에디터를 쓰지 않는다 — 받은 파일로도 사본을 만들 수 없다 */
    @Test
    void 기관_관리자는_사본을_만들_수_없다() {
        String adminId = UUID.randomUUID().toString();
        user(adminId, "orgadmin", com.semojum.backend.domain.auth.enums.Role.ROLE_ORG_ADMIN);

        CustomException e = assertThrows(CustomException.class, () -> service.save(adminId, "job1", request(1)));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
        verify(jobCopyService, never()).copyFor(any(), any());
    }

    @Test
    void 없는_작업은_JOB4001이다() {
        when(jobRepository.findById("ghost")).thenReturn(Optional.empty());

        CustomException e = assertThrows(CustomException.class, () -> service.save(USER_ID, "ghost", request(1)));
        assertEquals(ErrorCode.JOB_NOT_FOUND, e.getErrorCode());
    }

    // ── 요청 검증 · 쪽별 실패 사유 (FE 점검 B-07) ─────────────────────

    /** 같은 쪽이 두 번 오면 뒤가 앞을 덮어 앞 내용이 조용히 사라진다 — 쓰기 전에 거절 */
    @Test
    void 같은_쪽이_두_번_오거나_0쪽이면_아무것도_쓰지_않고_400이다() {
        for (JobRequestDto.SaveJob bad : List.of(request(1, 1), request(0))) {
            CustomException e = assertThrows(CustomException.class, () -> service.save(USER_ID, "job1", bad));
            assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
        }
        verify(jobService, never()).updateJobOptions(any(), any(), any());
        verify(pageContentSaver, never()).savePage(any(), any());
    }

    /** 실패한 쪽의 사유를 공개 에러 코드로 — 잘못된 데이터(4xx)와 일시 장애(5000)를 앱이 가른다 */
    @Test
    void 쪽마다_결과와_실패_사유를_준다() {
        doThrow(new CustomException(ErrorCode.ELEMENT_LIST_MISMATCH))
                .when(pageContentSaver).savePage(any(), argThat(p -> p != null && p.pageNo() == 2));
        doThrow(new RuntimeException("DB 연결 끊김 — 원문은 앱에 보내지 않는다"))
                .when(pageContentSaver).savePage(any(), argThat(p -> p != null && p.pageNo() == 3));

        JobResponseDto.SaveResult result = service.save(USER_ID, "job1", request(1, 2, 3));

        assertEquals(List.of(
                new JobResponseDto.PageSaveResult(1, "SAVED", null),
                new JobResponseDto.PageSaveResult(2, "FAILED", "JOB4006"),
                new JobResponseDto.PageSaveResult(3, "FAILED", "COMMON5000")), result.pageResults());
        assertEquals(List.of(2, 3), result.failedPages(), "기존 failedPages는 그대로");
    }

    /** 쪽 · 블록 · 메모 · 답글까지 필수값을 검증한다 — 종전엔 컨트롤러 검증이 쪽 안으로 내려가지 않아 쪽 실패로 묻혔다 */
    @Test
    void 중첩된_항목의_필수값도_검증한다() {
        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        java.util.function.Function<JobRequestDto.SaveJobPage, JobRequestDto.SaveJob> wrap =
                pg -> new JobRequestDto.SaveJob(null, null, null, null, List.of(pg), null);
        java.util.List<JobRequestDto.SaveElement> withNull = new java.util.ArrayList<>();
        withNull.add(null);

        for (JobRequestDto.SaveJobPage bad : List.of(
                new JobRequestDto.SaveJobPage(1, List.of(new JobRequestDto.SaveElement("e1", null, null)), null, null),
                new JobRequestDto.SaveJobPage(1, withNull, null, null),
                new JobRequestDto.SaveJobPage(1, null, null, List.of(new JobRequestDto.SaveMemo(
                        null, "TEXT", "e1", 0, null, null, null, null, null, null, null))),
                new JobRequestDto.SaveJobPage(1, null, null, List.of(new JobRequestDto.SaveMemo(
                        null, "TEXT", "e1", 0, null, "메모", null, null, null, null,
                        List.of(new JobRequestDto.SaveMemoReply("이교정", null, null))))))) {
            assertFalse(validator.validate(wrap.apply(bad)).isEmpty(), bad.toString());
        }
        assertTrue(validator.validate(wrap.apply(page(1))).isEmpty(), "정상 요청은 통과");
    }
}
