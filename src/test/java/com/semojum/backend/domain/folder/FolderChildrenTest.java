package com.semojum.backend.domain.folder;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.folder.dto.FolderDto;
import com.semojum.backend.domain.folder.repository.FolderRepository;
import com.semojum.backend.domain.folder.service.FolderService;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 폴더 한 단계 자식 조회(S2 폴더 내부 화면)를 검증한다.
 *
 * <p>parentId 유무로 최상위/하위가 갈리는 쿼리라, 두 경우와 휴지통 제외를 함께 고정한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
@Testcontainers
class FolderChildrenTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18");

    @Autowired FolderRepository folderRepository;
    @Autowired JobRepository jobRepository;
    @Autowired UserRepository userRepository;

    FolderService folderService;
    User user;
    static final AtomicInteger SEQ = new AtomicInteger();

    @BeforeEach
    void setUp() {
        folderService = new FolderService(folderRepository, jobRepository, userRepository);
        user = userRepository.save(User.builder()
                .loginId("children-user-" + SEQ.incrementAndGet())
                .build());
    }

    private UUID mkFolder(String name, UUID parentId) {
        FolderDto.Create req = new FolderDto.Create();
        try {
            var nameField = FolderDto.Create.class.getDeclaredField("name");
            nameField.setAccessible(true);
            nameField.set(req, name);
            var parentField = FolderDto.Create.class.getDeclaredField("parentFolderId");
            parentField.setAccessible(true);
            parentField.set(req, parentId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return folderService.create(user.getId(), req).folderId();
    }

    private List<String> names(FolderDto.Items items) {
        return items.folders().stream().map(FolderDto.Item::name).toList();
    }

    @Test
    void parentId를_생략하면_최상위_폴더만_준다() {
        UUID root1 = mkFolder("국어교재", null);
        mkFolder("수학교재", null);
        mkFolder("1학기", root1);   // 하위 폴더는 최상위 목록에 없어야 한다

        List<String> result = names(folderService.children(user.getId(), null, false, true));
        assertEquals(List.of("국어교재", "수학교재"), result);
    }

    @Test
    void parentId를_주면_그_폴더의_자식만_준다() {
        UUID root = mkFolder("국어교재", null);
        mkFolder("1학기", root);
        mkFolder("2학기", root);
        UUID other = mkFolder("수학교재", null);
        mkFolder("남의자식", other);

        List<String> result = names(folderService.children(user.getId(), root, false, true));
        assertEquals(List.of("1학기", "2학기"), result);
    }

    @Test
    void 손자는_포함되지_않는다() {
        UUID root = mkFolder("국어교재", null);
        UUID child = mkFolder("1학기", root);
        mkFolder("1단원", child);

        assertEquals(List.of("1학기"), names(folderService.children(user.getId(), root, false, true)));
        assertEquals(List.of("1단원"), names(folderService.children(user.getId(), child, false, true)));
    }

    @Test
    void 휴지통_폴더는_제외된다() {
        UUID root = mkFolder("국어교재", null);
        UUID trashed = mkFolder("버릴폴더", root);
        mkFolder("남길폴더", root);
        folderService.moveToTrash(user.getId(), trashed);

        assertEquals(List.of("남길폴더"), names(folderService.children(user.getId(), root, false, true)));
    }

    @Test
    void 즐겨찾기_필터와_정렬이_동작한다() {
        UUID root = mkFolder("국어교재", null);
        UUID a = mkFolder("먼저", root);
        mkFolder("나중", root);
        folderService.toggleFavorite(user.getId(), a);

        assertEquals(List.of("먼저"), names(folderService.children(user.getId(), root, true, true)));
        // 기본은 최신순 → 나중에 만든 폴더가 앞
        assertEquals(List.of("나중", "먼저"), names(folderService.children(user.getId(), root, false, false)));
    }

    @Test
    void 없는_폴더를_지정하면_404를_준다() {
        CustomException e = assertThrows(CustomException.class,
                () -> folderService.children(user.getId(), UUID.randomUUID(), false, true));
        assertEquals(ErrorCode.FOLDER_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void 타인_폴더는_조회할_수_없다() {
        UUID mine = mkFolder("내폴더", null);
        User other = userRepository.save(User.builder().loginId("other-" + SEQ.incrementAndGet()).build());

        CustomException e = assertThrows(CustomException.class,
                () -> folderService.children(other.getId(), mine, false, true));
        assertEquals(ErrorCode.FOLDER_NOT_FOUND, e.getErrorCode());
    }
}
