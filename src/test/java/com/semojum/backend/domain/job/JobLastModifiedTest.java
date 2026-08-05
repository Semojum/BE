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
 * 갱신하지 않아, 카드 날짜·정렬·재시작 복구가 모두 실제 작업과 어긋났다.
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

        job.markContentEdited();

        assertTrue(job.getLastModifiedAt().isAfter(before) || job.getLastModifiedAt().isEqual(before));
        assertNotEquals(before, job.getLastModifiedAt(), "편집 시각이 새로 찍혀야 한다");
        assertTrue(job.isEdited(), "편집 여부가 기록돼야 한다");
    }

    @Test
    void 이름변경_폴더이동_휴지통복원은_카드_날짜를_건드리지_않는다() {
        Job job = newJob();
        LocalDateTime original = job.getLastModifiedAt();

        job.rename("새이름.hwp");
        assertEquals(original, job.getLastModifiedAt(), "이름 변경은 내용 변경이 아니다");

        job.moveToFolder(UUID.randomUUID());
        assertEquals(original, job.getLastModifiedAt(), "폴더 이동은 내용 변경이 아니다");

        job.moveToTrash(LocalDateTime.now());
        job.restoreTo(null);
        assertEquals(original, job.getLastModifiedAt(), "휴지통 복원은 내용 변경이 아니다");

        assertFalse(job.isEdited(), "관리 동작만으로 편집 이력이 생기면 안 된다");
    }

    @Test
    void 즐겨찾기_토글도_카드_날짜를_건드리지_않는다() {
        Job job = newJob();
        LocalDateTime original = job.getLastModifiedAt();

        job.toggleFavorite();

        assertEquals(original, job.getLastModifiedAt());
    }
}
