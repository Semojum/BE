package com.semojum.backend.domain.auth;

import com.semojum.backend.domain.auth.dto.AuthRequestDto;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.auth.repository.UserSessionRepository;
import com.semojum.backend.domain.auth.service.AuthService;
import com.semojum.backend.domain.auth.service.LoginAttemptService;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.jwt.JwtProvider;
import com.semojum.backend.global.util.ConsoleOrigins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 로그인 실패 잠금 (기획 9-4 · 피그마 메시지창 Case 14 — 「남은 횟수 2회입니다. 5회 실패하면 10분…」).
 */
class LoginLockTest {

    Map<String, String> redis;
    RedisTemplate<String, String> redisTemplate;
    LoginAttemptService attempts;
    UserRepository userRepository;
    PasswordEncoder passwordEncoder;
    AuthService authService;
    User user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = new HashMap<>();
        redisTemplate = mock(RedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> redis.get(inv.<String>getArgument(0)));
        when(ops.increment(anyString())).thenAnswer(inv ->
                (long) Integer.parseInt(redis.merge(inv.getArgument(0), "1", (a, b) -> String.valueOf(Integer.parseInt(a) + 1))));
        doAnswer(inv -> redis.put(inv.getArgument(0), inv.getArgument(1)))
                .when(ops).set(anyString(), anyString(), any(Duration.class));
        when(redisTemplate.delete(anyString())).thenAnswer(inv -> redis.remove(inv.<String>getArgument(0)) != null);
        attempts = new LoginAttemptService(redisTemplate);

        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authService = new AuthService(userRepository, mock(UserSessionRepository.class), mock(JwtProvider.class),
                passwordEncoder, mock(ConsoleOrigins.class), attempts);

        user = User.builder().loginId("testorg01").password("hash").build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(userRepository.findByLoginId("testorg01")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("right", "hash")).thenReturn(true);
    }

    private CustomException login(String loginId, String password) {
        return assertThrows(CustomException.class,
                () -> authService.login(new AuthRequestDto.Login(loginId, password), null));
    }

    private int remaining(CustomException e) {
        assertEquals(ErrorCode.AUTH_INVALID_CREDENTIALS, e.getErrorCode());
        return ((LoginAttemptService.Failure) e.getResult()).remainingAttempts();
    }

    @Test
    void 틀릴_때마다_남은_횟수를_알려준다() {
        assertEquals(4, remaining(login("testorg01", "wrong")));
        assertEquals(3, remaining(login("testorg01", "wrong")));
        assertEquals(2, remaining(login("testorg01", "wrong")));   // 피그마 「남은 횟수 2회입니다」
    }

    @Test
    void 다섯_번째에_10분_잠긴다() {
        for (int i = 0; i < 4; i++) login("testorg01", "wrong");

        CustomException e = login("testorg01", "wrong");
        assertEquals(ErrorCode.AUTH_LOGIN_LOCKED, e.getErrorCode());
        LocalDateTime unlockAt = ((LoginAttemptService.Locked) e.getResult()).unlockAt();
        assertTrue(unlockAt.isAfter(LocalDateTime.now().plusMinutes(9)));
        assertTrue(unlockAt.isBefore(LocalDateTime.now().plusMinutes(11)));
    }

    /** 잠긴 동안은 비밀번호가 맞아도 못 들어간다 — 맞을 때까지 계속 시도하는 것을 막는 게 잠금이다 */
    @Test
    void 잠긴_동안은_맞는_비밀번호도_거절한다() {
        for (int i = 0; i < 5; i++) login("testorg01", "wrong");

        assertEquals(ErrorCode.AUTH_LOGIN_LOCKED, login("testorg01", "right").getErrorCode());
        verify(passwordEncoder, never()).matches(eq("right"), any());
    }

    /** 비밀번호가 맞으면 센 것을 지운다 — 이후 다시 5번이 주어진다 */
    @Test
    void 비밀번호가_맞으면_횟수를_지운다() {
        login("testorg01", "wrong");
        login("testorg01", "wrong");
        user.changeStatus(com.semojum.backend.domain.auth.enums.UserStatus.INACTIVE);   // 맞은 뒤 비활성으로 막히는 길 — 토큰 발급까지 가지 않고 확인한다

        assertEquals(ErrorCode.AUTH_INACTIVE_ACCOUNT, login("testorg01", "right").getErrorCode());
        user.changeStatus(com.semojum.backend.domain.auth.enums.UserStatus.ACTIVE);
        assertEquals(4, remaining(login("testorg01", "wrong")));
    }

    /** 없는 ID도 같은 응답 — 다르면 어떤 ID가 있는지 알아낼 수 있다 */
    @Test
    void 없는_아이디도_똑같이_세고_잠근다() {
        when(userRepository.findByLoginId("ghost")).thenReturn(Optional.empty());

        assertEquals(4, remaining(login("ghost", "x")));
        for (int i = 0; i < 3; i++) login("ghost", "x");
        assertEquals(ErrorCode.AUTH_LOGIN_LOCKED, login("ghost", "x").getErrorCode());
    }

    @Test
    void 아이디마다_따로_센다() {
        when(userRepository.findByLoginId("testorg02")).thenReturn(Optional.empty());
        for (int i = 0; i < 4; i++) login("testorg01", "wrong");

        assertEquals(4, remaining(login("testorg02", "wrong")));
    }
}
