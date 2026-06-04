package com.semojum.backend.domain.result.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

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
    private int elementId;

    @Column(nullable = false)
    private int x;

    @Column(nullable = false)
    private int y;

    @Column(nullable = false)
    private int x2;

    @Column(nullable = false)
    private int y2;

    @Builder
    public BoundingBox(PageResult pageResult, int elementId, int x, int y, int x2, int y2) {
        this.pageResult = pageResult;
        this.elementId = elementId;
        this.x = x;
        this.y = y;
        this.x2 = x2;
        this.y2 = y2;
    }
}
