package com.semojum.backend.domain.folder.service;

import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.folder.dto.FolderDto;
import com.semojum.backend.domain.folder.entity.Folder;
import com.semojum.backend.domain.folder.repository.FolderRepository;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.dto.JobSearchCondition;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.user.service.UserService;
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
    private final UserService userService;

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

    /**
     * 폴더 내부 화면(S2)·첫 화면(S1)이 쓰는 단일 조회 — 하위 폴더와 파일을 함께 준다.
     *
     * <p>{@code folderId}가 null이면 최상위 폴더 + 루트 파일이다. 화면 하나를 그리는 데
     * 호출 두 번이 필요하던 것을 하나로 합친다. 파일 쪽 필터·정렬·커서는 목록 조회와 동일하다.
     */
    @Transactional(readOnly = true)
    public FolderDto.Contents contents(UUID userId, UUID folderId, boolean favoriteOnly,
                                       boolean oldestFirst, JobSearchCondition fileCondition) {
        JobResponseDto.JobList files = userService.getMyJobs(userId.toString(), fileCondition);

        // 상태·모드는 폴더에 없는 속성 → 그 필터가 걸리면 폴더는 결과에서 빠진다
        // (윈도우 탐색기 원칙: 필터는 그 속성을 가진 항목만 남긴다)
        boolean fileOnlyFilter = notEmpty(fileCondition.statuses()) || notEmpty(fileCondition.modes());
        if (fileOnlyFilter) {
            // 폴더 유효성은 그대로 검사해야 없는 폴더가 빈 화면으로 보이지 않는다
            requireActiveFolder(userId, folderId);
            return new FolderDto.Contents(List.of(), files.items(), files.nextCursor(), files.hasMore());
        }

        List<FolderDto.Item> folders = children(userId, folderId, favoriteOnly, oldestFirst).folders();
        // 검색은 폴더 이름에도 적용한다(탐색기와 동일)
        String keyword = fileCondition.search();
        if (keyword != null && !keyword.isBlank()) {
            String needle = keyword.toLowerCase().strip();
            folders = folders.stream()
                    .filter(f -> f.name().toLowerCase().contains(needle))
                    .toList();
        }
        return new FolderDto.Contents(folders, files.items(), files.nextCursor(), files.hasMore());
    }

    private static boolean notEmpty(List<String> values) {
        return values != null && !values.isEmpty();
    }

    private void requireActiveFolder(UUID userId, UUID folderId) {
        if (folderId != null && folderRepository.findActiveByIdAndUserId(folderId, userId).isEmpty()) {
            throw new CustomException(ErrorCode.FOLDER_NOT_FOUND);
        }
    }

    /**
     * 한 단계 자식 폴더 목록. {@code parentId}가 null이면 최상위 폴더를 준다.
     *
     * <p>폴더 내부 화면(S2)과 마이페이지 첫 화면이 쓴다 — 폴더 하나 보려고 전체 트리를
     * 받지 않게 한다. 전체 구조가 필요한 화면(이동 모달)은 {@code tree()}를 쓴다.
     */
    @Transactional(readOnly = true)
    public FolderDto.Items children(UUID userId, UUID parentId, boolean favoriteOnly, boolean oldestFirst) {
        // 없는 폴더·타인 폴더·휴지통 폴더를 빈 목록으로 얼버무리지 않는다
        requireActiveFolder(userId, parentId);
        List<Folder> folders = folderRepository.findActiveChildren(userId, parentId, favoriteOnly);
        Comparator<Folder> order = Comparator.comparing(Folder::getCreatedAt)
                .thenComparing(f -> f.getId().toString());
        if (!oldestFirst) order = order.reversed();
        folders.sort(order);

        List<FolderDto.Item> items = new ArrayList<>();
        for (Folder f : folders) {
            // 탐색 화면은 이미 그 폴더 안에 들어와 있으므로 위치 표시가 필요 없다
            items.add(new FolderDto.Item(f.getId(), f.getName(), f.isFavorite(), f.getCreatedAt(), null));
        }
        return new FolderDto.Items(items);
    }

    /**
     * 폴더 트리 조회.
     *
     * <p>정렬 기준은 <b>생성일(createdAt)</b>이다 — 폴더는 파일처럼 "수정" 개념이 없어
     * 별도의 last_modified_at을 두지 않았다. {@code favoriteOnly}면 즐겨찾기 폴더만 남긴다
     * (이때 부모가 걸러져도 자식은 루트 레벨로 올라와 보인다).
     */
    @Transactional(readOnly = true)
    public FolderDto.Tree tree(UUID userId, boolean favoriteOnly, boolean oldestFirst) {
        List<Folder> folders = folderRepository.findActiveForTree(userId, favoriteOnly);
        Set<UUID> present = new HashSet<>();
        for (Folder f : folders) present.add(f.getId());

        Map<UUID, List<Folder>> byParent = new HashMap<>();
        for (Folder f : folders) {
            // 필터로 부모가 빠진 폴더는 고아가 되므로 루트로 끌어올린다
            UUID parent = (f.getParentFolderId() != null && present.contains(f.getParentFolderId()))
                    ? f.getParentFolderId() : null;
            byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(f);
        }
        Comparator<Folder> order = Comparator.comparing(Folder::getCreatedAt)
                .thenComparing(f -> f.getId().toString());
        if (!oldestFirst) order = order.reversed();
        for (List<Folder> siblings : byParent.values()) siblings.sort(order);

        return new FolderDto.Tree(buildNodes(null, byParent));
    }

    private List<FolderDto.TreeNode> buildNodes(UUID parentId, Map<UUID, List<Folder>> byParent) {
        List<FolderDto.TreeNode> nodes = new ArrayList<>();
        for (Folder f : byParent.getOrDefault(parentId, List.of())) {
            nodes.add(new FolderDto.TreeNode(f.getId(), f.getName(), f.isFavorite(),
                    f.getCreatedAt(), buildNodes(f.getId(), byParent)));
        }
        return nodes;
    }

    /** 즐겨찾기 토글 — 폴더도 파일과 동일하게 목록 필터 대상이 된다. */
    @Transactional
    public boolean toggleFavorite(UUID userId, UUID folderId) {
        Folder folder = folderRepository.findActiveByIdAndUserId(folderId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.FOLDER_NOT_FOUND));
        return folder.toggleFavorite();
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
