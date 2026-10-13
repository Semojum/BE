package com.semojum.backend.domain.user;

import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.job.service.FooterBrailleService;
import com.semojum.backend.domain.result.entity.PageMemo;
import com.semojum.backend.domain.result.entity.PageResult;
import com.semojum.backend.domain.result.repository.PageMemoRepository;
import com.semojum.backend.domain.result.repository.PageResultRepository;
import com.semojum.backend.domain.result.service.PageResultSerializer;
import com.semojum.backend.domain.user.service.UserService;
import com.semojum.backend.global.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * 쪽 조회가 줄 메모를 함께 준다.
 *
 * <p>쓰기는 작업 저장 본문에만 있고 전용 API가 없으므로, <b>읽는 길은 여기뿐</b>이다.
 * 이게 빠지면 메모가 저장돼도 FE가 다시 받을 수 없다.
 */
class PageMemoReadTest {

    JobRepository jobRepo;
    PageRepository pageRepo;
    PageResultRepository pageResultRepo;
    PageMemoRepository memoRepo;
    UserService service;

    final String USER_ID = UUID.randomUUID().toString();
    final UUID PAGE_ID = UUID.randomUUID();
    Job job;

    @BeforeEach
    void setUp() {
        jobRepo = Mockito.mock(JobRepository.class);
        pageRepo = Mockito.mock(PageRepository.class);
        pageResultRepo = Mockito.mock(PageResultRepository.class);
        memoRepo = Mockito.mock(PageMemoRepository.class);
        service = new UserService(jobRepo, pageRepo, Mockito.mock(RedisTemplate.class),
                Mockito.mock(S3Service.class), pageResultRepo,
                Mockito.mock(PageResultSerializer.class),
                Mockito.mock(FooterBrailleService.class), memoRepo);

        job = Job.builder().id("job1").mode("c").totalPages(1).originalFileName("교재.pdf").build();
        when(jobRepo.findByIdAndUserId(anyString(), any())).thenReturn(Optional.of(job));
        when(pageResultRepo.findByJobIdAndPageNumber(anyString(), anyInt()))
                .thenReturn(Optional.of(PageResult.builder().pageNumber(1).mode("c").status("COMPLETED").build()));
        when(pageRepo.findByJobAndPageNo(any(), anyInt())).thenAnswer(i -> {
            Page p = Page.builder().job(job).pageNo(1).build();
            java.lang.reflect.Field f;
            try { f = Page.class.getDeclaredField("id"); f.setAccessible(true); f.set(p, PAGE_ID); }
            catch (Exception ignored) { }
            return Optional.of(p);
        });
    }

    private PageMemo memo(String panel, String elementId, int line, String lineText, String body) {
        return PageMemo.builder().pageId(PAGE_ID).panel(panel).elementId(elementId)
                .lineIndex(line).lineText(lineText).body(body)
                .createdAt(LocalDateTime.of(2026, 10, 11, 14, 2)).build();
    }

    @Test
    void 쪽_조회가_줄_메모를_함께_준다() {
        when(memoRepo.findByPageIdOrderByElementIdAscLineIndexAsc(PAGE_ID)).thenReturn(List.of(
                memo("TEXT", "t7", 2, "이 시기 도시 인구는 크게 줄었다.", "원문이 「작다」인데 「크다」가 맞습니다")));

        JobResponseDto.JobDetail detail = service.getJobPage(USER_ID, "job1", 1);

        assertEquals(1, detail.memos().size());
        JobResponseDto.Memo m = detail.memos().get(0);
        assertEquals("TEXT", m.panel());
        assertEquals("t7", m.elementId());
        assertEquals(2, m.lineIndex());
        assertEquals("이 시기 도시 인구는 크게 줄었다.", m.lineText(), "그 줄의 원문");
        assertEquals("원문이 「작다」인데 「크다」가 맞습니다", m.body(), "사용자가 쓴 메모");
        assertEquals(LocalDateTime.of(2026, 10, 11, 14, 2), m.createdAt(), "작성 시각 보존");
    }

    @Test
    void 메모가_없으면_빈_배열이다() {
        when(memoRepo.findByPageIdOrderByElementIdAscLineIndexAsc(PAGE_ID)).thenReturn(List.of());

        assertEquals(List.of(), service.getJobPage(USER_ID, "job1", 1).memos(),
                "null이 아니라 빈 배열 — FE가 null 검사 없이 순회한다");
    }

    @Test
    void 두_패널의_메모가_함께_나온다() {
        when(memoRepo.findByPageIdOrderByElementIdAscLineIndexAsc(PAGE_ID)).thenReturn(List.of(
                memo("TEXT", "t1", 0, "원문 줄", "텍스트 메모"),
                memo("BRAILLE", "t1", 0, "⠏⠒⠃⠷", "점자 메모")));

        List<JobResponseDto.Memo> memos = service.getJobPage(USER_ID, "job1", 1).memos();

        assertEquals(2, memos.size());
        assertEquals(List.of("TEXT", "BRAILLE"), memos.stream().map(JobResponseDto.Memo::panel).toList(),
                "같은 블록 id라도 패널로 갈린다");
    }
}
