package com.semojum.backend.domain.folder;

import com.semojum.backend.domain.folder.dto.FolderDto;
import com.semojum.backend.domain.folder.service.FolderService;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.dto.JobSearchCondition;
import com.semojum.backend.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 폴더 내부 화면(S2) 단일 조회의 필터 규칙을 검증한다.
 *
 * <p>윈도우 탐색기 원칙 — 필터는 그 속성을 가진 항목만 남긴다.
 * 상태·모드는 폴더에 없는 속성이므로 그 필터가 걸리면 폴더는 결과에서 빠져야 한다.
 */
class FolderContentsTest {

    FolderService folderService;
    UserService userService;
    com.semojum.backend.domain.folder.repository.FolderRepository folderRepository;

    UUID userId = UUID.randomUUID();
    JobResponseDto.JobList emptyFiles = new JobResponseDto.JobList(List.of(), null, false);

    @BeforeEach
    void setUp() {
        folderRepository = mock(com.semojum.backend.domain.folder.repository.FolderRepository.class);
        userService = mock(UserService.class);
        folderService = new FolderService(
                folderRepository,
                mock(com.semojum.backend.domain.job.repository.JobRepository.class),
                mock(com.semojum.backend.domain.auth.repository.UserRepository.class),
                userService);

        when(userService.getMyJobs(anyString(), any())).thenReturn(emptyFiles);
    }

    /** 최상위에 폴더 두 개가 있는 상황을 만든다. */
    private void givenRootFolders(String... names) {
        List<com.semojum.backend.domain.folder.entity.Folder> folders = new java.util.ArrayList<>();
        for (String name : names) {
            var f = mock(com.semojum.backend.domain.folder.entity.Folder.class);
            when(f.getId()).thenReturn(UUID.randomUUID());
            when(f.getName()).thenReturn(name);
            when(f.isFavorite()).thenReturn(false);
            when(f.getCreatedAt()).thenReturn(LocalDateTime.now());
            folders.add(f);
        }
        when(folderRepository.findActiveChildren(any(), any(), anyBoolean())).thenReturn(folders);
    }

    private JobSearchCondition cond(List<String> statuses, List<String> modes, String search) {
        return new JobSearchCondition(null, false, search, statuses, modes, null, false, null, 30);
    }

    @Test
    void 필터가_없으면_폴더와_파일을_함께_준다() {
        givenRootFolders("국어교재", "수학교재");

        FolderDto.Contents result = folderService.contents(userId, null, false, false, cond(null, null, null));

        assertEquals(2, result.folders().size());
        assertNotNull(result.files());
    }

    @Test
    void 상태_필터가_걸리면_폴더는_빠진다() {
        givenRootFolders("국어교재");

        FolderDto.Contents result = folderService.contents(
                userId, null, false, false, cond(List.of("COMPLETED"), null, null));

        assertTrue(result.folders().isEmpty(), "폴더에는 상태 속성이 없으므로 결과에서 빠져야 한다");
        assertNotNull(result.files());
    }

    @Test
    void 모드_필터가_걸리면_폴더는_빠진다() {
        givenRootFolders("국어교재");

        FolderDto.Contents result = folderService.contents(
                userId, null, false, false, cond(null, List.of("b"), null));

        assertTrue(result.folders().isEmpty());
    }

    @Test
    void 검색어는_폴더_이름에도_적용된다() {
        givenRootFolders("국어교재", "수학교재");

        FolderDto.Contents result = folderService.contents(userId, null, false, false, cond(null, null, "국어"));

        assertEquals(List.of("국어교재"), result.folders().stream().map(FolderDto.Item::name).toList());
    }
}
