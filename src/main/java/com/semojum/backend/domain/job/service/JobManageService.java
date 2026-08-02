package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.folder.repository.FolderRepository;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// V3 마이페이지 작업 관리: 이름 변경 · 일괄 이동 · 일괄 삭제(휴지통).
// 벌크 연산은 전체 트랜잭션 — 하나라도 실패하면 전체 롤백 (명세 확정)
@Service
@RequiredArgsConstructor
public class JobManageService {

    private static final int MAX_WORK_NAME_LENGTH = 100;

    private final JobRepository jobRepository;
    private final FolderRepository folderRepository;

    @Transactional
    public Job rename(UUID userId, String jobId, String workName) {
        String name = normalize(workName);
        Job job = jobRepository.findActiveByIdAndUserId(jobId, userId)
                .orElseThrow(() -> new CustomException(ErrorCode.JOB_NOT_FOUND));
        if (job.isInProgress()) {
            throw new CustomException(ErrorCode.JOB_IN_PROGRESS);
        }
        job.rename(name);
        return job;
    }

    @Transactional
    public int moveAll(UUID userId, List<String> jobIds, UUID targetFolderId) {
        List<Job> jobs = loadAndValidate(userId, jobIds);
        if (targetFolderId != null
                && folderRepository.findActiveByIdAndUserId(targetFolderId, userId).isEmpty()) {
            throw new CustomException(ErrorCode.FOLDER_NOT_FOUND);
        }
        for (Job job : jobs) {
            job.moveToFolder(targetFolderId);
        }
        return jobs.size();
    }

    @Transactional
    public int trashAll(UUID userId, List<String> jobIds) {
        List<Job> jobs = loadAndValidate(userId, jobIds);
        LocalDateTime batchAt = LocalDateTime.now();
        for (Job job : jobs) {
            job.moveToTrash(batchAt);
        }
        return jobs.size();
    }

    // 존재·소유·변환 중 검증. 하나라도 걸리면 예외 → 전체 롤백
    private List<Job> loadAndValidate(UUID userId, List<String> jobIds) {
        if (jobIds == null || jobIds.isEmpty()) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        List<Job> jobs = jobRepository.findActiveByIdsAndUserId(jobIds, userId);
        if (jobs.size() != jobIds.size()) {
            throw new CustomException(ErrorCode.JOB_NOT_FOUND);
        }
        if (jobs.stream().anyMatch(Job::isInProgress)) {
            throw new CustomException(ErrorCode.JOB_IN_PROGRESS);
        }
        return jobs;
    }

    private String normalize(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_WORK_NAME_LENGTH) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        return trimmed;
    }
}
