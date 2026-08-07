package com.semojum.backend.domain.trash.repository;

import com.semojum.backend.domain.job.entity.Job;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

// 휴지통 완전 삭제 전용 — FK 순서대로 자식 테이블부터 제거한다.
// 순서: rule_trails → text/braille_elements → bounding_boxes → quality_* → page_results → pages → page_edit_logs → jobs
public interface TrashPurgeRepository extends Repository<Job, String> {

    @Modifying
    @Query(value = """
            DELETE FROM rule_trails WHERE element_id IN (
                SELECT id FROM text_elements WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id IN :jobIds)
                UNION
                SELECT id FROM braille_elements WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id IN :jobIds)
            )
            """, nativeQuery = true)
    void deleteRuleTrails(@Param("jobIds") List<String> jobIds);

    @Modifying
    @Query(value = "DELETE FROM text_elements WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id IN :jobIds)", nativeQuery = true)
    void deleteTextElements(@Param("jobIds") List<String> jobIds);

    @Modifying
    @Query(value = "DELETE FROM braille_elements WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id IN :jobIds)", nativeQuery = true)
    void deleteBrailleElements(@Param("jobIds") List<String> jobIds);

    @Modifying
    @Query(value = "DELETE FROM bounding_boxes WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id IN :jobIds)", nativeQuery = true)
    void deleteBoundingBoxes(@Param("jobIds") List<String> jobIds);

    @Modifying
    @Query(value = "DELETE FROM quality_critical_errors WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id IN :jobIds)", nativeQuery = true)
    void deleteQualityCriticalErrors(@Param("jobIds") List<String> jobIds);

    @Modifying
    @Query(value = "DELETE FROM quality_review_flags WHERE page_result_id IN (SELECT id FROM page_results WHERE job_id IN :jobIds)", nativeQuery = true)
    void deleteQualityReviewFlags(@Param("jobIds") List<String> jobIds);

    @Modifying
    @Query(value = "DELETE FROM page_results WHERE job_id IN :jobIds", nativeQuery = true)
    void deletePageResults(@Param("jobIds") List<String> jobIds);

    @Modifying
    @Query(value = "DELETE FROM pages WHERE job_id IN :jobIds", nativeQuery = true)
    void deletePages(@Param("jobIds") List<String> jobIds);

    @Modifying
    @Query(value = "DELETE FROM page_edit_logs WHERE job_id IN :jobIds", nativeQuery = true)
    void deleteEditLogs(@Param("jobIds") List<String> jobIds);

    @Modifying
    @Query(value = "DELETE FROM jobs WHERE id IN :jobIds", nativeQuery = true)
    void deleteJobs(@Param("jobIds") List<String> jobIds);

    @Modifying
    @Query(value = "DELETE FROM folders WHERE id IN :folderIds", nativeQuery = true)
    void deleteFolders(@Param("folderIds") List<java.util.UUID> folderIds);
}
