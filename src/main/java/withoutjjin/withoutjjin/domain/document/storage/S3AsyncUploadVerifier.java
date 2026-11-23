package withoutjjin.withoutjjin.domain.document.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
public class S3AsyncUploadVerifier {

    private final S3AsyncObjectReader reader;

    public CompletableFuture<Void> verify(String objectKey, Long sizeBytes, String mediaType) {
        return reader.head(objectKey).thenAccept(response -> validate(response, sizeBytes, mediaType));
    }

    private static void validate(HeadObjectResponse response, Long sizeBytes, String mediaType) {
        if (!Objects.equals(sizeBytes, response.contentLength())) {
            throw new IllegalStateException("S3 파일 크기가 StoreFile.sizeBytes와 일치하지 않습니다.");
        }
        if (!mediaType.equalsIgnoreCase(response.contentType())) {
            throw new IllegalStateException("S3 Content-Type이 StoreFile.mediaType과 일치하지 않습니다.");
        }
    }
}
