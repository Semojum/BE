package com.semojum.backend.domain.folder.entity;

import com.semojum.backend.domain.auth.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "folders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Folder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 상위 폴더. NULL = 루트(전체)
    @Column(name = "parent_folder_id")
    private UUID parentFolderId;

    @Column(nullable = false, length = 50)
    private String name;

    // 값이 있으면 휴지통 (30일 후 완전 삭제)
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public Folder(User user, UUID parentFolderId, String name) {
        this.user = user;
        this.parentFolderId = parentFolderId;
        this.name = name;
        this.createdAt = LocalDateTime.now();
    }

    public boolean isTrashed() {
        return deletedAt != null;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void moveTo(UUID parentFolderId) {
        this.parentFolderId = parentFolderId;
    }

    // 폴더째 삭제 시 하위 항목과 같은 시각을 공유해야 배치 복원이 가능하므로 시각을 받는다
    public void moveToTrash(LocalDateTime at) {
        this.deletedAt = at;
    }

    public void restoreTo(UUID parentFolderId) {
        this.deletedAt = null;
        this.parentFolderId = parentFolderId;
    }
}
