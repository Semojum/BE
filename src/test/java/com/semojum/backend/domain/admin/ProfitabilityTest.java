package com.semojum.backend.domain.admin;

import com.semojum.backend.domain.admin.repository.AdminStatsRepository;
import com.semojum.backend.domain.admin.service.AdminStatsService;
import com.semojum.backend.domain.billing.entity.PricingConfig;
import com.semojum.backend.domain.billing.repository.CreditTransactionRepository;
import com.semojum.backend.domain.billing.repository.PricingConfigRepository;
import com.semojum.backend.domain.org.entity.Organization;
import com.semojum.backend.domain.org.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

// T1-2 기관별 수익성 — 환산 매출·차액 계산과 정렬 (저장소 mock)
class ProfitabilityTest {

    private AdminStatsRepository statsRepository;
    private CreditTransactionRepository creditTransactionRepository;
    private OrganizationRepository organizationRepository;
    private PricingConfigRepository pricingConfigRepository;
    private AdminStatsService service;

    private final UUID orgPaid = UUID.randomUUID();
    private final UUID orgTrial = UUID.randomUUID();

    private static void setId(Object entity, Object value) throws Exception {
        var f = entity.getClass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, value);
    }

    // contractType은 insertable=false(DB default) — 테스트에선 리플렉션으로 주입
    private static void setContractType(Organization org, String type) throws Exception {
        var f = Organization.class.getDeclaredField("contractType");
        f.setAccessible(true);
        f.set(org, type);
    }

    @BeforeEach
    void setUp() throws Exception {
        statsRepository = Mockito.mock(AdminStatsRepository.class);
        creditTransactionRepository = Mockito.mock(CreditTransactionRepository.class);
        organizationRepository = Mockito.mock(OrganizationRepository.class);
        pricingConfigRepository = Mockito.mock(PricingConfigRepository.class);
        service = new AdminStatsService(statsRepository, creditTransactionRepository,
                organizationRepository, pricingConfigRepository);

        PricingConfig pc = PricingConfig.builder()
                .config(Map.of("creditPricesByContract",
                        Map.of("BASIC", 200, "STANDARD", 150, "PREMIUM", 120, "FREE", 0, "COUPON", 0)))
                .note("test").build();
        when(pricingConfigRepository.findTopByOrderByIdDesc()).thenReturn(Optional.of(pc));

        Organization paid = Organization.builder().name("유료기관").code("paid1").build();
        setId(paid, orgPaid);
        setContractType(paid, "BASIC");
        Organization trial = Organization.builder().name("체험기관").code("trial1").build();
        setId(trial, orgTrial);
        setContractType(trial, "COUPON");
        when(organizationRepository.findAll()).thenReturn(List.of(paid, trial));
    }

    @Test
    void 유형별_단가로_차액_계산과_밑지는_기관_정렬() {
        // BASIC: 4,600크레딧 × 200 = 920,000 − 원가 147,200 = +772,800
        // COUPON: 단가 0 → 매출 0 − 원가 77,080 = −77,080 (원가만큼 마이너스 — 기획 예시)
        when(creditTransactionRepository.sumPerOrganizationBetween(any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{orgPaid, 4600L}));
        when(statsRepository.orgCostSums(any(), any())).thenReturn(List.<Object[]>of(
                new Object[]{orgPaid, new BigDecimal("147200.000"), false},
                new Object[]{orgTrial, new BigDecimal("77080.000"), false}));

        var result = service.getProfitability("2026-08");

        assertEquals(200L, result.creditPricesByContract().get("BASIC"));
        assertEquals(2, result.items().size());
        var first = result.items().get(0);   // 차액 큰 순 — 유료기관 먼저
        assertEquals("유료기관", first.orgName());
        assertEquals(200L, first.appliedPriceKrw());
        assertEquals(0, new BigDecimal("920000").compareTo(first.revenueKrw()));
        assertEquals(0, new BigDecimal("772800.000").compareTo(first.marginKrw()));
        var second = result.items().get(1);
        assertEquals("체험기관", second.orgName());
        assertEquals(0L, second.appliedPriceKrw());
        assertEquals(0, new BigDecimal("-77080.000").compareTo(second.marginKrw()));
        // 합계
        assertEquals(4600L, result.totals().creditsUsed());
        assertEquals(0, new BigDecimal("695720.000").compareTo(result.totals().marginKrw()));
    }

    @Test
    void 단가_미설정이면_매출_0으로_계산() {
        PricingConfig noPrice = PricingConfig.builder().config(Map.of()).note("t").build();
        when(pricingConfigRepository.findTopByOrderByIdDesc()).thenReturn(Optional.of(noPrice));
        when(creditTransactionRepository.sumPerOrganizationBetween(any(), any()))
                .thenReturn(List.<Object[]>of(new Object[]{orgPaid, 100L}));
        when(statsRepository.orgCostSums(any(), any())).thenReturn(List.of());

        var result = service.getProfitability(null);
        assertTrue(result.creditPricesByContract().isEmpty());
        assertEquals(0L, result.items().get(0).appliedPriceKrw());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.items().get(0).revenueKrw()));
    }
}
