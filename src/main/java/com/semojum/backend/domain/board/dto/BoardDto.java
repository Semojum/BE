package com.semojum.backend.domain.board.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** QnA 게시판 (V3.1, 기획 6-2 · 피그마 D8 · D8-2) */
public class BoardDto {

    // ── 목록 ──

    /**
     * @param notices 맨 위에 고정되는 공지 — 운영자 공지(notices)에서 기간 안이고 전체이거나 내 기관 대상인 것.
     *                검색하거나 「내 글 보기」일 때는 빈 배열이다
     * @param totalCount 공지를 뺀 글 수 (화면 「전체 128건」)
     */
    public record PostList(int page, long totalCount, List<NoticeRow> notices, List<PostRow> items) {}

    public record NoticeRow(UUID noticeId, String title, String author, LocalDateTime createdAt, int viewCount) {}

    /** @param author 가린 아이디(test***). 운영자 콘솔 목록에서만 원래 아이디 */
    public record PostRow(Long postId, String title, int answerCount, String author, boolean secret,
                          boolean hidden, LocalDateTime createdAt, int viewCount) {}

    // ── 상세 ──

    /** @param mine 내가 쓴 글 — 앱이 이걸로 「수정」·「삭제」 단추를 보인다 */
    public record PostDetail(Long postId, String title, String content, String author, boolean mine,
                             boolean secret, boolean hidden, LocalDateTime createdAt, LocalDateTime updatedAt,
                             int viewCount, List<Attachment> attachments, List<Answer> answers) {}

    /** @param url presigned URL — 15분이라 오래 들고 있지 않는다 */
    public record Attachment(Long attachmentId, String fileName, long size, String url) {}

    /** @param mine 내가 쓴 답변 — 운영자 콘솔이 이걸로 답변 「수정」·「삭제」를 보인다 */
    public record Answer(Long answerId, String content, boolean mine, LocalDateTime answeredAt, LocalDateTime updatedAt) {}

    public record NoticeDetail(UUID noticeId, String title, String body, String author,
                               LocalDateTime createdAt, int viewCount) {}

    public record Created(Long postId) {}

    // ── 운영자 답변 ──

    /** @param notifyAuthor 「글쓴이에게 알림 보내기」(피그마 D8-2 답변 등록 창) */
    public record CreateAnswer(String content, Boolean notifyAuthor) {}

    public record UpdateAnswer(String content) {}

    public record UpdateHidden(Boolean hidden) {}

    // ── 알림 ──

    public record Notifications(long unreadCount, List<Notification> items) {}

    public record Notification(Long notificationId, Long postId, String title, int answerCount,
                               LocalDateTime lastAnsweredAt, boolean read) {}
}
