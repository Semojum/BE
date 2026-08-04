package com.semojum.backend.domain.folder.repository;

import com.semojum.backend.domain.folder.entity.Folder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FolderRepository extends JpaRepository<Folder, UUID> {

    // ===== 활성(휴지통 제외) =====
    @Query("SELECT f FROM Folder f WHERE f.id = :id AND f.user.id = :userId AND f.deletedAt IS NULL")
    Optional<Folder> findActiveByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT f FROM Folder f WHERE f.user.id = :userId AND f.deletedAt IS NULL ORDER BY f.name ASC")
    List<Folder> findAllActiveByUserId(@Param("userId") UUID userId);

    // 목록 화면용 — 즐겨찾기 필터 + 생성일 정렬(폴더는 수정 개념이 없어 createdAt이 기준)
    @Query("SELECT f FROM Folder f WHERE f.user.id = :userId AND f.deletedAt IS NULL"
            + " AND (:favoriteOnly = false OR f.isFavorite = true)")
    List<Folder> findActiveForTree(@Param("userId") UUID userId,
                                   @Param("favoriteOnly") boolean favoriteOnly);

    @Query("""
            SELECT COUNT(f) > 0 FROM Folder f
            WHERE f.user.id = :userId AND f.deletedAt IS NULL AND f.name = :name
              AND ((:parentId IS NULL AND f.parentFolderId IS NULL) OR f.parentFolderId = :parentId)
              AND (:excludeId IS NULL OR f.id <> :excludeId)
            """)
    boolean existsActiveName(@Param("userId") UUID userId, @Param("parentId") UUID parentId,
                             @Param("name") String name, @Param("excludeId") UUID excludeId);

    @Query("SELECT COUNT(f) FROM Folder f WHERE f.user.id = :userId AND f.deletedAt IS NULL")
    long countActiveByUserId(@Param("userId") UUID userId);

    // ===== 휴지통 =====
    @Query("SELECT f FROM Folder f WHERE f.id = :id AND f.user.id = :userId AND f.deletedAt IS NOT NULL")
    Optional<Folder> findTrashedByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    // 휴지통 목록에는 "함께 삭제된 하위"가 아닌, 삭제의 진입점(부모가 활성이거나 루트)만 한 줄로 보인다.
    @Query("""
            SELECT f FROM Folder f WHERE f.user.id = :userId AND f.deletedAt IS NOT NULL
              AND (f.parentFolderId IS NULL OR NOT EXISTS (
                    SELECT 1 FROM Folder p WHERE p.id = f.parentFolderId AND p.deletedAt IS NOT NULL))
            ORDER BY f.deletedAt DESC
            """)
    List<Folder> findTrashRootsByUserId(@Param("userId") UUID userId);

    // 함께 삭제된 하위 폴더 (복원·완전삭제 시 같이 처리). 삭제 시각이 같은 배치만 대상
    @Query("SELECT f FROM Folder f WHERE f.user.id = :userId AND f.deletedAt = :deletedAt")
    List<Folder> findByUserIdAndDeletedAt(@Param("userId") UUID userId, @Param("deletedAt") LocalDateTime deletedAt);

    @Query("SELECT f FROM Folder f WHERE f.user.id = :userId AND f.deletedAt IS NOT NULL")
    List<Folder> findAllTrashedByUserId(@Param("userId") UUID userId);

    // 30일 경과 완전 삭제 대상
    @Query("SELECT f FROM Folder f WHERE f.deletedAt IS NOT NULL AND f.deletedAt < :cutoff")
    List<Folder> findExpired(@Param("cutoff") LocalDateTime cutoff);
}
