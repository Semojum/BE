package com.semojum.backend.domain.trash.service;

import com.semojum.backend.domain.folder.entity.Folder;
import com.semojum.backend.domain.folder.repository.FolderRepository;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.trash.dto.TrashDto;
import com.semojum.backend.domain.trash.repository.TrashPurgeRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.s3.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrashService {

    public static final int RETENTION_DAYS = 30;

    private final FolderRepository folderRepository;
    private final JobRepository jobRepository;
    private final TrashPurgeRepository purgeRepository;
    private final S3Service s3Service;

    // 휴지통 목록 — 폴더는 삭제 진입점만 한 줄로, 작업은 폴더째 삭제된 것(같은 배치)을 제외하고 표시
    @Transactional(readOnly = true)
    public TrashDto.Items list(UUID userId) {
        List<Folder> trashRoots = folderRepository.findTrashRootsByUserId(userId);
        List<Folder> allTrashedFolders = folderRepository.findAllTrashedByUserId(userId);
        List<Job> trashedJobs = jobRepository.findTrashedByUserId(userId);

        Map<UUID, Folder> trashedFolderById = new HashMap<>();
        for (Folder f : allTrashedFolders) {
            trashedFolderById.put(f.getId(), f);
        }

        List<TrashDto.Item> items = new ArrayList<>();
        for (Folder root : trashRoots) {
            long jobCount = trashedJobs.stream()
                    .filter(j -> Objects.equals(j.getDeletedAt(), root.getDeletedAt()))
                    .count();
            items.add(new TrashDto.Item("FOLDER", root.getId().toString(), root.getName(), (int) jobCount,
                    root.getDeletedAt(), root.getDeletedAt().plusDays(RETENTION_DAYS)));
        }
        for (Job job : trashedJobs) {
            Folder parent = job.getFolderId() != null ? trashedFolderById.get(job.getFolderId()) : null;
            boolean partOfFolderBatch = parent != null && Objects.equals(parent.getDeletedAt(), job.getDeletedAt());
            if (!partOfFolderBatch) {
                items.add(new TrashDto.Item("JOB", job.getId(), job.getWorkName(), null,
                        job.getDeletedAt(), job.getDeletedAt().plusDays(RETENTION_DAYS)));
            }
        }
        items.sort(Comparator.comparing(TrashDto.Item::deletedAt).reversed());
        return new TrashDto.Items(items);
    }

    @Transactional
    public TrashDto.RestoreResult restore(UUID userId, String id) {
        if (id.startsWith("job_")) {
            return restoreJob(userId, id);
        }
        return restoreFolder(userId, parseUuid(id));
    }

    private TrashDto.RestoreResult restoreJob(UUID userId, String jobId) {
        Job job = jobRepository.findTrashedByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        UUID target = job.getFolderId();
        if (target != null && folderRepository.findActiveByIdAndUserId(target, userId).isEmpty()) {
            target = null; // 원래 폴더가 없으면 루트로
        }
        job.restoreTo(target);
        return new TrashDto.RestoreResult(target);
    }

    private TrashDto.RestoreResult restoreFolder(UUID userId, UUID folderId) {
        Folder root = folderRepository.findTrashedByIdAndUserId(folderId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.FOLDER_NOT_FOUND));
        LocalDateTime batchAt = root.getDeletedAt();

        // 복원 위치: 원래 부모가 살아 있으면 그 자리, 없으면 루트
        UUID target = root.getParentFolderId();
        if (target != null && folderRepository.findActiveByIdAndUserId(target, userId).isEmpty()) {
            target = null;
        }
        if (folderRepository.existsActiveName(userId, target, root.getName(), null)) {
            throw new CustomException(ErrorCode.FOLDER_NAME_DUPLICATE);
        }

        // 같은 배치의 하위 폴더·작업을 폴더째 복원 (내부 구조는 그대로 유지)
        for (Folder f : folderRepository.findByUserIdAndDeletedAt(userId, batchAt)) {
            if (f.getId().equals(root.getId())) {
                f.restoreTo(target);
            } else {
                f.restoreTo(f.getParentFolderId());
            }
        }
        for (Job j : jobRepository.findByUserIdAndDeletedAt(userId, batchAt)) {
            j.restoreTo(j.getFolderId());
        }
        return new TrashDto.RestoreResult(target);
    }

    @Transactional
    public void purge(UUID userId, String id) {
        if (id.startsWith("job_")) {
            Job job = jobRepository.findTrashedByIdAndUserId(id, userId)
                    .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
            purgeJobs(List.of(job.getId()));
            return;
        }
        UUID folderId = parseUuid(id);
        Folder root = folderRepository.findTrashedByIdAndUserId(folderId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.FOLDER_NOT_FOUND));
        LocalDateTime batchAt = root.getDeletedAt();
        List<UUID> folderIds = folderRepository.findByUserIdAndDeletedAt(userId, batchAt).stream()
                .map(Folder::getId).toList();
        List<String> jobIds = jobRepository.findByUserIdAndDeletedAt(userId, batchAt).stream()
                .map(Job::getId).toList();
        if (!jobIds.isEmpty()) {
            purgeJobs(jobIds);
        }
        purgeRepository.deleteFolders(folderIds);
    }

    // FK 순서대로 자식 테이블부터 삭제 후 S3 정리. 스케줄러와 공유
    public void purgeJobs(List<String> jobIds) {
        purgeRepository.deleteRuleTrails(jobIds);
        purgeRepository.deleteTextElements(jobIds);
        purgeRepository.deleteBrailleElements(jobIds);
        purgeRepository.deleteBoundingBoxes(jobIds);
        purgeRepository.deleteQualityCriticalErrors(jobIds);
        purgeRepository.deleteQualityReviewFlags(jobIds);
        purgeRepository.deletePageResults(jobIds);
        purgeRepository.deletePages(jobIds);
        purgeRepository.deleteEditLogs(jobIds);
        purgeRepository.deleteJobs(jobIds);
        for (String jobId : jobIds) {
            try {
                s3Service.deleteByPrefix(jobId + "/");
            } catch (Exception e) {
                // S3 정리 실패가 DB 삭제를 되돌리진 않는다 — 로그만 남기고 진행 (잔여 객체는 수동 정리)
                log.error("S3 정리 실패: jobId={}", jobId, e);
            }
        }
    }

    private UUID parseUuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.FOLDER_NOT_FOUND);
        }
    }
}
