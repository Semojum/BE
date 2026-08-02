package com.semojum.backend.domain.job.repository;

import com.semojum.backend.domain.job.entity.Job;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, String> {
    Optional<Job> findByIdAndUserId(String id, UUID userId);
    List<Job> findByUserIdOrderByStartedAtDesc(UUID userId);

    // 앱 재시작·네트워크 재연결 시 복구용: 아직 끝나지 않은 Job 목록
    List<Job> findByUserIdAndStatusInOrderByStartedAtDesc(UUID userId, List<String> statuses);

    // ===== V3 마이페이지 디렉토리 =====
    // 벌크 이동·삭제 대상 조회 (활성만). 검증은 서비스에서 수행
    @Query("SELECT j FROM Job j WHERE j.id IN :ids AND j.user.id = :userId AND j.deletedAt IS NULL")
    List<Job> findActiveByIdsAndUserId(@Param("ids") List<String> ids, @Param("userId") UUID userId);

    @Query("SELECT j FROM Job j WHERE j.id = :id AND j.user.id = :userId AND j.deletedAt IS NULL")
    Optional<Job> findActiveByIdAndUserId(@Param("id") String id, @Param("userId") UUID userId);

    // 폴더 안 활성 작업 (폴더 삭제 캐스케이드·목록용)
    @Query("SELECT j FROM Job j WHERE j.user.id = :userId AND j.folderId IN :folderIds AND j.deletedAt IS NULL")
    List<Job> findActiveByUserIdAndFolderIds(@Param("userId") UUID userId, @Param("folderIds") List<UUID> folderIds);

    // 휴지통 목록: 직접 삭제된 작업만 한 줄로 (폴더째 삭제된 작업은 폴더 행에 묶임 → 같은 deleted_at 배치로 판별)
    @Query("SELECT j FROM Job j WHERE j.user.id = :userId AND j.deletedAt IS NOT NULL ORDER BY j.deletedAt DESC")
    List<Job> findTrashedByUserId(@Param("userId") UUID userId);

    @Query("SELECT j FROM Job j WHERE j.id = :id AND j.user.id = :userId AND j.deletedAt IS NOT NULL")
    Optional<Job> findTrashedByIdAndUserId(@Param("id") String id, @Param("userId") UUID userId);

    // 폴더째 삭제/복원 시 같은 배치(deleted_at 동일)의 작업들
    @Query("SELECT j FROM Job j WHERE j.user.id = :userId AND j.deletedAt = :deletedAt")
    List<Job> findByUserIdAndDeletedAt(@Param("userId") UUID userId, @Param("deletedAt") java.time.LocalDateTime deletedAt);

    // 30일 경과 완전 삭제 대상
    @Query("SELECT j FROM Job j WHERE j.deletedAt IS NOT NULL AND j.deletedAt < :cutoff")
    List<Job> findExpired(@Param("cutoff") java.time.LocalDateTime cutoff);

    // 작업 이름 중복 시 "(2)" 부여 판단용
    @Query("SELECT COUNT(j) > 0 FROM Job j WHERE j.user.id = :userId AND j.deletedAt IS NULL AND j.workName = :workName")
    boolean existsActiveWorkName(@Param("userId") UUID userId, @Param("workName") String workName);

    // 페이지 이벤트 발생 시 호출: PENDING이면 IN_PROGRESS로 전이하고 updated_at 갱신.
    // WHERE 가드로 이미 종료(COMPLETED/FAILED)된 Job은 절대 되살리지 않는다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE jobs
            SET status = CASE WHEN status = 'PENDING' THEN 'IN_PROGRESS' ELSE status END,
                updated_at = now()
            WHERE id = :jobId AND status IN ('PENDING', 'IN_PROGRESS')
            """, nativeQuery = true)
    int touchJob(@Param("jobId") String jobId);

    // 종료 전이: PENDING/IN_PROGRESS 상태에서만 newStatus(COMPLETED/FAILED)로 전이.
    // failedPages는 '{1,2,3}' 형식 텍스트를 integer[]로 캐스팅하여 저장.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE jobs
            SET status = :newStatus,
                finished_at = now(),
                updated_at = now(),
                failed_pages = CAST(:failedPages AS integer[])
            WHERE id = :jobId AND status IN ('PENDING', 'IN_PROGRESS')
            """, nativeQuery = true)
    int finishJob(@Param("jobId") String jobId,
                  @Param("newStatus") String newStatus,
                  @Param("failedPages") String failedPages);

    // stale-job 안전망: 무진행 IN_PROGRESS → FAILED.
    // cutoff는 Instant(절대시각) → updated_at(timestamptz) 비교가 타임존 무관하게 정확.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE jobs
            SET status = 'FAILED', finished_at = now(), updated_at = now()
            WHERE status = 'IN_PROGRESS' AND updated_at < :cutoff
            """, nativeQuery = true)
    int failStaleInProgress(@Param("cutoff") Instant cutoff);

    // stale-job 안전망: 고아 PENDING → FAILED.
    // updated_at은 DDL(NOT NULL + DEFAULT now())로 항상 non-null이라 started_at 폴백 불필요.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE jobs
            SET status = 'FAILED', finished_at = now(), updated_at = now()
            WHERE status = 'PENDING' AND updated_at < :cutoff
            """, nativeQuery = true)
    int failStalePending(@Param("cutoff") Instant cutoff);
}
