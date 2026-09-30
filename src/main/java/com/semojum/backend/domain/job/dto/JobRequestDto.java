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

    // 페이지 일괄 저장 — 페이지 최종 상태 전체를 순서대로.
    // target: 편집 패널 "text" | "braille" (선택). 생략하면 mode 기본값(a=text, b·c=braille).
    // mode c(3패널)만 둘 다 받는다 — "text"로 저장하면 같은 id 점자가 재점역돼 따라간다
    public record SavePage(
            @NotNull List<@Valid SaveElement> elements,
            String target
    ) {}

    // id=null이면 사용자 작성 새 블록(서버가 id 발급, type은 항상 "text" — 사용자가 만들 수 있는 블록은 텍스트뿐)
    public record SaveElement(
            String id,
            @NotNull List<String> contents
    ) {}

    // 대체 초안 선택: 0-based 초안 번호. -1 = 선택 해제(AI 원본 복귀)
    public record SelectDraft(
            @NotNull Integer selectedIdx
    ) {}

    // 다운로드: 파일명 지정(선택). format: "txt" | "brf" (선택) — 생략하면 mode 기본값(a=txt, b·c=brf).
    // mode c(3패널)만 둘 다 받는다(텍스트 결과 .txt / 점자 결과 .brf)
    public record Download(
            String fileName,
            String format
    ) {}

    /**
     * 업로드 후 조판 설정 변경 (V32) — 에디터 "이 파일의 조판 설정" 모달.
     *
     * <p><b>전부 선택이고, 보낸 항목만 바뀐다.</b> null·미전송은 "그대로 두라"는 뜻이다
     * ({@code layoutOptions} 안의 12개 항목도 같은 규칙 — {@link LayoutOptions#merge}).
     *
     * <p>꼬리말만 예외적으로 지울 수 있다 — {@code footerText: ""}를 보내면 삭제된다.
     * null(미전송)과 구분해야 해서 빈 문자열에 그 뜻을 준다.
     */
    public record UpdateOptions(
            Boolean insertPageNumber,
            String footerText,
            LayoutOptions layoutOptions
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