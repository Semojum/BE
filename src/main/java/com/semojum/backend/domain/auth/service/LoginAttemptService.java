package com.semojum.backend.domain.auth.service;

import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 로그인 실패 잠금 (V3.1, 기획 9-4 「로그인 실패」 · 피그마 메시지창 Case 14).
 *
 * <p>틀릴 때마다 남은 횟수를 알려 주고, <b>5번 틀리면 10분 동안 잠근다.</b> 잠긴 동안에는 비밀번호가 맞아도
 * 들어가지 못한다. 로그인에 성공하면 센 것을 지운다.
 *
 * <p>정한 것:
 * <ul>
 *   <li><b>로그인 ID별로 센다</b> — 없는 ID도 똑같이 센다. 응답이 달라지면 어떤 ID가 있는지 알아낼 수 있다</li>
 *   <li>틀린 횟수는 <b>마지막으로 틀린 뒤 10분</b>이 지나면 사라진다 — 지난달 4번 + 오늘 1번으로 잠기지 않게</li>
 *   <li>잠긴 동안 다시 틀려도 잠금을 늘리지 않는다</li>
 * </ul>
 *
 * <p>Redis에만 둔다 — 마이그레이션이 없고 BE를 재시작해도 이어진다. Redis가 비면 잠금도 풀린다(fail-open).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginAttemptService {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(10);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final RedisTemplate<String, String> redisTemplate;

    /** AUTH4001의 result — 화면 「남은 횟수 2회입니다」 */
    public record Failure(int remainingAttempts) {}

    /** AUTH4006의 result — 잠금이 풀리는 시각(KST) */
    public record Locked(LocalDateTime unlockAt) {}

    static String failKey(String loginId) {
        return "auth:login-fail:" + loginId;
    }

    static String lockKey(String loginId) {
        return "auth:login-lock:" + loginId;
    }

    /** 잠겨 있으면 AUTH4006 — 비밀번호를 확인하기 전에 부른다 */
    public void checkNotLocked(String loginId) {
        String until = redisTemplate.opsForValue().get(lockKey(loginId));
        if (until != null) throw locked(Instant.ofEpochMilli(Long.parseLong(until)));
    }

    /**
     * 틀린 것을 하나 센다. 5번째면 잠그고 AUTH4006, 아니면 남은 횟수를 실은 AUTH4001.
     * 늘 예외를 던진다 — 호출부는 {@code throw loginAttemptService.fail(id)}로 쓴다.
     */
    public CustomException fail(String loginId) {
        Long failures = redisTemplate.opsForValue().increment(failKey(loginId));
        int count = failures == null ? 1 : failures.intValue();
        if (count >= MAX_FAILURES) {
            // 초 단위로 자른다 — 잠근 순간과 잠긴 뒤 조회의 해제 시각이 같은 글자로 나가게
            Instant unlockAt = Instant.now().plus(LOCK_DURATION).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            redisTemplate.opsForValue().set(lockKey(loginId), String.valueOf(unlockAt.toEpochMilli()), LOCK_DURATION);
            redisTemplate.delete(failKey(loginId));
            log.warn("로그인 잠금: loginId={} ({}회 실패, {}분)", loginId, count, LOCK_DURATION.toMinutes());
            return locked(unlockAt);
        }
        redisTemplate.expire(failKey(loginId), LOCK_DURATION);
        return new CustomException(ErrorCode.AUTH_INVALID_CREDENTIALS, new Failure(MAX_FAILURES - count));
    }

    /** 로그인 성공 — 센 것을 지운다 */
    public void reset(String loginId) {
        redisTemplate.delete(failKey(loginId));
    }

    private static CustomException locked(Instant unlockAt) {
        return new CustomException(ErrorCode.AUTH_LOGIN_LOCKED, new Locked(LocalDateTime.ofInstant(unlockAt, KST)));
    }
}
