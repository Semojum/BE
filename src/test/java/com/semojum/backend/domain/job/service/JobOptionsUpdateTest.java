package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.job.dto.JobRequestDto;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 업로드 후 조판 설정 변경 (V32) — 에디터 "이 파일의 조판 설정" 모달.
 *
 * <p>종전엔 조회만 있어 모달이 화면에만 반영됐다: 40칸으로 다듬어 놓고 내려받으면 32칸 파일이
 * 나오고, 다시 열면 32칸으로 돌아갔다. 여기서 보는 것은 ① 보낸 항목만 바뀌는가 ② 꼬리말을
 * 언제 다시 점역하는가 ③ 판면이 좁아졌을 때 길이 검증이 다시 도는가다.
 */
class JobOptionsUpdateTest {

    JobRepository jobRepository;
    FooterBrailleService footerBrailleService;
    JobService jobService;
    String userId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        jobRepository = Mockito.mock(JobRepository.class);
        footerBrailleService = Mockito.mock(FooterBrailleService.class);
        jobService = Mockito.mock(JobService.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(jobService, "jobRepository", jobRepository);
        ReflectionTestUtils.setField(jobService, "footerBrailleService", footerBrailleService);
    }

    /**
     * 기준 작업 — 조판 옵션이 <b>12개 모두 기본값이 아니다.</b>
     *
     * <p>기본값(32×26·odd·center …)으로 두면 "안 보낸 값이 유지됐다"와 "안 보낸 값이 기본값으로
     * 덮였다"가 구분되지 않아, 부분 갱신이 깨져도 테스트가 통과한다(실제로 그렇게 짰다가 회귀
     * 검증에서 잡혔다). 그래서 일부러 전부 비기본값으로 둔다.
     */
    private static final LayoutOptions BASE = new LayoutOptions(
            40, 20, "every", 2, 100, 5, false, false, false, "right", "page", true).withDefaults();

    private Job job(String status, String footerText, String footerBraille) {
        Job job = Job.builder()
                .id("job-1").user(User.builder().loginId("testorg01").password("pw").build())
                .mode("b").totalPages(8).originalFileName("교재.txt")
                .insertPageNumber(true).footerText(footerText)
                .layoutOptions(BASE)
                .build();
        job.updateStatus(status);
        job.updateFooterBraille(footerBraille);
        when(jobRepository.findByIdAndUserId(anyString(), any(UUID.class))).thenReturn(Optional.of(job));
        return job;
    }

    private JobRequestDto.UpdateOptions patch(LayoutOptions options) {
        return new JobRequestDto.UpdateOptions(null, null, options);
    }

    /** 12개 중 하나만 담은 부분 패치 — 나머지는 null(=그대로 두라) */
    private LayoutOptions onlyCellsPerLine(int cells) {
        return new LayoutOptions(cells, null, null, null, null, null, null, null, null, null, null, null);
    }

    // ── 부분 갱신 ────────────────────────────────────────────────────────

    @Test
    void 보낸_항목만_바뀌고_나머지는_유지된다() {
        Job job = job("COMPLETED", null, null);

        JobResponseDto.Options result = jobService.updateJobOptions(userId, "job-1",
                patch(new LayoutOptions(36, null, "odd", null, null, null,
                        null, null, true, null, null, null)));
        LayoutOptions o = result.layoutOptions();

        // 보낸 3개는 바뀐다
        assertEquals(36, o.cellsPerLine());
        assertEquals("odd", o.pageNumberLine());
        assertTrue(o.showChangeLine());
        // 안 보낸 9개는 BASE 값 그대로 — 기본값(26·true·center·all·false …)으로 덮이면 안 된다
        assertEquals(20, o.linesPerPage());
        assertEquals(2, o.coverPages());
        assertEquals(100, o.sourcePageStart());
        assertEquals(5, o.braillePageStart());
        assertFalse(o.showSourcePageNumber());
        assertFalse(o.showBraillePageNumber());
        assertEquals("right", o.footerAlign());
        assertEquals("page", o.editScope());
        assertTrue(o.advancedAi());
        // 엔티티에도 반영됐는가 — 다운로드·재열람이 읽는 것은 이쪽이다
        assertEquals(36, job.resolveLayoutOptions().cellsPerLine());
        assertEquals(20, job.resolveLayoutOptions().linesPerPage());
    }

    @Test
    void layoutOptions를_아예_안_보내도_된다() {
        job("COMPLETED", null, null);

        JobResponseDto.Options result = jobService.updateJobOptions(userId, "job-1",
                new JobRequestDto.UpdateOptions(false, null, null));

        assertFalse(result.insertPageNumber());
        assertEquals(40, result.layoutOptions().cellsPerLine(), "조판 옵션은 손대지 않는다");
        assertEquals(20, result.layoutOptions().linesPerPage());
    }

    /** 설정 변경은 "내용이 바뀐 것"이 아니다 — 카드 날짜·정렬 기준을 흔들면 안 된다 */
    @Test
    void lastModifiedAt은_건드리지_않는다() {
        Job job = job("COMPLETED", null, null);
        var before = job.getLastModifiedAt();

        jobService.updateJobOptions(userId, "job-1", patch(onlyCellsPerLine(36)));

        assertEquals(before, job.getLastModifiedAt());
    }

    // ── 꼬리말 ──────────────────────────────────────────────────────────

    @Test
    void 꼬리말이_바뀌면_다시_점역한다() {
        job("COMPLETED", "안녕", "⠣⠒⠉⠻");
        when(footerBrailleService.translateForUpload(anyString(), any(), anyInt())).thenReturn("⠨⠎⠢⠨");

        JobResponseDto.Options result = jobService.updateJobOptions(userId, "job-1",
                new JobRequestDto.UpdateOptions(null, "제1장", null));

        assertEquals("제1장", result.footerText());
        assertEquals("⠨⠎⠢⠨", result.footerBraille());
        verify(footerBrailleService).translateForUpload(eq("제1장"), any(), eq(8));
    }

    /** 판면만 바꾼 경우 — AI를 다시 부르면 느리고 값도 같다 */
    @Test
    void 꼬리말이_그대로면_다시_점역하지_않는다() {
        job("COMPLETED", "안녕", "⠣⠒⠉⠻");

        JobResponseDto.Options result = jobService.updateJobOptions(userId, "job-1",
                patch(onlyCellsPerLine(36)));

        assertEquals("⠣⠒⠉⠻", result.footerBraille());
        verify(footerBrailleService, never()).translateForUpload(anyString(), any(), anyInt());
    }

    /**
     * 꼬리말은 그대로여도 판면이 좁아지면 페이지행에 안 들어갈 수 있다.
     * 라이브러리가 말없이 뒤에서 자르므로(지침 1장3-4) 여기서 막아야 한다.
     */
    @Test
    void 판면이_좁아지면_기존_꼬리말_길이를_다시_검사한다() {
        job("COMPLETED", "안녕", "⠣⠒⠉⠻");
        doThrow(new CustomException(ErrorCode.COMMON_BAD_REQUEST))
                .when(footerBrailleService).validateFits(anyString(), any(), anyInt());

        CustomException e = assertThrows(CustomException.class,
                () -> jobService.updateJobOptions(userId, "job-1", patch(onlyCellsPerLine(10))));

        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 빈_문자열이면_꼬리말과_점역_결과를_함께_지운다() {
        Job job = job("COMPLETED", "안녕", "⠣⠒⠉⠻");

        JobResponseDto.Options result = jobService.updateJobOptions(userId, "job-1",
                new JobRequestDto.UpdateOptions(null, "", null));

        assertNull(result.footerText());
        assertNull(result.footerBraille(), "묵자를 지웠으면 점자도 남으면 안 된다");
        assertNull(job.getFooterBraille());
        verify(footerBrailleService, never()).translateForUpload(anyString(), any(), anyInt());
    }

    @Test
    void 꼬리말을_안_보내면_그대로_둔다() {
        job("COMPLETED", "안녕", "⠣⠒⠉⠻");

        JobResponseDto.Options result = jobService.updateJobOptions(userId, "job-1",
                patch(onlyCellsPerLine(36)));

        assertEquals("안녕", result.footerText());
        assertEquals("⠣⠒⠉⠻", result.footerBraille());
    }

    @Test
    void 꼬리말_200자_초과는_거절한다() {
        job("COMPLETED", null, null);

        CustomException e = assertThrows(CustomException.class, () -> jobService.updateJobOptions(
                userId, "job-1", new JobRequestDto.UpdateOptions(null, "가".repeat(201), null)));

        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    // ── 가드 ────────────────────────────────────────────────────────────

    /** advancedAi가 gRPC 요청에 실리므로, 중간에 바뀌면 쪽마다 다른 설정으로 처리된다 */
    @Test
    void 변환_중이면_JOB4010이다() {
        for (String status : new String[]{"PENDING", "IN_PROGRESS"}) {
            job(status, null, null);

            CustomException e = assertThrows(CustomException.class,
                    () -> jobService.updateJobOptions(userId, "job-1", patch(onlyCellsPerLine(36))));

            assertEquals(ErrorCode.JOB_IN_PROGRESS, e.getErrorCode(), status);
        }
    }

    @Test
    void 타인_작업이면_403이다() {
        when(jobRepository.findByIdAndUserId(anyString(), any(UUID.class))).thenReturn(Optional.empty());

        CustomException e = assertThrows(CustomException.class,
                () -> jobService.updateJobOptions(userId, "job-1", patch(onlyCellsPerLine(36))));

        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
    }

    @Test
    void 값_범위를_벗어나면_COMMON4000이다() {
        job("COMPLETED", null, null);

        // 칸 수 하한(8) 미만 — BrailleAssist가 던지는 조건과 같다
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, assertThrows(CustomException.class,
                () -> jobService.updateJobOptions(userId, "job-1", patch(onlyCellsPerLine(4)))).getErrorCode());

        job("COMPLETED", null, null);
        LayoutOptions badEnum = new LayoutOptions(null, null, "sometimes", null, null, null,
                null, null, null, null, null, null);
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, assertThrows(CustomException.class,
                () -> jobService.updateJobOptions(userId, "job-1", patch(badEnum))).getErrorCode());
    }
}
