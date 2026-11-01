package withoutjjin.withoutjjin.domain.document.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class S3UploadUrlGeneratorTest {

    @Test
    @DisplayName("지정한 버킷과 저장 키를 대상으로 서명된 업로드 URL을 생성한다")
    void generatesSignedUrlForConfiguredBucketAndStorageKey() {
        try (S3Presigner presigner = S3Presigner.builder()
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                "test-access-key",
                                "test-secret-key"
                        )
                ))
                .build()) {

            S3UploadUrlGenerator generator = new S3UploadUrlGenerator(
                    presigner,
                    new DocumentStorageKeyGenerator()
            );

            ReflectionTestUtils.setField(
                    generator, "bucket", "ftfm-test-bucket"
            );
            ReflectionTestUtils.setField(
                    generator, "uploadUrlExpiration", "5m"
            );

            Instant before = Instant.now();
            GeneratedUploadUrl result = generator.generate(10L);
            Instant after = Instant.now();

            URI uri = URI.create(result.uploadUrl());

            assertThat(uri.getScheme()).isEqualTo("https");
            assertThat(uri.getHost())
                    .isEqualTo("ftfm-test-bucket.s3.ap-northeast-2.amazonaws.com");
            assertThat(result.storageKey())
                    .startsWith("workspaces/10/uploads/");
            assertThat(uri.getPath())
                    .isEqualTo("/" + result.storageKey());

            //X-Amz-Signature는 AWS SDK가 계산한 서명값을 넣는 항목 이름
            assertThat(uri.getRawQuery())
                    .contains("X-Amz-Signature=");

            // URL에 유효기간 300초가 설정됐는지 확인
            assertThat(uri.getRawQuery().split("&"))
                    .contains("X-Amz-Expires=300");

            // 반환된 만료 시각도 생성 시점에서 약 5분 뒤인지 확인
            assertThat(result.expiresAt()).isBetween(
                    before.plusSeconds(299),
                    after.plusSeconds(301)
            );

        }




    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("버킷 이름이 없으면 서명하지 않고 URL 생성을 거부한다")
    void rejectsBlankBucketName(String bucket) {
        S3Presigner presigner = mock(S3Presigner.class);

        S3UploadUrlGenerator generator = new S3UploadUrlGenerator(
                presigner,
                new DocumentStorageKeyGenerator()
        );

        ReflectionTestUtils.setField(generator, "bucket", bucket);
        ReflectionTestUtils.setField(
                generator, "uploadUrlExpiration", "5m"
        );

        assertThatThrownBy(() -> generator.generate(10L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("S3 버킷 이름을 설정해야 합니다.");

        verifyNoInteractions(presigner);
        //서명 객체를 호출하지 않았는지 확인,
        // 이 모의 객체의 메서드가 한 번도 호출되지 않았는지 확인해라
    }
}