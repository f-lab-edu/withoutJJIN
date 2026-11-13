package withoutjjin.withoutjjin.domain.document.storage;

import java.time.Instant;

public record GeneratedUploadUrl(
        String uploadUrl, //브라우저가 파일을 보낼 임시 URL
        String storageKey, //S3에 저장할 파일의 키
        Instant expiresAt // URL이 만료되는 시각
) {
}
