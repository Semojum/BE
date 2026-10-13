package com.semojum.backend.domain.job.service;

import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.job.entity.Page;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.job.scheduler.JobDispatcher;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.hwp.HwpToPdfConverter;
import com.semojum.backend.global.s3.S3Service;
import com.semojum.backend.global.thumbnail.ThumbnailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 이미지 업로드 (V3.1) — 한 장이 한 쪽, 쪽 순서는 보낸 순서 (피그마 C11 「이미지 올리기 확인」).
 *
 * <p>여러 파일을 받을 수 있는 유일한 입력이라, 개수 검증이 여기서만 의미를 갖는다.
 */
class ImageUploadTest {

    // ── 확장자 판정 ──────────────────────────────────────────────

    @Test
    void 이미지도_모드는_c다() {
        assertEquals("c", JobService.modeForExtension("jpg"));
        assertEquals("c", JobService.modeForExtension("jpeg"));
        assertEquals("c", JobService.modeForExtension("png"));
    }

    /** 피그마는 JPG·PNG라고만 적었지만 .jpeg는 같은 형식이라 거절하면 사용자가 막힌다 */
    @Test
    void jpeg도_이미지로_본다() {
        assertTrue(JobService.isImage("jpeg"));
        assertFalse(JobService.isImage("pdf"));
        assertFalse(JobService.isImage("txt"));
    }

    @Test
    void 확장자는_소문자로_읽는다() {
        assertEquals("png", JobService.extensionOf("표지.PNG"));
        assertEquals("jpg", JobService.extensionOf("a.b.jpg"));
        assertEquals("", JobService.extensionOf("확장자없음"));
        assertEquals("", JobService.extensionOf(null));
    }

    @Test
    void png만_image_png다() {
        assertEquals("image/png", JobService.imageMime("png"));
        assertEquals("image/jpeg", JobService.imageMime("jpg"));
        assertEquals("image/jpeg", JobService.imageMime("jpeg"));
    }

    // ── 파일 개수 ────────────────────────────────────────────────

    @Test
    void 이미지는_여러_장을_받는다() {
        JobService.validateUploadFiles(List.of("jpg", "png", "jpeg"));
    }

    @Test
    void 파일이_없으면_400이다() {
        CustomException e = assertThrows(CustomException.class,
                () -> JobService.validateUploadFiles(List.of()));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    /** PDF는 한 파일이 문서 하나다 — 여러 개를 이어붙일 기준이 없다 */
    @Test
    void 이미지가_아닌_파일은_하나만_받는다() {
        JobService.validateUploadFiles(List.of("pdf"));

        CustomException e = assertThrows(CustomException.class,
                () -> JobService.validateUploadFiles(List.of("pdf", "pdf")));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    /** 섞이면 쪽 순서를 정할 수 없다 */
    @Test
    void 이미지와_문서를_섞으면_400이다() {
        CustomException e = assertThrows(CustomException.class,
                () -> JobService.validateUploadFiles(List.of("jpg", "pdf")));
        assertEquals(ErrorCode.COMMON_BAD_REQUEST, e.getErrorCode());
    }

    // ── 쪽 생성 ──────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void 이미지_세_장이_쪽_셋이_되고_보낸_순서를_지킨다() throws Exception {
        PageRepository pageRepository = Mockito.mock(PageRepository.class);
        JobDispatcher dispatcher = Mockito.mock(JobDispatcher.class);
        S3Service s3Service = Mockito.mock(S3Service.class);
        UserRepository userRepository = Mockito.mock(UserRepository.class);
        RedisTemplate<String, String> redisTemplate = Mockito.mock(RedisTemplate.class);

        when(redisTemplate.opsForHash()).thenReturn(Mockito.mock(HashOperations.class));
        when(userRepository.findById(any(UUID.class)))
                .thenReturn(Optional.of(user()));
        // S3가 돌려주는 전체 경로 = 올린 키 (업로드 키가 그대로 태스크·Page에 실리는지 보기 위해)
        when(s3Service.uploadFile(anyString(), any(), anyString()))
                .thenAnswer(call -> call.getArgument(0));

        JobService service = new JobService(
                Mockito.mock(JobRepository.class), pageRepository, userRepository, s3Service,
                redisTemplate, dispatcher, Mockito.mock(ThumbnailService.class),
                Mockito.mock(HwpToPdfConverter.class), Mockito.mock(FooterBrailleService.class));

        // 사용자가 「순서 바꾸기」로 이름순이 아닌 순서를 보낸 상황
        List<MultipartFile> files = List.of(
                image("3-표지.png", 30), image("1-본문.jpg", 10), image("2-본문.jpeg", 20));

        var created = service.createJob(UUID.randomUUID().toString(), files,
                null, false, null, null, null);

        assertEquals("c", created.mode());
        assertEquals(3, created.totalPages());

        ArgumentCaptor<List<Page>> pages = ArgumentCaptor.forClass(List.class);
        Mockito.verify(pageRepository).saveAll(pages.capture());

        List<Page> saved = pages.getValue();
        assertEquals(3, saved.size());
        // 보낸 순서가 곧 쪽 번호 — 파일 이름으로 다시 정렬하지 않는다
        assertEquals(1, saved.get(0).getPageNo());
        assertTrue(saved.get(0).getPdfPath().endsWith("/pages/page-1.png"));
        assertTrue(saved.get(1).getPdfPath().endsWith("/pages/page-2.jpg"));
        assertTrue(saved.get(2).getPdfPath().endsWith("/pages/page-3.jpeg"));

        // 원본이 곧 미리보기 — 워커가 렌더하지 않으므로 업로드 시점에 채워야 한다
        assertEquals(saved.get(0).getPdfPath(), saved.get(0).getImagePath());

        // 쪽 i에 i번째로 보낸 파일이 올라갔는지 — 경로만 보면 서버가 몰래 정렬해도 드러나지 않는다
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        Mockito.verify(s3Service, Mockito.atLeast(3))
                .uploadFile(keys.capture(), bytes.capture(), anyString());

        assertEquals(30, markerOf(keys, bytes, "page-1.png"));
        assertEquals(10, markerOf(keys, bytes, "page-2.jpg"));
        assertEquals(20, markerOf(keys, bytes, "page-3.jpeg"));
    }

    /** 확장자별 Content-Type으로 올라가야 한다 — 전부 image/jpeg로 올리면 PNG가 깨진다 */
    @Test
    @SuppressWarnings("unchecked")
    void 확장자에_맞는_타입으로_올린다() throws Exception {
        S3Service s3Service = Mockito.mock(S3Service.class);
        UserRepository userRepository = Mockito.mock(UserRepository.class);
        RedisTemplate<String, String> redisTemplate = Mockito.mock(RedisTemplate.class);

        when(redisTemplate.opsForHash()).thenReturn(Mockito.mock(HashOperations.class));
        when(userRepository.findById(any(UUID.class)))
                .thenReturn(Optional.of(user()));
        when(s3Service.uploadFile(anyString(), any(), anyString()))
                .thenAnswer(call -> call.getArgument(0));

        JobService service = new JobService(
                Mockito.mock(JobRepository.class), Mockito.mock(PageRepository.class),
                userRepository, s3Service, redisTemplate, Mockito.mock(JobDispatcher.class),
                Mockito.mock(ThumbnailService.class), Mockito.mock(HwpToPdfConverter.class),
                Mockito.mock(FooterBrailleService.class));

        service.createJob(UUID.randomUUID().toString(),
                List.of(image("a.png"), image("b.jpg")), null, false, null, null, null);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> types = ArgumentCaptor.forClass(String.class);
        Mockito.verify(s3Service, Mockito.atLeast(2))
                .uploadFile(keys.capture(), any(), types.capture());

        int png = keys.getAllValues().indexOf(keys.getAllValues().stream()
                .filter(k -> k.endsWith("page-1.png")).findFirst().orElseThrow());
        int jpg = keys.getAllValues().indexOf(keys.getAllValues().stream()
                .filter(k -> k.endsWith("page-2.jpg")).findFirst().orElseThrow());

        assertEquals("image/png", types.getAllValues().get(png));
        assertEquals("image/jpeg", types.getAllValues().get(jpg));
    }

    /** 그 키로 올라간 바이트의 표식 — 쪽 번호와 보낸 순서가 맞는지 보는 데 쓴다 */
    private int markerOf(ArgumentCaptor<String> keys, ArgumentCaptor<byte[]> bytes, String keySuffix) {
        List<String> allKeys = keys.getAllValues();
        for (int i = 0; i < allKeys.size(); i++) {
            if (allKeys.get(i).endsWith(keySuffix)) return bytes.getAllValues().get(i)[0];
        }
        throw new AssertionError(keySuffix + " 키로 올라간 파일이 없다");
    }

    /** id는 DB가 매기는 값이라 빌더로는 안 채워진다 — 태스크·큐 키에 쓰이므로 넣어 준다 */
    private User user() {
        User user = User.builder().loginId("semojum01").password("pw").build();
        org.springframework.test.util.ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    /** 바이트를 파일마다 다르게 둔다 — 쪽 번호와 올라간 내용이 맞는지 보려면 구분이 돼야 한다 */
    private MultipartFile image(String name, int marker) {
        return new MockMultipartFile("file", name, "image/png", new byte[]{(byte) marker});
    }

    private MultipartFile image(String name) {
        return image(name, 0);
    }
}
