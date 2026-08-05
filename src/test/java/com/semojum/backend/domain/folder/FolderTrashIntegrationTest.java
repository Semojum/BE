package com.semojum.backend.domain.folder;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.folder.dto.FolderDto;
import com.semojum.backend.domain.folder.entity.Folder;
import com.semojum.backend.domain.folder.repository.FolderRepository;
import com.semojum.backend.domain.folder.service.FolderService;
import com.semojum.backend.domain.folder.service.FolderTouch;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.user.service.UserService;
import com.semojum.backend.domain.job.service.JobManageService;
import com.semojum.backend.domain.trash.dto.TrashDto;
import com.semojum.backend.domain.trash.repository.TrashPurgeRepository;
import com.semojum.backend.domain.trash.service.TrashService;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.s3.S3Service;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// 실제 PostgreSQL(Testcontainers)로 폴더·휴지통·작업 관리의 핵심 규칙을 검증한다.
// 스키마는 엔티티 기준 create-drop으로 생성 (Flyway는 운영 스키마 baseline 전제라 테스트에서 비활성)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Testcontainers
class FolderTrashIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Autowired FolderRepository folderRepository;
    @Autowired JobRepository jobRepository;
    @Autowired UserRepository userRepository;
    @Autowired TrashPurgeRepository purgeRepository;
    @Autowired TestEntityManager em;

    FolderService folderService;
    JobManageService jobManageService;
    TrashService trashService;
    S3Service s3Service;

    User user;
    static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void setUp() {
        FolderTouch folderTouch = new FolderTouch(folderRepository);
        folderService = new FolderService(folderRepository, jobRepository, userRepository,
                mock(UserService.class), folderTouch);
        jobManageService = new JobManageService(jobRepository, folderRepository, folderTouch);
        s3Service = mock(S3Service.class);
        trashService = new TrashService(folderRepository, jobRepository, purgeRepository, s3Service, folderTouch);

        int n = SEQ.incrementAndGet();
        user = userRepository.save(User.builder()
                .loginId("it-user-" + n)
                .build());
    }

    private UUID createFolder(String name, UUID parentId) {
        FolderDto.Create req = new FolderDto.Create();
        set(req, "name", name);
        set(req, "parentFolderId", parentId);
        return folderService.create(user.getId(), req).folderId();
    }

    private Job createJob(String suffix, String status, UUID folderId) {
        Job job = Job.builder()
                .id("job_test_" + SEQ.get() + "_" + suffix)
                .user(user)
                .mode("a")
                .totalPages(1)
                .originalFileName(suffix + ".pdf")
                .build();
        if (!"PENDING".equals(status)) {
            job.updateStatus(status);
        }
        if (folderId != null) {
            job.moveToFolder(folderId);
        }
        return jobRepository.save(job);
    }

    // DTO가 세터 없는 요청 객체라 테스트에서는 리플렉션으로 채운다
    private static void set(Object target, String field, Object value) {
        try {
            var f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ===== 폴더 규칙 =====

    // ===== 폴더 "수정한 날짜" =====

    private LocalDateTime modifiedAt(UUID folderId) {
        em.flush(); em.clear();
        return folderRepository.findById(folderId).orElseThrow().getLastModifiedAt();
    }

    @Test
    void 항목이_추가되면_상위_폴더의_수정_날짜가_갱신된다() {
        UUID parent = createFolder("상위", null);
        LocalDateTime before = modifiedAt(parent);

        createFolder("하위", parent);

        assertTrue(modifiedAt(parent).isAfter(before));
    }

    @Test
    void 파일을_옮기면_뺀_폴더와_넣은_폴더_모두_갱신된다() {
        UUID from = createFolder("보낸쪽", null);
        UUID to = createFolder("받은쪽", null);
        Job job = createJob("move", "COMPLETED", from);
        LocalDateTime fromBefore = modifiedAt(from);
        LocalDateTime toBefore = modifiedAt(to);

        jobManageService.moveAll(user.getId(), List.of(job.getId()), to);

        assertTrue(modifiedAt(from).isAfter(fromBefore), "파일이 빠진 폴더도 갱신된다");
        assertTrue(modifiedAt(to).isAfter(toBefore), "파일이 들어온 폴더도 갱신된다");
    }

    @Test
    void 파일_이름_변경도_그_폴더를_갱신한다() {
        UUID folderId = createFolder("교재", null);
        Job job = createJob("rename", "COMPLETED", folderId);
        LocalDateTime before = modifiedAt(folderId);

        jobManageService.rename(user.getId(), job.getId(), "새이름.pdf");

        assertTrue(modifiedAt(folderId).isAfter(before));
    }

    @Test
    void 파일을_휴지통에_넣으면_그_폴더가_갱신된다() {
        UUID folderId = createFolder("교재", null);
        Job job = createJob("trash", "COMPLETED", folderId);
        LocalDateTime before = modifiedAt(folderId);

        jobManageService.trashAll(user.getId(), List.of(job.getId()));

        assertTrue(modifiedAt(folderId).isAfter(before));
    }

    @Test
    void 하위_폴더_안의_변화는_상위로_전파되지_않는다() {
        UUID parent = createFolder("상위", null);
        UUID child = createFolder("하위", parent);
        Job job = createJob("nested", "COMPLETED", null);
        LocalDateTime parentBefore = modifiedAt(parent);
        LocalDateTime childBefore = modifiedAt(child);

        jobManageService.moveAll(user.getId(), List.of(job.getId()), child);

        assertTrue(modifiedAt(child).isAfter(childBefore), "직속 폴더는 갱신된다");
        assertEquals(parentBefore, modifiedAt(parent), "상위 폴더는 그대로여야 한다");
    }

    @Test
    void 같은_위치_같은_이름_폴더는_거부된다() {
        createFolder("수학", null);
        CustomException e = assertThrows(CustomException.class, () -> createFolder("수학", null));
        assertEquals(ErrorCode.FOLDER_NAME_DUPLICATE, e.getErrorCode());
        // 다른 위치에는 같은 이름 허용
        UUID other = createFolder("영어", null);
        assertDoesNotThrow(() -> createFolder("수학", other));
    }

    @Test
    void 깊이_5단계를_넘는_생성은_거부된다() {
        UUID parent = null;
        for (int depth = 1; depth <= 5; depth++) {
            parent = createFolder("깊이" + depth, parent);
        }
        UUID fifth = parent;
        CustomException e = assertThrows(CustomException.class, () -> createFolder("깊이6", fifth));
        assertEquals(ErrorCode.FOLDER_DEPTH_EXCEEDED, e.getErrorCode());
    }

    @Test
    void 자기_하위로의_이동은_순환이라_거부된다() {
        UUID a = createFolder("A", null);
        UUID b = createFolder("B", a);
        FolderDto.Update req = new FolderDto.Update();
        req.setParentFolderId(b); // A를 자신의 자식 B 밑으로
        CustomException e = assertThrows(CustomException.class,
                () -> folderService.update(user.getId(), a, req));
        assertEquals(ErrorCode.FOLDER_DEPTH_EXCEEDED, e.getErrorCode());
    }

    // ===== 휴지통 캐스케이드 =====

    @Test
    void 폴더_삭제는_하위_폴더와_작업까지_같은_배치로_휴지통에_보낸다() {
        UUID a = createFolder("A", null);
        UUID b = createFolder("B", a);
        Job j1 = createJob("inA", "COMPLETED", a);
        Job j2 = createJob("inB", "COMPLETED", b);

        folderService.moveToTrash(user.getId(), a);
        em.flush(); em.clear();

        Folder fa = folderRepository.findById(a).orElseThrow();
        Folder fb = folderRepository.findById(b).orElseThrow();
        Job r1 = jobRepository.findById(j1.getId()).orElseThrow();
        Job r2 = jobRepository.findById(j2.getId()).orElseThrow();
        assertNotNull(fa.getDeletedAt());
        assertEquals(fa.getDeletedAt(), fb.getDeletedAt());
        assertEquals(fa.getDeletedAt(), r1.getDeletedAt());
        assertEquals(fa.getDeletedAt(), r2.getDeletedAt());

        // 휴지통 목록에는 진입점 폴더 한 줄 + 작업 수 집계
        TrashDto.Items items = trashService.list(user.getId());
        assertEquals(1, items.items().size());
        assertEquals("FOLDER", items.items().get(0).type());
        assertEquals(2, items.items().get(0).itemCount());
    }

    @Test
    void 변환_중_작업이_들어있는_폴더는_삭제할_수_없다() {
        UUID a = createFolder("A", null);
        createJob("running", "IN_PROGRESS", a);
        CustomException e = assertThrows(CustomException.class,
                () -> folderService.moveToTrash(user.getId(), a));
        assertEquals(ErrorCode.JOB_IN_PROGRESS, e.getErrorCode());
        assertNull(folderRepository.findById(a).orElseThrow().getDeletedAt());
    }

    @Test
    void 폴더_복원은_내부_구조를_그대로_되살린다() {
        UUID a = createFolder("A", null);
        UUID b = createFolder("B", a);
        Job j = createJob("inB", "COMPLETED", b);
        folderService.moveToTrash(user.getId(), a);

        TrashDto.RestoreResult result = trashService.restore(user.getId(), a.toString());
        em.flush(); em.clear();

        assertNull(result.restoredTo()); // 원래 루트였음
        assertNull(folderRepository.findById(a).orElseThrow().getDeletedAt());
        Folder fb = folderRepository.findById(b).orElseThrow();
        assertNull(fb.getDeletedAt());
        assertEquals(a, fb.getParentFolderId());
        Job rj = jobRepository.findById(j.getId()).orElseThrow();
        assertNull(rj.getDeletedAt());
        assertEquals(b, rj.getFolderId());
    }

    @Test
    void 원래_부모가_사라진_폴더는_루트로_복원된다() {
        UUID p = createFolder("부모", null);
        UUID c = createFolder("자식", p);
        folderService.moveToTrash(user.getId(), c); // 배치 1: 자식만
        folderService.moveToTrash(user.getId(), p); // 배치 2: 부모

        TrashDto.RestoreResult result = trashService.restore(user.getId(), c.toString());
        em.flush(); em.clear();

        assertNull(result.restoredTo());
        assertNull(folderRepository.findById(c).orElseThrow().getParentFolderId());
    }

    @Test
    void 완전_삭제는_DB_행과_S3_프리픽스를_정리한다() {
        UUID a = createFolder("A", null);
        Job j = createJob("purge", "COMPLETED", a);
        folderService.moveToTrash(user.getId(), a);
        em.flush();

        trashService.purge(user.getId(), a.toString());
        em.flush(); em.clear();

        assertTrue(jobRepository.findById(j.getId()).isEmpty());
        assertTrue(folderRepository.findById(a).isEmpty());
        verify(s3Service).deleteByPrefix(j.getId() + "/");
    }

    // ===== 작업 관리 =====

    @Test
    void 변환_중_작업은_이름을_바꿀_수_없다() {
        Job pending = createJob("pending", "PENDING", null);
        CustomException e = assertThrows(CustomException.class,
                () -> jobManageService.rename(user.getId(), pending.getId(), "새이름"));
        assertEquals(ErrorCode.JOB_IN_PROGRESS, e.getErrorCode());
    }

    @Test
    void 완료된_작업은_파일_이름이_변경된다() {
        Job done = createJob("done", "COMPLETED", null);
        jobManageService.rename(user.getId(), done.getId(), "새이름.pdf");
        em.flush(); em.clear();
        assertEquals("새이름.pdf", jobRepository.findById(done.getId()).orElseThrow().getOriginalFileName());
    }

    @Test
    void 벌크_이동은_하나라도_실패하면_아무것도_바꾸지_않는다() {
        UUID target = createFolder("대상", null);
        Job ok = createJob("ok", "COMPLETED", null);
        CustomException e = assertThrows(CustomException.class,
                () -> jobManageService.moveAll(user.getId(),
                        List.of(ok.getId(), "job_ghost_000000"), target));
        assertEquals(ErrorCode.JOB_NOT_FOUND, e.getErrorCode());
        em.flush(); em.clear();
        assertNull(jobRepository.findById(ok.getId()).orElseThrow().getFolderId());
    }

    @Test
    void 벌크_삭제는_같은_배치_시각으로_휴지통에_보낸다() {
        Job j1 = createJob("bulk1", "COMPLETED", null);
        Job j2 = createJob("bulk2", "COMPLETED", null);
        int count = jobManageService.trashAll(user.getId(), List.of(j1.getId(), j2.getId()));
        em.flush(); em.clear();
        assertEquals(2, count);
        Job r1 = jobRepository.findById(j1.getId()).orElseThrow();
        Job r2 = jobRepository.findById(j2.getId()).orElseThrow();
        assertNotNull(r1.getDeletedAt());
        assertEquals(r1.getDeletedAt(), r2.getDeletedAt());
    }

    // ===== 즐겨찾기 =====

    @Test
    void 폴더_즐겨찾기를_토글하면_트리_필터에_반영된다() {
        UUID a = createFolder("즐겨찾는폴더", null);
        createFolder("일반폴더", null);

        assertFalse(folderRepository.findById(a).orElseThrow().isFavorite());
        assertTrue(folderService.toggleFavorite(user.getId(), a));
        em.flush(); em.clear();

        // 전체 트리에는 둘 다, 즐겨찾기 필터에는 하나만
        assertEquals(2, folderService.tree(user.getId(), false, false).folders().size());
        var favOnly = folderService.tree(user.getId(), true, false).folders();
        assertEquals(1, favOnly.size());
        assertEquals("즐겨찾는폴더", favOnly.get(0).name());
        assertTrue(favOnly.get(0).isFavorite());

        // 다시 토글하면 해제
        assertFalse(folderService.toggleFavorite(user.getId(), a));
        em.flush(); em.clear();
        assertEquals(0, folderService.tree(user.getId(), true, false).folders().size());
    }

    @Test
    void 트리는_생성일_기준으로_정렬된다() {
        UUID first = createFolder("먼저만든폴더", null);
        UUID second = createFolder("나중에만든폴더", null);

        var latest = folderService.tree(user.getId(), false, false).folders();
        assertEquals(List.of("나중에만든폴더", "먼저만든폴더"),
                latest.stream().map(FolderDto.TreeNode::name).toList());

        var oldest = folderService.tree(user.getId(), false, true).folders();
        assertEquals(List.of("먼저만든폴더", "나중에만든폴더"),
                oldest.stream().map(FolderDto.TreeNode::name).toList());
        assertNotNull(latest.get(0).createdAt());
        assertEquals(second, latest.get(0).folderId());
        assertEquals(first, oldest.get(0).folderId());
    }

    @Test
    void 즐겨찾기_필터에서_부모가_빠진_하위폴더는_루트로_올라온다() {
        UUID parent = createFolder("부모", null);
        UUID child = createFolder("자식", parent);
        folderService.toggleFavorite(user.getId(), child);   // 자식만 즐겨찾기
        em.flush(); em.clear();

        var favOnly = folderService.tree(user.getId(), true, false).folders();
        assertEquals(1, favOnly.size());
        assertEquals("자식", favOnly.get(0).name());   // 고아가 되지 않고 루트에 노출
    }
}
