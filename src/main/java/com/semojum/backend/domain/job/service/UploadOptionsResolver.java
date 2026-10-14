package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.job.dto.BrailleOptions;
import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.user.entity.BrailleForm;
import com.semojum.backend.domain.user.repository.BrailleFormRepository;
import com.semojum.backend.domain.user.service.BuiltinBrailleForms;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 업로드 시점에 그 작업에 적용할 조판·점역 옵션을 정한다.
 *
 * <p>D2 「열기」 창이 양식과 개별 항목을 함께 보여주므로 순서가 있다.
 *
 * <pre>
 *   ① 양식을 골랐으면 그 양식의 값이 바탕      (조판·점역 둘 다)
 *   ② 안 골랐으면 계정 기본값이 바탕            (점역만 — 조판은 계정 기본값 개념이 없다)
 *   ③ 개별 항목을 보냈으면 그 위에 덮는다
 * </pre>
 *
 * <p>⚠️ 바탕은 <b>공장 기본값</b>이지 구 {@code insert_page_number} 환산값이 아니다. 한때 그걸 바탕으로
 * 써서(2026-10-13) 앱이 {@code pageNumberLine}을 안 보내면 페이지행이 {@code none}으로 빠졌다.
 *
 * <p><b>결과는 작업에 사본으로 저장된다.</b> 양식이나 계정 기본값을 나중에 고쳐도, 양식을 지워도
 * 이미 만든 작업의 설정은 그대로여야 한다 — 작업은 그때의 설정으로 점역된 결과물이기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class UploadOptionsResolver {

    private final BrailleFormRepository brailleFormRepository;
    private final BuiltinBrailleForms builtinForms;

    public record Resolved(LayoutOptions layoutOptions, BrailleOptions brailleOptions) {}

    public Resolved resolve(User user, String formId,
                            LayoutOptions requestedLayout, BrailleOptions requestedBraille) {

        LayoutOptions baseLayout = LayoutOptions.defaults();
        BrailleOptions baseBraille = user.resolveBrailleOptions();

        if (formId != null && !formId.isBlank()) {
            if (BuiltinBrailleForms.isBuiltin(formId)) {
                var form = builtinForms.all().stream()
                        .filter(f -> f.formId().equals(formId)).findFirst()
                        .orElseThrow(() -> new CustomException(ErrorCode.COMMON_BAD_REQUEST));
                baseLayout = form.layoutOptions();
                baseBraille = form.brailleOptions();
            } else {
                BrailleForm form = brailleFormRepository
                        .findByIdAndUserId(parseFormId(formId), user.getId())
                        .orElseThrow(() -> new CustomException(ErrorCode.COMMON_BAD_REQUEST));
                baseLayout = form.getLayoutOptions();
                baseBraille = form.getBrailleOptions();
            }
        }

        return new Resolved(
                baseLayout.merge(requestedLayout),
                baseBraille.merge(requestedBraille).validated());
    }

    /** 없는 양식과 모양이 틀린 formId는 둘 다 COMMON4000 — 업로드 파라미터가 잘못된 것이다 */
    private UUID parseFormId(String formId) {
        try {
            return UUID.fromString(formId);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
    }
}
