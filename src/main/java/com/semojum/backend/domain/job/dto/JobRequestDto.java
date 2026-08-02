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

    // 블록 순서변경 요청: 그 페이지의 최종 element_id 순서 전체를 받아 reading_order를 1..N으로 재작성
    public record ReorderElements(
            @NotBlank String elementType,
            @NotNull List<String> orderedElementIds
    ) {}

    // 블록 추가 요청: afterElementId 뒤에 삽입(null이면 맨 앞). type은 블록 종류(기본 "text").
    public record AddElement(
            @NotBlank String elementType,
            @NotNull List<String> contents,
            String afterElementId,
            String type
    ) {}

    // ===== V3 마이페이지 작업 관리 =====
    public record Rename(
            @NotBlank String workName
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