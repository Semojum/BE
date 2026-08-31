package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** 업로드 설정 조회 — 옵션 없이 만든 기존 작업도 완전한 형태를 주고, 타인 작업은 막는다 */
class JobOptionsQueryTest {

    JobRepository jobRepository;
    JobService jobService;
    String userId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        jobRepository = Mockito.mock(JobRepository.class);
        jobService = Mockito.mock(JobService.class, Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(jobService, "jobRepository", jobRepository);
    }

    private Job job(LayoutOptions options, boolean insertPageNumber, String footerText) {
        return Job.builder()
                .id("job-1").user(User.builder().loginId("semojum01").password("pw").build())
                .mode("c").totalPages(1).originalFileName("교재.pdf")
                .insertPageNumber(insertPageNumber).footerText(footerText)
                .layoutOptions(options)
                .build();
    }

    @Test
    void 저장된_옵션을_그대로_돌려준다() {
        LayoutOptions saved = new LayoutOptions(40, 20, "every", 2, 100, 5,
                true, false, "right", "page", true).withDefaults();
        when(jobRepository.findByIdAndUserId(anyString(), any(UUID.class)))
                .thenReturn(Optional.of(job(saved, true, "수학 익힘책 1")));

        JobResponseDto.Options result = jobService.getJobOptions(userId, "job-1");

        assertEquals("job-1", result.jobId());
        assertEquals("수학 익힘책 1", result.footerText());
        assertEquals(40, result.layoutOptions().cellsPerLine());
        assertEquals("every", result.layoutOptions().pageNumberLine());
        assertTrue(result.layoutOptions().advancedAi());
    }

    /** 옵션 없이 만든 기존 작업 — 구 insertPageNumber를 반영한 기본값이 채워져야 한다 */
    @Test
    void 옵션_없는_기존_작업도_완전한_기본값을_준다() {
        when(jobRepository.findByIdAndUserId(anyString(), any(UUID.class)))
                .thenReturn(Optional.of(job(null, false, null)));

        JobResponseDto.Options result = jobService.getJobOptions(userId, "job-1");

        assertEquals(32, result.layoutOptions().cellsPerLine());
        assertEquals(26, result.layoutOptions().linesPerPage());
        assertEquals("none", result.layoutOptions().pageNumberLine());  // insertPageNumber=false 반영
    }

    @Test
    void 타인_작업이면_403이다() {
        when(jobRepository.findByIdAndUserId(anyString(), any(UUID.class))).thenReturn(Optional.empty());

        CustomException e = assertThrows(CustomException.class,
                () -> jobService.getJobOptions(userId, "job-1"));

        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
    }
}
