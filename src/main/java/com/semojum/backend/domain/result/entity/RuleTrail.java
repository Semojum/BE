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

    @Column(nullable = false)
    private String section;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String excerpt;

    @Builder
    public RuleTrail(UUID elementId, String elementType, String ruleId,
                     String section, String title, String excerpt) {
        this.elementId = elementId;
        this.elementType = elementType;
        this.ruleId = ruleId;
        this.section = section;
        this.title = title;
        this.excerpt = excerpt;
    }
}
