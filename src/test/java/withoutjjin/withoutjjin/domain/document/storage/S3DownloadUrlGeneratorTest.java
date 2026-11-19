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
}
