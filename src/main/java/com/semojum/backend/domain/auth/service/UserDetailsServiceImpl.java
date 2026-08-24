package com.semojum.backend.domain.auth.service;

import com.semojum.backend.domain.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    public UserDetails loadUserByUsername(String userId) throws UsernameNotFoundException {
        // 계정 role을 그대로 권한으로. loginId는 액세스 로그의 user= 표기용 (2026-08-24)
        return userRepository.findById(UUID.fromString(userId))
                .map(user -> new AuthUser(user.getId().toString(), user.getLoginId(), user.getRole().name()))
                .orElseThrow(() -> new UsernameNotFoundException("유저를 찾을 수 없습니다: " + userId));
    }
}