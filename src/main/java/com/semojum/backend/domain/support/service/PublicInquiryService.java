package com.semojum.backend.domain.support.service;

import com.semojum.backend.domain.support.entity.Inquiry;
import com.semojum.backend.domain.support.repository.InquiryRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Set;

/**
 * 홈페이지(미가입) 문의 접수 — 인증 없는 공개 엔드포인트라 남용 방어가 본체다:
 *  - 허니팟(website 필드): 봇이 채우면 성공한 척 응답하고 버린다 (봇에게 실패 신호를 주지 않음)
 *  - IP 레이트리밋: 시간당 5건 (Redis INCR+EXPIRE — 장애 시 접수는 허용, 방어보다 접수 유실이 더 나쁨)
 * 접수분은 T1-9 문의 목록에 orgName·loginId 없이(미가입) 표시된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicInquiryService {

    private static final Set<String> PUBLIC_TYPES =
            Set.of(Inquiry.TYPE_ONBOARDING, Inquiry.TYPE_ERROR_REPORT, Inquiry.TYPE_ETC);
    private static final int RATE_LIMIT_PER_HOUR = 5;

    private final InquiryRepository inquiryRepository;
    private final RedisTemplate<String, String> redisTemplate;

    @Transactional
    public void submit(String type, String name, String email, String message,
                       String honeypot, String clientIp) {
        if (!PUBLIC_TYPES.contains(type)) {
            log.warn("공개 문의 유형 오류: {}", type);
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        // 허니팟 — 사람 눈에 안 보이는 필드가 채워져 있으면 봇. 성공처럼 응답하고 버린다
        if (honeypot != null && !honeypot.isBlank()) {
            log.warn("공개 문의 허니팟 감지 — 폐기: ip={}", clientIp);
            return;
        }
        if (clientIp != null && overRateLimit(clientIp)) {
            log.warn("공개 문의 레이트리밋 초과: ip={}", clientIp);
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        inquiryRepository.save(Inquiry.builder()
                .type(type)
                .senderEmail(email.trim())
                .subject(name == null || name.isBlank() ? null : name.trim())   // 보낸 사람 이름 자리
                .message(message.trim())
                .build());
        log.info("홈페이지 문의 접수: type={}, email={}", type, email);
    }

    private boolean overRateLimit(String ip) {
        try {
            String key = "public-inquiry:rate:" + ip;
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofHours(1));
            }
            return count != null && count > RATE_LIMIT_PER_HOUR;
        } catch (Exception e) {
            // Redis 장애 시 접수 허용 — 방어 실패보다 문의 유실이 더 나쁘다
            log.warn("레이트리밋 확인 실패(접수 허용): {}", e.getMessage());
            return false;
        }
    }
}
