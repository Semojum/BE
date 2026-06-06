package com.semojum.backend.domain.result.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "rule_trails")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RuleTrail {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid", updatable = false)
    private UUID id;

    // 다형성 FK: text_elements.id 또는 braille_elements.id
    @Column(nullable = false, columnDefinition = "uuid")
    private UUID elementId;

    // "TEXT" or "BRAILLE"
    @Column(nullable = false)
    private String elementType;

    @Column(nullable = false)
    private String ruleId;

    private String source;
    private String priority;

    @Column(nullable = false)
    private String section;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String excerpt;

    private Integer spanStart;
    private Integer spanEnd;
    private String tag;

    @Builder
    public RuleTrail(UUID elementId, String elementType, String ruleId,
                     String source, String priority,
                     String section, String title, String excerpt,
                     Integer spanStart, Integer spanEnd, String tag) {
        this.elementId = elementId;
        this.elementType = elementType;
        this.ruleId = ruleId;
        this.source = source;
        this.priority = priority;
        this.section = section;
        this.title = title;
        this.excerpt = excerpt;
        this.spanStart = spanStart;
        this.spanEnd = spanEnd;
        this.tag = tag;
    }
}
