package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.grpc.BrailleGrpcClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 꼬리말 점역을 한 곳에서 맡는다 (FE 요청 S-4·S-9, 2026-09-03).
 *
 * <p>종전엔 다운로드하는 순간에만 점역해서 ① 에디터 화면이 점역된 꼬리말을 볼 방법이 없었고
 * (페이지행의 꼬리말 자리가 늘 빈칸) ② 내려받을 때마다 AI를 다시 불렀다. 이제 업로드 때 한 번
 * 점역해 {@code jobs.footer_braille}에 담고, 화면·SSE·다운로드가 모두 그 값을 읽는다.
 *
 * <p>{@code footer_text}는 업로드 후 수정 경로가 없어(2026-09-03 확인) 캐시 무효화를 걱정하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FooterBrailleService {

    /** 항목 사이 두 칸 이상 (지침 1장3-1) — 쪽 번호와 꼬리말 사이 간격 */
    private static final int GAP = 2;

    private final BrailleGrpcClient grpcClient;
    private final JobRepository jobRepository;

    /**
     * 업로드 시점 점역. <b>AI 호출이 실패해도 예외를 던지지 않는다</b> — 썸네일과 같은 취급으로,
     * 꼬리말 하나 때문에 업로드 전체가 실패하면 안 된다. 실패하면 null을 담고 조회 때 다시 시도한다.
     *
     * @throws CustomException 점역 결과가 판면에 들어갈 수 없을 만큼 길면 COMMON4000 (S-9)
     */
    public String translateForUpload(String footerText, LayoutOptions options, int totalPages) {
        if (footerText == null || footerText.isBlank()) return null;

        String braille;
        try {
            braille = grpcClient.translateText(footerText);
        } catch (Exception e) {
            log.warn("꼬리말 점역 실패(업로드는 계속): text={}, error={}", footerText, e.getMessage());
            return null;
        }
        validateFits(braille, options, totalPages);
        return braille;
    }

    /**
     * 조회·조판이 쓸 점역 꼬리말. 이미 있으면 그대로, 없는데 꼬리말은 있으면(업로드 때 실패했거나
     * V31 이전에 만든 작업) 이 자리에서 채운다. 여기서도 실패는 삼킨다 — 꼬리말 때문에 화면이나
     * 다운로드가 죽으면 안 된다.
     *
     * <p>새 트랜잭션으로 저장한다 — 호출부가 읽기 전용 트랜잭션(페이지 조회)일 수 있다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String resolve(Job job) {
        if (job.getFooterBraille() != null) return job.getFooterBraille();
        if (job.getFooterText() == null || job.getFooterText().isBlank()) return null;

        try {
            String braille = grpcClient.translateText(job.getFooterText());
            jobRepository.findById(job.getId()).ifPresent(fresh -> fresh.updateFooterBraille(braille));
            log.info("꼬리말 점역 보충: jobId={}", job.getId());
            return braille;
        } catch (Exception e) {
            log.warn("꼬리말 점역 실패(계속): jobId={}, error={}", job.getId(), e.getMessage());
            return null;
        }
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
     */
    private void validateFits(String braille, LayoutOptions options, int totalPages) {
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
