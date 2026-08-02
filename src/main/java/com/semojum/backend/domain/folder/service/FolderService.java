package com.semojum.backend.domain.folder.service;

import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.folder.dto.FolderDto;
import com.semojum.backend.domain.folder.entity.Folder;
import com.semojum.backend.domain.folder.repository.FolderRepository;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class FolderService {

    private static final int MAX_NAME_LENGTH = 50;
    private static final int MAX_DEPTH = 5;
    private static final int MAX_FOLDERS_PER_USER = 200;

    private final FolderRepository folderRepository;
    private final JobRepository jobRepository;
    private final UserRepository userRepository;

    @Transactional
    public FolderDto.Response create(UUID userId, FolderDto.Create request) {
        String name = normalizeName(request.getName());
        UUID parentId = request.getParentFolderId();

        if (folderRepository.countActiveByUserId(userId) >= MAX_FOLDERS_PER_USER) {
            throw new CustomException(ErrorCode.FOLDER_LIMIT_EXCEEDED);
        }
        if (parentId != null) {
            Folder parent = folderRepository.findActiveByIdAndUserId(parentId, userId)
                    .orElseThrow(() -> new CustomException(ErrorCode.FOLDER_NOT_FOUND));
            if (depthOf(parent.getId(), activeFolderMap(userId)) >= MAX_DEPTH) {
                throw new CustomException(ErrorCode.FOLDER_DEPTH_EXCEEDED);
            }
        }
        if (folderRepository.existsActiveName(userId, parentId, name, null)) {
            throw new CustomException(ErrorCode.FOLDER_NAME_DUPLICATE);
        }

        Folder folder = Folder.builder()
                .user(userRepository.getReferenceById(userId))
                .parentFolderId(parentId)
                .name(name)
                .build();
        folderRepository.save(folder);
        return new FolderDto.Response(folder.getId(), folder.getName(), folder.getParentFolderId());
    }

    @Transactional
    public FolderDto.Response update(UUID userId, UUID folderId, FolderDto.Update request) {
        Folder folder = folderRepository.findActiveByIdAndUserId(folderId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.FOLDER_NOT_FOUND));

        UUID targetParentId = request.isParentFolderIdPresent() ? request.getParentFolderId() : folder.getParentFolderId();
        String targetName = request.getName() != null ? normalizeName(request.getName()) : folder.getName();

        Map<UUID, Folder> active = activeFolderMap(userId);

        if (request.isParentFolderIdPresent()) {
            if (targetParentId != null) {
                if (!active.containsKey(targetParentId)) {
                    throw new CustomException(ErrorCode.FOLDER_NOT_FOUND);
                }
                // 순환 방지: 자기 자신 또는 자신의 하위로 이동 불가
                if (folderId.equals(targetParentId) || isDescendant(folderId, targetParentId, active)) {
                    throw new CustomException(ErrorCode.FOLDER_DEPTH_EXCEEDED);
                }
                // 이동 결과 깊이 검증: 대상 깊이 + 이 폴더 서브트리 높이 ≤ MAX_DEPTH
                int targetDepth = depthOf(targetParentId, active);
                int subtreeHeight = subtreeHeight(folderId, active);
                if (targetDepth + subtreeHeight > MAX_DEPTH) {
                    throw new CustomException(ErrorCode.FOLDER_DEPTH_EXCEEDED);
                }
            }
        }
        if (folderRepository.existsActiveName(userId, targetParentId, targetName, folderId)) {
            throw new CustomException(ErrorCode.FOLDER_NAME_DUPLICATE);
        }

        if (request.getName() != null) {
            folder.rename(targetName);
        }
        if (request.isParentFolderIdPresent()) {
            folder.moveTo(targetParentId);
        }
        return new FolderDto.Response(folder.getId(), folder.getName(), folder.getParentFolderId());
    }

    // 하위 폴더·작업까지 통째로 휴지통. 같은 deleted_at 시각을 공유해 배치 복원을 가능하게 한다
    @Transactional
    public void moveToTrash(UUID userId, UUID folderId) {
        Folder folder = folderRepository.findActiveByIdAndUserId(folderId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.FOLDER_NOT_FOUND));

        Map<UUID, Folder> active = activeFolderMap(userId);
        List<UUID> subtreeIds = collectSubtreeIds(folderId, active);

        List<Job> jobs = jobRepository.findActiveByUserIdAndFolderIds(userId, subtreeIds);
        boolean hasInProgress = jobs.stream().anyMatch(Job::isInProgress);
        if (hasInProgress) {
            throw new CustomException(ErrorCode.JOB_IN_PROGRESS);
        }

        LocalDateTime batchAt = LocalDateTime.now();
        for (UUID id : subtreeIds) {
            active.get(id).moveToTrash(batchAt);
        }
        for (Job job : jobs) {
            job.moveToTrash(batchAt);
        }
    }

    @Transactional(readOnly = true)
    public FolderDto.Tree tree(UUID userId) {
        List<Folder> folders = folderRepository.findAllActiveByUserId(userId);
        Map<UUID, List<Folder>> byParent = new HashMap<>();
        for (Folder f : folders) {
            byParent.computeIfAbsent(f.getParentFolderId(), k -> new ArrayList<>()).add(f);
        }
        return new FolderDto.Tree(buildNodes(null, byParent));
    }

    private List<FolderDto.TreeNode> buildNodes(UUID parentId, Map<UUID, List<Folder>> byParent) {
        List<FolderDto.TreeNode> nodes = new ArrayList<>();
        for (Folder f : byParent.getOrDefault(parentId, List.of())) {
            nodes.add(new FolderDto.TreeNode(f.getId(), f.getName(), buildNodes(f.getId(), byParent)));
        }
        return nodes;
    }

    // ===== 내부 유틸 =====
    private String normalizeName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        return trimmed;
    }

    private Map<UUID, Folder> activeFolderMap(UUID userId) {
        Map<UUID, Folder> map = new HashMap<>();
        for (Folder f : folderRepository.findAllActiveByUserId(userId)) {
            map.put(f.getId(), f);
        }
        return map;
    }

    // 루트 직속 폴더 = 깊이 1
    private int depthOf(UUID folderId, Map<UUID, Folder> active) {
        int depth = 0;
        UUID cursor = folderId;
        while (cursor != null && active.containsKey(cursor)) {
            depth++;
            cursor = active.get(cursor).getParentFolderId();
            if (depth > MAX_DEPTH + 1) break; // 방어적 상한
        }
        return depth;
    }

    private boolean isDescendant(UUID ancestorId, UUID nodeId, Map<UUID, Folder> active) {
        UUID cursor = nodeId;
        int hops = 0;
        while (cursor != null && active.containsKey(cursor) && hops++ <= MAX_DEPTH + 1) {
            UUID parent = active.get(cursor).getParentFolderId();
            if (ancestorId.equals(parent)) return true;
            cursor = parent;
        }
        return false;
    }

    private int subtreeHeight(UUID folderId, Map<UUID, Folder> active) {
        Map<UUID, List<UUID>> children = new HashMap<>();
        for (Folder f : active.values()) {
            if (f.getParentFolderId() != null) {
                children.computeIfAbsent(f.getParentFolderId(), k -> new ArrayList<>()).add(f.getId());
            }
        }
        return heightFrom(folderId, children);
    }

    private int heightFrom(UUID id, Map<UUID, List<UUID>> children) {
        int max = 0;
        for (UUID child : children.getOrDefault(id, List.of())) {
            max = Math.max(max, heightFrom(child, children));
        }
        return max + 1;
    }

    private List<UUID> collectSubtreeIds(UUID rootId, Map<UUID, Folder> active) {
        Map<UUID, List<UUID>> children = new HashMap<>();
        for (Folder f : active.values()) {
            if (f.getParentFolderId() != null) {
                children.computeIfAbsent(f.getParentFolderId(), k -> new ArrayList<>()).add(f.getId());
            }
        }
        List<UUID> result = new ArrayList<>();
        Deque<UUID> stack = new ArrayDeque<>();
        stack.push(rootId);
        while (!stack.isEmpty()) {
            UUID id = stack.pop();
            result.add(id);
            for (UUID child : children.getOrDefault(id, List.of())) {
                stack.push(child);
            }
        }
        return result;
    }
}
