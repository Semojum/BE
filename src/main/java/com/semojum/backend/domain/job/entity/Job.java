package com.semojum.backend.domain.job.entity;

import com.semojum.backend.domain.auth.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Job {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String mode;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int totalPages;

    @Column(columnDefinition = "integer[]")
    private int[] failedPages;

    private String originalFilePath;
    private String originalFileName;
    private String thumbnailUrl;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    // updated_at은 DB default(now()) 및 JobRepository의 명시적 UPDATE로만 갱신한다.
    // 엔티티 저장으로 덮어쓰지 않도록 insertable/updatable=false로 둔다.
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    // ===== V3 마이페이지 디렉토리 =====
    // 파일 이름은 originalFileName 하나만 사용한다 (팀 결정: 파일 하나 = 이름 하나).
    // 카드 표시·이름 변경 모두 이 컬럼 대상이며, 업로드 시 중복이면 "(2)"가 자동 부여된다.

    // 소속 폴더. NULL = 루트(전체)
    @Column(name = "folder_id")
    private UUID folderId;

    // 값이 있으면 휴지통 (30일 후 완전 삭제)
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // 카드 날짜·정렬 기준. updated_at(변환 파이프라인·StaleJobScheduler 전용)과 분리
    @Column(name = "last_modified_at", nullable = false)
    private LocalDateTime lastModifiedAt;

    // 페이지 일괄 저장 API용 (V3 4장) — 컬럼 선반영
    @Column(name = "last_edited_page")
    private Integer lastEditedPage;

    @Column(name = "is_edited", nullable = false)
    private boolean isEdited;

    // 업로드 시 선택 — 점자 판면 마지막 줄에 쪽번호를 넣을지 여부 (조판·렌더링 기준)
    @Column(name = "insert_page_number", nullable = false)
    private boolean insertPageNumber;

    // 마이페이지 즐겨찾기 (목록 필터·정렬용)
    @Column(name = "is_favorite", nullable = false)
    private boolean isFavorite;

    @Builder
    public Job(String id, User user, String mode, int totalPages, String originalFileName,
               String thumbnailUrl, boolean insertPageNumber) {
        this.id = id;
        this.user = user;
        this.mode = mode;
        this.totalPages = totalPages;
        this.originalFileName = originalFileName;
        this.thumbnailUrl = thumbnailUrl;
        this.insertPageNumber = insertPageNumber;
        this.isFavorite = false;
        this.status = "PENDING";
        this.failedPages = new int[]{};
        this.startedAt = LocalDateTime.now();
        this.lastModifiedAt = LocalDateTime.now();
        this.isEdited = false;
    }

    // ===== V3 도메인 메서드 =====
    public boolean isInProgress() {
        return "PENDING".equals(status) || "IN_PROGRESS".equals(status);
    }

    public boolean isTrashed() {
        return deletedAt != null;
    }

    public void rename(String fileName) {
        this.originalFileName = fileName;
        this.lastModifiedAt = LocalDateTime.now();
    }

    public void moveToFolder(UUID folderId) {
        this.folderId = folderId;
        this.lastModifiedAt = LocalDateTime.now();
    }

    // 폴더째 삭제 시 폴더와 같은 시각을 공유해야 배치 복원이 가능하므로 시각을 받는다
    public void moveToTrash(LocalDateTime at) {
        this.deletedAt = at;
    }

    public void restoreTo(UUID folderId) {
        this.deletedAt = null;
        this.folderId = folderId;
        this.lastModifiedAt = LocalDateTime.now();
    }

    public void updateStatus(String status) {
        this.status = status;
    }

    // 즐겨찾기 토글 — 카드 날짜(last_modified_at)는 건드리지 않는다(내용 변경이 아니므로)
    public boolean toggleFavorite() {
        this.isFavorite = !this.isFavorite;
        return this.isFavorite;
    }

    public void updateThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public void complete(int[] failedPages) {
        this.status = "COMPLETED";
        this.failedPages = failedPages;
        this.finishedAt = LocalDateTime.now();
    }
}