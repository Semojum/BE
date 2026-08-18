package com.semojum.backend.domain.billing;

import com.semojum.backend.domain.billing.entity.PricingConfig;
import com.semojum.backend.domain.billing.repository.PricingConfigRepository;
import com.semojum.backend.domain.billing.service.UsageCostService;
import com.semojum.backend.grpc.ModelUsage;
import com.semojum.backend.grpc.PageLayoutType;
import com.semojum.backend.grpc.UsageReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

// AI팀 "BE 관리 변수" 계산식 검증 — 단가표·환율·배율은 노션 초기값(2026-08-17) 그대로 사용
class UsageCostServiceTest {

    private PricingConfigRepository repository;
    private UsageCostService service;

    @BeforeEach
    void setUp() throws Exception {
        repository = Mockito.mock(PricingConfigRepository.class);
        service = new UsageCostService(repository);

        Map<String, Object> config = Map.of(
                "modelPrices", Map.of(
                        "claude-sonnet-5", Map.of("input", 3.00, "output", 15.00),
                        "gpt-4o", Map.of("input", 2.50, "output", 10.00)),
                "gpuUsdPerHour", 1.006,
                "usdKrw", 1390,
                "cardFeeRate", 0.010,
                "creditMultiplier", Map.of(
                        "PAGE_LAYOUT_UNSPECIFIED", 0,
                        "PAGE_LAYOUT_TEXT", 1,
                        "PAGE_LAYOUT_FORMULA", 2,
                        "PAGE_LAYOUT_TABLE", 3,
                        "PAGE_LAYOUT_VISUAL", 5));

        // PricingConfig는 @NoArgsConstructor(PROTECTED) + @Builder — 리플렉션 없이 빌더로 생성 후 id만 주입
        PricingConfig pc = PricingConfig.builder().config(config).note("test").build();
        var idField = PricingConfig.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(pc, 7L);
        when(repository.findTopByOrderByIdDesc()).thenReturn(Optional.of(pc));
    }

    @Test
    void 계산식_LLM과_GPU와_환율() {
        // sonnet 1M in($3.00) + 200k out($3.00) = $6.00, GPU 1시간 = $1.006
        UsageReport usage = UsageReport.newBuilder()
                .setLayoutType(PageLayoutType.PAGE_LAYOUT_TABLE)
                .addModels(ModelUsage.newBuilder()
                        .setModel("claude-sonnet-5").setCalls(3)
                        .setInputTokens(1_000_000).setOutputTokens(200_000))
                .setGpuTimeMs(3_600_000)
                .build();

        var cost = service.calculate(usage);

        assertEquals(0, new BigDecimal("6.000000000").compareTo(cost.llmCostUsd()));
        assertEquals(0, new BigDecimal("1.006000000").compareTo(cost.gpuCostUsd()));
        // (6 + 1.006) × 1390 × 1.01 = 9835.7234 → scale 3 반올림
        assertEquals(0, new BigDecimal("9835.723").compareTo(cost.costKrw()));
        assertEquals(3, cost.credit());                       // TABLE 배율
        assertFalse(cost.uncertain());
        assertEquals(7L, cost.pricingConfigId());
        assertEquals("PAGE_LAYOUT_TABLE", cost.layoutType());
    }

    @Test
    void 미등록_모델은_0원으로_삼키지_않고_미계상_표시() {
        UsageReport usage = UsageReport.newBuilder()
                .setLayoutType(PageLayoutType.PAGE_LAYOUT_TEXT)
                .addModels(ModelUsage.newBuilder()
                        .setModel("gpt-4o").setInputTokens(2_000_000).setOutputTokens(0))
                .addModels(ModelUsage.newBuilder()
                        .setModel("unknown-model-v9").setInputTokens(1_000_000).setOutputTokens(1_000_000))
                .setGpuTimeMs(0)
                .build();

        var cost = service.calculate(usage);

        assertTrue(cost.uncertain());                                          // 미계상 플래그
        assertEquals(0, new BigDecimal("5.000000000").compareTo(cost.llmCostUsd())); // 등록된 모델만 합산
        assertEquals(Boolean.TRUE, cost.modelUsage().get(1).get("unpriced"));  // 원자료에 미계상 표식
        assertEquals(1, cost.credit());
    }

    @Test
    void UNSPECIFIED는_크레딧_0_LLM_미사용은_GPU만() {
        UsageReport usage = UsageReport.newBuilder()
                .setLayoutType(PageLayoutType.PAGE_LAYOUT_UNSPECIFIED)
                .setGpuTimeMs(1_800_000)   // 30분 → 0.503 USD
                .build();

        var cost = service.calculate(usage);

        assertEquals(0, cost.credit());
        assertEquals(0, BigDecimal.ZERO.setScale(9).compareTo(cost.llmCostUsd()));
        assertEquals(0, new BigDecimal("0.503000000").compareTo(cost.gpuCostUsd()));
        assertNull(cost.modelUsage());     // 모델 없음 → jsonb도 null
    }

    @Test
    void VISUAL은_크레딧_5() {
        UsageReport usage = UsageReport.newBuilder()
                .setLayoutType(PageLayoutType.PAGE_LAYOUT_VISUAL)
                .build();
        assertEquals(5, service.calculate(usage).credit());
    }
}
