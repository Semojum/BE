package com.semojum.backend.domain.billing.service;

import com.semojum.backend.domain.billing.entity.PricingConfig;
import com.semojum.backend.domain.billing.repository.PricingConfigRepository;
import com.semojum.backend.grpc.ModelUsage;
import com.semojum.backend.grpc.UsageReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI UsageReport(측정값) → 원가(USD)·크레딧 계산 (AI팀 노션 "BE 관리 변수" 계산식).
 *   llm_cost_usd = Σ_모델( input_tokens/1e6 × input단가 + output_tokens/1e6 × output단가 )
 *   gpu_cost_usd = gpu_time_ms / 3_600_000 × GPU_USD_PER_HOUR
 *   cost_krw     = (llm+gpu) × USD_KRW × (1 + CARD_FEE_RATE)   — 참고값, 정본은 USD
 *   credit       = CREDIT_MULTIPLIER[layout_type]              — 쪽당
 * 단가·환율은 수시로 바뀌므로 계산 결과는 처리 시점 값으로 확정 저장한다(호출부 책임).
 * 단가표에 없는 모델은 0원으로 삼키지 않고 uncertain=true("미계상")로 표시한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageCostService {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal MS_PER_HOUR = BigDecimal.valueOf(3_600_000);
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private final PricingConfigRepository pricingConfigRepository;

    public record CostBreakdown(
            String layoutType,
            long gpuTimeMs,
            List<Map<String, Object>> modelUsage,   // jsonb 저장용 원자료
            BigDecimal llmCostUsd,
            BigDecimal gpuCostUsd,
            BigDecimal costKrw,
            boolean uncertain,                      // 단가표에 없는 모델 포함 = 미계상
            Long pricingConfigId,
            int credit
    ) {}

    @SuppressWarnings("unchecked")
    public CostBreakdown calculate(UsageReport usage) {
        PricingConfig pc = pricingConfigRepository.findTopByOrderByIdDesc()
                .orElseThrow(() -> new IllegalStateException("pricing_configs가 비어 있음 — V16 시드 확인"));
        Map<String, Object> cfg = pc.getConfig();
        Map<String, Object> modelPrices = (Map<String, Object>) cfg.getOrDefault("modelPrices", Map.of());
        Map<String, Object> multipliers = (Map<String, Object>) cfg.getOrDefault("creditMultiplier", Map.of());
        BigDecimal gpuUsdPerHour = decimal(cfg.get("gpuUsdPerHour"));
        BigDecimal usdKrw = decimal(cfg.get("usdKrw"));
        BigDecimal cardFeeRate = decimal(cfg.get("cardFeeRate"));

        String layoutType = usage.getLayoutType().name();

        // LLM 비용 — 모델별 토큰 × 단가. 미등록 모델은 uncertain으로 표시하고 합산에서 제외
        BigDecimal llmUsd = BigDecimal.ZERO;
        boolean uncertain = false;
        List<Map<String, Object>> modelUsageJson = new ArrayList<>();
        for (ModelUsage mu : usage.getModelsList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("model", mu.getModel());
            row.put("calls", mu.getCalls());
            row.put("inputTokens", mu.getInputTokens());
            row.put("outputTokens", mu.getOutputTokens());
            Map<String, Object> price = (Map<String, Object>) modelPrices.get(mu.getModel());
            if (price == null) {
                uncertain = true;
                row.put("unpriced", true);
                log.warn("단가표에 없는 모델 — 미계상 처리: model={}", mu.getModel());
            } else {
                BigDecimal cost = BigDecimal.valueOf(mu.getInputTokens()).divide(MILLION, MC).multiply(decimal(price.get("input")), MC)
                        .add(BigDecimal.valueOf(mu.getOutputTokens()).divide(MILLION, MC).multiply(decimal(price.get("output")), MC));
                llmUsd = llmUsd.add(cost);
            }
            modelUsageJson.add(row);
        }

        BigDecimal gpuUsd = BigDecimal.valueOf(usage.getGpuTimeMs())
                .divide(MS_PER_HOUR, MC).multiply(gpuUsdPerHour, MC);

        BigDecimal krw = llmUsd.add(gpuUsd)
                .multiply(usdKrw, MC)
                .multiply(BigDecimal.ONE.add(cardFeeRate), MC)
                .setScale(3, RoundingMode.HALF_UP);

        // 크레딧 배율 — 설정에 없는 유형은 UNSPECIFIED(0)로 취급하되 경고를 남긴다
        Object mult = multipliers.get(layoutType);
        if (mult == null && !layoutType.equals("PAGE_LAYOUT_UNSPECIFIED")) {
            log.warn("creditMultiplier에 없는 layout_type — 0 크레딧 처리: {}", layoutType);
        }
        int credit = mult == null ? 0 : decimal(mult).intValue();

        return new CostBreakdown(layoutType, usage.getGpuTimeMs(),
                modelUsageJson.isEmpty() ? null : modelUsageJson,
                llmUsd.setScale(9, RoundingMode.HALF_UP),
                gpuUsd.setScale(9, RoundingMode.HALF_UP),
                krw, uncertain, pc.getId(), credit);
    }

    // jsonb에서 온 숫자는 Integer/Long/Double/BigDecimal 어느 것이든 올 수 있다
    private static BigDecimal decimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        return new BigDecimal(String.valueOf(v));
    }
}
