package com.semojum.backend.domain.board.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.enums.Role;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.board.dto.BoardDto;
import com.semojum.backend.domain.board.entity.BoardAnswer;
import com.semojum.backend.domain.board.entity.BoardAttachment;
import com.semojum.backend.domain.board.entity.BoardNotification;
import com.semojum.backend.domain.board.entity.BoardPost;
import com.semojum.backend.domain.board.repository.BoardAnswerRepository;
import com.semojum.backend.domain.board.repository.BoardAttachmentRepository;
import com.semojum.backend.domain.board.repository.BoardNotificationRepository;
import com.semojum.backend.domain.board.repository.BoardPostRepository;
import com.semojum.backend.domain.support.entity.Notice;
import com.semojum.backend.domain.support.repository.NoticeRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.s3.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * QnA 게시판 (V3.1, 기획 6-2 · 피그마 D8 · D8-2).
 *
 * <p>권한 규칙: 글과 답변은 <b>쓴 사람 본인만</b> 고치고 지운다. 운영자는 남의 글을 고칠 수 없고
 * 숨김·삭제만 한다. 비밀글은 작성자와 운영자만 연다. 기관 관리자도 게시판을 쓴다.
 *
 * <p>공지는 따로 쓰지 않는다 — 운영자 콘솔 공지(notices)가 모든 계정의 게시판 맨 위에 뜬다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BoardService {

    public static final int PAGE_SIZE = 10;                 // 기획 6-2 「10건씩」
    public static final int MAX_TITLE = 100;
    public static final int MAX_CONTENT = 10_000;
    public static final int MAX_ATTACHMENTS = 5;            // 피그마 「최대 5개 · 한 개 10MB」
    public static final long MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;
    /** 이미지·문서·zip — 실행 파일은 받지 않는다 */
    static final Set<String> ALLOWED_EXTS = Set.of(
            "jpg", "jpeg", "png", "gif",
            "pdf", "hwp", "hwpx", "txt", "doc", "docx", "xls", "xlsx", "brf",
            "zip");
    static final Set<String> SEARCH_FIELDS = Set.of("TITLE", "CONTENT", "TITLE_CONTENT", "AUTHOR");
    private static final String ADMIN_AUTHOR = "운영자";
    private static final Duration URL_TTL = Duration.ofMinutes(15);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final BoardPostRepository postRepository;
    private final BoardAttachmentRepository attachmentRepository;
    private final BoardAnswerRepository answerRepository;
    private final BoardNotificationRepository notificationRepository;
    private final NoticeRepository noticeRepository;
    private final UserRepository userRepository;
    private final S3Service s3Service;

    // ── 목록 ─────────────────────────────────────────────────────────

    /** 게시판 목록 — 공지 고정 + 글 10건씩. 운영자 콘솔은 {@link #listForAdmin} */
    @Transactional(readOnly = true)
    public BoardDto.PostList list(String userId, Integer page, String field, String keyword, boolean mine) {
        User me = user(userId);
        boolean searching = keyword != null && !keyword.isBlank();
        Page<BoardPost> posts = search(me, isAdmin(me), page, field, keyword, mine, false);

        // 공지는 그냥 목록을 볼 때만 위에 붙인다 — 검색 결과나 내 글에 공지가 끼면 결과가 아니다
        List<BoardDto.NoticeRow> notices = searching || mine ? List.of() : visibleNotices(me).stream()
                .map(n -> new BoardDto.NoticeRow(n.getId(), n.getTitle(), ADMIN_AUTHOR,
                        toLocal(n.getCreatedAt()), n.getViewCount()))
                .toList();
        return new BoardDto.PostList(pageNo(page), posts.getTotalElements(), notices, rows(posts.getContent(), false));
    }

    /** 운영자 콘솔 목록 — 숨긴 글까지, 아이디는 가리지 않는다. {@code waitingOnly} = 「대기만 보기」 */
    @Transactional(readOnly = true)
    public BoardDto.PostList listForAdmin(String adminId, Integer page, String field, String keyword, boolean waitingOnly) {
        User admin = user(adminId);
        Page<BoardPost> posts = search(admin, true, page, field, keyword, false, waitingOnly);
        return new BoardDto.PostList(pageNo(page), posts.getTotalElements(), List.of(), rows(posts.getContent(), true));
    }

    private Page<BoardPost> search(User me, boolean admin, Integer page, String field, String keyword,
                                   boolean mine, boolean waitingOnly) {
        String f = field == null || field.isBlank() ? "TITLE_CONTENT" : field;
        if (!SEARCH_FIELDS.contains(f)) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        boolean hasKeyword = keyword != null && !keyword.isBlank();
        String like = hasKeyword ? "%" + escapeLike(keyword.trim().toLowerCase()) + "%" : "%";
        return postRepository.search(me.getId(), admin, mine, waitingOnly, hasKeyword, f, like,
                PageRequest.of(pageNo(page) - 1, PAGE_SIZE));
    }

    private List<BoardDto.PostRow> rows(List<BoardPost> posts, boolean rawLoginId) {
        if (posts.isEmpty()) return List.of();
        List<Long> ids = posts.stream().map(BoardPost::getId).toList();
        Map<Long, Integer> answerCounts = new HashMap<>();
        for (Object[] row : answerRepository.countByPostIds(ids)) {
            answerCounts.put((Long) row[0], ((Number) row[1]).intValue());
        }
        Map<UUID, User> authors = authors(posts.stream().map(BoardPost::getUserId).collect(Collectors.toSet()));
        return posts.stream().map(p -> new BoardDto.PostRow(p.getId(), p.getTitle(),
                answerCounts.getOrDefault(p.getId(), 0),
                authorName(authors.get(p.getUserId()), rawLoginId),
                p.isSecret(), p.isHidden(), p.getCreatedAt(), p.getViewCount())).toList();
    }

    // ── 상세 ─────────────────────────────────────────────────────────

    /** 글 상세 — 열 때마다 조회수 +1. 작성자가 열면 그 글의 답변 알림을 읽음으로 */
    @Transactional
    public BoardDto.PostDetail getPost(String userId, Long postId) {
        User me = user(userId);
        boolean admin = isAdmin(me);
        BoardPost post = visiblePost(postId, admin);
        boolean mine = post.getUserId().equals(me.getId());
        if (post.isSecret() && !mine && !admin) throw new CustomException(ErrorCode.COMMON_FORBIDDEN);

        post.increaseViewCount();
        if (mine) notificationRepository.findByPostId(postId).ifPresent(BoardNotification::markRead);

        List<BoardDto.Attachment> attachments = attachmentRepository.findByPostIdOrderByIdAsc(postId).stream()
                .map(a -> new BoardDto.Attachment(a.getId(), a.getFileName(), a.getSizeBytes(),
                        s3Service.getPresignedUrl(a.getStoragePath(), URL_TTL)))
                .toList();
        List<BoardDto.Answer> answers = answerRepository.findByPostIdOrderByIdAsc(postId).stream()
                .map(a -> new BoardDto.Answer(a.getId(), a.getContent(), a.getUserId().equals(me.getId()),
                        a.getCreatedAt(), a.getUpdatedAt()))
                .toList();
        User author = userRepository.findById(post.getUserId()).orElse(null);
        return new BoardDto.PostDetail(post.getId(), post.getTitle(), post.getContent(),
                authorName(author, false), mine, post.isSecret(), post.isHidden(),
                post.getCreatedAt(), post.getUpdatedAt(), post.getViewCount(), attachments, answers);
    }

    /** 공지 상세 — 게시판에서 열 때마다 조회수 +1. 게시판에 안 뜨는 공지(고정 해제 · 기간 밖 · 남의 기관)는 없는 것으로 본다 */
    @Transactional
    public BoardDto.NoticeDetail getNotice(String userId, UUID noticeId) {
        User me = user(userId);
        Notice notice = visibleNotices(me).stream().filter(n -> n.getId().equals(noticeId)).findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.BOARD_NOTICE_NOT_FOUND));
        notice.increaseViewCount();
        return new BoardDto.NoticeDetail(notice.getId(), notice.getTitle(), notice.getBody(), ADMIN_AUTHOR,
                toLocal(notice.getCreatedAt()), notice.getViewCount());
    }

    // ── 쓰기 ─────────────────────────────────────────────────────────

    @Transactional
    public BoardDto.Created create(String userId, String title, String content, Boolean secret,
                                   List<MultipartFile> files) {
        User me = user(userId);
        List<MultipartFile> uploads = nonEmpty(files);
        validateText(title, content);
        validateFiles(uploads, 0);

        BoardPost post = postRepository.save(BoardPost.builder()
                .userId(me.getId()).title(title.trim()).content(content).secret(Boolean.TRUE.equals(secret))
                .build());
        uploadAll(post.getId(), uploads);
        log.info("게시글 등록: postId={}, 첨부 {}개", post.getId(), uploads.size());
        return new BoardDto.Created(post.getId());
    }

    /**
     * 글 수정 — 본인만. 창이 항목 전부를 보여주므로 제목·본문·비밀글은 통째로 덮어쓴다.
     * 첨부는 {@code keepAttachmentIds}에 든 것만 남기고, {@code files}를 더한다(합쳐 5개까지).
     */
    @Transactional
    public BoardDto.PostDetail update(String userId, Long postId, String title, String content, Boolean secret,
                                      List<Long> keepAttachmentIds, List<MultipartFile> files) {
        User me = user(userId);
        BoardPost post = visiblePost(postId, false);
        if (!post.getUserId().equals(me.getId())) throw new CustomException(ErrorCode.COMMON_FORBIDDEN);
        List<MultipartFile> uploads = nonEmpty(files);
        validateText(title, content);

        Set<Long> keep = keepAttachmentIds == null ? Set.of() : new HashSet<>(keepAttachmentIds);
        List<BoardAttachment> current = attachmentRepository.findByPostIdOrderByIdAsc(postId);
        List<BoardAttachment> removed = current.stream().filter(a -> !keep.contains(a.getId())).toList();
        validateFiles(uploads, current.size() - removed.size());

        post.update(title.trim(), content, Boolean.TRUE.equals(secret));
        attachmentRepository.deleteAll(removed);
        uploadAll(postId, uploads);
        removed.forEach(a -> deleteObjectQuietly(a.getStoragePath()));
        return getPostAfterWrite(me, post);
    }

    /** 글 삭제 — 본인만. 번호는 당기지 않는다(표시만 지운다) */
    @Transactional
    public void delete(String userId, Long postId) {
        User me = user(userId);
        BoardPost post = visiblePost(postId, false);
        if (!post.getUserId().equals(me.getId())) throw new CustomException(ErrorCode.COMMON_FORBIDDEN);
        post.delete();
        log.info("게시글 삭제(작성자): postId={}", postId);
    }

    // ── 운영자 ───────────────────────────────────────────────────────

    /** 답변 등록 — 「글쓴이에게 알림 보내기」를 체크했으면 글쓴이 알림을 만들거나 다시 안 읽음으로 */
    @Transactional
    public BoardDto.Answer createAnswer(String adminId, Long postId, BoardDto.CreateAnswer request) {
        User admin = user(adminId);
        BoardPost post = visiblePost(postId, true);
        String content = validAnswer(request == null ? null : request.content());
        BoardAnswer answer = answerRepository.save(BoardAnswer.builder()
                .postId(postId).userId(admin.getId()).content(content).build());

        boolean notify = Boolean.TRUE.equals(request.notifyAuthor());
        if (notify) {
            notificationRepository.findByPostId(postId).ifPresentOrElse(
                    n -> n.renew(answer.getCreatedAt()),
                    () -> notificationRepository.save(BoardNotification.builder()
                            .userId(post.getUserId()).postId(postId).lastAnsweredAt(answer.getCreatedAt()).build()));
        }
        log.info("게시판 답변 등록: postId={}, answerId={}, notify={}", postId, answer.getId(), notify);
        return new BoardDto.Answer(answer.getId(), answer.getContent(), true, answer.getCreatedAt(), answer.getUpdatedAt());
    }

    /** 답변 수정 — 그 답변을 쓴 운영자만 */
    @Transactional
    public BoardDto.Answer updateAnswer(String adminId, Long answerId, BoardDto.UpdateAnswer request) {
        BoardAnswer answer = ownAnswer(adminId, answerId);
        answer.update(validAnswer(request == null ? null : request.content()));
        return new BoardDto.Answer(answer.getId(), answer.getContent(), true, answer.getCreatedAt(), answer.getUpdatedAt());
    }

    /** 답변 삭제 — 그 답변을 쓴 운영자만 */
    @Transactional
    public void deleteAnswer(String adminId, Long answerId) {
        answerRepository.delete(ownAnswer(adminId, answerId));
        log.info("게시판 답변 삭제: answerId={}", answerId);
    }

    /** 숨김 · 숨김 해제 — 숨긴 글은 운영자에게만 보인다 */
    @Transactional
    public void updateHidden(Long postId, BoardDto.UpdateHidden request) {
        if (request == null || request.hidden() == null) throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        visiblePost(postId, true).updateHidden(request.hidden());
        log.info("게시글 숨김 변경: postId={}, hidden={}", postId, request.hidden());
    }

    /** 운영자 삭제 — 부적절한 글. 본문은 고칠 수 없고 지우기만 한다 */
    @Transactional
    public void deleteByAdmin(Long postId) {
        visiblePost(postId, true).delete();
        log.info("게시글 삭제(운영자): postId={}", postId);
    }

    // ── 알림 ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public BoardDto.Notifications notifications(String userId) {
        User me = user(userId);
        List<BoardNotification> all = notificationRepository.findByUserIdOrderByLastAnsweredAtDesc(me.getId());
        Map<Long, BoardPost> posts = postRepository.findAllById(all.stream().map(BoardNotification::getPostId).toList())
                .stream().filter(p -> !p.isDeleted() && !p.isHidden())
                .collect(Collectors.toMap(BoardPost::getId, Function.identity()));
        List<BoardNotification> live = all.stream().filter(n -> posts.containsKey(n.getPostId())).toList();
        Map<Long, Integer> counts = new HashMap<>();
        if (!live.isEmpty()) {
            for (Object[] row : answerRepository.countByPostIds(posts.keySet())) {
                counts.put((Long) row[0], ((Number) row[1]).intValue());
            }
        }
        List<BoardDto.Notification> items = live.stream().map(n -> new BoardDto.Notification(n.getId(), n.getPostId(),
                posts.get(n.getPostId()).getTitle(), counts.getOrDefault(n.getPostId(), 0),
                n.getLastAnsweredAt(), n.isRead())).toList();
        return new BoardDto.Notifications(items.stream().filter(n -> !n.read()).count(), items);
    }

    /** 「모두 읽음」 */
    @Transactional
    public void readAllNotifications(String userId) {
        User me = user(userId);
        notificationRepository.findByUserIdOrderByLastAnsweredAtDesc(me.getId()).forEach(BoardNotification::markRead);
    }

    // ── 내부 ─────────────────────────────────────────────────────────

    /**
     * 아이디 가림 — 앞 절반(올림)만 보이고 나머지는 *** (testorg01 → testo***, 피그마 D8).
     * 원래 아이디는 응답에 싣지 않는다 — 앱이 자르게 하면 원본이 응답에 실려 나간다.
     */
    static String mask(String loginId) {
        if (loginId == null || loginId.isEmpty()) return "***";
        return loginId.substring(0, (loginId.length() + 1) / 2) + "***";
    }

    private String authorName(User author, boolean rawLoginId) {
        if (author == null) return "***";
        if (author.getRole() == Role.ROLE_ADMIN) return ADMIN_AUTHOR;
        return rawLoginId ? author.getLoginId() : mask(author.getLoginId());
    }

    private Map<UUID, User> authors(Collection<UUID> ids) {
        return userRepository.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    /** 지운 글은 누구에게도 없다. 숨긴 글은 운영자에게만 있다 */
    private BoardPost visiblePost(Long postId, boolean admin) {
        BoardPost post = postRepository.findById(postId)
                .orElseThrow(() -> new CustomException(ErrorCode.BOARD_POST_NOT_FOUND));
        if (post.isDeleted() || (post.isHidden() && !admin)) throw new CustomException(ErrorCode.BOARD_POST_NOT_FOUND);
        return post;
    }

    private BoardDto.PostDetail getPostAfterWrite(User me, BoardPost post) {
        List<BoardDto.Attachment> attachments = attachmentRepository.findByPostIdOrderByIdAsc(post.getId()).stream()
                .map(a -> new BoardDto.Attachment(a.getId(), a.getFileName(), a.getSizeBytes(),
                        s3Service.getPresignedUrl(a.getStoragePath(), URL_TTL)))
                .toList();
        List<BoardDto.Answer> answers = answerRepository.findByPostIdOrderByIdAsc(post.getId()).stream()
                .map(a -> new BoardDto.Answer(a.getId(), a.getContent(), false, a.getCreatedAt(), a.getUpdatedAt()))
                .toList();
        return new BoardDto.PostDetail(post.getId(), post.getTitle(), post.getContent(), authorName(me, false), true,
                post.isSecret(), post.isHidden(), post.getCreatedAt(), post.getUpdatedAt(), post.getViewCount(),
                attachments, answers);
    }

    private BoardAnswer ownAnswer(String adminId, Long answerId) {
        User admin = user(adminId);
        BoardAnswer answer = answerRepository.findById(answerId)
                .orElseThrow(() -> new CustomException(ErrorCode.BOARD_ANSWER_NOT_FOUND));
        if (!answer.getUserId().equals(admin.getId())) throw new CustomException(ErrorCode.COMMON_FORBIDDEN);
        return answer;
    }

    /** 게시판에 뜨는 공지 — 고정돼 있고, 노출 기간 안이고, 전체 공지이거나 내 기관 대상 */
    private List<Notice> visibleNotices(User me) {
        UUID orgId = me.getOrganization() == null ? null : me.getOrganization().getId();
        return noticeRepository.findPinnedForBoard(orgId, LocalDate.now(KST));
    }

    private void validateText(String title, String content) {
        if (title == null || title.isBlank() || title.trim().length() > MAX_TITLE
                || content == null || content.isBlank() || content.length() > MAX_CONTENT) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
    }

    private String validAnswer(String content) {
        if (content == null || content.isBlank() || content.length() > MAX_CONTENT) {
            throw new CustomException(ErrorCode.COMMON_BAD_REQUEST);
        }
        return content;
    }

    /** 첨부 — 남는 것과 합쳐 5개, 한 개 10MB, 허용 확장자 */
    static void validateFiles(List<MultipartFile> files, int existing) {
        if (existing + files.size() > MAX_ATTACHMENTS) throw new CustomException(ErrorCode.BOARD_ATTACHMENT_INVALID);
        for (MultipartFile f : files) {
            if (f.getSize() > MAX_ATTACHMENT_BYTES) throw new CustomException(ErrorCode.BOARD_ATTACHMENT_TOO_LARGE);
            if (!ALLOWED_EXTS.contains(extensionOf(f.getOriginalFilename()))) {
                throw new CustomException(ErrorCode.BOARD_ATTACHMENT_INVALID);
            }
        }
    }

    private void uploadAll(Long postId, List<MultipartFile> files) {
        for (MultipartFile f : files) {
            String name = f.getOriginalFilename() == null ? "file" : f.getOriginalFilename();
            // 키에 원래 이름을 쓰지 않는다 — 한글·공백·슬래시가 섞여도 경로가 깨지지 않게. 이름은 DB에 둔다
            String key = "board/" + postId + "/" + UUID.randomUUID() + "." + extensionOf(name);
            try {
                String path = s3Service.uploadFile(key, f.getBytes(), f.getContentType());
                attachmentRepository.save(BoardAttachment.builder().postId(postId).fileName(name)
                        .contentType(f.getContentType()).sizeBytes(f.getSize()).storagePath(path).build());
            } catch (IOException e) {
                throw new CustomException(ErrorCode.COMMON_INTERNAL_ERROR);
            }
        }
    }

    private void deleteObjectQuietly(String path) {
        try {
            s3Service.deleteObject(path);
        } catch (Exception e) {
            log.warn("게시판 첨부 S3 삭제 실패(무시): {}", path);
        }
    }

    private static List<MultipartFile> nonEmpty(List<MultipartFile> files) {
        return files == null ? List.of() : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
    }

    static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static int pageNo(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }

    private static LocalDateTime toLocal(java.time.Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, KST);
    }

    private User user(String userId) {
        return userRepository.findById(UUID.fromString(userId))
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    private static boolean isAdmin(User user) {
        return user.getRole() == Role.ROLE_ADMIN;
    }
}
