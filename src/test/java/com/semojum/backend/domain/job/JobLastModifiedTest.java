package com.semojum.backend.domain.job;

import com.semojum.backend.domain.job.entity.Job;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code last_modified_at}은 "파일 내용이 마지막으로 바뀐 시각"이라는 규칙을 고정한다.
 *
 * <p>예전에는 이름 변경·폴더 이동·휴지통 복원이 이 값을 갱신하고 정작 점역사의 편집은
 * 갱신하지 않았다. 그 관리 기능들은 V3.1에서 없앴고(2026-10-13) 편집 쪽 규칙만 남는다.
 */
class JobLastModifiedTest {

    private Job newJob() {
        return Job.builder()
                .id("job_test_" + UUID.randomUUID())
                .mode("b")
                .totalPages(1)
                .originalFileName("원본.hwp")
                .build();
    }

    @Test
    void 내용_편집은_카드_날짜를_갱신한다() {
        Job job = newJob();
        LocalDateTime before = job.getLastModifiedAt();
        assertFalse(job.isEdited(), "생성 직후에는 편집 이력이 없다");

        assertNull(job.getLastEditedPage(), "편집 전에는 마지막 편집 페이지가 없다");

        job.markContentEdited(7);

        assertNotEquals(before, job.getLastModifiedAt(), "편집 시각이 새로 찍혀야 한다");
        assertTrue(job.isEdited(), "편집 여부가 기록돼야 한다");
        assertEquals(7, job.getLastEditedPage(), "재시작 복구용 페이지 번호가 남아야 한다");
    }
}
