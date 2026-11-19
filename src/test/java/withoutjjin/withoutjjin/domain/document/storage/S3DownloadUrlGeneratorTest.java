package withoutjjin.withoutjjin.domain.document.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class S3DownloadUrlGeneratorTest {

    @Test
    @DisplayName("GET 서명 URL에 지정한 버킷·PDF 키·5분 만료·화면 표시와 캐시 방지 조건을 포함한다")
    void signsPdfGetUrlWithoutContactingS3() {
        try (S3Presigner presigner = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access", "test-secret"))).build()) {
            var generator = new S3DownloadUrlGenerator(presigner, "ftfm-test-bucket", "5m");
            Instant before = Instant.now();
            var generated = generator.generatePdf("workspaces/10/review-pdfs/test.pdf");
            URI uri = URI.create(generated.downloadUrl());
            String query = URLDecoder.decode(uri.getRawQuery(), StandardCharsets.UTF_8);
            assertThat(uri.getScheme()).isEqualTo("https");
            assertThat(uri.getHost()).isEqualTo("ftfm-test-bucket.s3.ap-northeast-2.amazonaws.com");
            assertThat(uri.getPath()).isEqualTo("/workspaces/10/review-pdfs/test.pdf");
            assertThat(query).contains("X-Amz-Signature=", "X-Amz-Expires=300",
                    "response-content-type=application/pdf", "response-content-disposition=inline; filename=\"document.pdf\"",
                    "response-cache-control=no-store");
            assertThat(generated.expiresAt()).isBetween(before.plusSeconds(299), Instant.now().plusSeconds(301));
            assertThat(generated.downloadUrl()).doesNotContain("test-secret");
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    @DisplayName("저장 키가 없으면 서명 객체를 호출하지 않는다")
    void rejectsMissingKey(String key) {
        S3Presigner presigner = mock(S3Presigner.class);
        var generator = new S3DownloadUrlGenerator(presigner, "ftfm-test-bucket", "5m");
        assertThatIllegalArgumentException().isThrownBy(() -> generator.generatePdf(key));
        verifyNoInteractions(presigner);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    @DisplayName("버킷 이름이 없으면 초기화에 실패한다")
    void rejectsMissingBucket(String bucket) {
        assertThatIllegalArgumentException().isThrownBy(() -> new S3DownloadUrlGenerator(mock(S3Presigner.class), bucket, "5m"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "500ms", "8d", "invalid", ""})
    @DisplayName("잘못된 조회 URL 유효기간은 초기화할 때 거부한다")
    void rejectsInvalidExpiration(String expiration) {
        assertThatIllegalArgumentException().isThrownBy(() -> new S3DownloadUrlGenerator(mock(S3Presigner.class), "ftfm-test-bucket", expiration));
    }

    @ParameterizedTest
    @ValueSource(strings = {"resume.docx", "제출 이력서 \"최종\" 100%.docx", "기획서(최종)+수정본.docx"})
    @DisplayName("원본 다운로드 URL은 UTF-8 파일명·첨부 다운로드·DOCX 형식·캐시 방지·5분 만료를 포함한다")
    void signsOriginalDownloadWithoutContactingS3(String fileName) {
        try (S3Presigner presigner = S3Presigner.builder().region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test-access", "test-secret"))).build()) {
            var generator = new S3DownloadUrlGenerator(presigner, "ftfm-test-bucket", "5m");
            Instant before = Instant.now();
            var generated = generator.generateOriginal("workspaces/10/uploads/test.docx", fileName);
            URI uri = URI.create(generated.downloadUrl());
            assertThat(uri.getHost()).isEqualTo("ftfm-test-bucket.s3.ap-northeast-2.amazonaws.com");
            assertThat(uri.getPath()).isEqualTo("/workspaces/10/uploads/test.docx");
            String disposition = queryValue(uri, "response-content-disposition");
            var parsed = org.springframework.http.ContentDisposition.parse(disposition);
            assertThat(parsed.getType()).isEqualTo("attachment");
            assertThat(parsed.getFilename()).isEqualTo(fileName);
            assertThat(disposition).doesNotContain("\r", "\n");
            assertThat(queryValue(uri, "response-content-type")).isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            assertThat(queryValue(uri, "response-cache-control")).isEqualTo("no-store");
            assertThat(queryValue(uri, "X-Amz-Expires")).isEqualTo("300");
            assertThat(queryValue(uri, "X-Amz-Signature")).isNotBlank();
            assertThat(generated.expiresAt()).isBetween(before.plusSeconds(299), Instant.now().plusSeconds(301));
            assertThat(generated.downloadUrl()).doesNotContain("test-secret");
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    @DisplayName("원본 저장 키가 없으면 서명하지 않는다")
    void rejectsMissingOriginalKey(String key) {
        S3Presigner presigner = mock(S3Presigner.class);
        var generator = new S3DownloadUrlGenerator(presigner, "ftfm-test-bucket", "5m");
        assertThatIllegalArgumentException().isThrownBy(() -> generator.generateOriginal(key, "document.docx"));
        verifyNoInteractions(presigner);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "file\r\nInjected.docx", "file\nname.docx", "file\tname.docx"})
    @DisplayName("파일명이 비어 있거나 제어 문자를 포함하면 서명하지 않는다")
    void rejectsInvalidOriginalFilename(String name) {
        S3Presigner presigner = mock(S3Presigner.class);
        var generator = new S3DownloadUrlGenerator(presigner, "ftfm-test-bucket", "5m");
        assertThatIllegalArgumentException().isThrownBy(() -> generator.generateOriginal("workspaces/10/uploads/test.docx", name));
        verifyNoInteractions(presigner);
    }

    private String queryValue(URI uri, String name) {
        String entry = java.util.Arrays.stream(uri.getRawQuery().split("&"))
                .filter(value -> value.startsWith(name + "=")).findFirst().orElseThrow();
        return URLDecoder.decode(entry.substring(name.length() + 1), StandardCharsets.UTF_8);
    }

}
