package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.job.dto.JobRequestDto;
import com.semojum.backend.domain.job.entity.Job;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.job.scheduler.JobDispatcher;
import com.semojum.backend.domain.user.repository.BrailleFormRepository;
import com.semojum.backend.domain.user.service.BuiltinBrailleForms;
import com.semojum.backend.global.hwp.HwpToPdfConverter;
import com.semojum.backend.global.s3.S3Service;
import com.semojum.backend.global.thumbnail.ThumbnailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 텍스트 작업(TXT 업로드 · 새 문서) — V3.1부터 AI를 거치지 않는다(기획 확정 2026-10-14).
 *
 * <p>AI가 점자를 주지 않게 됐고(proto에서 점역 결과 삭제), 텍스트에서 AI가 해 줄 일이 없다.
 * 점역·쪽 나누기는 앱이 하고 서버는 저장용 jobId만 준다. 지킬 것은 넷이다 —
 * 즉시 COMPLETED, 큐에 안 넣음(= 크레딧 0), S3에 안 올림, 쪽을 미리 만들지 않음.
 */
class TextJobTest {

    private JobRepository jobRepository;
    private PageRepository pageRepository;
    private S3Service s3Service;
    private JobDispatcher dispatcher;
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> redisTemplate = Mockito.mock(RedisTemplate.class);
    private JobService service;
    private final String userId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        jobRepository = Mockito.mock(JobRepository.class);
        pageRepository = Mockito.mock(PageRepository.class);
        s3Service = Mockito.mock(S3Service.class);
        dispatcher = Mockito.mock(JobDispatcher.class);
        UserRepository userRepository = Mockito.mock(UserRepository.class);

        User user = User.builder().loginId("semojum01").password("pw").build();
        ReflectionTestUtils.setField(user, "id", UUID.fromString(userId));
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.of(user));

        BuiltinBrailleForms builtin = new BuiltinBrailleForms();
        builtin.load();
        service = new JobService(jobRepository, pageRepository, userRepository, s3Service,
                redisTemplate, dispatcher, Mockito.mock(ThumbnailService.class),
                Mockito.mock(HwpToPdfConverter.class), Mockito.mock(FooterBrailleService.class),
                new UploadOptionsResolver(Mockito.mock(BrailleFormRepository.class), builtin));
    }

    private JobRequestDto.NewDocument doc(String text, String fileName) {
        return new JobRequestDto.NewDocument(text, fileName, null, null, null, null, null);
    }

    /** 기다릴 변환이 없다 — 앱이 SSE를 붙이지 않고 바로 편집에 들어간다 */
    @Test
    void 새_문서는_만들자마자_COMPLETED다() {
        var created = service.createNewDocument(userId, doc("본문", "중학 수학"), null);

        assertEquals("COMPLETED", created.status());
        assertEquals("b", created.mode());
        assertEquals(0, created.totalPages(), "쪽 수는 첫 저장이 정한다");

        ArgumentCaptor<Job> job = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(job.capture());
        assertEquals("COMPLETED", job.getValue().getStatus());
    }

    /** 큐에 넣지 않는다 = AI를 부르지 않는다 = 크레딧이 차감되지 않는다(차감은 AI 결과가 올 때만) */
    @Test
    void 새_문서는_AI_큐에도_S3에도_가지_않는다() {
        service.createNewDocument(userId, doc("본문", null), null);

        verifyNoInteractions(dispatcher, s3Service, pageRepository, redisTemplate);
    }

    /** 서버가 본문을 쓰지 않으니 안 보내도 된다 — 구 클라이언트는 보내도 무시된다 */
    @Test
    void 새_문서는_본문_없이도_만들어진다() {
        assertDoesNotThrow(() -> service.createNewDocument(userId, doc(null, null), null));
    }

    /** TXT 업로드도 같은 경로 — 파일 내용은 쓰지 않는다(앱이 이미 쥐고 있다) */
    @Test
    void TXT_업로드도_AI를_거치지_않는다() throws Exception {
        var file = new MockMultipartFile("file", "교재.txt", "text/plain", "본문\n둘째 줄".getBytes());

        var created = service.createJob(userId, List.of(file), null, false, null,
                null, null, null, null);

        assertEquals("COMPLETED", created.status());
        verifyNoInteractions(dispatcher, s3Service, pageRepository, redisTemplate);
    }

    @Test
    void 이름을_안_주면_무제가_된다() {
        assertEquals("무제", JobService.resolveNewDocumentName(doc("본문", null)));
        assertEquals("무제", JobService.resolveNewDocumentName(doc("본문", "   ")));
        assertEquals("중학 수학 1단원", JobService.resolveNewDocumentName(doc("본문", " 중학 수학 1단원 ")));
    }
}
