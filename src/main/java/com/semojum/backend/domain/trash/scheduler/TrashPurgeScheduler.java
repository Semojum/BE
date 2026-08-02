package com.semojum.backend.domain.trash.scheduler;

import com.semojum.backend.domain.folder.entity.Folder;
import com.semojum.backend.domain.folder.repository.FolderRepository;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.trash.repository.TrashPurgeRepository;
import com.semojum.backend.domain.trash.service.TrashService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

// 휴지통 30일 경과 항목을 DB·S3까지 완전 삭제하는 안전망. 하루 1회 (새벽 4시 KST 기준 서버 시각)
@Slf4j
@Component
@RequiredArgsConstructor
public class TrashPurgeScheduler {

    private final JobRepository jobRepository;
    private final FolderRepository folderRepository;
    private final TrashPurgeRepository purgeRepository;
    private final TrashService trashService;

    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(TrashService.RETENTION_DAYS);

        List<String> jobIds = jobRepository.findExpired(cutoff).stream().map(Job::getId).toList();
        if (!jobIds.isEmpty()) {
            trashService.purgeJobs(jobIds);
        }
        List<java.util.UUID> folderIds = folderRepository.findExpired(cutoff).stream().map(Folder::getId).toList();
        if (!folderIds.isEmpty()) {
            purgeRepository.deleteFolders(folderIds);
        }
        if (!jobIds.isEmpty() || !folderIds.isEmpty()) {
            log.info("휴지통 완전 삭제: jobs={}, folders={}", jobIds.size(), folderIds.size());
        }
    }
}
