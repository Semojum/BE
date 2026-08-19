package com.semojum.backend.domain.support.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.enums.Role;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.org.entity.Organization;
import com.semojum.backend.domain.support.dto.SupportDto;
import com.semojum.backend.domain.support.entity.Inquiry;
import com.semojum.backend.domain.support.entity.Notice;
import com.semojum.backend.domain.support.repository.InquiryRepository;
import com.semojum.backend.domain.support.repository.NoticeRepository;
import com.semojum.backend.domain.support.entity.Order;
import com.semojum.backend.domain.support.repository.OrderRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * T2 기관 관리자 화면의 공지·주문 조회 + 요청(크레딧 추가·계정 발급) 접수.
 * 요청은 문의(inquiries)로 들어가 T1-9 목록에 모이고, 처리 상태가 이 화면에 남는다(기획 확정).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrgSupportService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // T2에서 넣을 수 있는 요청 유형 — 오류 신고 등은 후속(문의 UI가 생기면)
    private static final Set<String> REQUEST_TYPES = Set.of(Inquiry.TYPE_CREDIT_ADD, Inquiry.TYPE_ACCOUNT_ISSUE);

    private final UserRepository userRepository;
    private final NoticeRepository noticeRepository;
    private final OrderRepository orderRepository;
    private final InquiryRepository inquiryRepository;
    private final com.semojum.backend.global.s3.S3Service s3Service;

    // 로그인 전 공개 공지 (무인증) — 전체 대상만. 기관별 공지는 로그인 후 getNotices가 담당
    @Transactional(readOnly = true)
    public List<SupportDto.PublicNotice> getPublicNotices() {
        return noticeRepository.findVisibleForAll(LocalDate.now(KST)).stream()
                .map(n -> new SupportDto.PublicNotice(n.getId(), n.getTitle(), n.getBody(),
                        n.getStartsOn(), n.getEndsOn(), n.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SupportDto.OrgNotice> getNotices(String adminUserId) {
        Organization org = resolveOrgAdmin(adminUserId).getOrganization();
        return noticeRepository.findVisibleForOrganization(org.getId(), LocalDate.now(KST)).stream()
                .map(n -> new SupportDto.OrgNotice(n.getId(),
                        n.getTargetOrganizationId() == null ? "ALL" : "ORG",
                        n.getTitle(), n.getBody(), n.getStartsOn(), n.getEndsOn(), n.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public SupportDto.OrgOrders getOrders(String adminUserId) {
        Organization org = resolveOrgAdmin(adminUserId).getOrganization();
        List<SupportDto.OrgOrderItem> items = orderRepository
                .findByOrganizationIdOrderByOrderDateDesc(org.getId()).stream()
                .map(o -> new SupportDto.OrgOrderItem(o.getId(), o.getOrderDate(), o.getDescription(),
                        o.getAmountKrw(), o.getCreditAmount(), o.getPaidAt(), o.getInvoiceStatus(),
                        o.getReceiptFileName()))
                .toList();
        return new SupportDto.OrgOrders(org.getReceiptEmail(), items);
    }

    // 증빙(계산서·전표) 내려받기 — 자기 기관 주문만, presigned 15분 (V25)
    @Transactional(readOnly = true)
    public SupportDto.ReceiptDownload getOrderReceipt(String adminUserId, java.util.UUID orderId) {
        Organization org = resolveOrgAdmin(adminUserId).getOrganization();
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_NOT_FOUND));
        if (!order.getOrganizationId().equals(org.getId())) {
            log.warn("타 기관 주문 증빙 접근 거부: org={}, order={}", org.getCode(), orderId);
            throw new CustomException(ErrorCode.COMMON_FORBIDDEN);
        }
        if (order.getReceiptFileKey() == null) {
            throw new CustomException(ErrorCode.COMMON_NOT_FOUND);
        }
        return new SupportDto.ReceiptDownload(order.getReceiptFileName(),
                s3Service.getPresignedUrl(order.getReceiptFileKey(), java.time.Duration.ofMinutes(15)));
    }

    @Transactional
    public void updateReceiptEmail(String adminUserId, String email) {
        Organization org = resolveOrgAdmin(adminUserId).getOrganization();
        org.changeReceiptEmail(email == null || email.isBlank() ? null : email.trim());
        log.info("증빙 이메일 변경: org={}", org.getCode());
    }

    @Transactional
    public SupportDto.RequestItem createRequest(String adminUserId, SupportDto.CreateRequest request) {
        User admin = resolveOrgAdmin(adminUserId);
        if (!REQUEST_TYPES.contains(request.type())) {
            log.warn("허용되지 않는 요청 유형: {}", request.type());
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        Inquiry saved = inquiryRepository.save(Inquiry.builder()
                .type(request.type())
                .organizationId(admin.getOrganization().getId())
                .userId(admin.getId())
                .message(request.message())
                .build());
        log.info("기관 요청 접수: org={}, type={}, id={}", admin.getOrganization().getCode(), request.type(), saved.getId());
        Inquiry loaded = inquiryRepository.findById(saved.getId()).orElse(saved);
        return new SupportDto.RequestItem(loaded.getId(), loaded.getType(), loaded.getStatus(),
                loaded.getMessage(), loaded.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public List<SupportDto.RequestItem> getRequests(String adminUserId) {
        Organization org = resolveOrgAdmin(adminUserId).getOrganization();
        return inquiryRepository.findByOrganizationIdOrderByCreatedAtDesc(org.getId()).stream()
                .map(i -> new SupportDto.RequestItem(i.getId(), i.getType(), i.getStatus(),
                        i.getMessage(), i.getCreatedAt()))
                .toList();
    }

    /** 요청 취소 — 자기 기관의 요청(크레딧 추가·계정 발급)이면서 아직 OPEN(미답변)일 때만. 접수 전 회수라 hard delete */
    @Transactional
    public void cancelRequest(String adminUserId, UUID requestId) {
        Organization org = resolveOrgAdmin(adminUserId).getOrganization();
        Inquiry inquiry = inquiryRepository.findById(requestId)
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_NOT_FOUND));
        if (inquiry.getOrganizationId() == null || !inquiry.getOrganizationId().equals(org.getId())
                || !REQUEST_TYPES.contains(inquiry.getType())) {
            log.warn("요청 취소 거부(타 기관 또는 요청 아님): id={}, org={}", requestId, org.getCode());
            throw new CustomException(ErrorCode.COMMON_FORBIDDEN);
        }
        if (!Inquiry.STATUS_OPEN.equals(inquiry.getStatus())) {
            log.warn("요청 취소 거부(이미 처리 중): id={}, status={}", requestId, inquiry.getStatus());
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        inquiryRepository.delete(inquiry);
        log.info("기관 요청 취소: org={}, id={}", org.getCode(), requestId);
    }

    // ROLE_ORG_ADMIN + 소속 기관 확인 — OrgAdminService와 동일 규칙 (아니면 403)
    private User resolveOrgAdmin(String adminUserId) {
        User admin = userRepository.findById(UUID.fromString(adminUserId))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        if (admin.getRole() != Role.ROLE_ORG_ADMIN || admin.getOrganization() == null) {
            log.warn("기관 관리 접근 거부: loginId={}, role={}", admin.getLoginId(), admin.getRole());
            throw new CustomException(ErrorCode.COMMON_FORBIDDEN);
        }
        return admin;
    }
}
