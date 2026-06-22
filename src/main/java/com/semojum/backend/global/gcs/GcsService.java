package com.semojum.backend.global.gcs;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GcsService {

    private final Storage storage;

    @Value("${gcs.bucket-name}")
    private String bucketName;

    // GCS에 파일 업로드
    public String uploadFile(String gcsPath, byte[] fileBytes, String contentType) {
        BlobId blobId = BlobId.of(bucketName, gcsPath);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                .setContentType(contentType)
                .build();
        storage.create(blobInfo, fileBytes);
        log.info("GCS 업로드 완료: {}", gcsPath);
        return "gs://" + bucketName + "/" + gcsPath;
    }

    // GCS 경로를 공개 URL로 변환 (gs://bucket/path → https://storage.googleapis.com/bucket/path)
    public String getPublicUrl(String gcsPath) {
        String objectPath = gcsPath.replace("gs://" + bucketName + "/", "");
        return "https://storage.googleapis.com/" + bucketName + "/" + objectPath;
    }

    // GCS에서 파일 다운로드
    public byte[] downloadFile(String gcsPath) {
        // gs://semojum-bucket/path 형태에서 path만 추출
        String objectPath = gcsPath.replace("gs://" + bucketName + "/", "");
        BlobId blobId = BlobId.of(bucketName, objectPath);
        byte[] content = storage.readAllBytes(blobId);
        log.info("GCS 다운로드 완료: {}", objectPath);
        return content;
    }
}