package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 점역된 꼬리말({@code jobs.footer_braille})을 한 곳에서 다룬다.
 *
 * <p><b>V3.1부터 서버는 꼬리말을 점역하지 않는다.</b> proto에서 TranslateText가 삭제됐다 —
 * 점역은 앱이 엔진으로 직접 한다. 그래서 새 작업의 {@code footer_braille}은 늘 null이고,
 * 이 클래스는 <b>예전에 점역해 둔 값을 돌려주는 일</b>만 남았다(V31~V3.0 작업).
 *
 * <p>종전(2026-09-03, FE 요청 S-4·S-9)엔 업로드 때 한 번 점역해 담고 화면·SSE·다운로드가
 * 그 값을 읽었다. 점역 결과가 판면에 안 들어가면 업로드에서 막았는데(S-9), 점역을 못 하니
 * 새 작업에서는 그 검사도 할 수 없다 — 앱이 점역할 때 판단한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FooterBrailleService {

    /** 항목 사이 두 칸 이상 (지침 1장3-1) — 쪽 번호와 꼬리말 사이 간격 */
    private static final int GAP = 2;


    /**
     * 화면·SSE가 쓸 점역 꼬리말 — 저장된 값을 그대로 준다. 없으면 null.
     *
     * <p>종전엔 비어 있으면 이 자리에서 AI로 채웠지만, V3.1부터 서버가 점역하지 않으므로
     * 새 작업은 늘 null이다. 앱이 꼬리말 묵자({@code footerText})로 직접 점역한다.
     */
    public String resolve(Job job) {
        return job.getFooterBraille();
    }

    /**
     * 점역된 꼬리말이 페이지행에 들어가는지 검사한다 (S-9).
     *
     * <p>종전엔 묵자 200자만 봤는데, 점역하면 길이가 달라져 실제로 들어갈지는 알 수 없었다.
     * 라이브러리는 자리에 안 맞는 꼬리말을 <b>말없이 뒤에서 자르므로</b>(지침 1장3-4) 사용자가
     * 잘린 줄 모른 채 내려받게 된다. 그래서 업로드에서 막는다.
     *
     * <p>남는 자리는 면마다 조금씩 다르다(쪽 번호 자릿수·걸침 순번). 여기서는 <b>가장 빠듯한 경우</b>로
     * 잡는다 — 원본 쪽·점자 면 번호가 모두 이 문서에서 가장 긴 자릿수일 때.
     *
     * <p>V3.1에서 쓰는 곳은 하나다 — 예전에 점역해 둔 꼬리말이 있는 작업을 저장하면서 판면을
     * 좁힐 때(32칸→20칸 등) 그 값이 여전히 들어가는지 다시 본다. 새 작업은 점역 값이 없어 건너뛴다.
     */
    void validateFits(String braille, LayoutOptions options, int totalPages) {
        LayoutOptions opts = options == null ? LayoutOptions.legacy(false) : options.withDefaults();
        if ("none".equals(opts.pageNumberLine())) return;   // 페이지행 자체가 없으면 잘릴 일이 없다

        int available = opts.cellsPerLine()
                - (opts.showSourcePageNumber() ? numCells(opts.sourcePageStart() + totalPages - 1) + GAP : 0)
                - (opts.showBraillePageNumber() ? numCells(opts.braillePageStart() + totalPages - 1) + GAP : 0);

        if (braille.length() > available) {
            log.warn("꼬리말이 판면에 안 들어감: 점역 {}칸 > 남는 자리 {}칸 (한 줄 {}칸)",
                    braille.length(), available, opts.cellsPerLine());
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
    }

    /** 수표(⠼) 1칸 + 자릿수. 예: 102 → 4칸 */
    private int numCells(int page) {
        return 1 + String.valueOf(Math.max(page, 1)).length();
    }
}
