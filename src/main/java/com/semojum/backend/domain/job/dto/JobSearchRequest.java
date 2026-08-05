package com.semojum.backend.domain.job.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/**
 * 목록 조회 쿼리 파라미터 바인딩 객체.
 *
 * <p>컨트롤러에 {@code @RequestParam}을 여러 개 나열하는 대신 하나로 받는다.
 * 쿼리스트링 형식은 그대로이므로 <b>API 계약에는 변화가 없다</b>.
 * 문자열 → 내부 표현 변환({@code sort=oldest})도 여기서 끝내
 * 컨트롤러가 요청 해석에 관여하지 않게 한다.
 */
@Getter
@Setter
public class JobSearchRequest {

    private String search;
    private List<String> status;
    private List<String> mode;
    private Boolean favorite;
    private String sort = "latest";  // latest | oldest
    private String cursor;
    private int size = JobSearchCondition.DEFAULT_SIZE;

    /**
     * 조회 범위는 <b>경로가 정한다</b> — 폴더 범위는 경로 변수로, 전역은 전용 엔드포인트로 받는다.
     * 그래서 folderId·allScope는 쿼리에서 바인딩하지 않고 호출부가 넘긴다.
     */
    public JobSearchCondition toCondition(UUID folderId, boolean allScope) {
        return new JobSearchCondition(
                folderId,
                allScope,
                search,
                status,
                mode,
                favorite,
                "oldest".equalsIgnoreCase(sort),
                cursor,
                size
        );
    }
}
