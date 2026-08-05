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

    // 마이페이지 즐겨찾기 (파일과 동일하게 폴더도 대상)
    @Column(name = "is_favorite", nullable = false)
    private boolean isFavorite;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // 폴더 목록 정렬 기준. 직속 항목이 바뀌면 touchModified()로 갱신한다
    @Column(name = "last_modified_at", nullable = false)
    private LocalDateTime lastModifiedAt;

    @Builder
    public Folder(User user, UUID parentFolderId, String name) {
        this.user = user;
        this.parentFolderId = parentFolderId;
        this.name = name;
        this.isFavorite = false;
        this.createdAt = LocalDateTime.now();
        this.lastModifiedAt = this.createdAt;
    }

    /**
     * 이 폴더의 "수정한 날짜"를 갱신한다.
     *
     * <p><b>윈도우 탐색기 규칙을 따른다</b> — 디렉터리 항목이 바뀔 때만 갱신한다.
     * 즉 직속 항목의 <b>추가·삭제·이름변경</b>이 대상이다.
     *
     * <p><b>파일 내용 편집은 갱신하지 않는다.</b> NTFS도 파일 내용만 바뀌면 그 파일의 날짜만
     * 바꾸고 상위 폴더는 건드리지 않는다. 즐겨찾기 토글도 마찬가지로 제외한다.
     *
     * <p><b>상위 폴더로 전파하지 않는다.</b> 하위 폴더 안의 변화는 그 하위 폴더만 갱신한다.
     */
    public void touchModified() {
        this.lastModifiedAt = LocalDateTime.now();
    }

    /** 즐겨찾기 토글 — 반환값은 토글 이후 상태. */
    public boolean toggleFavorite() {
        this.isFavorite = !this.isFavorite;
        return this.isFavorite;
    }

    public boolean isTrashed() {
        return deletedAt != null;
    }

    // 이름 변경은 이 폴더 자신의 항목 정보가 바뀐 것 — 상위 폴더가 touchModified 대상이다
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
