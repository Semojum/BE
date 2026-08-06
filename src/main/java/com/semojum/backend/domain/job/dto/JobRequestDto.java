package com.semojum.backend.domain.job.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;

public class JobRequestDto {

    public record Create(
            @NotBlank String mode
    ) {}

    // 페이지 일괄 저장 — 페이지 최종 상태 전체를 순서대로. 편집 대상은 mode가 정한다(a=text, b·c=braille)
    public record SavePage(
            @NotNull List<@Valid SaveElement> elements
    ) {}

    // id=null이면 사용자 작성 새 블록(서버가 id 발급). type은 블록 종류(기본 "text"), 기존 요소는 무시.
    public record SaveElement(
            String id,
            String type,
            @NotNull List<String> contents
    ) {}

    // ===== V3 마이페이지 작업 관리 =====
    // 파일 이름은 하나만 사용 (팀 결정) — 이름 변경은 originalFileName 자체를 바꾼다
    public record Rename(
            @NotBlank String fileName
    ) {}

    // targetFolderId: null = 루트(전체)로 이동
    public record BulkMove(
            @NotNull List<String> jobIds,
            java.util.UUID targetFolderId
    ) {}

    public record BulkTrash(
            @NotNull List<String> jobIds
    ) {}
}