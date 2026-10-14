package com.semojum.backend.domain.board.repository;

import com.semojum.backend.domain.board.entity.BoardAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BoardAttachmentRepository extends JpaRepository<BoardAttachment, Long> {

    List<BoardAttachment> findByPostIdOrderByIdAsc(Long postId);
}
