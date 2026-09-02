package com.semojum.backend.domain.job.repository;

import com.semojum.backend.domain.job.entity.Job;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 원본 페이지 한 장을 영구 삭제하고 뒤 쪽 번호를 당긴다 (FE 요청 X-1, 2026-09-03).
 *
 * <p>FK가 NO ACTION이라 자식 테이블부터 지운다 — 순서는 휴지통 완전 삭제
 * ({@code TrashPurgeRepository})와 같다: rule_trails → text/braille_elements →
 * bounding_boxes → quality_* → page_results → pages.
 *
 * <p><b>남기는 것</b>: {@code credit_transactions}(환불 없음)와 {@code page_edit_logs}(RLHF 학습 자료).
 * 유저 확정 사항이다. 둘 다 page_no를 들고 있지만 <b>번호를 당기지 않는다</b> — 이미 일어난 일을
 * 적어 둔 장부라 뒤늦게 번호를 바꾸면 그때의 기록이 아니게 된다. 그래서 삭제 뒤에는 이 두 표의
 * page_no가 현재 쪽 번호와 어긋날 수 있다.
 */
public interface PageDeleteRepository extends Repository<Job, String> {

    @Modifying
    @Query(value = """
            DELETE FROM rule_trails WHERE element_id IN (
                SELECT id FROM text_elements
                 WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id = :jobId AND page_number = :pageNo)
                UNION
                SELECT id FROM braille_elements
                 WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id = :jobId AND page_number = :pageNo)
            )
            """, nativeQuery = true)
    void deleteRuleTrails(@Param("jobId") String jobId, @Param("pageNo") int pageNo);

    @Modifying
    @Query(value = """
            DELETE FROM text_elements
             WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id = :jobId AND page_number = :pageNo)
            """, nativeQuery = true)
    void deleteTextElements(@Param("jobId") String jobId, @Param("pageNo") int pageNo);

    @Modifying
    @Query(value = """
            DELETE FROM braille_elements
             WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id = :jobId AND page_number = :pageNo)
            """, nativeQuery = true)
    void deleteBrailleElements(@Param("jobId") String jobId, @Param("pageNo") int pageNo);

    @Modifying
    @Query(value = """
            DELETE FROM bounding_boxes
             WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id = :jobId AND page_number = :pageNo)
            """, nativeQuery = true)
    void deleteBoundingBoxes(@Param("jobId") String jobId, @Param("pageNo") int pageNo);

    @Modifying
    @Query(value = """
            DELETE FROM quality_critical_errors
             WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id = :jobId AND page_number = :pageNo)
            """, nativeQuery = true)
    void deleteQualityCriticalErrors(@Param("jobId") String jobId, @Param("pageNo") int pageNo);

    @Modifying
    @Query(value = """
            DELETE FROM quality_review_flags
             WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id = :jobId AND page_number = :pageNo)
            """, nativeQuery = true)
    void deleteQualityReviewFlags(@Param("jobId") String jobId, @Param("pageNo") int pageNo);

    @Modifying
    @Query(value = "DELETE FROM page_results WHERE job_id = :jobId AND page_number = :pageNo", nativeQuery = true)
    void deletePageResult(@Param("jobId") String jobId, @Param("pageNo") int pageNo);

    @Modifying
    @Query(value = "DELETE FROM pages WHERE job_id = :jobId AND page_no = :pageNo", nativeQuery = true)
    void deletePage(@Param("jobId") String jobId, @Param("pageNo") int pageNo);

    // ── 번호 당김 ────────────────────────────────────────────────────────
    // S3 키는 그대로 둔다. pages.pdf_path·image_path가 컬럼으로 저장돼 있어 읽는 쪽이 그 값을 쓰므로,
    // 번호만 당기면 된다. 객체를 옮기면 삭제 한 번에 남은 쪽 수만큼 복사+삭제가 일어난다.

    @Modifying
    @Query(value = "UPDATE pages SET page_no = page_no - 1 WHERE job_id = :jobId AND page_no > :pageNo", nativeQuery = true)
    void shiftPagesAfter(@Param("jobId") String jobId, @Param("pageNo") int pageNo);

    @Modifying
    @Query(value = "UPDATE page_results SET page_number = page_number - 1 WHERE job_id = :jobId AND page_number > :pageNo", nativeQuery = true)
    void shiftPageResultsAfter(@Param("jobId") String jobId, @Param("pageNo") int pageNo);
}
