package com.semojum.backend.domain.board.controller;

import com.semojum.backend.domain.board.dto.BoardDto;
import com.semojum.backend.domain.board.service.BoardService;
import com.semojum.backend.global.exception.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/** QnA 게시판 — 정보 › 게시판 (기획 6-2 · 피그마 D8 · D8-2). 로그인한 모든 계정(기관 관리자 포함) */
@RestController
@RequestMapping("/api/board")
@RequiredArgsConstructor
public class BoardController {

    private final BoardService boardService;

    /** 목록 · 검색 — 공지 고정 + 10건씩. field: TITLE · CONTENT · TITLE_CONTENT(기본) · AUTHOR */
    @GetMapping("/posts")
    public ApiResponse<BoardDto.PostList> list(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) String field,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "false") boolean mine) {
        return ApiResponse.success(boardService.list(userDetails.getUsername(), page, field, keyword, mine));
    }

    @GetMapping("/posts/{postId}")
    public ApiResponse<BoardDto.PostDetail> get(@AuthenticationPrincipal UserDetails userDetails,
                                                @PathVariable Long postId) {
        return ApiResponse.success(boardService.getPost(userDetails.getUsername(), postId));
    }

    /** 글쓰기 — multipart. files는 같은 이름으로 여러 개(최대 5개). 「현재 작업 정보」도 앱이 만든 파일로 여기 담는다 */
    @PostMapping(value = "/posts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<BoardDto.Created> create(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam String title,
            @RequestParam String content,
            @RequestParam(required = false) Boolean secret,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return ApiResponse.success(boardService.create(userDetails.getUsername(), title, content, secret, files));
    }

    /** 글 수정 — 본인만. keepAttachmentIds에 든 첨부만 남기고 files를 더한다 */
    @PutMapping(value = "/posts/{postId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<BoardDto.PostDetail> update(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable Long postId,
            @RequestParam String title,
            @RequestParam String content,
            @RequestParam(required = false) Boolean secret,
            @RequestParam(required = false) List<Long> keepAttachmentIds,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return ApiResponse.success(boardService.update(userDetails.getUsername(), postId, title, content, secret,
                keepAttachmentIds, files));
    }

    @DeleteMapping("/posts/{postId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserDetails userDetails, @PathVariable Long postId) {
        boardService.delete(userDetails.getUsername(), postId);
        return ApiResponse.success(null);
    }

    /** 공지 상세 — 운영자 콘솔에서 쓴 공지 */
    @GetMapping("/notices/{noticeId}")
    public ApiResponse<BoardDto.NoticeDetail> notice(@AuthenticationPrincipal UserDetails userDetails,
                                                     @PathVariable UUID noticeId) {
        return ApiResponse.success(boardService.getNotice(userDetails.getUsername(), noticeId));
    }

    /** 내 글에 달린 답변 알림 */
    @GetMapping("/notifications")
    public ApiResponse<BoardDto.Notifications> notifications(@AuthenticationPrincipal UserDetails userDetails) {
        return ApiResponse.success(boardService.notifications(userDetails.getUsername()));
    }

    /** 「모두 읽음」 — 하나씩은 그 글을 열면 읽음이 된다 */
    @PatchMapping("/notifications/read-all")
    public ApiResponse<Void> readAll(@AuthenticationPrincipal UserDetails userDetails) {
        boardService.readAllNotifications(userDetails.getUsername());
        return ApiResponse.success(null);
    }
}
