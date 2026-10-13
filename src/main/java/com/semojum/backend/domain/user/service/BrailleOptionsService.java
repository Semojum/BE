package com.semojum.backend.domain.user.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.job.dto.BrailleOptions;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 계정별 기본 점역 옵션 — 설정 › 기본 점역 옵션 설정(D6, Alt+G).
 *
 * <p>작업에 딸린 값이 아니라 <b>계정에 딸린 값</b>이다. 사용자가 언제든 열어서 보고 고치고 저장한다.
 * 작업 조회·목록과 생명주기가 달라 {@code UserService}와 나눴다.
 *
 * <p>업로드 때 이 값이 작업으로 복사될 예정이다 — 기본값을 나중에 고쳐도 이미 만든 작업은
 * 그대로여야 하기 때문이다(점역 양식 복사와 같은 원칙).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrailleOptionsService {

    private final UserRepository userRepository;

    /** 저장한 적 없는 계정은 공장 기본값을 받는다 — FE가 빈 화면을 그리지 않게 */
    @Transactional(readOnly = true)
    public BrailleOptions get(String userId) {
        return find(userId).resolveBrailleOptions();
    }

    /**
     * D6 「확인」 — 창이 항목 전부를 보여주므로 통째로 덮어쓴다.
     *
     * <p>빠진 항목은 기본값으로 채운다. 부분 갱신이 아니라서 "안 보낸 항목은 그대로"가 아니다 —
     * 화면이 전부를 들고 있으니 서버가 이전 값을 기억할 이유가 없다.
     */
    @Transactional
    public BrailleOptions update(String userId, BrailleOptions requested) {
        User user = find(userId);
        BrailleOptions options = (requested == null ? BrailleOptions.defaults() : requested).validated();
        user.updateBrailleOptions(options);
        log.info("기본 점역 옵션 저장: user={}", user.getLoginId());
        return options;
    }

    private User find(String userId) {
        return userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }
}
