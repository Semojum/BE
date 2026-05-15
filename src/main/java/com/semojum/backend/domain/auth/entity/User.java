package com.semojum.backend.domain.auth.entity;

import com.semojum.backend.domain.auth.enums.AuthProvider;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(unique = true)
    private String email;

    @Column
    private String password;

    @Column
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuthProvider provider;

    @Column(name = "provider_uid")
    private String providerUid;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public User(String email, String password, String name, AuthProvider provider, String providerUid) {
        this.email = email;
        this.password = password;
        this.name = name;
        this.provider = provider;
        this.providerUid = providerUid;
        this.createdAt = LocalDateTime.now();
    }
}