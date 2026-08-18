package com.semojum.backend.domain.billing.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

public class PricingDto {

    // 새 판 등록 요청 — config 전문을 통째로 받는다 (부분 수정 없음: 판 단위 이력이 목적)
    public record Update(
            @NotNull Map<String, Object> config,
            @Size(max = 500) String note
    ) {}

    public record Response(
            Long id,
            Map<String, Object> config,
            String note,
            Instant createdAt
    ) {}
}
