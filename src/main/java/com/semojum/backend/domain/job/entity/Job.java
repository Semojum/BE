package com.semojum.backend.domain.job.entity;

import com.semojum.backend.domain.auth.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Job {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String mode;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int totalPages;

    @Column(columnDefinition = "integer[]")
    private int[] failedPages;

    private String originalFilePath;
    private String originalFileName;
    private String thumbnailUrl;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    // updated_at은 DB default(now()) 및 JobRepository의 명시적 UPDATE로만 갱신한다.
    // 엔티티 저장으로 덮어쓰지 않도록 insertable/updatable=false로 둔다.
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    // ===== V3 마이페이지 디렉토리 =====
    // 파일 이름은 originalFileName 하나만 사용한다 (팀 결정: 파일 하나 = 이름 하나).
    // 카드 표시·이름 변경 모두 이 컬럼 대상이며, 업로드 시 중복이면 "(2)"가 자동 부여된다.

    // 소속 폴더. NULL = 루트(전체)
    @Column(name = "folder_id")
    private UUID folderId;

    // 값이 있으면 휴지통 (30일 후 완전 삭제)
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // 카드 날짜·정렬 기준. updated_at(변환 파이프라인·StaleJobScheduler 전용)과 분리
    @Column(name = "last_modified_at", nullable = false)
    private LocalDateTime lastModifiedAt;

    // 변환 취소 기록 (V14) — 결과물은 완료분까지로 잘리지만 원래 규모·취소 시각은 남긴다 (운영·CS용, 화면 로직 무관)
    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "original_total_pages")
    private Integer originalTotalPages;

    // 페이지 일괄 저장 API용 (V3 4장) — 컬럼 선반영
    @Column(name = "last_edited_page")
    private Integer lastEditedPage;

    @Column(name = "is_edited", nullable = false)
    private boolean isEdited;

    // 업로드 시 선택 — 점자 판면 마지막 줄에 쪽번호를 넣을지 여부 (조판·렌더링 기준)
    @Column(name = "insert_page_number", nullable = false)
    private boolean insertPageNumber;

    // 업로드 시 입력 — 꼬리말(묵자, V15). 다운로드(brf) 때 TranslateText로 점역해 페이지행 가운데 배치
    @Column(name = "footer_text", length = 200)
    private String footerText;

    // 업로드 시 고른 조판 옵션 (V30) — 한 줄 칸 수·한 면 줄 수·페이지행·꼬리말 정렬·고급 점역 등.
    // 기획 확정 전이라 컬럼을 쪼개지 않는다. null = 옵션 없이 만든 기존 작업 → 코드가 기본값으로 처리
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "layout_options", columnDefinition = "jsonb")
    private com.semojum.backend.domain.job.dto.LayoutOptions layoutOptions;

    // 접속 메타데이터 (V19, T1-4 요청 정보) — 생성 시 1회 기록. 위치는 표시 시점에 IP로 GeoIP 조회
    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "client_os", length = 50)
    private String clientOs;

    @Column(name = "client_browser", length = 80)
    private String clientBrowser;

    @Column(name = "client_user_agent", length = 300)
    private String clientUserAgent;

    // 마이페이지 즐겨찾기 (목록 필터·정렬용)
    @Column(name = "is_favorite", nullable = false)
    private boolean isFavorite;

    // 관리자 사본(send-to-mypage) 표식 (V28) — 실제 변환이 아니므로 통계(건수·쪽수·원가)에서 제외
    @Column(name = "admin_copy", nullable = false)
    private boolean adminCopy;

    @Builder
    public Job(String id, User user, String mode, int totalPages, String originalFileName,
               String thumbnailUrl, boolean insertPageNumber, String footerText, boolean adminCopy,
               com.semojum.backend.domain.job.dto.LayoutOptions layoutOptions) {
        this.id = id;
        this.user = user;
        this.mode = mode;
        this.totalPages = totalPages;
        this.originalFileName = originalFileName;
        this.thumbnailUrl = thumbnailUrl;
        this.insertPageNumber = insertPageNumber;
        this.footerText = footerText;
        this.layoutOptions = layoutOptions;
        this.adminCopy = adminCopy;
        this.isFavorite = false;
        this.status = "PENDING";
        this.failedPages = new int[]{};
        this.startedAt = LocalDateTime.now();
        this.lastModifiedAt = LocalDateTime.now();
        this.isEdited = false;
    }

    // 접속 메타데이터 기록 — Job 생성 직후 1회 (ClientInfoResolver 산출값)
    public void recordClientInfo(String ip, String os, String browser, String userAgent) {
        this.clientIp = ip;
        this.clientOs = os;
        this.clientBrowser = browser;
        this.clientUserAgent = userAgent;
    }

    // ===== V3 도메인 메서드 =====
    public boolean isInProgress() {
        return "PENDING".equals(status) || "IN_PROGRESS".equals(status);
    }

    public boolean isTrashed() {
        return deletedAt != null;
    }

    // 이름·위치 변경은 파일 내용이 바뀌는 게 아니므로 카드 날짜를 건드리지 않는다(윈도우 탐색기와 동일)
    public void rename(String fileName) {
        this.originalFileName = fileName;
    }

    public void moveToFolder(UUID folderId) {
        this.folderId = folderId;
    }

    // 폴더째 삭제 시 폴더와 같은 시각을 공유해야 배치 복원이 가능하므로 시각을 받는다
    public void moveToTrash(LocalDateTime at) {
        this.deletedAt = at;
    }

    public void restoreTo(UUID folderId) {
        this.deletedAt = null;
        this.folderId = folderId;
    }

    /**
     * 점역사가 페이지 내용을 편집했을 때 카드 날짜·마지막 편집 페이지를 갱신한다.
     *
     * <p>{@code last_modified_at}은 "파일 내용이 마지막으로 바뀐 시각"이다. 이름 변경·폴더 이동·
     * 휴지통 복원처럼 내용이 그대로인 동작은 갱신하지 않는다. 변환 진행 상황은 별도로
     * {@code updated_at}이 담당하므로 여기서 건드리지 않는다.
     *
     * <p>{@code last_edited_page}는 재시작 복구용이다 — FE는 가장 최근에 수정한 작업의
     * 이 페이지로 바로 이동한다.
     */
    public void markContentEdited(int pageNo) {
        this.lastModifiedAt = LocalDateTime.now();
        this.lastEditedPage = pageNo;
        this.isEdited = true;
    }

    /** 조판에 쓸 옵션 — 없으면(기존 작업) 구 insert_page_number만 반영한 기본값을 준다 */
    public com.semojum.backend.domain.job.dto.LayoutOptions resolveLayoutOptions() {
        return layoutOptions != null
                ? layoutOptions.withDefaults()
                : com.semojum.backend.domain.job.dto.LayoutOptions.legacy(insertPageNumber);
    }

    public void updateStatus(String status) {
        this.status = status;
    }

    // 변환 취소 확정 시 기록 — total_pages가 잘리기 전의 원래 값을 보존한다
    public void markCanceled(int originalTotalPages) {
        this.canceledAt = LocalDateTime.now();
        this.originalTotalPages = originalTotalPages;
    }

    // 즐겨찾기 토글 — 카드 날짜(last_modified_at)는 건드리지 않는다(내용 변경이 아니므로)
    public boolean toggleFavorite() {
        this.isFavorite = !this.isFavorite;
        return this.isFavorite;
    }

    public void updateThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public void complete(int[] failedPages) {
        this.status = "COMPLETED";
        this.failedPages = failedPages;
        this.finishedAt = LocalDateTime.now();
    }
}