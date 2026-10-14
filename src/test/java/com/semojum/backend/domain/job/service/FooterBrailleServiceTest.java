package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 점역된 꼬리말 (FE 요청 S-4·S-9).
 *
 * <p><b>V3.1부터 서버는 꼬리말을 점역하지 않는다</b> — proto에서 TranslateText가 삭제됐고 점역은 앱이
 * 엔진으로 한다. 남은 일은 둘이다: 예전에 점역해 둔 값을 돌려주는 것, 그리고 그 값이 있는 작업에서
 * 판면을 좁혔을 때 여전히 들어가는지 보는 것(S-9).
 */
class FooterBrailleServiceTest {

    private final FooterBrailleService service = new FooterBrailleService();

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

    // ── 조회 ───────────────────────────────────────────────────────

    /** V3.0 이전 작업 — 업로드 때 점역해 둔 값을 그대로 준다 */
    @Test
    void 저장된_점역_꼬리말을_그대로_준다() {
        assertEquals("⠎⠣", service.resolve(job("수특", "⠎⠣")));
    }

    /** 새 작업은 점역 값이 없다 — 서버가 채우지 않고 앱이 묵자로 점역한다 */
    @Test
    void 점역_값이_없으면_채우지_않고_null이다() {
        assertNull(service.resolve(job("수특", null)));
    }

    @Test
    void 꼬리말이_없으면_null이다() {
        assertNull(service.resolve(job(null, null)));
    }

    // ── S-9: 길이 검증 (예전 점역 값이 있는 작업에서 판면을 좁힐 때) ──────

    /** 라이브러리는 자리에 안 맞는 꼬리말을 말없이 뒤에서 자른다(지침 1장3-4) */
    @Test
    void 판면에_안_들어가는_꼬리말은_COMMON4000() {
        CustomException e = assertThrows(CustomException.class,
                () -> service.validateFits("⠁".repeat(40), opts(32, "every"), 10));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 자리에_들어가면_통과한다() {
        assertDoesNotThrow(() -> service.validateFits("⠎⠣⠞⠕⠛", opts(32, "every"), 10));
    }

    /** 한 줄 칸 수를 늘리면 더 긴 꼬리말도 들어간다 — 검증이 옵션을 실제로 반영한다 */
    @Test
    void 검증은_한_줄_칸_수를_따른다() {
        assertThrows(CustomException.class, () -> service.validateFits("⠁".repeat(30), opts(32, "every"), 10));
        assertDoesNotThrow(() -> service.validateFits("⠁".repeat(30), opts(60, "every"), 10));
    }

    /** 페이지행 자체가 없으면 꼬리말이 놓일 자리도 없다 */
    @Test
    void 페이지행이_없으면_길이를_따지지_않는다() {
        assertDoesNotThrow(() -> service.validateFits("⠁".repeat(80), opts(32, "none"), 10));
    }
}
