package com.semojum.backend.domain.user;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.service.JobProgressReader;
import com.semojum.backend.domain.org.entity.Organization;
import com.semojum.backend.domain.user.dto.UsageDto;
import com.semojum.backend.domain.user.service.UsageService;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 마이페이지 [사용량] (피그마 마이페이지 · 사용량 · 2026-10-14 결정).
 *
 * <p>단위는 차감량(크레딧), 범위는 내 사용량, 기간은 오늘 기준. 상단 비율은 내 사용량 ÷ 기관 할당 총량.
 */
class DailyUsageTest {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    UserRepository userRepository;
    CreditTransactionRepository creditRepository;
    JobRepository jobRepository;
    UsageService service;
    User user;
    Organization org;
    List<Object[]> rows;
    LocalDate today = LocalDate.now(KST);

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        creditRepository = mock(CreditTransactionRepository.class);
        jobRepository = mock(JobRepository.class);
        service = new UsageService(userRepository, creditRepository, jobRepository, mock(JobProgressReader.class));

        org = Organization.builder().name("기관").code("org").build();
        ReflectionTestUtils.setField(org, "id", UUID.randomUUID());
        org.allocateCredit(2000);
        user = User.builder().loginId("testorg01").organization(org).password("pw").build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        rows = new ArrayList<>();
        when(creditRepository.findUsageRowsByUserBetween(any(), any(), any())).thenAnswer(inv -> rows);
        when(creditRepository.sumContractByOrganization(org.getId())).thenReturn(582L);
    }

    private Job job(String id, String mode, String fileName, boolean adminCopy) {
        Job j = Job.builder().id(id).mode(mode).totalPages(1).originalFileName(fileName).adminCopy(adminCopy).build();
        return j;
    }

    private void jobs(Job... js) {
        when(jobRepository.findAllById(any())).thenReturn(List.of(js));
    }

    private void charge(LocalDate day, int amount, String jobId) {
        rows.add(new Object[]{day.atTime(10, 0).atZone(KST).toInstant(), amount, jobId});
    }

    @Test
    void 쓰지_않은_날도_0으로_채워_오늘까지_보낸다() {
        jobs(job("j1", "c", "교재.pdf", false));
        charge(today, 18, "j1");

        UsageDto.Daily d = service.getDaily(user.getId().toString(), "MONTH");

        assertEquals(today.withDayOfMonth(1), d.from());
        assertEquals(today, d.to());
        assertEquals(today.getDayOfMonth(), d.daily().size(), "1일부터 오늘까지 하루도 빠지지 않는다");
        assertEquals(18, d.daily().get(d.daily().size() - 1).credits());
        assertEquals(18, d.usedCredits());
    }

    /** 오늘 기준 — 이 달 1일 · 3개월 전 같은 날 · 1월 1일 (2026-10-14 결정) */
    @Test
    void 기간은_오늘을_기준으로_한다() {
        LocalDate oct14 = LocalDate.of(2026, 10, 14);
        assertEquals(LocalDate.of(2026, 10, 1), UsageService.periodStart("MONTH", oct14));
        assertEquals(LocalDate.of(2026, 7, 14), UsageService.periodStart("QUARTER", oct14));
        assertEquals(LocalDate.of(2026, 1, 1), UsageService.periodStart("YEAR", oct14));
    }

    @Test
    void 입력_종류별로_나눈다_텍스트는_0이어도_줄이_있다() {
        jobs(job("pdf", "c", "교재.pdf", false), job("hwp", "c", "시험.hwp", false),
                job("img", "c", "사진1.JPG", false), job("old", "a", "옛날.pdf", false));
        charge(today, 10, "pdf");
        charge(today, 5, "hwp");
        charge(today, 7, "img");
        charge(today, 3, "old");

        Map<String, Long> by = new LinkedHashMap<>();
        service.getDaily(user.getId().toString(), null).bySource().forEach(s -> by.put(s.source(), s.credits()));

        assertEquals(List.of("PDF_HWP", "IMAGE", "TEXT"), List.copyOf(by.keySet()));
        assertEquals(18, by.get("PDF_HWP"));
        assertEquals(7, by.get("IMAGE"));
        assertEquals(0, by.get("TEXT"), "텍스트 작업은 차감이 없지만 줄은 보낸다");
    }

    /** 상단 카드 — 남은 쪽 1,418 / 전체 2,000 · 사용 582 (29.1%) */
    @Test
    void 비율은_내_사용량을_기관_할당_총량으로_나눈다() {
        jobs(job("j1", "c", "교재.pdf", false));
        charge(today, 582, "j1");

        UsageDto.Daily d = service.getDaily(user.getId().toString(), "MONTH");

        assertEquals(2000L, d.orgAllocated());
        assertEquals(1418L, d.orgRemaining());
        assertEquals(582, d.usedCredits());
        assertEquals(29.1, d.usedPercent());
    }

    @Test
    void 운영자_사본은_세지_않는다() {
        jobs(job("copy", "c", "교재.pdf", true), job("mine", "c", "교재.pdf", false));
        charge(today, 50, "copy");
        charge(today, 4, "mine");

        assertEquals(4, service.getDaily(user.getId().toString(), "MONTH").usedCredits());
    }

    @Test
    void 무소속이면_기관_칸과_비율이_null이다() {
        ReflectionTestUtils.setField(user, "organization", null);
        jobs();

        UsageDto.Daily d = service.getDaily(user.getId().toString(), "YEAR");
        assertNull(d.orgAllocated());
        assertNull(d.usedPercent());
    }

    @Test
    void 모르는_기간은_400이다() {
        CustomException e = assertThrows(CustomException.class,
                () -> service.getDaily(user.getId().toString(), "WEEK"));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }
}
