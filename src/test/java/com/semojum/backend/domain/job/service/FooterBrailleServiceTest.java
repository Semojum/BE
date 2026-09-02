package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.grpc.BrailleGrpcClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 꼬리말 점역 (FE 요청 S-4·S-9).
 *
 * <p>화면이 페이지행의 꼬리말을 그릴 수 있으려면 점역된 값이 응답에 실려야 하고,
 * 그 값이 판면에 안 들어갈 길이면 업로드에서 막아야 한다(라이브러리는 말없이 자른다).
 */
class FooterBrailleServiceTest {

    BrailleGrpcClient grpc;
    JobRepository jobRepository;
    FooterBrailleService service;

    @BeforeEach
    void setUp() {
        grpc = Mockito.mock(BrailleGrpcClient.class);
        jobRepository = Mockito.mock(JobRepository.class);
        service = new FooterBrailleService(grpc, jobRepository);
    }

    private LayoutOptions opts(Integer cells, String pageRow) {
        return new LayoutOptions(cells, null, pageRow, null, null, null,
                null, null, null, null, null, null).withDefaults();
    }

    private Job job(String footerText, String footerBraille) {
        Job j = Job.builder().id("job1").mode("c").totalPages(2)
                .originalFileName("교재.pdf").footerText(footerText).build();
        j.updateFooterBraille(footerBraille);
        return j;
    }

    // ── 업로드 시점 ────────────────────────────────────────────────

    @Test
    void 꼬리말이_없으면_점역하지_않는다() {
        assertNull(service.translateForUpload(null, opts(null, null), 10));
        assertNull(service.translateForUpload("   ", opts(null, null), 10));
        verify(grpc, never()).translateText(anyString());
    }

    @Test
    void 업로드_때_한_번_점역한다() {
        when(grpc.translateText("수특")).thenReturn("⠎⠣");
        assertEquals("⠎⠣", service.translateForUpload("수특", opts(null, null), 10));
    }

    /** AI가 죽어도 업로드는 진행돼야 한다 — 썸네일과 같은 취급 */
    @Test
    void 점역_실패는_업로드를_막지_않는다() {
        when(grpc.translateText(anyString())).thenThrow(new RuntimeException("AI 연결 실패"));
        assertNull(service.translateForUpload("수특", opts(null, null), 10));
    }

    // ── S-9: 길이 검증 ─────────────────────────────────────────────

    /**
     * 라이브러리는 자리에 안 맞는 꼬리말을 말없이 뒤에서 자른다(지침 1장3-4).
     * 사용자가 잘린 줄 모르고 내려받지 않도록 업로드에서 막는다.
     */
    @Test
    void 판면에_안_들어가는_꼬리말은_COMMON4000() {
        when(grpc.translateText(anyString())).thenReturn("⠁".repeat(40));   // 32칸 판면에 40칸

        CustomException e = assertThrows(CustomException.class,
                () -> service.translateForUpload("아주 긴 꼬리말", opts(32, "every"), 10));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 자리에_들어가면_통과한다() {
        when(grpc.translateText(anyString())).thenReturn("⠎⠣⠞⠕⠛");
        assertEquals("⠎⠣⠞⠕⠛", service.translateForUpload("수특", opts(32, "every"), 10));
    }

    /** 한 줄 칸 수를 늘리면 더 긴 꼬리말도 들어간다 — 검증이 옵션을 실제로 반영한다 */
    @Test
    void 검증은_한_줄_칸_수를_따른다() {
        when(grpc.translateText(anyString())).thenReturn("⠁".repeat(30));

        assertThrows(CustomException.class,
                () -> service.translateForUpload("긴 꼬리말", opts(32, "every"), 10));
        assertDoesNotThrow(
                () -> service.translateForUpload("긴 꼬리말", opts(60, "every"), 10));
    }

    /** 페이지행 자체가 없으면 꼬리말이 놓일 자리도 없다 — 길이를 따지지 않는다 */
    @Test
    void 페이지행이_없으면_길이를_따지지_않는다() {
        when(grpc.translateText(anyString())).thenReturn("⠁".repeat(80));
        assertDoesNotThrow(() -> service.translateForUpload("긴 꼬리말", opts(32, "none"), 10));
    }

    // ── 조회 시점 ──────────────────────────────────────────────────

    @Test
    void 이미_점역돼_있으면_다시_부르지_않는다() {
        assertEquals("⠎⠣", service.resolve(job("수특", "⠎⠣")));
        verify(grpc, never()).translateText(anyString());
    }

    /** 업로드 때 실패했거나 V31 이전에 만든 작업 — 조회 시점에 채운다 */
    @Test
    void 미점역이면_조회_때_채운다() {
        Job j = job("수특", null);
        Job fresh = job("수특", null);
        when(jobRepository.findById("job1")).thenReturn(Optional.of(fresh));
        when(grpc.translateText("수특")).thenReturn("⠎⠣");

        assertEquals("⠎⠣", service.resolve(j));
        assertEquals("⠎⠣", fresh.getFooterBraille(), "다음 조회부터는 저장된 값을 쓴다");
    }

    @Test
    void 꼬리말이_없으면_null이다() {
        assertNull(service.resolve(job(null, null)));
        verify(grpc, never()).translateText(anyString());
    }

    /** 조회 경로에서는 실패해도 화면·다운로드가 죽으면 안 된다 */
    @Test
    void 조회_때_점역_실패는_null로_삼킨다() {
        when(jobRepository.findById("job1")).thenReturn(Optional.of(job("수특", null)));
        when(grpc.translateText(anyString())).thenThrow(new RuntimeException("AI 연결 실패"));

        assertNull(service.resolve(job("수특", null)));
    }
}
