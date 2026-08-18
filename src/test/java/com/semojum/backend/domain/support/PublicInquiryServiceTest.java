package com.semojum.backend.domain.support;

import com.semojum.backend.domain.support.repository.InquiryRepository;
import com.semojum.backend.domain.support.service.PublicInquiryService;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// 공개 문의 접수의 남용 방어 규칙 (저장소·Redis mock)
class PublicInquiryServiceTest {

    private InquiryRepository inquiryRepository;
    private ValueOperations<String, String> valueOps;
    private PublicInquiryService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        inquiryRepository = Mockito.mock(InquiryRepository.class);
        RedisTemplate<String, String> redisTemplate = Mockito.mock(RedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new PublicInquiryService(inquiryRepository, redisTemplate);
        when(valueOps.increment(anyString())).thenReturn(1L);
    }

    @Test
    void 정상_접수() {
        service.submit("ONBOARDING", "홍길동", "hong@example.com", "도입 문의드립니다", null, "1.2.3.4");
        verify(inquiryRepository).save(any());
    }

    @Test
    void 허니팟이_채워지면_성공한_척_버림() {
        service.submit("ONBOARDING", null, "bot@spam.com", "spam", "http://spam.link", "1.2.3.4");
        verify(inquiryRepository, never()).save(any());   // 예외 없이 조용히 폐기
    }

    @Test
    void 허용_외_유형_거절() {
        CustomException e = assertThrows(CustomException.class, () ->
                service.submit("CREDIT_ADD", null, "a@b.com", "msg", null, "1.2.3.4"));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 시간당_5건_초과는_거절() {
        when(valueOps.increment(anyString())).thenReturn(6L);
        CustomException e = assertThrows(CustomException.class, () ->
                service.submit("ETC", null, "a@b.com", "msg", null, "1.2.3.4"));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
        verify(inquiryRepository, never()).save(any());
    }

    @Test
    void 레이트리밋_확인_실패는_접수_허용() {
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("redis down"));
        service.submit("ETC", null, "a@b.com", "msg", null, "1.2.3.4");
        verify(inquiryRepository).save(any());
    }
}
