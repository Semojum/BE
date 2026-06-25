package com.semojum.backend.domain.result.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// 점역사 수정 이력(RLHF 학습용 스냅샷). 수정 1건 = 1행, 입력 컨텍스트까지 자기완결적으로 보존.
@Entity
@Table(name = "edit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @Column(nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(nullable = false)
    private String jobId;

    @Column(nullable = false)
    private int pageNo;

    // 수정된 요소 행의 PK (text_elements.id 또는 braille_elements.id)
    @Column(nullable = false, columnDefinition = "uuid")
    private UUID elementId;

    @Column(nullable = false)
    private String elementType; // "TEXT" | "BRAILLE"

    @Column(nullable = false)
    private String mode; // "a" | "b" | "c"

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> beforeContent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> afterContent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> aiOriginalContent;

    // mode a/c: 원본 페이지 입력 정보 (PDF 바이너리는 저장 안 하고 gs 경로만)
    private String sourcePdfPath;
    private Integer imageWidth;
    private Integer imageHeight;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String boundingBox; // {x,y,x2,y2,type}

    // mode b: 변환에 사용한 원본 한글 텍스트
    @Column(columnDefinition = "text")
    private String sourceText;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    public EditLog(UUID userId, String jobId, int pageNo, UUID elementId, String elementType,
                   String mode, List<String> beforeContent, List<String> afterContent,
                   List<String> aiOriginalContent, String sourcePdfPath, Integer imageWidth,
                   Integer imageHeight, String boundingBox, String sourceText) {
        this.userId = userId;
        this.jobId = jobId;
        this.pageNo = pageNo;
        this.elementId = elementId;
        this.elementType = elementType;
        this.mode = mode;
        this.beforeContent = beforeContent;
        this.afterContent = afterContent;
        this.aiOriginalContent = aiOriginalContent;
        this.sourcePdfPath = sourcePdfPath;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.boundingBox = boundingBox;
        this.sourceText = sourceText;
        this.createdAt = LocalDateTime.now();
    }
}
