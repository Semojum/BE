package com.semojum.backend.domain.user.entity;

import com.semojum.backend.domain.job.dto.BrailleOptions;
import com.semojum.backend.domain.job.dto.LayoutOptions;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 사용자 점역 양식 — 조판 옵션 + 점역 옵션 한 벌을 이름 붙여 저장해 둔 것 (D7-2 「사용자 양식 설정」).
 *
 * <p>업로드할 때 골라 쓰면 그 값이 <b>작업으로 복사된다</b>. 참조가 아니라 사본인 이유는 양식을
 * 나중에 고쳐도 이미 만든 작업이 바뀌면 안 되기 때문이다.
 *
 * <p>두 묶음을 그대로 담는다 — 양식 적용이 통째 복사 한 줄로 끝나고, 조판 옵션에 항목이 늘어도
 * 양식이 저절로 따라온다. 필드를 평평하게 펴서 이름을 새로 지으면 번역표가 생기고 조용히 어긋난다.
 *
 * <p>기본 양식 5종은 이 표에 없다 — 클래스패스 리소스({@code braille-forms/builtin.json}).
 */
@Entity
@Getter
@Table(name = "braille_forms")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BrailleForm {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(nullable = false, length = 100)
    private String name;

    // 바탕 양식 — D7-2 「바탕 값으로」의 기준. builtin:XXX 또는 사용자 양식 UUID. null = 바탕 없음
    @Column(name = "base_form_id", length = 64)
    private String baseFormId;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "layout_options", nullable = false, columnDefinition = "jsonb")
    private LayoutOptions layoutOptions;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "braille_options", nullable = false, columnDefinition = "jsonb")
    private BrailleOptions brailleOptions;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public BrailleForm(UUID userId, String name, String baseFormId,
                       LayoutOptions layoutOptions, BrailleOptions brailleOptions) {
        this.userId = userId;
        this.name = name;
        this.baseFormId = baseFormId;
        this.layoutOptions = layoutOptions;
        this.brailleOptions = brailleOptions;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /** D7-2 「저장」 — 창이 항목 전부를 보여주므로 통째로 덮어쓴다 */
    public void update(String name, String baseFormId,
                       LayoutOptions layoutOptions, BrailleOptions brailleOptions) {
        this.name = name;
        this.baseFormId = baseFormId;
        this.layoutOptions = layoutOptions;
        this.brailleOptions = brailleOptions;
        this.updatedAt = LocalDateTime.now();
    }
}
