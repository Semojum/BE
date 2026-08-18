package com.semojum.backend.domain.support;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.enums.Role;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.org.entity.Organization;
import com.semojum.backend.domain.org.repository.OrganizationRepository;
import com.semojum.backend.domain.support.dto.SupportDto;
import com.semojum.backend.domain.support.entity.Inquiry;
import com.semojum.backend.domain.support.repository.InquiryRepository;
import com.semojum.backend.domain.support.repository.NoticeRepository;
import com.semojum.backend.domain.support.repository.OrderRepository;
import com.semojum.backend.domain.support.service.AdminSupportService;
import com.semojum.backend.domain.support.service.OrgSupportService;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// 문의·공지·주문의 검증 규칙 (저장소 mock)
class SupportServiceTest {

    private UserRepository userRepository;
    private InquiryRepository inquiryRepository;
    private NoticeRepository noticeRepository;
    private OrganizationRepository organizationRepository;
    private OrgSupportService orgService;
    private AdminSupportService adminService;

    private Organization orgA;
    private Organization orgB;
    private User orgAdmin;

    private static void setId(Object entity, Object value) throws Exception {
        var f = entity.getClass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, value);
    }

    @BeforeEach
    void setUp() throws Exception {
        userRepository = Mockito.mock(UserRepository.class);
        inquiryRepository = Mockito.mock(InquiryRepository.class);
        noticeRepository = Mockito.mock(NoticeRepository.class);
        organizationRepository = Mockito.mock(OrganizationRepository.class);
        OrderRepository orderRepository = Mockito.mock(OrderRepository.class);

        orgService = new OrgSupportService(userRepository, noticeRepository, orderRepository, inquiryRepository);
        adminService = new AdminSupportService(noticeRepository, inquiryRepository, orderRepository,
                organizationRepository, userRepository);

        orgA = Organization.builder().name("기관A").code("orga").build();
        setId(orgA, UUID.randomUUID());
        orgB = Organization.builder().name("기관B").code("orgb").build();
        setId(orgB, UUID.randomUUID());

        orgAdmin = User.builder().loginId("orga01").organization(orgA).password("pw").build();
        orgAdmin.changeRole(Role.ROLE_ORG_ADMIN);
        setId(orgAdmin, UUID.randomUUID());
        when(userRepository.findById(orgAdmin.getId())).thenReturn(Optional.of(orgAdmin));
        // save가 넘긴 엔티티를 그대로 반환 (id는 미설정이어도 검증에 무관)
        when(inquiryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(inquiryRepository.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void 요청_유형은_크레딧_추가와_계정_발급만() {
        CustomException e = assertThrows(CustomException.class, () ->
                orgService.createRequest(orgAdmin.getId().toString(),
                        new SupportDto.CreateRequest("ERROR_REPORT", "메시지")));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 요청_취소는_자기_기관의_OPEN만() throws Exception {
        // 타 기관 요청 → 403
        Inquiry other = Inquiry.builder().type(Inquiry.TYPE_CREDIT_ADD)
                .organizationId(orgB.getId()).userId(UUID.randomUUID()).message(null).build();
        UUID otherId = UUID.randomUUID();
        setId(other, otherId);
        when(inquiryRepository.findById(otherId)).thenReturn(Optional.of(other));
        CustomException e1 = assertThrows(CustomException.class,
                () -> orgService.cancelRequest(orgAdmin.getId().toString(), otherId));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e1.getErrorCode());

        // 자기 기관이지만 이미 처리 중 → 400
        Inquiry reviewing = Inquiry.builder().type(Inquiry.TYPE_ACCOUNT_ISSUE)
                .organizationId(orgA.getId()).userId(orgAdmin.getId()).message(null).build();
        reviewing.changeStatus(Inquiry.STATUS_IN_REVIEW);
        UUID reviewingId = UUID.randomUUID();
        setId(reviewing, reviewingId);
        when(inquiryRepository.findById(reviewingId)).thenReturn(Optional.of(reviewing));
        CustomException e2 = assertThrows(CustomException.class,
                () -> orgService.cancelRequest(orgAdmin.getId().toString(), reviewingId));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e2.getErrorCode());

        // 자기 기관 + OPEN → 삭제
        Inquiry open = Inquiry.builder().type(Inquiry.TYPE_CREDIT_ADD)
                .organizationId(orgA.getId()).userId(orgAdmin.getId()).message(null).build();
        UUID openId = UUID.randomUUID();
        setId(open, openId);
        when(inquiryRepository.findById(openId)).thenReturn(Optional.of(open));
        orgService.cancelRequest(orgAdmin.getId().toString(), openId);
        verify(inquiryRepository).delete(open);
    }

    @Test
    void 공지_기간_역전은_거절() {
        CustomException e = assertThrows(CustomException.class, () ->
                adminService.createNotice(new SupportDto.CreateNotice(null, "제목", "본문",
                        LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 14))));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 기관_공지는_기관_존재_확인() {
        UUID ghost = UUID.randomUUID();
        when(organizationRepository.findById(ghost)).thenReturn(Optional.empty());
        CustomException e = assertThrows(CustomException.class, () ->
                adminService.createNotice(new SupportDto.CreateNotice(ghost, "제목", "본문",
                        LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 20))));
        assertEquals(ErrorCode.ORG_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void 문의_상태값_검증() {
        CustomException e = assertThrows(CustomException.class,
                () -> adminService.updateInquiryStatus(UUID.randomUUID(), "CANCELED"));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 주문_갱신은_변경_항목이_하나는_있어야() {
        CustomException e = assertThrows(CustomException.class,
                () -> adminService.updateOrder(UUID.randomUUID(), new SupportDto.UpdateOrder(null, null)));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }
}
