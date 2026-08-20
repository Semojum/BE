package com.semojum.backend.domain.support.repository;

import com.semojum.backend.domain.support.entity.InquiryAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface InquiryAttachmentRepository extends JpaRepository<InquiryAttachment, UUID> {

    List<InquiryAttachment> findByInquiryIdInOrderByCreatedAtAsc(Collection<UUID> inquiryIds);

    List<InquiryAttachment> findByInquiryIdOrderByCreatedAtAsc(UUID inquiryId);
}
