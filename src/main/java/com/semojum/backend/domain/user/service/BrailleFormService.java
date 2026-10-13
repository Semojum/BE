package com.semojum.backend.domain.user.service;

import com.semojum.backend.domain.job.dto.BrailleOptions;
import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.user.dto.BrailleFormDto;
import com.semojum.backend.domain.user.entity.BrailleForm;
import com.semojum.backend.domain.user.repository.BrailleFormRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 점역 양식 CRUD — D7 「점역 양식」 · D7-2 「사용자 양식 설정」.
 *
 * <p>양식은 <b>계정별</b>이다. 같은 기관이라도 공유하지 않는다(기관 공유 저장소는 이번 개편 제외).
 *
 * <p>목록은 기본 양식 5종과 사용자 양식을 <b>한 목록에 섞어</b> 준다 — 화면이 드롭다운 하나이기 때문이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrailleFormService {

    private final BrailleFormRepository brailleFormRepository;
    private final BuiltinBrailleForms builtinForms;

    /** 계정당 양식 개수 상한 — 드롭다운에서 고르는 목록이라 수백 개가 되면 쓸 수 없다 */
    static final int MAX_FORMS = 50;

    @Transactional(readOnly = true)
    public BrailleFormDto.FormList list(String userId) {
        List<BrailleFormDto.Form> items = new ArrayList<>(builtinForms.all());
        brailleFormRepository.findByUserIdOrderByCreatedAtAsc(UUID.fromString(userId))
                .forEach(f -> items.add(toDto(f)));
        return new BrailleFormDto.FormList(items);
    }

    @Transactional
    public BrailleFormDto.Form create(String userId, BrailleFormDto.SaveForm request) {
        UUID uid = UUID.fromString(userId);
        String name = validateName(request.name());

        if (brailleFormRepository.existsByUserIdAndName(uid, name)) {
            throw new CustomException(ErrorCode.COMMON_NAME_DUPLICATE);
        }
        if (brailleFormRepository.findByUserIdOrderByCreatedAtAsc(uid).size() >= MAX_FORMS) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }

        BrailleForm form = BrailleForm.builder()
                .userId(uid)
                .name(name)
                .baseFormId(request.baseFormId())
                .layoutOptions(resolveLayout(request.layoutOptions()))
                .brailleOptions(resolveBraille(request.brailleOptions()))
                .build();
        brailleFormRepository.save(form);
        log.info("점역 양식 저장: formId={}, name={}", form.getId(), name);
        return toDto(form);
    }

    /** D7-2 「저장」 — 통째로 덮어쓴다. 기본 양식은 되돌릴 길이 없어 거절한다 */
    @Transactional
    public BrailleFormDto.Form update(String userId, String formId, BrailleFormDto.SaveForm request) {
        UUID uid = UUID.fromString(userId);
        BrailleForm form = findOwned(uid, formId);
        String name = validateName(request.name());

        if (brailleFormRepository.existsByUserIdAndNameAndIdNot(uid, name, form.getId())) {
            throw new CustomException(ErrorCode.COMMON_NAME_DUPLICATE);
        }

        form.update(name, request.baseFormId(),
                resolveLayout(request.layoutOptions()), resolveBraille(request.brailleOptions()));
        return toDto(form);
    }

    @Transactional
    public void delete(String userId, String formId) {
        BrailleForm form = findOwned(UUID.fromString(userId), formId);
        brailleFormRepository.delete(form);
        log.info("점역 양식 삭제: formId={}", formId);
    }

    /**
     * 내 사용자 양식만 찾는다.
     *
     * <p>기본 양식은 COMMON4003 — 없는 것이 아니라 손댈 수 없는 것이다.
     * 남의 양식과 없는 양식은 둘 다 404로 묶는다(소유자까지 걸어 조회하므로 구분되지 않는다).
     */
    private BrailleForm findOwned(UUID userId, String formId) {
        if (BuiltinBrailleForms.isBuiltin(formId)) {
            throw new CustomException(ErrorCode.COMMON_FORBIDDEN);
        }
        UUID id;
        try {
            id = UUID.fromString(formId);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.COMMON_NOT_FOUND);
        }
        return brailleFormRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_NOT_FOUND));
    }

    private String validateName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        return trimmed;
    }

    // 빠진 항목은 기본값으로 채워 완전한 형태로 저장한다 — 읽는 쪽에 null 분기를 안 남긴다
    private LayoutOptions resolveLayout(LayoutOptions o) {
        return o == null ? LayoutOptions.legacy(false) : o.withDefaults();
    }

    private BrailleOptions resolveBraille(BrailleOptions o) {
        return o == null ? BrailleOptions.defaults() : o.validated();
    }

    private BrailleFormDto.Form toDto(BrailleForm f) {
        return new BrailleFormDto.Form(
                f.getId().toString(), f.getName(), false, f.getBaseFormId(),
                f.getLayoutOptions(), f.getBrailleOptions());
    }
}
