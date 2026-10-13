package com.semojum.backend.domain.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semojum.backend.domain.user.dto.BrailleFormDto;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * 기본 점역 양식 5종 — 클래스패스 리소스 {@code braille-forms/builtin.json}에서 읽는다.
 *
 * <p>DB가 아닌 이유: 세 환경이 RDS 하나를 공유해서 DB에 넣으면 배포를 되돌려도 양식만 새 버전으로
 * 남는다. 코드와 함께 배포해 커밋 단위로 동기화한다 — 점자 규정과 같은 판단이다.
 *
 * <p>값은 읽는 즉시 기본값을 채운다. 리소스에는 유형별 차이만 적고 나머지는 비워 두기 때문이다.
 */
@Slf4j
@Component
public class BuiltinBrailleForms {

    /** 기본 양식의 formId 접두사 — 사용자 양식(UUID)과 가르는 기준이다 */
    public static final String PREFIX = "builtin:";

    private static final String RESOURCE = "/braille-forms/builtin.json";

    private List<BrailleFormDto.Form> forms = List.of();

    // 리소스의 _comment(설명 주석)처럼 모르는 필드는 무시한다 — 없으면 파싱이 통째로 실패하고
    // 아래 catch가 그걸 삼켜 "기본 양식 0종"이 조용히 지나간다
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record Raw(List<Entry> forms) {}

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record Entry(String formId, String name,
                         com.semojum.backend.domain.job.dto.LayoutOptions layoutOptions,
                         com.semojum.backend.domain.job.dto.BrailleOptions brailleOptions) {}

    @PostConstruct
    public void load() {
        try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
            Raw raw = new ObjectMapper().readValue(in, Raw.class);
            forms = raw.forms().stream()
                    .map(e -> new BrailleFormDto.Form(
                            e.formId(), e.name(), true, null,
                            e.layoutOptions() == null
                                    ? com.semojum.backend.domain.job.dto.LayoutOptions.legacy(false)
                                    : e.layoutOptions().withDefaults(),
                            e.brailleOptions() == null
                                    ? com.semojum.backend.domain.job.dto.BrailleOptions.defaults()
                                    : e.brailleOptions().withDefaults()))
                    .toList();
            log.info("기본 점역 양식 {}종 로드", forms.size());
        } catch (Exception e) {
            // 양식이 없어도 사용자 양식은 쓸 수 있어야 한다 — 기동을 막지 않는다
            log.error("기본 점역 양식 로드 실패 — 기본 양식 없이 뜬다", e);
        }
    }

    public List<BrailleFormDto.Form> all() {
        return forms;
    }

    public static boolean isBuiltin(String formId) {
        return formId != null && formId.startsWith(PREFIX);
    }
}
