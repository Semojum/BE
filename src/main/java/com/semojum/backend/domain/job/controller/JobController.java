package com.semojum.backend.domain.job.controller;

import com.semojum.backend.domain.job.dto.JobRequestDto;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.service.JobService;
import com.semojum.backend.domain.job.service.SseService;
import com.semojum.backend.global.exception.ApiResponse;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;
    private final com.semojum.backend.global.util.ClientInfoResolver clientInfoResolver;
    private final SseService sseService;
    private final JobRepository jobRepository;
    private final com.semojum.backend.domain.job.service.JobCancelService jobCancelService;
    private final com.semojum.backend.domain.job.service.JobSaveService jobSaveService;

    /**
     * 파일 업로드로 변환 작업 생성.
     *
     * <p><b>{@code file} 파트를 여러 개 보낼 수 있는 건 이미지뿐이다</b> — 한 장이 한 쪽이 되고
     * 쪽 순서는 보낸 순서다(피그마 C11). PDF·HWP·HWPX·TXT는 하나만 받고, 섞어 보내면 COMMON4000.
     * 파트 이름은 그대로 {@code file}이라 한 장만 보내는 기존 호출은 바뀌지 않는다.
     */
    @PostMapping(consumes = "multipart/form-data")
    public ApiResponse<JobResponseDto.Create> createJob(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestPart("file") List<MultipartFile> files,
            // 선택 — 서버가 파일 종류로 정한다(PDF·HWP·HWPX·이미지=c, TXT=b). 구 클라이언트 호환으로 받기만 한다
            @RequestParam(value = "mode", required = false) String mode,
            // 점자 판면 마지막 줄에 쪽번호를 넣을지 — 업로드 시 선택 (미전송 시 false)
            @RequestParam(value = "insertPageNumber", defaultValue = "false") boolean insertPageNumber,
            // 꼬리말(묵자, 최대 200자) — 다운로드(brf) 때 점역해 페이지행 가운데 배치. 미전송 시 없음
            @RequestParam(value = "footerText", required = false) String footerText,
            // 조판 옵션 (V30) — 폼 필드로 하나씩 받는다. 안 보낸 항목은 기본값(32칸×26줄·홀수 면 …)
            @RequestParam(value = "cellsPerLine", required = false) Integer cellsPerLine,
            @RequestParam(value = "linesPerPage", required = false) Integer linesPerPage,
            @RequestParam(value = "pageNumberLine", required = false) String pageNumberLine,
            @RequestParam(value = "coverPages", required = false) Integer coverPages,
            @RequestParam(value = "sourcePageStart", required = false) Integer sourcePageStart,
            @RequestParam(value = "braillePageStart", required = false) Integer braillePageStart,
            @RequestParam(value = "showSourcePageNumber", required = false) Boolean showSourcePageNumber,
            @RequestParam(value = "showBraillePageNumber", required = false) Boolean showBraillePageNumber,
            @RequestParam(value = "showChangeLine", required = false) Boolean showChangeLine,
            @RequestParam(value = "footerAlign", required = false) String footerAlign,
            @RequestParam(value = "editScope", required = false) String editScope,
            @RequestParam(value = "advancedAi", required = false) Boolean advancedAi,
            // 점역 양식 (V3.1) — 고르면 그 양식의 조판·점역 값이 바탕이 되고, 아래 개별 항목이 그 위에 덮인다.
            // 안 고르면 계정 기본 점역 옵션이 바탕이다
            @RequestParam(value = "formId", required = false) String formId,
            // 점역 옵션 (V3.1) — D2 「열기」 창에서 이 작업만 따로 바꾼 항목. 안 보낸 항목은 바탕 값 그대로
            @RequestParam(value = "hangulGrade", required = false) String hangulGrade,
            @RequestParam(value = "englishGrade", required = false) String englishGrade,
            @RequestParam(value = "brailleRule", required = false) String brailleRule,
            @RequestParam(value = "mixedRule", required = false) String mixedRule,
            @RequestParam(value = "consecutiveSpace", required = false) String consecutiveSpace,
            @RequestParam(value = "lineBreakUnit", required = false) String lineBreakUnit,
            @RequestParam(value = "choiceLayout", required = false) String choiceLayout,
            jakarta.servlet.http.HttpServletRequest request
    ) throws Exception {
        LayoutOptions options = new LayoutOptions(cellsPerLine, linesPerPage, pageNumberLine, coverPages,
                sourcePageStart, braillePageStart, showSourcePageNumber, showBraillePageNumber,
                showChangeLine, footerAlign, editScope, advancedAi);
        com.semojum.backend.domain.job.dto.BrailleOptions brailleOptions =
                // 시각 자료 설명 유형은 폼 필드로 받지 않는다 — 구조가 있고 업로드 창(D2)에 없는 항목이라
                // 양식이나 계정 기본값에서 온다
                new com.semojum.backend.domain.job.dto.BrailleOptions(hangulGrade, englishGrade, brailleRule,
                        mixedRule, consecutiveSpace, lineBreakUnit, choiceLayout, null);
        return ApiResponse.success(
                jobService.createJob(userDetails.getUsername(), files, mode, insertPageNumber, footerText,
                        options, formId, brailleOptions, clientInfoResolver.resolve(request)));
    }

    // 업로드 설정 조회 — 조판 옵션 + 꼬리말. 에디터가 작업을 열 때 설정 복원용 (V30)
    /**
     * 새 문서 — 파일 없이 본문 텍스트로 작업을 만든다(파일 › 새 문서, Ctrl+N).
     *
     * <p>쪽 분리는 TXT 업로드와 같다. 원본 패널이 없는 2패널 작업이 된다.
     */
    @PostMapping("/text")
    public ApiResponse<JobResponseDto.Create> createFromText(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody JobRequestDto.NewDocument request,
            jakarta.servlet.http.HttpServletRequest httpRequest
    ) {
        return ApiResponse.success(jobService.createNewDocument(
                userDetails.getUsername(), request, clientInfoResolver.resolve(httpRequest)));
    }

    @GetMapping("/{jobId}/options")
    public ApiResponse<JobResponseDto.Options> getJobOptions(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(jobService.getJobOptions(userDetails.getUsername(), jobId));
    }

    // job 상태 확인 API
    /**
     * 작업 저장 — V3.1의 유일한 저장 경로.
     *
     * <p>FE가 「저장」을 누를 때 한 번만 부른다(자동 저장 없음). 본문은 작업 최종 상태 전체 —
     * 조판 설정 · 점역 옵션 · 세 패널 · 줄 메모가 함께 들어온다.
     *
     * <p>쪽 단위 트랜잭션이라 일부만 실패할 수 있다. {@code failedPages}가 비어 있으면 전부 성공이다.
     * 한글 JSON은 7~8배 압축되므로 {@code Content-Encoding: gzip}을 권한다.
     */
    @PostMapping("/{jobId}/save")
    public ApiResponse<JobResponseDto.SaveResult> saveJob(
            @PathVariable String jobId,
            @Valid @RequestBody JobRequestDto.SaveJob request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(jobSaveService.save(userDetails.getUsername(), jobId, request));
    }

    @GetMapping("/{jobId}/status")
    public ApiResponse<JobResponseDto.Status> getJobStatus(
            @PathVariable String jobId
    ) {
        return ApiResponse.success(jobService.getJobStatus(jobId));
    }

    // 변환 취소 — 완료된 페이지까지만 남기고 중단. AI에 이미 들어간 페이지는 마무리 후 저장(취소는 수렴).
    // 이미 끝난 작업이면 멱등(canceled=false, 현재 상태 반환). 확정 여부는 status로 구분(IN_PROGRESS=인플라이트 마무리 중).
    @PostMapping("/{jobId}/cancel")
    public ApiResponse<Map<String, Object>> cancelJob(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(jobCancelService.cancel(userDetails.getUsername(), jobId));
    }

    @GetMapping(value = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamJobEvents(
            @PathVariable String jobId,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        UUID userId = UUID.fromString(userDetails.getUsername());
        jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_FORBIDDEN));
        return sseService.connect(jobId);
    }

}