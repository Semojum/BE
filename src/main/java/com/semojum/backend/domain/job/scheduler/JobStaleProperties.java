package com.semojum.backend.domain.job.scheduler;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

// stale-job 타임아웃 설정 (application.yaml: job.stale.*). 하드코딩 금지.
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "job.stale")
public class JobStaleProperties {

    // IN_PROGRESS 무진행 타임아웃 (기본 1시간)
    private Duration inProgressTimeout = Duration.ofHours(1);

    // PENDING 고아 타임아웃 (기본 12시간)
    private Duration pendingTimeout = Duration.ofHours(12);
}
