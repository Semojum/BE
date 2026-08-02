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

    public record TreeNode(UUID folderId, String name, List<TreeNode> children) {}

    public record Tree(List<TreeNode> folders) {}
}
