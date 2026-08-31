package com.semojum.backend.domain.rule;

/**
 * 점자 규정 한 건 (읽기 전용).
 *
 * <p>원본은 AI 팀 rule registry 와 같은 파일(resources/rules/braille-rules.json)이다.
 * AI 가 보내는 rule_trail.rule_id 는 이 집합의 부분집합이어야 하므로
 * (registry 의 _meta 에 "모든 emit rule_id ⊆ 이 키 집합" 명시) 파일을 코드와 함께 배포해
 * 커밋 단위로 동기화를 보장한다 — DB 에 두면 배포 색을 롤백해도 규정만 새 버전으로 남는다.
 */
public record BrailleRule(
        String ruleId,
        String publisherCode,   // MCST | NLD | NISE — rule_id 접두와 같다
        String publisher,
        String source,
        int version,
        String part,            // 없는 위계는 null
        String chapter,
        String section,
        String paragraph,
        String sectionDisplay,  // 위 4단계를 " · " 로 합친 표시용 한 줄 (rule_trail.section 과 같은 형식)
        String ruleName,
        String contents,
        String tag,
        int displayOrder,       // 원문 순서. 파일 키 순서는 문자열 정렬이라 쓸 수 없다(1.4.10 < 1.4.8)
        String searchBlob       // 소문자 합본 — 검색은 이 한 칸만 본다
) {}
