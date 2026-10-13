package com.semojum.backend.domain.user.controller;

import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.user.dto.BrailleFormDto;
import com.semojum.backend.domain.user.service.UserService;
import jakarta.validation.Valid;
import com.semojum.backend.global.exception.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 마이페이지.
 *
 * <p><b>V3.1부터 작업 목록을 주지 않는다</b> — 작업물은 로컬 드라이브에 {@code .smj}로 보관하고,
 * 마이페이지는 사용량·과금·계정·문의만 보여준다. 전체보기·검색({@code /jobs})과
 * 최근 작업({@code /jobs/recent})은 폴더·휴지통과 함께 폐기됐다.
 *
 * <p>여기 남은 둘은 목록이 아니다 — 하나는 앱 복구용, 하나는 에디터가 쪽을 읽는 경로다.
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final com.semojum.backend.domain.user.service.BrailleOptionsService brailleOptionsService;
    private final com.semojum.backend.domain.user.service.BrailleFormService brailleFormService;

    /**
     * 내 기본 점역 옵션 조회 — 설정 › 기본 점역 옵션 설정(D6, Alt+G)이 창을 열 때.
     *
     * <p>저장한 적 없으면 공장 기본값이 내려온다. 작업이 아니라 계정에 딸린 값이다.
     */
    @GetMapping("/braille-options")
    public ApiResponse<com.semojum.backend.domain.job.dto.BrailleOptions> getBrailleOptions(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(brailleOptionsService.get(userDetails.getUsername()));
    }

    /**
     * 점역 양식 목록 — 기본 양식 5종 + 내가 만든 양식을 한 목록에.
     *
     * <p>D6의 「점역 양식」 드롭다운 · D7 「양식 고르기」 · D7-2의 「바탕 양식」이 모두 이 하나를 쓴다.
     * <b>값 전체를 담아 보낸다</b> — D7의 읽기 전용 요약과 D7-2의 「바탕 값으로」가 추가 호출 없이
     * 동작해야 한다. 양식 수가 열 개 안팎이라 응답이 작다.
     */
    @GetMapping("/braille-forms")
    public ApiResponse<BrailleFormDto.FormList> listBrailleForms(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(brailleFormService.list(userDetails.getUsername()));
    }

    /** 새 양식 저장 — D7 「새 양식 저장」. 같은 이름이 있으면 COMMON4009 */
    @PostMapping("/braille-forms")
    public ApiResponse<BrailleFormDto.Form> createBrailleForm(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody BrailleFormDto.SaveForm request
    ) {
        return ApiResponse.success(brailleFormService.create(userDetails.getUsername(), request));
    }

    /**
     * 양식 수정 — D7-2 「저장」. 통째로 덮어쓴다.
     *
     * <p>기본 양식(builtin:)은 COMMON4003 — 되돌릴 길이 없어 손대지 못하게 한다.
     */
    @PatchMapping("/braille-forms/{formId}")
    public ApiResponse<BrailleFormDto.Form> updateBrailleForm(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String formId,
            @Valid @RequestBody BrailleFormDto.SaveForm request
    ) {
        return ApiResponse.success(brailleFormService.update(userDetails.getUsername(), formId, request));
    }

    /** 양식 삭제 — D7-2 「사용자 양식 지우기」. 이미 만든 작업은 값을 복사해 갔으므로 영향이 없다 */
    @DeleteMapping("/braille-forms/{formId}")
    public ApiResponse<Void> deleteBrailleForm(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String formId
    ) {
        brailleFormService.delete(userDetails.getUsername(), formId);
        return ApiResponse.success(null);
    }

    /**
     * 기본 점역 옵션 저장 — D6 「확인」.
     *
     * <p>창이 항목 전부를 보여주므로 <b>통째로 덮어쓴다</b>(PATCH가 아니라 PUT). 빠진 항목은
     * 기본값으로 채우고, 값이 enum 밖이면 COMMON4000 — 오타가 저장돼 FE 점역 엔진에서
     * 조용히 무시되는 것보다 여기서 막는 편이 낫다.
     */
    @PutMapping("/braille-options")
    public ApiResponse<com.semojum.backend.domain.job.dto.BrailleOptions> updateBrailleOptions(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody(required = false) com.semojum.backend.domain.job.dto.BrailleOptions request
    ) {
        return ApiResponse.success(brailleOptionsService.update(userDetails.getUsername(), request));
    }

    // 앱 재시작·네트워크 재연결 시 복구용 — 진행 중(PENDING/IN_PROGRESS) Job 목록.
    // FE는 lastModifiedAt이 가장 최신인 작업의 lastEditedPage로 이동한다.
    @GetMapping("/jobs/active")
    public ApiResponse<List<JobResponseDto.ActiveJob>> getActiveJobs(
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(userService.getActiveJobs(userDetails.getUsername()));
    }

    @GetMapping("/jobs/{jobId}/pages/{pageNo}")
    public ApiResponse<JobResponseDto.JobDetail> getJobPage(
            @PathVariable String jobId,
            @PathVariable int pageNo,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        return ApiResponse.success(userService.getJobPage(userDetails.getUsername(), jobId, pageNo));
    }
}
