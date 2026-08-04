package com.semojum.backend.domain.job;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.job.dto.JobSearchCondition;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobQueryRepositoryImpl;
import com.semojum.backend.domain.job.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

// 목록 조회의 범위·필터·정렬·커서 규칙을 실제 PostgreSQL로 검증한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Testcontainers
class JobListSearchTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Autowired JobRepository jobRepository;
    @Autowired UserRepository userRepository;
    @Autowired TestEntityManager em;

    User user;
    static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder()
                .loginId("list-user-" + SEQ.incrementAndGet())
                .build());
    }

    /** 카드 날짜(last_modified_at)는 엔티티가 now()로 잡으므로 테스트에서 직접 지정한다. */
    private Job job(String suffix, String mode, String status, UUID folderId,
                    String fileName, LocalDateTime modifiedAt, boolean favorite) {
        Job j = Job.builder()
                .id("job_" + SEQ.incrementAndGet() + "_" + suffix)
                .user(user).mode(mode).totalPages(1)
                .originalFileName(fileName)
                .build();
        if (!"PENDING".equals(status)) j.updateStatus(status);
        if (folderId != null) j.moveToFolder(folderId);
        if (favorite) j.toggleFavorite();
        jobRepository.saveAndFlush(j);
        em.getEntityManager().createQuery(
                        "UPDATE Job j SET j.lastModifiedAt = :at WHERE j.id = :id")
                .setParameter("at", modifiedAt).setParameter("id", j.getId()).executeUpdate();
        em.clear();
        return j;
    }

    private JobSearchCondition cond(UUID folderId, boolean all, String search, List<String> statuses,
                                    List<String> modes, Boolean fav, boolean oldest, String cursor, int size) {
        return new JobSearchCondition(folderId, all, search, statuses, modes, fav, oldest, cursor, size);
    }

    private List<Job> search(JobSearchCondition c) {
        return jobRepository.search(user.getId(), c);
    }

    @Test
    void 기본은_루트의_활성_작업만_최신순으로_반환한다() {
        UUID folder = UUID.randomUUID();
        job("root1", "a", "COMPLETED", null, "루트1.pdf", LocalDateTime.now().minusDays(2), false);
        job("root2", "a", "COMPLETED", null, "루트2.pdf", LocalDateTime.now().minusDays(1), false);
        job("inFolder", "a", "COMPLETED", folder, "폴더안.pdf", LocalDateTime.now(), false);

        List<Job> result = search(cond(null, false, null, null, null, null, false, null, 30));
        assertEquals(List.of("루트2.pdf", "루트1.pdf"),
                result.stream().map(Job::getOriginalFileName).toList());
    }

    @Test
    void 폴더_지정과_전역_범위가_구분된다() {
        UUID folder = UUID.randomUUID();
        job("root", "a", "COMPLETED", null, "루트.pdf", LocalDateTime.now().minusDays(1), false);
        job("inFolder", "a", "COMPLETED", folder, "폴더안.pdf", LocalDateTime.now(), false);

        assertEquals(List.of("폴더안.pdf"),
                search(cond(folder, false, null, null, null, null, false, null, 30))
                        .stream().map(Job::getOriginalFileName).toList());
        assertEquals(2, search(cond(null, true, null, null, null, null, false, null, 30)).size());
    }

    @Test
    void 휴지통_작업은_어떤_범위에서도_제외된다() {
        Job trashed = job("trashed", "a", "COMPLETED", null, "버린것.pdf", LocalDateTime.now(), false);
        job("alive", "a", "COMPLETED", null, "살아있음.pdf", LocalDateTime.now().minusDays(1), false);
        jobRepository.findById(trashed.getId()).orElseThrow().moveToTrash(LocalDateTime.now());
        em.flush(); em.clear();

        List<Job> all = search(cond(null, true, null, null, null, null, false, null, 30));
        assertEquals(List.of("살아있음.pdf"), all.stream().map(Job::getOriginalFileName).toList());
    }

    @Test
    void 검색은_대소문자를_무시하고_부분_일치한다() {
        job("k1", "a", "COMPLETED", null, "Mendel_Report.pdf", LocalDateTime.now(), false);
        job("k2", "a", "COMPLETED", null, "기타자료.pdf", LocalDateTime.now().minusDays(1), false);

        assertEquals(1, search(cond(null, true, "mendel", null, null, null, false, null, 30)).size());
        assertEquals(1, search(cond(null, true, "REPORT", null, null, null, false, null, 30)).size());
        assertEquals(0, search(cond(null, true, "없는이름", null, null, null, false, null, 30)).size());
    }

    @Test
    void 상태_모드_즐겨찾기_필터가_동작한다() {
        job("f1", "a", "COMPLETED", null, "a완료.pdf", LocalDateTime.now(), true);
        job("f2", "b", "FAILED", null, "b실패.hwp", LocalDateTime.now().minusDays(1), false);
        job("f3", "b", "COMPLETED", null, "b완료.hwp", LocalDateTime.now().minusDays(2), false);

        assertEquals(2, search(cond(null, true, null, List.of("COMPLETED"), null, null, false, null, 30)).size());
        assertEquals(2, search(cond(null, true, null, null, List.of("b"), null, false, null, 30)).size());
        assertEquals(1, search(cond(null, true, null, null, null, true, false, null, 30)).size());
        // 필터 조합은 AND
        assertEquals(1, search(cond(null, true, null, List.of("COMPLETED"), List.of("b"), null, false, null, 30)).size());
    }

    @Test
    void 오래된순_정렬이_동작한다() {
        job("s1", "a", "COMPLETED", null, "먼저.pdf", LocalDateTime.now().minusDays(2), false);
        job("s2", "a", "COMPLETED", null, "나중.pdf", LocalDateTime.now(), false);

        assertEquals(List.of("먼저.pdf", "나중.pdf"),
                search(cond(null, true, null, null, null, null, true, null, 30))
                        .stream().map(Job::getOriginalFileName).toList());
    }

    @Test
    void 커서_페이지네이션은_중복이나_누락_없이_이어진다() {
        LocalDateTime base = LocalDateTime.now();
        for (int i = 1; i <= 5; i++) {
            job("p" + i, "a", "COMPLETED", null, "파일" + i + ".pdf", base.minusMinutes(i), false);
        }
        // 1페이지 (2개)
        List<Job> first = search(cond(null, true, null, null, null, null, false, null, 2));
        JobQueryRepositoryImpl.PageSlice s1 = JobQueryRepositoryImpl.slice(first, 2);
        assertTrue(s1.hasMore());
        assertEquals(List.of("파일1.pdf", "파일2.pdf"),
                s1.items().stream().map(Job::getOriginalFileName).toList());

        // 2페이지
        List<Job> second = search(cond(null, true, null, null, null, null, false, s1.nextCursor(), 2));
        JobQueryRepositoryImpl.PageSlice s2 = JobQueryRepositoryImpl.slice(second, 2);
        assertEquals(List.of("파일3.pdf", "파일4.pdf"),
                s2.items().stream().map(Job::getOriginalFileName).toList());

        // 3페이지(마지막) — hasMore=false, nextCursor=null
        List<Job> third = search(cond(null, true, null, null, null, null, false, s2.nextCursor(), 2));
        JobQueryRepositoryImpl.PageSlice s3 = JobQueryRepositoryImpl.slice(third, 2);
        assertFalse(s3.hasMore());
        assertNull(s3.nextCursor());
        assertEquals(List.of("파일5.pdf"), s3.items().stream().map(Job::getOriginalFileName).toList());
    }

    @Test
    void 같은_시각_항목도_커서로_안전하게_나뉜다() {
        LocalDateTime same = LocalDateTime.now().withNano(0);
        for (int i = 1; i <= 4; i++) {
            job("t" + i, "a", "COMPLETED", null, "동시" + i + ".pdf", same, false);
        }
        List<Job> page1 = search(cond(null, true, null, null, null, null, false, null, 2));
        JobQueryRepositoryImpl.PageSlice s1 = JobQueryRepositoryImpl.slice(page1, 2);
        List<Job> page2 = search(cond(null, true, null, null, null, null, false, s1.nextCursor(), 2));
        JobQueryRepositoryImpl.PageSlice s2 = JobQueryRepositoryImpl.slice(page2, 2);

        List<String> ids1 = s1.items().stream().map(Job::getId).toList();
        List<String> ids2 = s2.items().stream().map(Job::getId).toList();
        assertEquals(2, ids1.size());
        assertEquals(2, ids2.size());
        assertTrue(ids1.stream().noneMatch(ids2::contains), "페이지 간 중복 없음");
    }

    @Test
    void 잘못된_커서는_무시하고_첫_페이지를_준다() {
        job("c1", "a", "COMPLETED", null, "정상.pdf", LocalDateTime.now(), false);
        assertEquals(1, search(cond(null, true, null, null, null, null, false, "!!broken!!", 30)).size());
    }

    @Test
    void 진행중_목록은_휴지통을_빼고_최근_수정순으로_준다() {
        job("a1", "a", "IN_PROGRESS", null, "진행중_오래됨.pdf", LocalDateTime.now().minusHours(2), false);
        job("a2", "a", "PENDING", null, "진행중_최신.pdf", LocalDateTime.now(), false);
        Job done = job("a3", "a", "COMPLETED", null, "완료.pdf", LocalDateTime.now(), false);
        Job trashed = job("a4", "a", "PENDING", null, "버려짐.pdf", LocalDateTime.now(), false);
        jobRepository.findById(trashed.getId()).orElseThrow().moveToTrash(LocalDateTime.now());
        em.flush(); em.clear();

        List<Job> active = jobRepository.findActiveByUserIdAndStatusIn(
                user.getId(), List.of("PENDING", "IN_PROGRESS"));
        assertEquals(List.of("진행중_최신.pdf", "진행중_오래됨.pdf"),
                active.stream().map(Job::getOriginalFileName).toList());
        assertFalse(active.stream().anyMatch(j -> j.getId().equals(done.getId())));
    }
}
