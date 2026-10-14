package com.semojum.backend.domain.board.repository;

import com.semojum.backend.domain.board.entity.BoardNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BoardNotificationRepository extends JpaRepository<BoardNotification, Long> {

    Optional<BoardNotification> findByPostId(Long postId);

    List<BoardNotification> findByUserIdOrderByLastAnsweredAtDesc(UUID userId);
}
