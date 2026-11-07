package withoutjjin.withoutjjin.domain.document.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import withoutjjin.withoutjjin.domain.document.entity.StoreFile;

import java.util.Objects;

@Component
@RequiredArgsConstructor
public class S3UploadVerifier {

    private final S3ObjectReader s3ObjectReader;

    public void verify(StoreFile storeFile) { // 파일이 제대로 왔는지 확인하는 과정
        verify(storeFile.getObjectKey(), storeFile.getSizeBytes(), storeFile.getMediaType());
    }

    // 트랜잭션 밖에서 엔티티를 지연 로딩하지 않도록 검증할 스칼라 값을 전달받는다.
    public void verify(String objectKey, Long sizeBytes, String mediaType) {
        HeadObjectResponse response = s3ObjectReader.head(objectKey);

        if (!Objects.equals(
                sizeBytes, // StoreFile.sizeBytes
                response.contentLength()// 콘덴트 길이가 같은지 확인
        )) {
            throw new IllegalStateException(
                    "S3 파일 크기가 StoreFile.sizeBytes와 일치하지 않습니다."
            );
        }

        if (!mediaType
                .equalsIgnoreCase(response.contentType())) {
            throw new IllegalStateException(
                    "S3 Content-Type이 StoreFile.mediaType과 일치하지 않습니다."
            );
        }
    }



}