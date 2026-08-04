package com.semojum.backend.domain.job.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

/**
 * 목록 조회 쿼리 파라미터 바인딩 객체.
 *
 * <p>컨트롤러에 {@code @RequestParam}을 9개 나열하는 대신 하나로 받는다.
 * 쿼리스트링 형식은 그대로이므로 <b>API 계약에는 변화가 없다</b>.
 * 문자열 → 내부 표현 변환({@code scope=all}, {@code sort=oldest})도 여기서 끝내
 * 컨트롤러가 요청 해석에 관여하지 않게 한다.
 */
@Getter
@Setter
public class JobSearchRequest {

    private UUID folderId;
    private String scope;            // "all"이면 폴더 무관 전역
    private String search;
    private List<String> status;
    private List<String> mode;
    private Boolean favorite;
    private String sort = "latest";  // latest | oldest
    private String cursor;
    private int size = JobSearchCondition.DEFAULT_SIZE;

    public JobSearchCondition toCondition() {
        return new JobSearchCondition(
                folderId,
                "all".equalsIgnoreCase(scope),
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
