package com.semojum.backend.domain.user.dto;

import com.semojum.backend.domain.job.dto.BrailleOptions;
import com.semojum.backend.domain.job.dto.LayoutOptions;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** 점역 양식 (D7 「점역 양식」 · D7-2 「사용자 양식 설정」) */
public class BrailleFormDto {

    /**
     * 양식 하나.
     *
     * <p>{@code formId}는 기본 양식이면 {@code builtin:} 접두사, 사용자 양식이면 UUID다.
     * {@code builtin}이 참이면 수정·삭제가 거절된다 — 되돌릴 길이 없기 때문이다.
     *
     * <p>목록 응답이 <b>값 전체를 담는 이유</b>: D7의 「이 양식이 정하는 값(읽기 전용)」과
     * D7-2의 「바탕 값으로」가 추가 호출 없이 동작해야 한다. 양식 수가 열 개 안팎이라 작다.
     */
    public record Form(
            String formId,
            String name,
            boolean builtin,
            String baseFormId,
            LayoutOptions layoutOptions,
            BrailleOptions brailleOptions
    ) {}

    public record FormList(List<Form> items) {}

    /**
     * 저장·수정 요청 (D7 「새 양식 저장」 · D7-2 「저장」).
     *
     * <p>수정도 통째로 덮어쓴다 — 창이 항목 전부를 보여주므로 빠진 항목은 「그대로」가 아니라
     * 기본값이다. 안 보낸 조판 옵션 항목도 기본값으로 채워진다.
     */
    public record SaveForm(
            @NotBlank String name,
            String baseFormId,
            LayoutOptions layoutOptions,
            BrailleOptions brailleOptions
    ) {}
}
