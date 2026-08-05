package com.semojum.backend.domain.folder.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

public class FolderDto {

    @Getter
    @NoArgsConstructor
    public static class Create {
        @NotBlank
        private String name;
        private UUID parentFolderId;
    }

    // name/parentFolderId 모두 선택 — parentFolderId는 "필드 존재 여부"로 이동 의사를 구분한다
    // (null을 보내면 루트로 이동, 필드를 생략하면 이동 안 함)
    @Getter
    @NoArgsConstructor
    public static class Update {
        private String name;
        private UUID parentFolderId;
        private boolean parentFolderIdPresent;

        @JsonSetter("parentFolderId")
        public void setParentFolderId(UUID parentFolderId) {
            this.parentFolderId = parentFolderId;
            this.parentFolderIdPresent = true;
        }
    }

    public record Response(UUID folderId, String name, UUID parentFolderId) {}

    public record TreeNode(
            UUID folderId,
            String name,
            boolean isFavorite,
            java.time.LocalDateTime createdAt,  // 폴더 정렬 기준
            List<TreeNode> children
    ) {}

    public record Tree(List<TreeNode> folders) {}

    /** 폴더 카드 — 트리와 달리 children 없이 평면 목록으로 준다 */
    public record Item(
            UUID folderId,
            String name,
            boolean isFavorite,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime lastModifiedAt,  // 폴더 정렬 기준 — 안의 항목이 바뀐 시각
            String folderPath                        // 상위 경로. 최상위면 null (전체보기·검색의 위치 표시용)
    ) {}

    public record Items(List<Item> folders) {}

    /**
     * 폴더 화면 응답 — 폴더 먼저, 그다음 파일.
     *
     * <p>화면에서 폴더가 항상 파일보다 앞에 오므로 응답 순서도 그대로 맞춘다.
     *
     * <p>파일만 {@code {items, nextCursor, hasMore}}로 한 겹 감싸는 것은 의도적이다.
     * 커서는 <b>파일에만</b> 해당하므로(폴더는 계정당 200개 상한이라 항상 전부 내려간다)
     * 커서를 파일 안에 두어야 무엇을 가리키는지가 구조로 드러난다. 최상위로 올리면
     * 두 목록의 모양은 같아지지만 커서의 소속이 사라져 문서로만 설명해야 한다.
     */
    public record Contents(
            List<Item> folders,
            com.semojum.backend.domain.job.dto.JobResponseDto.JobList files
    ) {}
}
