package com.semojum.backend.domain.job.service;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.job.dto.BrailleOptions;
import com.semojum.backend.domain.job.dto.JobResponseDto;
import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.job.scheduler.JobDispatcher;
import com.semojum.backend.global.exception.CustomException;
import com.semojum.backend.global.exception.ErrorCode;
import com.semojum.backend.global.hwp.HwpToPdfConverter;
import com.semojum.backend.global.s3.S3Service;
import com.semojum.backend.global.thumbnail.ThumbnailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 업로드 원본을 힙(byte[])에 올리지 않는다 (2026-10-16).
 *
 * <p>운영 힙 768MB · 업로드 상한 100MB에서 원본 배열이 OOM을 냈다(실측: 100MB 4건 동시 → 12건 중 6건).
 * 지킬 것: 원본을 {@code getBytes()}로 읽지 않는다 · 쪽 분리 결과는 그대로다 · 임시 파일은 성공해도
 * 실패해도 지운다 · HWP 변환 결과 폴더도 닫는다.
 */
class UploadPathTest {

    JobRepository jobRepository;
    S3Service s3Service;
    ThumbnailService thumbnailService;
    HwpToPdfConverter hwpToPdfConverter;
    JobService service;
    List<String> uploadedKeys;
    String userId;

    @BeforeEach
    void setUp() throws Exception {
        UserRepository users = mock(UserRepository.class);
        User user = User.builder().loginId("testorg01").password("pw").build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        userId = user.getId().toString();
        when(users.findById(any())).thenReturn(Optional.of(user));

        jobRepository = mock(JobRepository.class);
        when(jobRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(jobRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        uploadedKeys = new ArrayList<>();
        s3Service = mock(S3Service.class);
        when(s3Service.uploadFile(anyString(), any(), any())).thenAnswer(i -> {
            uploadedKeys.add(i.getArgument(0));
            return "s3://b/" + i.getArgument(0);
        });

        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);

        UploadOptionsResolver resolver = mock(UploadOptionsResolver.class);
        when(resolver.resolve(any(), any(), any(), any()))
                .thenReturn(new UploadOptionsResolver.Resolved(LayoutOptions.defaults(), BrailleOptions.defaults()));

        thumbnailService = mock(ThumbnailService.class);
        when(thumbnailService.generatePdfThumbnail(any(Path.class))).thenReturn(new byte[]{1});
        hwpToPdfConverter = mock(HwpToPdfConverter.class);

        service = new JobService(jobRepository, mock(PageRepository.class), users, s3Service, redis,
                mock(JobDispatcher.class), thumbnailService, hwpToPdfConverter,
                mock(FooterBrailleService.class), resolver);
    }

    private static byte[] pdf(int pages) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PdfDocument doc = new PdfDocument(new PdfWriter(out))) {
            for (int i = 0; i < pages; i++) doc.addNewPage();
        }
        return out.toByteArray();
    }

    /** getBytes()를 부르면 실패하는 업로드 — 원본을 힙에 올리는 코드로 돌아가면 잡힌다 */
    private static MultipartFile noGetBytes(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content) {
            @Override
            public byte[] getBytes() {
                throw new AssertionError("업로드 원본을 getBytes()로 힙에 올리면 안 된다");
            }
        };
    }

    private static Set<String> tempUploads() throws IOException {
        try (Stream<Path> s = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("upload-"))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    private JobResponseDto.Create upload(MultipartFile file) throws Exception {
        return service.createJob(userId, List.of(file), null, false, null, null, null, null, null);
    }

    @Test
    void PDF는_원본을_힙에_올리지_않고_쪽을_나눈다() throws Exception {
        JobResponseDto.Create created = upload(noGetBytes("교재.pdf", pdf(3)));

        assertEquals(3, created.totalPages());
        String jobId = created.jobId();
        assertEquals(List.of(jobId + "/thumbnail.png", jobId + "/pages/page-1.pdf",
                jobId + "/pages/page-2.pdf", jobId + "/pages/page-3.pdf"), uploadedKeys);
        verify(thumbnailService).generatePdfThumbnail(any(Path.class));
    }

    @Test
    void 성공하면_업로드_임시_파일을_지운다() throws Exception {
        Set<String> before = tempUploads();
        upload(noGetBytes("교재.pdf", pdf(2)));
        assertEquals(before, tempUploads());
    }

    /** 깨진 PDF — 예외가 나도 임시 파일이 남으면 안 된다(남으면 디스크가 업로드 크기만큼 샌다) */
    @Test
    void 실패해도_업로드_임시_파일을_지운다() throws Exception {
        Set<String> before = tempUploads();
        assertThrows(Exception.class, () -> upload(noGetBytes("깨진.pdf", "%PDF-1.4 not a pdf".getBytes())));
        assertEquals(before, tempUploads());
    }

    /** HWP는 변환기에 임시 파일 경로를 넘기고, 결과 PDF 폴더를 다 쓰면 닫는다 */
    @Test
    void HWP는_경로로_변환하고_결과_폴더를_닫는다() throws Exception {
        Path dir = Files.createTempDirectory("hwp2pdf-test");
        Path converted = dir.resolve("in.pdf");
        Files.write(converted, pdf(2));
        when(hwpToPdfConverter.convert(any(Path.class)))
                .thenAnswer(i -> {
                    Path src = i.getArgument(0);
                    assertTrue(Files.exists(src), "변환기는 디스크에 있는 원본 경로를 받는다");
                    return new HwpToPdfConverter.ConvertedPdf(converted, dir);
                });

        JobResponseDto.Create created = upload(noGetBytes("시험.hwp", new byte[]{1, 2, 3}));

        assertEquals(2, created.totalPages());
        assertFalse(Files.exists(dir), "변환 결과 폴더는 업로드 처리 뒤 지워진다");
    }

    @Test
    void HWP_변환_실패도_임시_파일을_지우고_에러를_그대로_던진다() throws Exception {
        when(hwpToPdfConverter.convert(any(Path.class))).thenThrow(new CustomException(ErrorCode.JOB_HWP_CONVERT_FAILED));
        Set<String> before = tempUploads();

        CustomException e = assertThrows(CustomException.class, () -> upload(noGetBytes("시험.hwp", new byte[]{1})));

        assertEquals(ErrorCode.JOB_HWP_CONVERT_FAILED, e.getErrorCode());
        assertEquals(before, tempUploads());
    }
}
