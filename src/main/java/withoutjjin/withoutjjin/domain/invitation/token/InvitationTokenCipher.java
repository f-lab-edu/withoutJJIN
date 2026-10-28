package withoutjjin.withoutjjin.domain.invitation.token;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class InvitationTokenCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding"; // 자바에 요청할 암호화 방식
    // AES : 같은 비밀키로 암호화·복호화하는 알고리즘
    // GCM  : AES를 사용하는 방식 중 하나. 암호문 변조 검사도 제공
    // NoPadding  : 입력 끝에 별도의 패딩 데이터를 붙이는 방식을 사용하지 않음


    private static final String VERSION_PREFIX = "v1:"; //우리가 만든 저장 형식의 버전 표시
    private static final int KEY_LENGTH_BYTES = 32; //비밀키 길이: 32바이트 = 256비트
    private static final int IV_LENGTH_BYTES = 12; // 암호화 때 사용하는 IV 길이: 12바이트
    private static final int TAG_LENGTH_BITS = 128; // 변조 검사에 사용하는 인증 태그 길이: 128비트 = 16바이트
    private static final byte[] PURPOSE = "invitation-token:v1".getBytes(StandardCharsets.UTF_8);
 // PURPOSE는 이 암호문의 용도를 구분하는 값이야 나중에 이 값을 AAD로 사용해.
 // AAD는 Additional Authenticated Data, 즉 암호화하지는 않지만 변조 검사에 포함하는 추가 데이터
    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public InvitationTokenCipher(
            @Value("${invitation.token.encryption-key}") String encodedKey
    ) {
        if (encodedKey == null || encodedKey.isBlank()) {
            throw new IllegalArgumentException("초대 토큰 암호화 키를 설정해야 합니다.");
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("초대 토큰 암호화 키는 Base64 형식이어야 합니다.");
        }

        try {
            if (keyBytes.length != KEY_LENGTH_BYTES) {
                throw new IllegalArgumentException("초대 토큰 암호화 키는 32바이트여야 합니다.");
            }
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }


    //암호화
    public String encrypt(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("암호화할 초대 토큰이 필요합니다.");
        }

        // IV(초기화 벡터)는 암호화할 때마다 새로 만드는 값이며, 비밀키가 아니다.
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);

        try {
            // Cipher는 호출마다 새로 만들어 여러 요청이 상태를 공유하지 않게 한다.
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            // AAD: 암호화하지 않지만 변조 검증에 포함할 용도 구분 값.
            cipher.updateAAD(PURPOSE);
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));

            // doFinal 결과에는 암호문과 인증 태그가 함께 들어 있다.
            byte[] payload = ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array();

            return VERSION_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("초대 토큰을 암호화할 수 없습니다.", exception);
        }
    }



    //복호화
    public String decrypt(String encryptedToken) {
        if (encryptedToken == null || !encryptedToken.startsWith(VERSION_PREFIX)) {
            throw new IllegalArgumentException("지원하지 않는 초대 토큰 암호문 형식입니다.");
        }

        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(encryptedToken.substring(VERSION_PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("초대 토큰 암호문의 인코딩이 올바르지 않습니다.");
        }

        // IV + 인증 태그 + 비어 있지 않은 암호문이 있어야 한다.
        if (payload.length <= IV_LENGTH_BYTES + TAG_LENGTH_BITS / Byte.SIZE) {
            throw new IllegalArgumentException("초대 토큰 암호문이 너무 짧습니다.");
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        byte[] iv = new byte[IV_LENGTH_BYTES];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(PURPOSE);
            // 인증 태그가 맞지 않으면 원문을 반환하지 않고 예외가 발생한다.
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("초대 토큰을 복호화할 수 없습니다.", exception);
        }
    }
}
