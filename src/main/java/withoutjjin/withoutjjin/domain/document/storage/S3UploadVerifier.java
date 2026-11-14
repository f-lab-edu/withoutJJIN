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
        HeadObjectResponse response = // 응답을 받음
                s3ObjectReader.head(storeFile.getObjectKey());

        if (!Objects.equals(
                storeFile.getSizeBytes(), //파일 용량
                response.contentLength()// 콘덴트 길이가 같은지 확인
        )) {
            throw new IllegalStateException(
                    "S3 파일 크기가 StoreFile.sizeBytes와 일치하지 않습니다."
            );
        }

        if (!storeFile.getMediaType()
                .equalsIgnoreCase(response.contentType())) {
            throw new IllegalStateException(
                    "S3 Content-Type이 StoreFile.mediaType과 일치하지 않습니다."
            );
        }
    }



}