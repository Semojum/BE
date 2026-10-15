package com.semojum.backend.domain.job.bench;

import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Image;
import com.semojum.backend.domain.auth.entity.User;
import com.semojum.backend.domain.auth.repository.UserRepository;
import com.semojum.backend.domain.job.dto.BrailleOptions;
import com.semojum.backend.domain.job.dto.LayoutOptions;
import com.semojum.backend.domain.job.repository.JobRepository;
import com.semojum.backend.domain.job.repository.PageRepository;
import com.semojum.backend.domain.job.scheduler.JobDispatcher;
import com.semojum.backend.domain.job.service.FooterBrailleService;
import com.semojum.backend.domain.job.service.JobService;
import com.semojum.backend.domain.job.service.UploadOptionsResolver;
import com.semojum.backend.global.hwp.HwpToPdfConverter;
import com.semojum.backend.global.pdf.PdfPageRenderer;
import com.semojum.backend.global.s3.S3Service;
import com.semojum.backend.global.thumbnail.ThumbnailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 업로드 쪽 분리 단계 실측 (byte[] → Path 전환 전후 비교용). CI에서는 돌지 않는다.
 *
 * <p>실행: {@code ./gradlew test --tests '*UploadSplitBenchTest' --init-script scripts/upload-bench.gradle}
 * (init 스크립트가 테스트 JVM을 -Xmx768m로 띄우고 {@code upload.bench=true}를 넘긴다).
 *
 * <p>잰 것: {@code JobService.createJob}의 PDF 경로 전체 — 쪽 분리 + 썸네일(pdftoppm) + 쪽 PDF 생성.
 * S3 · DB · Redis · 큐는 아무것도 기록하지 않는 스텁(stubOnly — 호출 인자를 쥐지 않는다)이라
 * 그쪽 비용과 메모리는 빠진다. 업로드 파일은 Spring처럼 디스크에 있는 MultipartFile로 흉내 낸다.
 *
 * <p>힙 피크: 매 회 GC 후 힙 메모리 풀들의 peak를 리셋하고, 끝난 뒤 풀별 peak를 더한다(풀마다
 * 최고점 시각이 달라 실제보다 조금 크게 나올 수 있다 — 전후 비교에는 같은 방식이라 공정하다).
 */
@EnabledIfSystemProperty(named = "upload.bench", matches = "true")
class UploadSplitBenchTest {

    static final int[] SIZES_MB = {10, 50, 100};
    static final int SINGLE_REPS = 5;
    static final int CONCURRENT_REPS = 3;
    static final int CONCURRENCY = 4;

    @Test
    void 실측() throws Exception {
        Path dir = Path.of(System.getProperty("upload.bench.dir", System.getProperty("java.io.tmpdir") + "/upload-bench"));
        Files.createDirectories(dir);
        Map<Integer, Path> pdfs = new LinkedHashMap<>();
        for (int mb : SIZES_MB) pdfs.put(mb, scanLikePdf(dir, mb));

        JobService service = jobService();
        StringBuilder out = new StringBuilder();
        out.append(String.format("JVM maxHeap=%dMB, java=%s, cpus=%d%n",
                Runtime.getRuntime().maxMemory() / 1024 / 1024, System.getProperty("java.version"),
                Runtime.getRuntime().availableProcessors()));
        out.append("| 입력 | 시나리오 | 반복 | 소요 p50 (ms) | 소요 최대 (ms) | 힙 피크 p50 (MB) | 힙 피크 최대 (MB) | OOM |\n");
        out.append("|---|---|---|---|---|---|---|---|\n");

        for (var e : pdfs.entrySet()) {
            int mb = e.getKey();
            Path pdf = e.getValue();
            run(service, pdf, 1, 1);   // 워밍업(JIT · 폰트 · 프로세스 첫 기동) — 기록하지 않는다

            List<Long> times = new ArrayList<>(), heaps = new ArrayList<>();
            int oom = 0;
            for (int r = 0; r < SINGLE_REPS; r++) {
                Result res = run(service, pdf, 1, 1);
                times.addAll(res.times); heaps.add(res.heapPeakMb); oom += res.oom;
            }
            out.append(row(mb, "단건", SINGLE_REPS, times, heaps, oom));

            times = new ArrayList<>(); heaps = new ArrayList<>(); oom = 0;
            for (int r = 0; r < CONCURRENT_REPS; r++) {
                Result res = run(service, pdf, CONCURRENCY, 1);
                times.addAll(res.times); heaps.add(res.heapPeakMb); oom += res.oom;
            }
            out.append(row(mb, CONCURRENCY + "건 동시", CONCURRENT_REPS, times, heaps, oom));
        }
        System.out.println("\n===== UPLOAD SPLIT BENCH =====\n" + out);
        String target = System.getProperty("upload.bench.out");
        if (target != null) Files.writeString(Path.of(target), out.toString());
    }

    record Result(List<Long> times, long heapPeakMb, int oom) {}

    private Result run(JobService service, Path pdf, int threads, int ignored) throws Exception {
        System.gc();
        Thread.sleep(200);
        List<MemoryPoolMXBean> heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getType() == MemoryType.HEAP).toList();
        heapPools.forEach(MemoryPoolMXBean::resetPeakUsage);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        int[] oom = {0};
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                long t0 = System.nanoTime();
                try {
                    service.createJob(UUID.randomUUID().toString(), List.of(new DiskMultipartFile(pdf)), null,
                            false, null, null, null, null, null);
                } catch (OutOfMemoryError err) {
                    synchronized (oom) { oom[0]++; }
                    return -1L;
                }
                return (System.nanoTime() - t0) / 1_000_000;
            }));
        }
        start.countDown();
        List<Long> times = new ArrayList<>();
        for (Future<Long> f : futures) {
            try {
                long v = f.get();
                if (v >= 0) times.add(v);
            } catch (ExecutionException ex) {
                if (ex.getCause() instanceof OutOfMemoryError) oom[0]++;
                else throw ex;
            }
        }
        pool.shutdown();
        long peak = heapPools.stream().mapToLong(p -> p.getPeakUsage().getUsed()).sum() / 1024 / 1024;
        return new Result(times, peak, oom[0]);
    }

    private static String row(int mb, String scenario, int reps, List<Long> times, List<Long> heaps, int oom) {
        return String.format("| %dMB | %s | %d | %s | %s | %s | %s | %d |%n", mb, scenario, reps,
                p50(times), max(times), p50(heaps), max(heaps), oom);
    }

    private static String p50(List<Long> v) {
        if (v.isEmpty()) return "-";
        List<Long> s = new ArrayList<>(v); Collections.sort(s);
        return String.valueOf(s.get((s.size() - 1) / 2));
    }

    private static String max(List<Long> v) {
        return v.isEmpty() ? "-" : String.valueOf(Collections.max(v));
    }

    // ── 대상 · 스텁 ─────────────────────────────────────────────────

    private JobService jobService() {
        var stub = withSettings().stubOnly();
        UserRepository users = mock(UserRepository.class, stub);
        User user = User.builder().loginId("bench").password("pw").build();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        when(users.findById(any())).thenReturn(Optional.of(user));

        JobRepository jobs = mock(JobRepository.class, stub);
        when(jobs.existsActiveFileName(any(), any())).thenReturn(false);
        when(jobs.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(jobs.save(any())).thenAnswer(i -> i.getArgument(0));

        S3Service s3 = mock(S3Service.class, stub);
        when(s3.uploadFile(anyString(), any(), any())).thenAnswer(i -> "s3://bench/" + i.getArgument(0));
        when(s3.getPublicUrl(anyString())).thenReturn("https://bench/thumbnail.png");

        @SuppressWarnings("unchecked")
        RedisTemplate<String, String> redis = mock(RedisTemplate.class, stub);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hash = mock(HashOperations.class, stub);
        when(redis.opsForHash()).thenReturn(hash);

        UploadOptionsResolver resolver = mock(UploadOptionsResolver.class, stub);
        when(resolver.resolve(any(), any(), any(), any()))
                .thenReturn(new UploadOptionsResolver.Resolved(LayoutOptions.defaults(), BrailleOptions.defaults()));

        ThumbnailService thumbnails = new ThumbnailService(new PdfPageRenderer("pdftoppm", 60, 85));
        thumbnails.init();

        return new JobService(jobs, mock(PageRepository.class, stub), users, s3, redis,
                mock(JobDispatcher.class, stub), thumbnails, mock(HwpToPdfConverter.class, stub),
                mock(FooterBrailleService.class, stub), resolver);
    }

    /** Spring이 받은 업로드처럼 디스크에 있는 파일 — getBytes는 매번 디스크에서 통째로 읽는다 */
    record DiskMultipartFile(Path path) implements MultipartFile {
        public String getName() { return "file"; }
        public String getOriginalFilename() { return "bench.pdf"; }
        public String getContentType() { return "application/pdf"; }
        public boolean isEmpty() { return size() == 0; }
        public long getSize() { return size(); }
        private long size() { try { return Files.size(path); } catch (IOException e) { throw new UncheckedIOException(e); } }
        public byte[] getBytes() throws IOException { return Files.readAllBytes(path); }
        public InputStream getInputStream() throws IOException { return Files.newInputStream(path); }
        public void transferTo(File dest) throws IOException { Files.copy(path, dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        public void transferTo(Path dest) throws IOException { Files.copy(path, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
    }

    /**
     * 스캔본 성격 PDF — 쪽마다 노이즈 JPEG(압축이 잘 안 되는 사진 같은 이미지) 한 장. 쪽당 약 1MB라
     * 목표 크기 ≈ 쪽 수. 시드가 고정이라 매번 같은 파일이 나오고, 이미 있으면 다시 만들지 않는다.
     */
    static Path scanLikePdf(Path dir, int targetMb) throws IOException {
        Path out = dir.resolve("scan-" + targetMb + "mb.pdf");
        if (Files.exists(out)) return out;
        byte[] jpeg = noiseJpeg(1240, 1754, new Random(targetMb));   // A4 150dpi
        int pages = Math.max(1, (int) Math.round(targetMb * 1024.0 * 1024 / jpeg.length));
        try (PdfDocument pdf = new PdfDocument(new PdfWriter(out.toString()));
             Document doc = new Document(pdf, PageSize.A4)) {
            doc.setMargins(0, 0, 0, 0);
            Random rnd = new Random(targetMb * 31L);
            for (int i = 0; i < pages; i++) {
                if (i > 0) doc.add(new com.itextpdf.layout.element.AreaBreak());
                // 쪽마다 다른 바이트 — 같은 이미지를 재사용하면 iText가 한 번만 저장해 파일이 작아진다
                byte[] page = jpeg.clone();
                for (int k = 0; k < 64; k++) page[600 + rnd.nextInt(page.length - 1200)] ^= (byte) rnd.nextInt(256);
                Image img = new Image(ImageDataFactory.create(page));
                img.scaleToFit(PageSize.A4.getWidth(), PageSize.A4.getHeight());
                doc.add(img);
            }
        }
        return out;
    }

    private static byte[] noiseJpeg(int w, int h, Random rnd) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int g = 200 + rnd.nextInt(56) - ((x / 40 + y / 25) % 7 == 0 ? 150 : 0);
            g = Math.max(0, Math.min(255, g));
            img.setRGB(x, y, (g << 16) | (g << 8) | g);
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(0.9f);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }
}
