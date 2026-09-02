package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.enums.Role;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.job.scheduler.JobDispatcher;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.hwp.HwpToPdfConverter;
import com.semojum.backend.global.s3.S3Service;
import com.semojum.backend.global.thumbnail.ThumbnailService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// 기관 관리자(ROLE_ORG_ADMIN)는 점역(에디터) 기능 사용 불가 — Job 생성 차단 (기획 확정 2026-08-19)
class JobCreateGuardTest {

    @Test
    @SuppressWarnings("unchecked")
    void 기관_관리자는_Job_생성_불가() throws Exception {
        UserRepository userRepository = Mockito.mock(UserRepository.class);
        JobService service = new JobService(
                Mockito.mock(JobRepository.class), Mockito.mock(PageRepository.class),
                userRepository, Mockito.mock(S3Service.class),
                Mockito.mock(RedisTemplate.class), Mockito.mock(JobDispatcher.class),
                Mockito.mock(ThumbnailService.class), Mockito.mock(HwpToPdfConverter.class),
                Mockito.mock(FooterBrailleService.class));

        User orgAdmin = User.builder().loginId("kblib01").password("pw").build();
        orgAdmin.changeRole(Role.ROLE_ORG_ADMIN);
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.of(orgAdmin));

        MockMultipartFile file = new MockMultipartFile("file", "sample.pdf", "application/pdf", new byte[]{1});
        CustomException e = assertThrows(CustomException.class, () ->
                service.createJob(UUID.randomUUID().toString(), file, "a", false, null, null, null));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
    }
}
