package com.semojum.backend.domain.job.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public class JobRequestDto {

    public record Create(
            @NotBlank String mode
    ) {}

    // 점역사 요소 수정 요청 (elementType으로 TEXT/BRAILLE 테이블 구분)
    public record EditElement(
            @NotBlank String elementType,
            @NotNull List<String> contents
    ) {}
}