package com.semojum.backend.domain.result.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "bounding_boxes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BoundingBox {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "page_result_id", nullable = false)
    private PageResult pageResult;

    @Column(nullable = false)
    private String elementId;

    @Column(nullable = false)
    private int x;

    @Column(nullable = false)
    private int y;

    @Column(nullable = false)
    private int x2;

    @Column(nullable = false)
    private int y2;

    private String type;
    private Integer headingLevel;
    private String captionRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> flags;

    @Builder
    public BoundingBox(PageResult pageResult, String elementId, int x, int y, int x2, int y2,
                       String type, Integer headingLevel, String captionRef, List<String> flags) {
        this.pageResult = pageResult;
        this.elementId = elementId;
        this.x = x;
        this.y = y;
        this.x2 = x2;
        this.y2 = y2;
        this.type = type;
        this.headingLevel = headingLevel;
        this.captionRef = captionRef;
        this.flags = flags;
    }
}
