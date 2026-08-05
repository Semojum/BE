package com.semojum.backend.domain.folder.service;

import com.semojum.backend.domain.folder.entity.Folder;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 폴더 경로("상위/하위") 계산.
 *
 * <p>파일 카드의 위치 표시와 검색 결과의 폴더 위치가 같은 규칙을 써야 하므로 한곳에 둔다.
 * 계정당 폴더 200개 상한이라 한 번에 읽어 메모리에서 계산한다.
 */
public final class FolderPaths {

    private FolderPaths() {
    }

    /** 폴더 id → 전체 경로. */
    public static Map<UUID, String> buildAll(Map<UUID, Folder> byId) {
        Map<UUID, String> paths = new HashMap<>();
        for (Folder f : byId.values()) {
            paths.put(f.getId(), pathOf(f, byId, new HashSet<>()));
        }
        return paths;
    }

    /**
     * 한 폴더의 전체 경로.
     *
     * <p>{@code visited}로 순환을 끊는다 — 폴더 이동에 순환 방지가 있지만, 데이터가 어긋나도
     * 조회가 무한 재귀로 죽지는 않아야 한다.
     */
    public static String pathOf(Folder folder, Map<UUID, Folder> byId, Set<UUID> visited) {
        if (folder == null || !visited.add(folder.getId())) return "";
        Folder parent = folder.getParentFolderId() == null ? null : byId.get(folder.getParentFolderId());
        String parentPath = parent == null ? "" : pathOf(parent, byId, visited);
        return parentPath.isEmpty() ? folder.getName() : parentPath + "/" + folder.getName();
    }
}
