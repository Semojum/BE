package com.semojum.backend.domain.result.service;

import com.semojum.backend.domain.job.dto.JobRequestDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 페이지 일괄 저장의 입구 — mode c(3패널) 텍스트 저장이면 재점역을 <b>트랜잭션 밖에서</b> 먼저 끝낸다.
 *
 * <p>AI 슬롯은 페이지 변환 워커와 같이 쓰여 수십 초 기다릴 수 있다. 그동안 DB 커넥션을 쥐고 있으면
 * 풀이 마른다(SSE 장기 연결 때문에 open-in-view도 꺼 둔 서비스다). 그래서
 * ① 읽기 트랜잭션으로 바뀐 텍스트를 추리고 ② AI를 부르고 ③ 쓰기 트랜잭션에서 한 번에 저장한다.
 * 재점역이 실패하면 ③에 가지 않는다 — 텍스트만 저장돼 점자와 어긋나는 일을 막는다.
 */
@Service
@RequiredArgsConstructor
public class PageSaveFacade {

    private final PageSaveService pageSaveService;
    private final BrailleRetranslator brailleRetranslator;

    public List<Map<String, Object>> save(String userId, String jobId, int pageNo, String target,
                                          List<JobRequestDto.SaveElement> elements) {
        Set<String> texts = pageSaveService.textsToRetranslate(userId, jobId, pageNo, target, elements);
        Map<String, String> bodies = texts.isEmpty() ? Map.of() : brailleRetranslator.translateBodies(texts);
        return pageSaveService.savePage(userId, jobId, pageNo, target, elements, bodies);
    }
}
