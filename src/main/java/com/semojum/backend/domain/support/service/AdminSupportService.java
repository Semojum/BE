package com.semojum.backend.domain.support.service;

import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.org.entity.Organization;
import com.semojum.backend.domain.org.repository.OrganizationRepository;
import com.semojum.backend.domain.support.dto.SupportDto;
import com.semojum.backend.domain.support.entity.Inquiry;
import com.semojum.backend.domain.support.entity.Notice;
import com.semojum.backend.domain.support.entity.Order;
import com.semojum.backend.domain.support.entity.InquiryAttachment;
import com.semojum.backend.domain.support.repository.InquiryAttachmentRepository;
import com.semojum.backend.domain.support.repository.InquiryRepository;
import com.semojum.backend.domain.support.repository.NoticeRepository;
import com.semojum.backend.domain.support.repository.OrderRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.s3.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * T1 운영자의 문의·공지·주문 관리 (ROLE_ADMIN JWT — AdminController 경유).
 * 공지 노출 종료는 스케줄러 없이 조회 시 기간으로 판정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSupportService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Set<String> INQUIRY_STATUSES =
            Set.of(Inquiry.STATUS_OPEN, Inquiry.STATUS_IN_REVIEW, Inquiry.STATUS_ANSWERED);
    private static final Set<String> INVOICE_STATUSES = Set.of(Order.INVOICE_PENDING, Order.INVOICE_ISSUED);

    private final NoticeRepository noticeRepository;
    private final InquiryRepository inquiryRepository;
    private final InquiryAttachmentRepository inquiryAttachmentRepository;
    private final OrderRepository orderRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final S3Service s3Service;

    // ── 공지 ──
    @Transactional
    public SupportDto.NoticeItem createNotice(SupportDto.CreateNotice request) {
        if (request.endsOn().isBefore(request.startsOn())) {
            log.warn("공지 기간 역전: startsOn={}, endsOn={}", request.startsOn(), request.endsOn());
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        String orgName = null;
        if (request.targetOrganizationId() != null) {
            orgName = organizationRepository.findById(request.targetOrganizationId())
                    .orElseThrow(() -> new CustomException(ErrorCode.ORG_NOT_FOUND)).getName();
        }
        Notice saved = noticeRepository.save(Notice.builder()
                .targetOrganizationId(request.targetOrganizationId())
                .title(request.title())
                .body(request.body())
                .startsOn(request.startsOn())
                .endsOn(request.endsOn())
                .build());
        log.info("공지 등록: id={}, 대상={}, 기간={}~{}", saved.getId(),
                orgName == null ? "전체" : orgName, request.startsOn(), request.endsOn());
        return toNoticeItem(noticeRepository.findById(saved.getId()).orElse(saved), orgName);
    }

    @Transactional(readOnly = true)
    public List<SupportDto.NoticeItem> listNotices() {
        Map<UUID, String> orgNames = orgNameMap();
        return noticeRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(n -> toNoticeItem(n, n.getTargetOrganizationId() == null ? null
                        : orgNames.get(n.getTargetOrganizationId())))
                .toList();
    }

    private SupportDto.NoticeItem toNoticeItem(Notice n, String orgName) {
        LocalDate today = LocalDate.now(KST);
        String displayStatus = today.isBefore(n.getStartsOn()) ? "SCHEDULED"
                : today.isAfter(n.getEndsOn()) ? "ENDED" : "ACTIVE";
        return new SupportDto.NoticeItem(n.getId(), n.getTargetOrganizationId(), orgName,
                n.getTitle(), n.getBody(), n.getStartsOn(), n.getEndsOn(), displayStatus, n.getCreatedAt());
    }

    // ── 문의 ──
    // 페이지네이션 (2026-08-20) — page 0부터, size 기본 20·최대 100
    @Transactional(readOnly = true)
    public SupportDto.InquiryPage listInquiries(String status, String type, Integer page, Integer size) {
        var pageable = org.springframework.data.domain.PageRequest.of(
                page == null || page < 0 ? 0 : page,
                size == null || size < 1 ? 20 : Math.min(size, 100));
        org.springframework.data.domain.Page<Inquiry> result;
        if (status != null && type != null) result = inquiryRepository.findByStatusAndTypeOrderByCreatedAtDesc(status, type, pageable);
        else if (status != null) result = inquiryRepository.findByStatusOrderByCreatedAtDesc(status, pageable);
        else if (type != null) result = inquiryRepository.findByTypeOrderByCreatedAtDesc(type, pageable);
        else result = inquiryRepository.findAllByOrderByCreatedAtDesc(pageable);
        List<Inquiry> inquiries = result.getContent();

        Map<UUID, String> orgNames = orgNameMap();
        Map<UUID, String> loginIds = inquiries.stream()
                .map(Inquiry::getUserId).filter(java.util.Objects::nonNull).distinct()
                .map(userRepository::findById)
                .filter(java.util.Optional::isPresent).map(java.util.Optional::get)
                .collect(Collectors.toMap(u -> u.getId(), u -> u.getLoginId()));

        Map<UUID, List<SupportDto.InquiryAttachmentItem>> attachments = attachmentsFor(
                inquiries.stream().map(Inquiry::getId).toList());

        List<SupportDto.InquiryItem> items = inquiries.stream().map(i -> new SupportDto.InquiryItem(
                i.getId(), i.getType(), i.getStatus(),
                i.getOrganizationId() == null ? null : orgNames.get(i.getOrganizationId()),
                i.getUserId() == null ? null : loginIds.get(i.getUserId()),
                i.getSenderEmail(), i.getSubject(),
                i.getMessage(), i.getCreatedAt(), i.getStatusChangedAt(),
                attachments.getOrDefault(i.getId(), List.of()))).toList();
        return new SupportDto.InquiryPage(items, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public SupportDto.InquiryItem updateInquiryStatus(UUID inquiryId, String status) {
        if (!INQUIRY_STATUSES.contains(status)) {
            log.warn("문의 상태값 오류: {}", status);
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        Inquiry inquiry = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_NOT_FOUND));
        inquiry.changeStatus(status);
        log.info("문의 상태 변경: id={}, status={}", inquiryId, status);
        String orgName = inquiry.getOrganizationId() == null ? null
                : organizationRepository.findById(inquiry.getOrganizationId()).map(Organization::getName).orElse(null);
        String loginId = inquiry.getUserId() == null ? null
                : userRepository.findById(inquiry.getUserId()).map(u -> u.getLoginId()).orElse(null);
        return new SupportDto.InquiryItem(inquiry.getId(), inquiry.getType(), inquiry.getStatus(),
                orgName, loginId, inquiry.getSenderEmail(), inquiry.getSubject(),
                inquiry.getMessage(), inquiry.getCreatedAt(), inquiry.getStatusChangedAt(),
                attachmentsFor(List.of(inquiry.getId())).getOrDefault(inquiry.getId(), List.of()));
    }

    private Map<UUID, List<SupportDto.InquiryAttachmentItem>> attachmentsFor(List<UUID> inquiryIds) {
        if (inquiryIds.isEmpty()) return Map.of();
        return inquiryAttachmentRepository.findByInquiryIdInOrderByCreatedAtAsc(inquiryIds).stream()
                .collect(Collectors.groupingBy(InquiryAttachment::getInquiryId,
                        Collectors.mapping(a -> new SupportDto.InquiryAttachmentItem(
                                a.getId(), a.getFileName(), a.getContentType(), a.getSizeBytes()),
                                Collectors.toList())));
    }

    // 문의 첨부 내려받기 — presigned 15분. 이미지면 FE가 미리보기로 렌더 가능 (V27)
    @Transactional(readOnly = true)
    public SupportDto.ReceiptDownload getInquiryAttachment(UUID inquiryId, UUID attachmentId) {
        InquiryAttachment att = inquiryAttachmentRepository.findById(attachmentId)
                .filter(a -> a.getInquiryId().equals(inquiryId))
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_NOT_FOUND));
        return new SupportDto.ReceiptDownload(att.getFileName(),
                s3Service.getPresignedUrl(att.getStoragePath(), java.time.Duration.ofMinutes(15)));
    }

    // ── 주문·수납 ──
    @Transactional
    public SupportDto.OrderItem createOrder(SupportDto.CreateOrder request) {
        Organization org = organizationRepository.findById(request.organizationId())
                .orElseThrow(() -> new CustomException(ErrorCode.ORG_NOT_FOUND));
        Order saved = orderRepository.save(Order.builder()
                .organizationId(request.organizationId())
                .orderDate(request.orderDate())
                .description(request.description())
                .amountKrw(request.amountKrw())
                .creditAmount(request.creditAmount())
                .build());
        log.info("주문 기록: id={}, org={}, 금액={}원", saved.getId(), org.getCode(), request.amountKrw());
        return toOrderItem(orderRepository.findById(saved.getId()).orElse(saved), org.getName());
    }

    @Transactional
    public SupportDto.OrderItem updateOrder(UUID orderId, SupportDto.UpdateOrder request) {
        if (request.paidAt() == null && request.invoiceStatus() == null) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_NOT_FOUND));
        if (request.paidAt() != null) order.recordPayment(request.paidAt());
        if (request.invoiceStatus() != null) {
            if (!INVOICE_STATUSES.contains(request.invoiceStatus())) {
                log.warn("계산서 상태값 오류: {}", request.invoiceStatus());
                throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
            }
            order.changeInvoiceStatus(request.invoiceStatus());
        }
        log.info("주문 갱신: id={}, paidAt={}, invoiceStatus={}", orderId, request.paidAt(), request.invoiceStatus());
        String orgName = organizationRepository.findById(order.getOrganizationId())
                .map(Organization::getName).orElse(null);
        return toOrderItem(order, orgName);
    }

    @Transactional(readOnly = true)
    public List<SupportDto.OrderItem> listOrders(UUID organizationId) {
        Map<UUID, String> orgNames = orgNameMap();
        List<Order> orders = organizationId == null ? orderRepository.findAllByOrderByOrderDateDesc()
                : orderRepository.findByOrganizationIdOrderByOrderDateDesc(organizationId);
        return orders.stream().map(o -> toOrderItem(o, orgNames.get(o.getOrganizationId()))).toList();
    }

    // ── 증빙(계산서·전표) 파일 — 운영자 업로드, 재업로드 = 교체 (V25) ──
    private static final Set<String> RECEIPT_EXTS = Set.of("pdf", "png", "jpg", "jpeg");
    private static final long RECEIPT_MAX_BYTES = 10L * 1024 * 1024;

    @Transactional
    public SupportDto.OrderItem uploadOrderReceipt(UUID orderId, org.springframework.web.multipart.MultipartFile file) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_NOT_FOUND));
        String name = file.getOriginalFilename();
        String ext = name != null && name.contains(".")
                ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!RECEIPT_EXTS.contains(ext)) {
            log.warn("증빙 파일 형식 오류: {}", name);
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        if (file.getSize() > RECEIPT_MAX_BYTES) {
            log.warn("증빙 파일 크기 초과: {} ({}KB)", name, file.getSize() / 1024);
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        try {
            // 교체 의미 — 기존 파일을 지우고 새 파일 하나만 유지
            s3Service.deleteByPrefix("receipts/" + orderId + "/");
            String key = "receipts/" + orderId + "/receipt." + ext;
            String contentType = ext.equals("pdf") ? "application/pdf" : "image/" + (ext.equals("jpg") ? "jpeg" : ext);
            s3Service.uploadFile(key, file.getBytes(), contentType);
            order.attachReceipt(key, name);
        } catch (java.io.IOException e) {
            log.error("증빙 파일 업로드 실패: order={}", orderId, e);
            throw new CustomException(ErrorCode.COMMON_INTERNAL_ERROR);
        }
        log.info("증빙 업로드: order={}, file={}", orderId, name);
        String orgName = organizationRepository.findById(order.getOrganizationId())
                .map(Organization::getName).orElse(null);
        return toOrderItem(order, orgName);
    }

    @Transactional(readOnly = true)
    public SupportDto.ReceiptDownload getOrderReceipt(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new CustomException(ErrorCode.COMMON_NOT_FOUND));
        if (order.getReceiptFileKey() == null) {
            throw new CustomException(ErrorCode.COMMON_NOT_FOUND);
        }
        return new SupportDto.ReceiptDownload(order.getReceiptFileName(),
                s3Service.getPresignedUrl(order.getReceiptFileKey(), java.time.Duration.ofMinutes(15)));
    }

    private SupportDto.OrderItem toOrderItem(Order o, String orgName) {
        return new SupportDto.OrderItem(o.getId(), o.getOrganizationId(), orgName, o.getOrderDate(),
                o.getDescription(), o.getAmountKrw(), o.getCreditAmount(), o.getPaidAt(),
                o.getInvoiceStatus(), o.getReceiptFileName(), o.getCreatedAt());
    }

    private Map<UUID, String> orgNameMap() {
        return organizationRepository.findAll().stream()
                .collect(Collectors.toMap(Organization::getId, Organization::getName, (a, b) -> a));
    }
}
