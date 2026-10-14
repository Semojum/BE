package com.semojum.backend.domain.board;

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
import com.semojum.backend.domain.board.service.BoardService;
import com.semojum.backend.domain.support.repository.NoticeRepository;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * QnA 게시판 (기획 6-2 · 피그마 D8 · D8-2).
 *
 * <p>지킬 것: 글·답변은 쓴 사람만 고치고 지운다 · 비밀글은 작성자와 운영자만 연다 · 숨긴 글은 운영자만 본다 ·
 * 아이디는 서버가 가린다 · 조회수는 열 때마다 +1 · 첨부는 5개 · 10MB · 허용 형식.
 */
class BoardServiceTest {

    BoardPostRepository postRepository;
    BoardAttachmentRepository attachmentRepository;
    BoardAnswerRepository answerRepository;
    BoardNotificationRepository notificationRepository;
    NoticeRepository noticeRepository;
    UserRepository userRepository;
    S3Service s3Service;
    BoardService service;

    User author, other, admin, admin2;

    @BeforeEach
    void setUp() {
        postRepository = mock(BoardPostRepository.class);
        attachmentRepository = mock(BoardAttachmentRepository.class);
        answerRepository = mock(BoardAnswerRepository.class);
        notificationRepository = mock(BoardNotificationRepository.class);
        noticeRepository = mock(NoticeRepository.class);
        userRepository = mock(UserRepository.class);
        s3Service = mock(S3Service.class);
        service = new BoardService(postRepository, attachmentRepository, answerRepository, notificationRepository,
                noticeRepository, userRepository, s3Service);

        author = user("testorg01", Role.ROLE_USER);
        other = user("testorg02", Role.ROLE_USER);
        admin = user("webadmin01", Role.ROLE_ADMIN);
        admin2 = user("webadmin02", Role.ROLE_ADMIN);

        when(attachmentRepository.findByPostIdOrderByIdAsc(any())).thenReturn(List.of());
        when(answerRepository.findByPostIdOrderByIdAsc(any())).thenReturn(List.of());
        when(postRepository.save(any())).thenAnswer(inv -> {
            BoardPost p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 128L);
            return p;
        });
        when(answerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(s3Service.uploadFile(anyString(), any(), any())).thenAnswer(inv -> "s3://b/" + inv.getArgument(0));
    }

    private User user(String loginId, Role role) {
        User u = User.builder().loginId(loginId).password("pw").build();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.changeRole(role);
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        return u;
    }

    private String id(User u) {
        return u.getId().toString();
    }

    private BoardPost post(User writer, boolean secret) {
        BoardPost p = BoardPost.builder().userId(writer.getId()).title("크레딧 추가 요청드립니다")
                .content("300쪽 추가 부탁드립니다").secret(secret).build();
        ReflectionTestUtils.setField(p, "id", 126L);
        when(postRepository.findById(126L)).thenReturn(Optional.of(p));
        return p;
    }

    private MockMultipartFile file(String name, int bytes) {
        return new MockMultipartFile("files", name, "application/octet-stream", new byte[bytes]);
    }

    // ── 아이디 가림 ──────────────────────────────────────────────────

    /** 피그마 D8: testorg01 → testo*** — 원래 아이디는 응답에 싣지 않는다 */
    @Test
    void 글쓴이_아이디는_앞_절반만_보인다() {
        BoardPost p = post(author, false);
        assertEquals("testo***", service.getPost(id(other), 126L).author());
    }

    @Test
    void 운영자가_쓴_글은_운영자로_보인다() {
        post(admin, false);
        assertEquals("운영자", service.getPost(id(author), 126L).author());
    }

    // ── 비밀글 · 숨김 · 삭제 ──────────────────────────────────────────

    @Test
    void 비밀글은_작성자와_운영자만_연다() {
        post(author, true);

        CustomException e = assertThrows(CustomException.class, () -> service.getPost(id(other), 126L));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
        assertDoesNotThrow(() -> service.getPost(id(author), 126L));
        assertDoesNotThrow(() -> service.getPost(id(admin), 126L));
    }

    /** 숨긴 글은 운영자 콘솔에서만 보인다 — 작성자에게도 없는 글이다 */
    @Test
    void 숨긴_글은_운영자만_본다() {
        post(author, false).updateHidden(true);

        CustomException e = assertThrows(CustomException.class, () -> service.getPost(id(author), 126L));
        assertEquals(ErrorCode.BOARD_POST_NOT_FOUND, e.getErrorCode());
        assertTrue(service.getPost(id(admin), 126L).hidden());
    }

    @Test
    void 지운_글은_누구에게도_없다() {
        post(author, false).delete();

        for (User u : List.of(author, admin)) {
            CustomException e = assertThrows(CustomException.class, () -> service.getPost(id(u), 126L));
            assertEquals(ErrorCode.BOARD_POST_NOT_FOUND, e.getErrorCode());
        }
    }

    // ── 조회수 ───────────────────────────────────────────────────────

    /** 열 때마다 +1 — 작성자 본인이 열어도, 같은 사람이 다시 열어도 센다(2026-10-14 결정) */
    @Test
    void 조회수는_열_때마다_오른다() {
        post(author, false);

        service.getPost(id(other), 126L);
        service.getPost(id(other), 126L);
        assertEquals(3, service.getPost(id(author), 126L).viewCount());
    }

    // ── 수정 · 삭제는 본인만 ─────────────────────────────────────────

    @Test
    void 남의_글은_고치거나_지울_수_없다() {
        post(author, false);

        for (User u : List.of(other, admin)) {   // 운영자도 남의 글 본문은 못 고친다 — 숨김·삭제만
            CustomException e = assertThrows(CustomException.class,
                    () -> service.update(id(u), 126L, "제목", "본문", false, null, null));
            assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
        }
        CustomException e = assertThrows(CustomException.class, () -> service.delete(id(other), 126L));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
    }

    @Test
    void 내_글은_고치고_지울_수_있다() {
        BoardPost p = post(author, false);

        BoardDto.PostDetail d = service.update(id(author), 126L, "새 제목", "새 본문", true, null, null);
        assertEquals("새 제목", d.title());
        assertTrue(d.secret());

        service.delete(id(author), 126L);
        assertTrue(p.isDeleted());
    }

    @Test
    void 운영자는_남의_글을_숨기거나_지울_수_있다() {
        BoardPost p = post(author, false);

        service.updateHidden(126L, new BoardDto.UpdateHidden(true));
        assertTrue(p.isHidden());
        service.deleteByAdmin(126L);
        assertTrue(p.isDeleted());
    }

    // ── 답변 · 알림 ──────────────────────────────────────────────────

    private BoardAnswer answer(User writer) {
        BoardAnswer a = BoardAnswer.builder().postId(126L).userId(writer.getId()).content("반영했습니다").build();
        ReflectionTestUtils.setField(a, "id", 88L);
        when(answerRepository.findById(88L)).thenReturn(Optional.of(a));
        return a;
    }

    /** 답변도 쓴 사람만 — 다른 운영자도 못 고친다 */
    @Test
    void 답변은_쓴_운영자만_고치고_지운다() {
        answer(admin);

        CustomException e = assertThrows(CustomException.class,
                () -> service.updateAnswer(id(admin2), 88L, new BoardDto.UpdateAnswer("수정")));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());
        e = assertThrows(CustomException.class, () -> service.deleteAnswer(id(admin2), 88L));
        assertEquals(ErrorCode.COMMON_FORBIDDEN, e.getErrorCode());

        assertEquals("수정", service.updateAnswer(id(admin), 88L, new BoardDto.UpdateAnswer("수정")).content());
    }

    @Test
    void 알림_보내기를_체크하면_글쓴이_알림이_생긴다() {
        post(author, false);
        when(notificationRepository.findByPostId(126L)).thenReturn(Optional.empty());

        service.createAnswer(id(admin), 126L, new BoardDto.CreateAnswer("반영했습니다", true));

        verify(notificationRepository).save(argThat(n -> n.getUserId().equals(author.getId()) && !n.isRead()));
    }

    @Test
    void 알림_보내기를_안_하면_알림이_없다() {
        post(author, false);

        service.createAnswer(id(admin), 126L, new BoardDto.CreateAnswer("반영했습니다", false));

        verify(notificationRepository, never()).save(any());
        verify(notificationRepository, never()).findByPostId(any());
    }

    /** 글 하나에 알림 하나 — 이미 읽은 알림은 새 답변으로 다시 안 읽음이 된다 */
    @Test
    void 두_번째_답변은_기존_알림을_다시_안_읽음으로_돌린다() {
        post(author, false);
        BoardNotification n = BoardNotification.builder().userId(author.getId()).postId(126L)
                .lastAnsweredAt(LocalDateTime.now().minusDays(1)).build();
        n.markRead();
        when(notificationRepository.findByPostId(126L)).thenReturn(Optional.of(n));

        service.createAnswer(id(admin), 126L, new BoardDto.CreateAnswer("추가 답변", true));

        assertFalse(n.isRead());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void 작성자가_글을_열면_알림이_읽음이_된다() {
        post(author, false);
        BoardNotification n = BoardNotification.builder().userId(author.getId()).postId(126L)
                .lastAnsweredAt(LocalDateTime.now()).build();
        when(notificationRepository.findByPostId(126L)).thenReturn(Optional.of(n));

        service.getPost(id(other), 126L);
        assertFalse(n.isRead(), "남이 열면 그대로");
        service.getPost(id(author), 126L);
        assertTrue(n.isRead());
    }

    // ── 첨부 ─────────────────────────────────────────────────────────

    @Test
    void 첨부는_다섯_개까지다() {
        List<MultipartFile> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) six.add(file("a" + i + ".png", 10));

        CustomException e = assertThrows(CustomException.class,
                () -> service.create(id(author), "제목", "본문", false, six));
        assertEquals(ErrorCode.BOARD_ATTACHMENT_INVALID, e.getErrorCode());
        verify(postRepository, never()).save(any());
    }

    @Test
    void 첨부_한_개가_10MB를_넘으면_413이다() {
        CustomException e = assertThrows(CustomException.class, () -> service.create(id(author), "제목", "본문",
                false, List.of(file("big.pdf", 10 * 1024 * 1024 + 1))));
        assertEquals(ErrorCode.BOARD_ATTACHMENT_TOO_LARGE, e.getErrorCode());
    }

    @Test
    void 실행_파일은_받지_않는다() {
        for (String name : List.of("setup.exe", "run.sh", "noext")) {
            CustomException e = assertThrows(CustomException.class,
                    () -> service.create(id(author), "제목", "본문", false, List.of(file(name, 10))));
            assertEquals(ErrorCode.BOARD_ATTACHMENT_INVALID, e.getErrorCode(), name);
        }
    }

    /** 앱이 만든 「현재 작업 정보」 파일도 일반 첨부로 받는다 */
    @Test
    void 첨부는_S3에_올리고_원래_이름을_기록한다() {
        service.create(id(author), "제목", "본문", false, List.of(file("작업정보_2026-10-06.txt", 2150)));

        verify(s3Service).uploadFile(startsWith("board/128/"), any(), any());
        verify(attachmentRepository).save(argThat(a -> a.getFileName().equals("작업정보_2026-10-06.txt")
                && a.getSizeBytes() == 2150));
    }

    /** 수정 때는 남기는 첨부와 새 첨부를 합쳐 5개 */
    @Test
    void 수정은_남기는_첨부와_합쳐_다섯_개까지다() {
        post(author, false);
        List<BoardAttachment> current = new ArrayList<>();
        for (long i = 1; i <= 4; i++) {
            BoardAttachment a = BoardAttachment.builder().postId(126L).fileName(i + ".png").sizeBytes(1)
                    .storagePath("s3://b/" + i).build();
            ReflectionTestUtils.setField(a, "id", i);
            current.add(a);
        }
        when(attachmentRepository.findByPostIdOrderByIdAsc(126L)).thenReturn(current);

        CustomException e = assertThrows(CustomException.class, () -> service.update(id(author), 126L, "제목", "본문",
                false, List.of(1L, 2L, 3L, 4L), List.of(file("a.png", 1), file("b.png", 1))));
        assertEquals(ErrorCode.BOARD_ATTACHMENT_INVALID, e.getErrorCode());

        // 둘을 빼면 둘을 더할 수 있다 — 뺀 것은 지운다
        service.update(id(author), 126L, "제목", "본문", false, List.of(1L, 2L),
                List.of(file("a.png", 1), file("b.png", 1)));
        verify(attachmentRepository).deleteAll(argThat(list -> ((List<?>) list).size() == 2));
    }

    // ── 입력 검증 · 목록 ─────────────────────────────────────────────

    @Test
    void 제목_100자_본문_10000자를_넘으면_400이다() {
        for (String[] bad : new String[][]{{" ", "본문"}, {"가".repeat(101), "본문"}, {"제목", "가".repeat(10_001)}}) {
            CustomException e = assertThrows(CustomException.class,
                    () -> service.create(id(author), bad[0], bad[1], false, null));
            assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
        }
    }

    @Test
    void 모르는_검색_범위는_400이다() {
        CustomException e = assertThrows(CustomException.class,
                () -> service.list(id(author), 1, "BODY", "크레딧", false));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    /** 공지는 그냥 목록일 때만 위에 붙는다 — 검색 결과에 공지가 끼면 결과가 아니다 */
    @Test
    void 검색할_때는_공지를_붙이지_않는다() {
        when(postRepository.search(any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.list(id(author), 1, null, "크레딧", false);
        service.list(id(author), 1, null, null, true);
        verify(noticeRepository, never()).findVisibleForOrganization(any(), any());

        service.list(id(author), 1, null, null, false);
        verify(noticeRepository).findVisibleForOrganization(any(), any());
    }

    /** 일반 사용자 목록엔 숨긴 글이 빠진다(admin=false), 운영자 콘솔은 포함 */
    @Test
    void 숨긴_글은_일반_목록에서_빠진다() {
        when(postRepository.search(any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.list(id(author), 1, null, null, false);
        verify(postRepository).search(eq(author.getId()), eq(false), eq(false), eq(false), eq(false),
                eq("TITLE_CONTENT"), eq("%"), any());

        service.listForAdmin(id(admin), 1, null, null, true);
        verify(postRepository).search(eq(admin.getId()), eq(true), eq(false), eq(true), eq(false),
                eq("TITLE_CONTENT"), eq("%"), any());
    }
}
