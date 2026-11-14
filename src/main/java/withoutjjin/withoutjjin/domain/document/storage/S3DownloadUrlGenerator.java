package withoutjjin.withoutjjin.domain.document.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;
import org.springframework.http.ContentDisposition;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.nio.charset.StandardCharsets;

@Component
public class S3DownloadUrlGenerator {

    private final S3Presigner presigner;
    private final String bucket;
    private final Duration expiration;

    public S3DownloadUrlGenerator(S3Presigner presigner,
            @Value("${aws.s3.bucket}") String bucket,
            @Value("${aws.s3.download-url-expiration:5m}") String expiration) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("파일 조회용 S3 버킷 이름이 필요합니다.");
        }
        Duration duration = DurationStyle.detectAndParse(expiration);
        if (duration.compareTo(Duration.ofSeconds(1)) < 0 || duration.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("다운로드 URL 유효기간은 1초 이상 7일 이하여야 합니다.");
        }
        this.presigner = presigner;
        this.bucket = bucket;
        this.expiration = duration;
    }

    // objectKey는 클라이언트 입력이 아니라 권한 확인 후 StoreFile에서 조회한 값을 사용한다.
    public GeneratedDownloadUrl generatePdf(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("PDF의 S3 저장 키가 필요합니다.");
        }
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket).key(objectKey)
                .responseContentType("application/pdf")
                .responseContentDisposition("inline; filename=\"document.pdf\"")
                .responseCacheControl("no-store")
                .build();
        return sign(request);
    }

    public GeneratedDownloadUrl generateOriginal(String objectKey, String fileName) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("원본의 S3 저장 키가 필요합니다.");
        }
        if (fileName == null || fileName.isBlank() || fileName.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("제어 문자가 없는 원본 파일명이 필요합니다.");
        }
        String disposition = ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString();
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket).key(objectKey)
                .responseContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .responseContentDisposition(disposition)
                .responseCacheControl("no-store")
                .build();
        return sign(request);
    }

    private GeneratedDownloadUrl sign(GetObjectRequest request) {
        var signed = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(expiration).getObjectRequest(request).build());
        return new GeneratedDownloadUrl(signed.url().toString(), signed.expiration());
    }

}
