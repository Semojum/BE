package com.semojum.backend.domain.support;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.enums.Role;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.org.entity.Organization;
import com.semojum.backend.domain.org.repository.OrganizationRepository;
import com.semojum.backend.domain.support.dto.SupportDto;
import com.semojum.backend.domain.support.entity.Inquiry;
import com.semojum.backend.domain.support.entity.Notice;
import com.semojum.backend.domain.support.entity.Order;
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
    private OrderRepository orderRepository;
    private com.semojum.backend.global.s3.S3Service s3Service;

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
        this.orderRepository = orderRepository;
        s3Service = Mockito.mock(com.semojum.backend.global.s3.S3Service.class);

        orgService = new OrgSupportService(userRepository, noticeRepository, orderRepository, inquiryRepository, s3Service);
        adminService = new AdminSupportService(noticeRepository, inquiryRepository, orderRepository,
                organizationRepository, userRepository, s3Service);

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

    // ── 증빙 파일 (V25) ──
    private Order receiptOrder(Organization owner) throws Exception {
        Order order = Order.builder().organizationId(owner.getId()).orderDate(java.time.LocalDate.now())
                .description("연간 계약").amountKrw(1000).build();
        setId(order, UUID.randomUUID());
        Mockito.when(orderRepository.findById(order.getId())).thenReturn(java.util.Optional.of(order));
        return order;
    }

    @Test
    void 증빙_업로드_교체_후_파일명이_남는다() throws Exception {
        Order order = receiptOrder(orgA);
        Mockito.when(organizationRepository.findById(orgA.getId())).thenReturn(java.util.Optional.of(orgA));
        var file = new org.springframework.mock.web.MockMultipartFile("file", "계산서_2월.pdf",
                "application/pdf", new byte[]{1, 2});
        SupportDto.OrderItem item = adminService.uploadOrderReceipt(order.getId(), file);
        assertEquals("계산서_2월.pdf", item.receiptFileName());
        Mockito.verify(s3Service).deleteByPrefix("receipts/" + order.getId() + "/");
        Mockito.verify(s3Service).uploadFile(Mockito.eq("receipts/" + order.getId() + "/receipt.pdf"),
                Mockito.any(), Mockito.eq("application/pdf"));
    }

    @Test
    void 증빙_허용_외_형식은_거절() throws Exception {
        Order order = receiptOrder(orgA);
        var file = new org.springframework.mock.web.MockMultipartFile("file", "virus.exe",
                "application/octet-stream", new byte[]{1});
        CustomException e = assertThrows(CustomException.class,
                () -> adminService.uploadOrderReceipt(order.getId(), file));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    @Test
    void 증빙_타_기관_주문은_403() throws Exception {
        Order order = receiptOrder(orgB);   // orgAdmin은 orgA 소속
        Mockito.when(userRepository.findById(orgAdmin.getId())).thenReturn(java.util.Optional.of(orgAdmin));
        CustomException e = assertThrows(CustomException.class,
                () -> orgService.getOrderReceipt(orgAdmin.getId().toString(), order.getId()));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
    }

    @Test
    void 증빙_없는_주문은_404() throws Exception {
        Order order = receiptOrder(orgA);
        Mockito.when(userRepository.findById(orgAdmin.getId())).thenReturn(java.util.Optional.of(orgAdmin));
        CustomException e = assertThrows(CustomException.class,
                () -> orgService.getOrderReceipt(orgAdmin.getId().toString(), order.getId()));
        assertEquals(ErrorCode.COMMON_NOT_FOUND, e.getErrorCode());
    }

    @Test
    void 증빙_내려받기_presigned() throws Exception {
        Order order = receiptOrder(orgA);
        order.attachReceipt("receipts/" + order.getId() + "/receipt.pdf", "계산서.pdf");
        Mockito.when(userRepository.findById(orgAdmin.getId())).thenReturn(java.util.Optional.of(orgAdmin));
        Mockito.when(s3Service.getPresignedUrl(Mockito.anyString(), Mockito.any()))
                .thenReturn("https://presigned.example/x");
        SupportDto.ReceiptDownload dl = orgService.getOrderReceipt(orgAdmin.getId().toString(), order.getId());
        assertEquals("계산서.pdf", dl.fileName());
        assertEquals("https://presigned.example/x", dl.url());
    }

    // ── 공개 공지 (무인증, 로그인 전) ──
    @Test
    void 공개_공지는_전체_대상_기간_내만() throws Exception {
        Notice all = Notice.builder().title("서버 점검").body("8/25 새벽")
                .startsOn(java.time.LocalDate.now().minusDays(1)).endsOn(java.time.LocalDate.now().plusDays(1)).build();
        setId(all, UUID.randomUUID());
        Mockito.when(noticeRepository.findVisibleForAll(Mockito.any())).thenReturn(java.util.List.of(all));
        var result = orgService.getPublicNotices();
        assertEquals(1, result.size());
        assertEquals("서버 점검", result.get(0).title());
        Mockito.verify(noticeRepository).findVisibleForAll(Mockito.any());  // 전체 대상·기간 필터는 쿼리가 담당
    }

    @Test
    void 요청_접수는_점역사도_가능_기관_미소속은_403() throws Exception {
        User member = User.builder().loginId("orga02").organization(orgA).password("pw").build();
        setId(member, UUID.randomUUID());   // 기본 ROLE_USER
        Mockito.when(userRepository.findById(member.getId())).thenReturn(java.util.Optional.of(member));
        Mockito.when(inquiryRepository.save(Mockito.any())).thenAnswer(inv -> {
            Inquiry i = inv.getArgument(0); setId(i, UUID.randomUUID()); return i;
        });
        Mockito.when(inquiryRepository.findById(Mockito.any())).thenReturn(java.util.Optional.empty());
        var item = orgService.createRequest(member.getId().toString(),
                new SupportDto.CreateRequest("CREDIT_ADD", "요청"));
        assertEquals("CREDIT_ADD", item.type());

        User orphan = User.builder().loginId("noorg01").password("pw").build();
        setId(orphan, UUID.randomUUID());
        Mockito.when(userRepository.findById(orphan.getId())).thenReturn(java.util.Optional.of(orphan));
        CustomException e = assertThrows(CustomException.class, () ->
                orgService.createRequest(orphan.getId().toString(),
                        new SupportDto.CreateRequest("CREDIT_ADD", "요청")));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
    }
}
