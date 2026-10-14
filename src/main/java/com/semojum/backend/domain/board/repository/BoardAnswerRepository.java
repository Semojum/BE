package com.semojum.backend.domain.board.repository;

import com.semojum.backend.domain.board.entity.BoardAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface BoardAnswerRepository extends JpaRepository<BoardAnswer, Long> {

    List<BoardAnswer> findByPostIdOrderByIdAsc(Long postId);

    // 목록 제목 뒤 (N) — 쪽 단위 한 번에 센다(글마다 세면 N+1)
    @Query("SELECT a.postId, COUNT(a) FROM BoardAnswer a WHERE a.postId IN :postIds GROUP BY a.postId")
    List<Object[]> countByPostIds(@Param("postIds") Collection<Long> postIds);
}
