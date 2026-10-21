package com.semojum.backend.domain.job.scheduler;

import com.semojum.backend.domain.billing.service.CreditBalanceService;
import com.semojum.backend.domain.job.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 잔여 크레딧에 따른 변환 관문 (2026-10-21 회의 결정).
 *
 * <ul>
 *   <li><b>잔여 10 미만이면 한 쪽씩</b> — 그 기관 쪽이 AI에 하나라도 들어가 있으면 다음 쪽을 꺼내지 않는다.
 *       크레딧은 결과를 받은 뒤에 빠지므로, 동시에 보낸 쪽은 모두 같은 잔액을 보고 통과한다.
 *       한 쪽씩이어야 넘치는 양이 마지막 한 쪽(최대 5)으로 묶인다</li>
 *   <li><b>기관 전체 기준</b> — 크레딧은 기관이 함께 쓴다. 사람마다 한 쪽이면 같은 기관 사람 수만큼
 *       동시에 들어가 그만큼 더 넘친다</li>
 *   <li><b>잔여 1 이상이면 보낸다</b> — 그 쪽이 잔여보다 많이 써도 끝까지 처리한다(2 남았는데 5를 쓰면 −3).
 *       0 이하가 되면 그 뒤 쪽은 AI에 보내지 않고 워커가 「크레딧 부족」으로 막는다</li>
 *   <li>무소속 계정(운영자 등)은 제한하지 않는다</li>
 * </ul>
 *
 * <p>⚠️ 잔여 10 이상일 땐 종전처럼 슬롯 수만큼 동시에 보낸다(회의 규칙 그대로) — 10 언저리에서 여러 쪽이
 * 함께 들어가 있으면 한 쪽 분량보다 더 넘칠 수 있다.
 *
 * <p>AI에 들어가 있는 쪽은 메모리에 센다 — BE 단일 인스턴스 전제(스케줄러와 같음). 재시작하면 0부터
 * 다시 세는데, 재시작 때 처리 중이던 쪽은 어차피 끊긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreditGate {

    /** 이 미만이면 기관 전체에서 한 번에 한 쪽만 AI에 보낸다 */
    static final long SERIAL_BELOW = 10;

    private final CreditBalanceService creditBalanceService;
    private final JobRepository jobRepository;

    /** jobId → 주인의 기관(무소속이면 empty). 작업 주인은 바뀌지 않는다 */
    private final Map<String, Optional<UUID>> orgOfJob = new ConcurrentHashMap<>();
    /** 꺼내 간 태스크 → 기관. 워커가 끝내면 지운다 */
    private final Map<String, UUID> inFlight = new ConcurrentHashMap<>();

    /** 이 작업의 쪽을 지금 꺼내도 되는가 — 스케줄러가 큐에서 꺼내기 직전에 묻는다 */
    public boolean mayDispatch(String jobId) {
        UUID org = orgOf(jobId);
        if (org == null) return true;
        long remaining = creditBalanceService.remaining(org);
        // 0 이하: 꺼내 준다 — 워커가 AI에 보내지 않고 바로 「크레딧 부족」으로 막는다(대기만 길어지지 않게)
        if (remaining <= 0 || remaining >= SERIAL_BELOW) return true;
        return inFlightCount(org) == 0;
    }

    /** 이 작업은 더 보낼 크레딧이 없다 — 워커가 AI 요청 직전에 묻는다 */
    public boolean exhausted(String jobId) {
        UUID org = orgOf(jobId);
        return org != null && creditBalanceService.remaining(org) <= 0;
    }

    /** 스케줄러가 태스크를 꺼내 줬다 */
    public void acquire(String task, String jobId) {
        UUID org = orgOf(jobId);
        if (org != null) inFlight.put(task, org);
    }

    /** 워커가 그 태스크를 끝냈다(성공 · 재시도 등록 · 차단 · 폐기 전부) */
    public void release(String task) {
        if (task != null) inFlight.remove(task);
    }

    int inFlightCount(UUID org) {
        int n = 0;
        for (UUID o : inFlight.values()) if (Objects.equals(o, org)) n++;
        return n;
    }

    private UUID orgOf(String jobId) {
        if (orgOfJob.size() > 10_000) orgOfJob.clear();   // 끝난 작업이 쌓이지 않게 — 다시 읽으면 된다
        return orgOfJob.computeIfAbsent(jobId,
                id -> Optional.ofNullable(jobRepository.findOrganizationIdById(id))).orElse(null);
    }
}
