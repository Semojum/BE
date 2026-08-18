package com.semojum.backend.domain.billing.service;

import com.semojum.backend.domain.billing.dto.PricingDto;
import com.semojum.backend.domain.billing.entity.PricingConfig;
import com.semojum.backend.domain.billing.repository.PricingConfigRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 단가·배율 관리 변수 운영자 API 로직.
 * 수정 = 새 판(행) 등록 — 과거 판은 불변으로 남아 page_results.pricing_config_id가 가리키는 근거가 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PricingAdminService {

    private static final List<String> REQUIRED_KEYS =
            List.of("modelPrices", "gpuUsdPerHour", "usdKrw", "cardFeeRate", "creditMultiplier",
                    "creditPriceKrw");   // 크레딧 판매 단가(원) — 수익성 환산 매출의 축
    private static final List<String> LAYOUT_TYPES = List.of(
            "PAGE_LAYOUT_UNSPECIFIED", "PAGE_LAYOUT_TEXT", "PAGE_LAYOUT_FORMULA",
            "PAGE_LAYOUT_TABLE", "PAGE_LAYOUT_VISUAL");

    private final PricingConfigRepository pricingConfigRepository;

    @Transactional(readOnly = true)
    public PricingDto.Response getCurrent() {
        PricingConfig pc = pricingConfigRepository.findTopByOrderByIdDesc()
                .orElseThrow(() -> new IllegalStateException("pricing_configs가 비어 있음 — V16 시드 확인"));
        return toResponse(pc);
    }

    @Transactional
    public PricingDto.Response update(PricingDto.Update request) {
        validate(request.config());
        PricingConfig saved = pricingConfigRepository.save(PricingConfig.builder()
                .config(request.config())
                .note(request.note())
                .build());
        log.info("단가표 새 판 등록: id={}, note={}", saved.getId(), request.note());
        return toResponse(pricingConfigRepository.findById(saved.getId()).orElse(saved));
    }

    @SuppressWarnings("unchecked")
    private void validate(Map<String, Object> config) {
        for (String key : REQUIRED_KEYS) {
            if (!config.containsKey(key)) {
                throw badRequest("필수 키 누락: " + key);
            }
        }
        requireNonNegativeNumber(config.get("gpuUsdPerHour"), "gpuUsdPerHour");
        requireNonNegativeNumber(config.get("usdKrw"), "usdKrw");
        requireNonNegativeNumber(config.get("cardFeeRate"), "cardFeeRate");
        requireNonNegativeNumber(config.get("creditPriceKrw"), "creditPriceKrw");

        Object mp = config.get("modelPrices");
        if (!(mp instanceof Map) || ((Map<String, Object>) mp).isEmpty()) {
            throw badRequest("modelPrices는 비어 있지 않은 객체여야 함");
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) mp).entrySet()) {
            if (!(e.getValue() instanceof Map)) {
                throw badRequest("modelPrices." + e.getKey() + "는 {input, output} 객체여야 함");
            }
            Map<String, Object> price = (Map<String, Object>) e.getValue();
            requireNonNegativeNumber(price.get("input"), "modelPrices." + e.getKey() + ".input");
            requireNonNegativeNumber(price.get("output"), "modelPrices." + e.getKey() + ".output");
        }

        Object cm = config.get("creditMultiplier");
        if (!(cm instanceof Map)) {
            throw badRequest("creditMultiplier는 객체여야 함");
        }
        // 5개 유형이 전부 있어야 함 — 빠진 유형은 런타임에 0으로 흘러 검산이 어긋난다
        for (String layout : LAYOUT_TYPES) {
            requireNonNegativeNumber(((Map<String, Object>) cm).get(layout), "creditMultiplier." + layout);
        }
    }

    private void requireNonNegativeNumber(Object v, String name) {
        if (v == null) {
            throw badRequest("필수 값 누락: " + name);
        }
        double d;
        try {
            d = Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw badRequest(name + "은(는) 숫자여야 함");
        }
        if (d < 0) {
            throw badRequest(name + "은(는) 0 이상이어야 함");
        }
    }

    // CustomException은 상세 메시지 오버로드가 없다 — 사유는 WARN 로그로 남기고 COMMON4000으로 응답
    private CustomException badRequest(String reason) {
        log.warn("단가표 검증 실패: {}", reason);
        return new CustomException(ErrorCode.COMMON_BAD_REQUEST);
    }

    private PricingDto.Response toResponse(PricingConfig pc) {
        return new PricingDto.Response(pc.getId(), pc.getConfig(), pc.getNote(), pc.getCreatedAt());
    }
}
