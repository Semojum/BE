# 작업 지시

아래 파일들을 생성 또는 수정해줘. 기존 파일 수정 시 명시된 내용만 변경하고 나머지는 그대로 유지해줘.

---

## 1. 기존 파일 수정: Job.java
경로: `src/main/java/com/semojum/backend/domain/job/entity/Job.java`

아래 두 메서드를 클래스 하단에 추가해줘:

```java
public void updateStatus(String status) {
    this.status = status;
}

public void complete(int[] failedPages) {
    this.status = "COMPLETED";
    this.failedPages = failedPages;
    this.finishedAt = LocalDateTime.now();
}
```

---

## 2. 기존 파일 수정: Page.java
경로: `src/main/java/com/semojum/backend/domain/job/entity/Page.java`

아래 메서드를 클래스 하단에 추가해줘:

```java
public void updateStatus(String status) {
    this.status = status;
}
```

---

## 3. 기존 파일 수정: PageRepository.java
경로: `src/main/java/com/semojum/backend/domain/job/repository/PageRepository.java`

기존 내용에 아래 메서드 3개를 추가해줘:

```java
Optional<Page> findByJobAndPageNo(Job job, int pageNo);
long countByJobAndStatusIn(Job job, List<String> statuses);
List<Page> findByJobAndStatus(Job job, String status);
```

import도 추가:
```java
import com.semojum.backend.domain.job.entity.Job;
import java.util.List;
import java.util.Optional;
```

---

## 4. 신규 파일: PageResult.java
경로: `src/main/java/com/semojum/backend/domain/result/entity/PageResult.java`

```java
package com.semojum.backend.domain.result.entity;

import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "page_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PageResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_id", nullable = false)
    private Page page;

    @Column(nullable = false)
    private int pageNumber;

    @Column(nullable = false)
    private String mode;

    @Column(nullable = false)
    private String status;

    private Integer imageWidth;
    private Integer imageHeight;
    private Double ocrConfidenceAvg;
    private Double lineOverflowRate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String rawResponse;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public PageResult(Job job, Page page, int pageNumber, String mode, String status,
                      Integer imageWidth, Integer imageHeight,
                      Double ocrConfidenceAvg, Double lineOverflowRate, String rawResponse) {
        this.job = job;
        this.page = page;
        this.pageNumber = pageNumber;
        this.mode = mode;
        this.status = status;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.ocrConfidenceAvg = ocrConfidenceAvg;
        this.lineOverflowRate = lineOverflowRate;
        this.rawResponse = rawResponse;
        this.createdAt = LocalDateTime.now();
    }
}
```

---

## 5. 신규 파일: TextElement.java
경로: `src/main/java/com/semojum/backend/domain/result/entity/TextElement.java`

```java
package com.semojum.backend.domain.result.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "text_elements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TextElement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_result_id", nullable = false)
    private PageResult pageResult;

    @Column(nullable = false)
    private int elementId;

    private String type;
    private Integer readingOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> originalContents;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> currentContents;

    @Column(nullable = false)
    private boolean isBlocked;

    @Builder
    public TextElement(PageResult pageResult, int elementId, String type,
                       Integer readingOrder, List<String> contents, boolean isBlocked) {
        this.pageResult = pageResult;
        this.elementId = elementId;
        this.type = type;
        this.readingOrder = readingOrder;
        this.originalContents = contents;
        this.currentContents = contents;
        this.isBlocked = isBlocked;
    }
}
```

---

## 6. 신규 파일: BrailleElement.java
경로: `src/main/java/com/semojum/backend/domain/result/entity/BrailleElement.java`

```java
package com.semojum.backend.domain.result.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "braille_elements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BrailleElement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_result_id", nullable = false)
    private PageResult pageResult;

    @Column(nullable = false)
    private int elementId;

    @Column(nullable = false)
    private String type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> originalContent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> currentContent;

    @Column(nullable = false)
    private boolean isBlocked;

    @Builder
    public BrailleElement(PageResult pageResult, int elementId, String type,
                          List<String> content, boolean isBlocked) {
        this.pageResult = pageResult;
        this.elementId = elementId;
        this.type = type;
        this.originalContent = content;
        this.currentContent = content;
        this.isBlocked = isBlocked;
    }
}
```

---

## 7. 신규 파일: BoundingBox.java
경로: `src/main/java/com/semojum/backend/domain/result/entity/BoundingBox.java`

```java
package com.semojum.backend.domain.result.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "bounding_boxes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BoundingBox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_result_id", nullable = false)
    private PageResult pageResult;

    @Column(nullable = false)
    private int elementId;

    @Column(nullable = false)
    private int x;

    @Column(nullable = false)
    private int y;

    @Column(nullable = false)
    private int x2;

    @Column(nullable = false)
    private int y2;

    @Builder
    public BoundingBox(PageResult pageResult, int elementId, int x, int y, int x2, int y2) {
        this.pageResult = pageResult;
        this.elementId = elementId;
        this.x = x;
        this.y = y;
        this.x2 = x2;
        this.y2 = y2;
    }
}
```

---

## 8. 신규 파일: RuleTrail.java
경로: `src/main/java/com/semojum/backend/domain/result/entity/RuleTrail.java`

```java
package com.semojum.backend.domain.result.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "rule_trails")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RuleTrail {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    // 다형성 FK: text_elements.id 또는 braille_elements.id
    @Column(nullable = false, columnDefinition = "uuid")
    private UUID elementId;

    // "TEXT" or "BRAILLE"
    @Column(nullable = false)
    private String elementType;

    @Column(nullable = false)
    private String ruleId;

    @Column(nullable = false)
    private String section;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String excerpt;

    @Builder
    public RuleTrail(UUID elementId, String elementType, String ruleId,
                     String section, String title, String excerpt) {
        this.elementId = elementId;
        this.elementType = elementType;
        this.ruleId = ruleId;
        this.section = section;
        this.title = title;
        this.excerpt = excerpt;
    }
}
```

---

## 9. 신규 파일: QualityCriticalError.java
경로: `src/main/java/com/semojum/backend/domain/result/entity/QualityCriticalError.java`

```java
package com.semojum.backend.domain.result.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "quality_critical_errors")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QualityCriticalError {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_result_id", nullable = false)
    private PageResult pageResult;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private int elementId;

    @Column(nullable = false)
    private String message;

    @Builder
    public QualityCriticalError(PageResult pageResult, String type, int elementId, String message) {
        this.pageResult = pageResult;
        this.type = type;
        this.elementId = elementId;
        this.message = message;
    }
}
```

---

## 10. 신규 파일: QualityReviewFlag.java
경로: `src/main/java/com/semojum/backend/domain/result/entity/QualityReviewFlag.java`

```java
package com.semojum.backend.domain.result.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "quality_review_flags")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QualityReviewFlag {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_result_id", nullable = false)
    private PageResult pageResult;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private int elementId;

    @Column(nullable = false)
    private String message;

    @Builder
    public QualityReviewFlag(PageResult pageResult, String type, int elementId, String message) {
        this.pageResult = pageResult;
        this.type = type;
        this.elementId = elementId;
        this.message = message;
    }
}
```

---

## 11. 신규 파일: PageResultRepository.java
경로: `src/main/java/com/semojum/backend/domain/result/repository/PageResultRepository.java`

```java
package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.PageResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PageResultRepository extends JpaRepository<PageResult, UUID> {}
```

---

## 12. 신규 파일: TextElementRepository.java
경로: `src/main/java/com/semojum/backend/domain/result/repository/TextElementRepository.java`

```java
package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.TextElement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TextElementRepository extends JpaRepository<TextElement, UUID> {}
```

---

## 13. 신규 파일: BrailleElementRepository.java
경로: `src/main/java/com/semojum/backend/domain/result/repository/BrailleElementRepository.java`

```java
package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.BrailleElement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BrailleElementRepository extends JpaRepository<BrailleElement, UUID> {}
```

---

## 14. 신규 파일: BoundingBoxRepository.java
경로: `src/main/java/com/semojum/backend/domain/result/repository/BoundingBoxRepository.java`

```java
package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.BoundingBox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BoundingBoxRepository extends JpaRepository<BoundingBox, UUID> {}
```

---

## 15. 신규 파일: RuleTrailRepository.java
경로: `src/main/java/com/semojum/backend/domain/result/repository/RuleTrailRepository.java`

```java
package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.RuleTrail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RuleTrailRepository extends JpaRepository<RuleTrail, UUID> {}
```

---

## 16. 신규 파일: QualityCriticalErrorRepository.java
경로: `src/main/java/com/semojum/backend/domain/result/repository/QualityCriticalErrorRepository.java`

```java
package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.QualityCriticalError;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface QualityCriticalErrorRepository extends JpaRepository<QualityCriticalError, UUID> {}
```

---

## 17. 신규 파일: QualityReviewFlagRepository.java
경로: `src/main/java/com/semojum/backend/domain/result/repository/QualityReviewFlagRepository.java`

```java
package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.QualityReviewFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface QualityReviewFlagRepository extends JpaRepository<QualityReviewFlag, UUID> {}
```

---

## 18. 신규 파일: ResultService.java
경로: `src/main/java/com/semojum/backend/domain/result/service/ResultService.java`

```java
package com.semojum.backend.domain.result.service;

import com.google.protobuf.util.JsonFormat;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.result.entity.*;
import com.semojum.backend.domain.result.repository.*;
import com.semojum.backend.grpc.BrailleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResultService {

    private final JobRepository jobRepository;
    private final PageRepository pageRepository;
    private final PageResultRepository pageResultRepository;
    private final TextElementRepository textElementRepository;
    private final BrailleElementRepository brailleElementRepository;
    private final BoundingBoxRepository boundingBoxRepository;
    private final RuleTrailRepository ruleTrailRepository;
    private final QualityCriticalErrorRepository qualityCriticalErrorRepository;
    private final QualityReviewFlagRepository qualityReviewFlagRepository;

    @Transactional
    public void save(BrailleResponse response) {
        String jobId = response.getJobId();
        int pageNumber = response.getPageNumber();
        String status = response.getStatus();

        // Job, Page 조회
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
        Page page = pageRepository.findByJobAndPageNo(job, pageNumber)
                .orElseThrow(() -> new RuntimeException("Page not found: " + jobId + ", pageNo=" + pageNumber));

        // raw_response 직렬화
        String rawResponse;
        try {
            rawResponse = JsonFormat.printer().print(response);
        } catch (Exception e) {
            rawResponse = "{}";
            log.warn("BrailleResponse JSON 직렬화 실패: {}", e.getMessage());
        }

        // PageResult 저장
        PageResult pageResult = PageResult.builder()
                .job(job)
                .page(page)
                .pageNumber(pageNumber)
                .mode(job.getMode())
                .status(status)
                .imageWidth(response.getImageWidth() > 0 ? response.getImageWidth() : null)
                .imageHeight(response.getImageHeight() > 0 ? response.getImageHeight() : null)
                .ocrConfidenceAvg(response.hasQualityReport() ? (double) response.getQualityReport().getOcrConfidenceAvg() : null)
                .lineOverflowRate(response.hasQualityReport() ? (double) response.getQualityReport().getLineOverflowRate() : null)
                .rawResponse(rawResponse)
                .build();
        pageResultRepository.save(pageResult);

        // TextElement 저장 (text_list)
        for (com.semojum.backend.grpc.TextElement protoText : response.getTextListList()) {
            TextElement textElement = TextElement.builder()
                    .pageResult(pageResult)
                    .elementId(parseId(protoText.getId()))
                    .type(protoText.getType().isEmpty() ? null : protoText.getType())
                    .readingOrder(protoText.getOrder() > 0 ? protoText.getOrder() : null)
                    .contents(new ArrayList<>(protoText.getContentsList()))
                    .isBlocked(protoText.getIsBlocked())
                    .build();
            textElementRepository.save(textElement);

            // RuleTrail 저장
            for (com.semojum.backend.grpc.RuleTrail protoRule : protoText.getRuleTrailList()) {
                RuleTrail ruleTrail = RuleTrail.builder()
                        .elementId(textElement.getId())
                        .elementType("TEXT")
                        .ruleId(protoRule.getRuleId())
                        .section(protoRule.getSection())
                        .title(protoRule.getTitle())
                        .excerpt(protoRule.getExcerpt())
                        .build();
                ruleTrailRepository.save(ruleTrail);
            }
        }

        // BrailleElement 저장 (braille_text_list)
        for (com.semojum.backend.grpc.TextElement protoBraille : response.getBrailleTextListList()) {
            BrailleElement brailleElement = BrailleElement.builder()
                    .pageResult(pageResult)
                    .elementId(parseId(protoBraille.getId()))
                    .type(protoBraille.getType().isEmpty() ? "text" : protoBraille.getType())
                    .content(new ArrayList<>(protoBraille.getContentsList()))
                    .isBlocked(protoBraille.getIsBlocked())
                    .build();
            brailleElementRepository.save(brailleElement);

            // RuleTrail 저장
            for (com.semojum.backend.grpc.RuleTrail protoRule : protoBraille.getRuleTrailList()) {
                RuleTrail ruleTrail = RuleTrail.builder()
                        .elementId(brailleElement.getId())
                        .elementType("BRAILLE")
                        .ruleId(protoRule.getRuleId())
                        .section(protoRule.getSection())
                        .title(protoRule.getTitle())
                        .excerpt(protoRule.getExcerpt())
                        .build();
                ruleTrailRepository.save(ruleTrail);
            }
        }

        // BoundingBox 저장 (mode a, c)
        for (com.semojum.backend.grpc.BoundingBox protoBbox : response.getBoundingBoxListList()) {
            BoundingBox boundingBox = BoundingBox.builder()
                    .pageResult(pageResult)
                    .elementId(parseId(protoBbox.getId()))
                    .x(protoBbox.getX())
                    .y(protoBbox.getY())
                    .x2(protoBbox.getX2())
                    .y2(protoBbox.getY2())
                    .build();
            boundingBoxRepository.save(boundingBox);
        }

        // QualityCriticalError 저장
        if (response.hasQualityReport()) {
            for (com.semojum.backend.grpc.CriticalError protoError : response.getQualityReport().getCriticalErrorsList()) {
                QualityCriticalError error = QualityCriticalError.builder()
                        .pageResult(pageResult)
                        .type(protoError.getType())
                        .elementId(parseId(protoError.getElementId()))
                        .message(protoError.getMessage())
                        .build();
                qualityCriticalErrorRepository.save(error);
            }

            // QualityReviewFlag 저장
            for (com.semojum.backend.grpc.ReviewFlag protoFlag : response.getQualityReport().getReviewFlagsList()) {
                QualityReviewFlag flag = QualityReviewFlag.builder()
                        .pageResult(pageResult)
                        .type(protoFlag.getType())
                        .elementId(parseId(protoFlag.getElementId()))
                        .message(protoFlag.getMessage())
                        .build();
                qualityReviewFlagRepository.save(flag);
            }
        }

        // Page 상태 업데이트
        page.updateStatus(status);
        pageRepository.save(page);

        // Job 완료 여부 확인
        long doneCount = pageRepository.countByJobAndStatusIn(job,
                List.of("COMPLETED", "NEEDS_REVIEW", "BLOCKED"));
        if (doneCount == job.getTotalPages()) {
            List<Page> blockedPages = pageRepository.findByJobAndStatus(job, "BLOCKED");
            int[] failedPageNos = blockedPages.stream()
                    .mapToInt(Page::getPageNo)
                    .toArray();
            job.complete(failedPageNos);
            jobRepository.save(job);
            log.info("Job 완료: jobId={}, failedPages={}", jobId, failedPageNos.length);
        } else {
            job.updateStatus("IN_PROGRESS");
            jobRepository.save(job);
        }

        log.info("ResultService 저장 완료: jobId={}, pageNo={}, status={}", jobId, pageNumber, status);
    }

    private int parseId(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
```

---

## 19. 기존 파일 수정: PageWorker.java
경로: `src/main/java/com/semojum/backend/domain/job/worker/PageWorker.java`

아래 전체 코드로 교체해줘:

```java
package com.semojum.backend.domain.job.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.semojum.backend.domain.result.service.ResultService;
import com.semojum.backend.global.gcs.GcsService;
import com.semojum.backend.global.grpc.BrailleGrpcClient;
import com.semojum.backend.grpc.BrailleRequest;
import com.semojum.backend.grpc.BrailleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class PageWorker {

    private final RedisTemplate<String, String> redisTemplate;
    private final GcsService gcsService;
    private final BrailleGrpcClient grpcClient;
    private final ObjectMapper objectMapper;
    private final ResultService resultService;

    private static final String TASK_QUEUE = "task_queue";
    private static final int WORKER_COUNT = 6;

    // 워커 실행 여부 플래그 (volatile: 멀티스레드 환경에서 즉시 반영)
    private volatile boolean running = true;
    private ExecutorService executor;

    // 애플리케이션 시작 시 워커 스레드 실행
    @PostConstruct
    public void startWorkers() {
        executor = Executors.newFixedThreadPool(WORKER_COUNT);
        for (int i = 0; i < WORKER_COUNT; i++) {
            final int workerId = i + 1;
            executor.submit(() -> runWorker(workerId));
        }
        log.info("PageWorker {}개 시작", WORKER_COUNT);
    }

    // 애플리케이션 종료 시 워커 스레드 정리
    @PreDestroy
    public void stopWorkers() {
        log.info("PageWorker 종료 중...");
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
        log.info("PageWorker 종료 완료");
    }

    private void runWorker(int workerId) {
        log.info("Worker-{} 시작", workerId);
        while (running) {
            try {
                // task_queue에서 작업 꺼내기
                String task = redisTemplate.opsForList().rightPop(TASK_QUEUE);
                if (task == null) {
                    Thread.sleep(100);
                    continue;
                }

                // 작업 파싱
                Map<String, Object> taskMap = objectMapper.readValue(task, Map.class);
                String jobId = (String) taskMap.get("jobId");
                int pageNo = (int) taskMap.get("pageNo");
                String gcsPath = (String) taskMap.get("gcsPath");
                String mode = (String) taskMap.get("mode");
                int totalPages = (int) taskMap.get("totalPages");

                log.info("Worker-{} 작업 시작: jobId={}, pageNo={}", workerId, jobId, pageNo);

                // Redis 상태 → RUNNING
                redisTemplate.opsForHash().put("job:" + jobId + ":pages", "page:" + pageNo, "RUNNING");

                // GCS에서 파일 다운로드
                byte[] fileData = gcsService.downloadFile(gcsPath);

                // gRPC 요청 빌드
                BrailleRequest.Builder requestBuilder = BrailleRequest.newBuilder()
                        .setJobId(jobId)
                        .setPageNo(pageNo)
                        .setTotalPages(totalPages)
                        .setMode(mode);

                if (mode.equals("b")) {
                    // mode b: 텍스트로 전송
                    String sourceText = new String(fileData);
                    requestBuilder.setSourceText(sourceText);
                } else {
                    // mode a, c: PDF 바이너리로 전송
                    requestBuilder.setPdfData(com.google.protobuf.ByteString.copyFrom(fileData));
                }

                // AI 서버에 gRPC 요청
                BrailleResponse response = grpcClient.processPage(requestBuilder.build());

                // DB에 결과 저장
                resultService.save(response);

                // Redis 상태 → COMPLETED or NEEDS_REVIEW or BLOCKED
                redisTemplate.opsForHash().put("job:" + jobId + ":pages", "page:" + pageNo, response.getStatus());

                log.info("Worker-{} 작업 완료: jobId={}, pageNo={}, status={}", workerId, jobId, pageNo, response.getStatus());

            } catch (Exception e) {
                if (running) {
                    log.error("Worker-{} 오류 발생: {}", workerId, e.getMessage());
                }
            }
        }
        log.info("Worker-{} 종료", workerId);
    }
}
```
