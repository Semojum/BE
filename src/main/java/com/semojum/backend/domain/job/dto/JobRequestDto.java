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

    /**
     * 작업 저장 — V3.1의 유일한 저장 경로. 본문은 <b>작업 최종 상태 전체</b>다.
     *
     * <p>FE 점역 엔진이 텍스트·점자를 모두 만들어 보내므로 저장 경로에서 AI를 부르지 않는다.
     * 블록 추가·삭제·순서는 서버가 판정한다 — 배열 순서가 곧 읽기 순서고, 배열에서 빠진
     * 기존 블록은 삭제, id가 null인 항목은 새 블록이다.
     */
    public record SaveJob(
            Boolean insertPageNumber,
            String footerText,
            LayoutOptions layoutOptions,
            java.util.Map<String, Object> brailleOptions,
            @NotNull List<SaveJobPage> pages
    ) {}

    /** 한 쪽의 최종 상태. 그 모드에 없는 패널은 null(TXT·새 문서엔 텍스트만) */
    public record SaveJobPage(
            int pageNo,
            List<SaveElement> text,
            List<SaveElement> braille,
            List<SaveMemo> memos
    ) {}

    /**
     * 줄 메모. {@code lineText}는 <b>그 줄의 원문</b>이지 메모 내용이 아니다 —
     * 줄이 밀렸을 때 다시 찾기 위한 단서다. {@code memoId}를 보내면 작성 시각이 보존된다.
     */
    public record SaveMemo(
            Long memoId,
            String panel,
            String elementId,
            int lineIndex,
            String lineText,
            @NotNull String body,
            java.time.LocalDateTime createdAt
    ) {}
}
