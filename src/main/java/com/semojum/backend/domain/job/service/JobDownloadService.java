package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.result.entity.BrailleElement;
import com.semojum.backend.domain.result.entity.PageResult;
import com.semojum.backend.domain.result.entity.TextElement;
import com.semojum.backend.domain.result.repository.BrailleElementRepository;
import com.semojum.backend.domain.result.repository.PageResultRepository;
import com.semojum.backend.domain.result.repository.TextElementRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.grpc.BrailleGrpcClient;
import com.semojum.brailleassist.BrailleAssist;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 결과 다운로드 파일 생성.
 *
 * <p>mode a → .txt: text_elements의 편집 최종본(current)을 페이지·읽기 순서대로 병합 (BE 자체 구현).
 * mode b·c → .brf: braille_elements를 braille-assist(공용 조판 라이브러리)의 조립 JSON으로 넘겨
 * 32칸 줄바꿈·변경선·26줄 면 나눔·페이지행·BRF-ASCII 변환까지 위임. 조판 규칙은 이 서비스에 없다 —
 * 규칙은 전부 braille-assist 레포가 소유한다(3개 언어 동일 출력, vectors.json 검증).
 *
 * <p>항상 DB의 현재 편집 상태로 즉시 생성한다. 조판이 AI 호출 없는 로컬 연산이라
 * "수정 있으면 재처리" 분기가 필요 없다(꼬리말 점역만 예외 — Job 생성 시 받은 묵자를 이때 점역).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobDownloadService {

    private final JobRepository jobRepository;
    private final PageResultRepository pageResultRepository;
    private final TextElementRepository textElementRepository;
    private final BrailleElementRepository brailleElementRepository;
    private final BrailleGrpcClient grpcClient;

    /** 생성된 파일 — 내용과 파일명(확장자 포함) */
    public record DownloadFile(byte[] content, String fileName, String contentType) {}

    @Transactional(readOnly = true)
    public DownloadFile download(String userId, String jobId, String requestedName) {
        Job job = jobRepository.findByIdAndUserId(jobId, UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        if (job.isInProgress()) {
            throw new CustomException(ErrorCode.JOB_IN_PROGRESS);
        }
        if (!"COMPLETED".equals(job.getStatus())) {
            // FAILED(전 페이지 변환 실패) — 담을 결과물이 없다
            throw new CustomException(ErrorCode.JOB_NO_RESULT);
        }

        List<PageResult> pageResults = pageResultRepository.findByJobIdOrderByPageNumber(jobId);
        if (pageResults.isEmpty()) {
            throw new CustomException(ErrorCode.JOB_NO_RESULT);
        }

        boolean isTxt = "a".equals(job.getMode());
        String body = isTxt ? buildTxt(pageResults) : buildBrf(job, pageResults);
        String fileName = resolveFileName(requestedName, job.getOriginalFileName(), isTxt ? "txt" : "brf");
        // brf는 BRF-ASCII(순수 ASCII)지만 미해석 셀 마커(⟨XXXX⟩)가 섞일 수 있어 UTF-8로 기록
        return new DownloadFile(body.getBytes(StandardCharsets.UTF_8), fileName,
                "text/plain; charset=UTF-8");
    }

    /**
     * mode a — 편집 최종본 텍스트 병합.
     * 요소 사이 줄바꿈, 원본 페이지 사이 빈 줄 1개. `<!점역자주>` 마커는 그대로 둔다
     * (mode a 결과는 "점역으로 보내기"로 mode b 입력이 되며 마커가 점역자주 처리 신호).
     */
    private String buildTxt(List<PageResult> pageResults) {
        List<String> pages = new ArrayList<>();
        for (PageResult pr : pageResults) {
            List<String> parts = new ArrayList<>();
            for (TextElement el : textElementRepository.findByPageResult(pr)) {
                List<String> contents = el.getCurrentContents();
                parts.add(contents == null || contents.isEmpty() ? "" : String.join("\n", contents));
            }
            pages.add(String.join("\n", parts));
        }
        return String.join("\n\n", pages);
    }

    /** mode b·c — braille-assist 조립 JSON을 만들어 조판·BRF 변환 전체를 위임 */
    private String buildBrf(Job job, List<PageResult> pageResults) {
        List<BrailleAssist.JobPage> pages = new ArrayList<>();
        for (PageResult pr : pageResults) {
            List<BrailleAssist.JobElement> elements = new ArrayList<>();
            for (BrailleElement el : brailleElementRepository.findByPageResult(pr)) {
                List<String> contents = el.getCurrentContent();
                String text = contents == null || contents.isEmpty() ? "" : String.join("\n", contents);
                elements.add(new BrailleAssist.JobElement(
                        el.getElementId(), el.getType(),
                        el.getHeadingLevel() == null ? 0 : el.getHeadingLevel(), text));
            }
            pages.add(new BrailleAssist.JobPage(pr.getPageNumber(), elements));
        }

        // 꼬리말: Job 생성 시 받은 묵자를 이 시점에 점역 (braille-assist는 점역하지 않는다)
        String footerBraille = "";
        if (job.getFooterText() != null) {
            footerBraille = grpcClient.translateText(job.getFooterText());
        }

        BrailleAssist.Job assistJob = new BrailleAssist.Job(
                job.getId(), job.isInsertPageNumber(), 26, 32, footerBraille, 1, pages);
        return BrailleAssist.buildBrf(assistJob);
    }

    /** 파일명: 요청값 우선, 없으면 원본 파일명 — 확장자는 모드가 결정. 경로 문자는 제거 */
    private String resolveFileName(String requested, String originalFileName, String ext) {
        String base = requested != null && !requested.isBlank() ? requested.trim() : originalFileName;
        if (base == null || base.isBlank()) base = "download";
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        base = base.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
        return base + "." + ext;
    }
}
