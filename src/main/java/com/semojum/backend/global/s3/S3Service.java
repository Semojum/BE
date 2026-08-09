package com.semojum.backend.global.s3;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

// GCS → S3 이전: 기존 GcsService와 동일한 3개 메서드 시그니처 유지 (호출부 로직 무변경)
@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Client s3Client;
    private final software.amazon.awssdk.services.s3.presigner.S3Presigner s3Presigner;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    @Value("${aws.region}")
    private String region;

    // S3에 파일 업로드
    public String uploadFile(String path, byte[] fileBytes, String contentType) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(path)
                .contentType(contentType)
                .build();
        s3Client.putObject(request, RequestBody.fromBytes(fileBytes));
        log.info("S3 업로드 완료: {}", path);
        return "s3://" + bucketName + "/" + path;
    }

    // 저장 경로를 공개 URL로 변환 (s3://bucket/key → https://bucket.s3.region.amazonaws.com/key)
    // 공개 읽기가 허용된 객체(썸네일)에만 쓸 것 — 원본 페이지는 getPresignedUrl 사용
    public String getPublicUrl(String storagePath) {
        return "https://" + bucketName + ".s3." + region + ".amazonaws.com/" + toKey(storagePath);
    }

    // 만료형 서명 URL 발급 — 버킷이 비공개인 객체(원본 페이지 PDF)를 FE가 직접 받을 때 사용.
    // 서명은 로컬 연산(HMAC)이라 S3 왕복 없음. 만료 후에는 페이지 조회 API를 다시 호출해 새 URL을 받는다.
    public String getPresignedUrl(String storagePath, java.time.Duration ttl) {
        software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest request =
                software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest.builder()
                        .signatureDuration(ttl)
                        .getObjectRequest(GetObjectRequest.builder()
                                .bucket(bucketName)
                                .key(toKey(storagePath))
                                .build())
                        .build();
        return s3Presigner.presignGetObject(request).url().toString();
    }

    // S3에서 파일 다운로드
    public byte[] downloadFile(String storagePath) {
        String key = toKey(storagePath);
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        byte[] content = s3Client.getObjectAsBytes(request).asByteArray();
        log.info("S3 다운로드 완료: {}", key);
        return content;
    }

    // V3 휴지통 완전 삭제: jobId 프리픽스 아래 모든 객체(원본·페이지·썸네일) 제거
    public void deleteByPrefix(String prefix) {
        software.amazon.awssdk.services.s3.model.ListObjectsV2Request listReq =
                software.amazon.awssdk.services.s3.model.ListObjectsV2Request.builder()
                        .bucket(bucketName)
                        .prefix(prefix)
                        .build();
        software.amazon.awssdk.services.s3.model.ListObjectsV2Response listRes;
        do {
            listRes = s3Client.listObjectsV2(listReq);
            for (software.amazon.awssdk.services.s3.model.S3Object obj : listRes.contents()) {
                s3Client.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
                        .bucket(bucketName)
                        .key(obj.key())
                        .build());
            }
            listReq = listReq.toBuilder().continuationToken(listRes.nextContinuationToken()).build();
        } while (Boolean.TRUE.equals(listRes.isTruncated()));
        log.info("S3 프리픽스 삭제 완료: {}", prefix);
    }

    // s3://bucket/key · gs://bucket/key(GCP 시절 DB 경로 호환) · 순수 key 모두에서 객체 key만 추출.
    // 기존 DB 행의 gs:// 경로도 객체를 같은 key로 S3에 복사해두면 UPDATE 없이 그대로 읽힌다.
    private String toKey(String storagePath) {
        if (storagePath.startsWith("s3://") || storagePath.startsWith("gs://")) {
            String withoutScheme = storagePath.substring(5);
            return withoutScheme.substring(withoutScheme.indexOf('/') + 1);
        }
        return storagePath;
    }
}
