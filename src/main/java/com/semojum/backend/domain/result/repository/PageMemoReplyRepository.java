package com.semojum.backend.domain.result.repository;

import com.semojum.backend.domain.result.entity.PageMemoReply;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface PageMemoReplyRepository extends JpaRepository<PageMemoReply, Long> {

    /** 쪽의 메모들 답글을 한 번에 — 메모마다 조회하면 N+1이다 */
    List<PageMemoReply> findByMemoIdInOrderByIdAsc(Collection<Long> memoIds);

    void deleteByMemoIdIn(Collection<Long> memoIds);
}
