package com.semojum.backend.domain.board.repository;

import com.semojum.backend.domain.board.entity.BoardPost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface BoardPostRepository extends JpaRepository<BoardPost, Long> {

    /**
     * 게시판 목록 · 검색 — 번호 내림차순.
     *
     * <p>지운 글은 빠진다. 숨긴 글은 운영자({@code admin})에게만 보인다.
     * 비밀글 <b>본문</b>은 작성자·운영자가 아니면 내용 검색에 걸리지 않는다 — 걸리면 목록만 보고도
     * 본문에 그 낱말이 있다는 걸 알 수 있다. 제목은 원래 목록에 보이므로 검색해도 된다.
     * {@code waitingOnly}는 운영자 콘솔 「대기만 보기」(답변이 하나도 없는 글).
     */
    @Query("SELECT p FROM BoardPost p WHERE p.deletedAt IS NULL " +
            "AND (:admin = true OR p.hidden = false) " +
            "AND (:mine = false OR p.userId = :me) " +
            "AND (:waitingOnly = false OR NOT EXISTS (SELECT 1 FROM BoardAnswer a WHERE a.postId = p.id)) " +
            "AND (:hasKeyword = false " +
            "  OR (:field IN ('TITLE', 'TITLE_CONTENT') AND LOWER(p.title) LIKE :keyword) " +
            "  OR (:field IN ('CONTENT', 'TITLE_CONTENT') AND LOWER(p.content) LIKE :keyword " +
            "      AND (p.secret = false OR p.userId = :me OR :admin = true)) " +
            "  OR (:field = 'AUTHOR' AND EXISTS (SELECT 1 FROM User u WHERE u.id = p.userId AND LOWER(u.loginId) LIKE :keyword))) " +
            "ORDER BY p.id DESC")
    Page<BoardPost> search(@Param("me") UUID me, @Param("admin") boolean admin,
                           @Param("mine") boolean mine, @Param("waitingOnly") boolean waitingOnly,
                           @Param("hasKeyword") boolean hasKeyword, @Param("field") String field,
                           @Param("keyword") String keyword, Pageable pageable);
}
