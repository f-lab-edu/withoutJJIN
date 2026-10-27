package withoutjjin.withoutjjin.domain.invitation.token;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class InvitationTokenGenerator {
// 초대 토큰 생성과 해시 계산 추측하기 어려운 무작위 문자열
    private final SecureRandom secureRandom = new SecureRandom();

    public String generateToken() {
        // 보안용 난수 32바이트를 만들고, URL에 넣기 편한 문자열로 변환
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);

        return Base64.getUrlEncoder()  // 무작위 바이트를 URL에서 사용할 수 있는 문자열로 표현하는 단계
                .withoutPadding()
                .encodeToString(bytes);
    }

    public String hashToken(String token) {
        // 토큰 문자열의 SHA-256 해시를 계산하고, 16진수 문자열로 변환
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            // 문자열을 UTF-8 바이트로 바꾸고 해시 계산
            byte[] hash = digest.digest(
                    token.getBytes(StandardCharsets.UTF_8)
            );
            //HexFormat: 계산된 바이트를 16진수 문자열로 표현하는 도구
            return HexFormat.of().formatHex(hash);

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 해시 알고리즘을 사용할 수 없습니다.",
                    exception
            );
        }
    }
}