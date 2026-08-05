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
                userService,
                new com.semojum.backend.domain.folder.service.FolderTouch(folderRepository));

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
            when(f.getLastModifiedAt()).thenReturn(LocalDateTime.now());
            folders.add(f);
        }
        when(folderRepository.findActiveChildren(any(), any(), anyBoolean())).thenReturn(folders);
        // 검색은 다른 경로(서브트리 전체 조회)를 탄다 — 그쪽도 같은 폴더들을 보게 한다
        when(folderRepository.findAllActiveByUserId(any())).thenReturn(folders);
    }

    private JobSearchCondition cond(List<String> statuses, List<String> modes, String search) {
        return new JobSearchCondition(null, false, search, statuses, modes, null, false, null, 30);
    }

    /** 2페이지 이후 요청 — 커서가 실려 온다 */
    private JobSearchCondition pagedCond(String cursor) {
        return new JobSearchCondition(null, false, null, null, null, null, false, cursor, 30);
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
    void 커서가_있으면_폴더를_다시_보내지_않는다() {
        givenRootFolders("국어교재", "수학교재");

        // 첫 페이지 — 폴더가 온다
        assertEquals(2, folderService.contents(userId, null, false, false, cond(null, null, null))
                .folders().size());

        // 2페이지 — 폴더는 빈 배열(클라이언트가 누적해도 중복되지 않게)
        FolderDto.Contents paged = folderService.contents(
                userId, null, false, false, pagedCond("eyJhdCI6IjIwMjYifQ"));
        assertTrue(paged.folders().isEmpty(), "커서 요청에는 폴더를 다시 보내지 않는다");
        assertNotNull(paged.files(), "파일은 이어서 준다");
    }

    @Test
    void 빈_커서는_첫_페이지로_보고_폴더를_준다() {
        givenRootFolders("국어교재");

        assertEquals(1, folderService.contents(userId, null, false, false, pagedCond("")).folders().size());
        assertEquals(1, folderService.contents(userId, null, false, false, pagedCond(null)).folders().size());
    }

    @Test
    void 검색어는_폴더_이름에도_적용된다() {
        givenRootFolders("국어교재", "수학교재");

        FolderDto.Contents result = folderService.contents(userId, null, false, false, cond(null, null, "국어"));

        assertEquals(List.of("국어교재"), result.folders().stream().map(FolderDto.Item::name).toList());
    }

    @Test
    void 루트에서_검색하면_파일도_전역으로_찾는다() {
        givenRootFolders("국어교재");
        org.mockito.ArgumentCaptor<JobSearchCondition> captor =
                org.mockito.ArgumentCaptor.forClass(JobSearchCondition.class);

        folderService.contents(userId, null, false, false, cond(null, null, "국어"));

        verify(userService).getMyJobs(anyString(), captor.capture());
        JobSearchCondition used = captor.getValue();
        assertTrue(used.allScope(), "루트 검색은 폴더 밖 파일까지 찾아야 한다");
        assertNull(used.folderId());
    }

    @Test
    void 검색이_아니면_루트_범위_그대로_조회한다() {
        givenRootFolders("국어교재");
        org.mockito.ArgumentCaptor<JobSearchCondition> captor =
                org.mockito.ArgumentCaptor.forClass(JobSearchCondition.class);

        folderService.contents(userId, null, false, false, cond(null, null, null));

        verify(userService).getMyJobs(anyString(), captor.capture());
        assertFalse(captor.getValue().allScope(), "탐색은 루트 한 층만 본다");
    }

    @Test
    void 검색은_한_층이_아니라_전체를_훑는_경로를_탄다() {
        givenRootFolders("국어교재");

        folderService.contents(userId, null, false, false, cond(null, null, "국어"));

        // 검색일 때는 한 단계 자식 조회가 아니라 서브트리 전체 조회를 써야 한다
        verify(folderRepository).findAllActiveByUserId(any());
        verify(folderRepository, never()).findActiveChildren(any(), any(), anyBoolean());
    }
}
