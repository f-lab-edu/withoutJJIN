package withoutjjin.withoutjjin.domain.invitation.token;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvitationTokenCipherTest {

    private String key;
    private InvitationTokenCipher cipher;
    private final SecureRandom secureRandom = new SecureRandom();
    @BeforeEach
    void setUp() {
        key = generateKey();
        cipher = new InvitationTokenCipher(key);
    }

    @Test
    @DisplayName("암호화한 토큰을 다시 복호화한다")
    void decryptsEncryptedToken() {
        String token = new InvitationTokenGenerator().generateToken();
        String encrypted = cipher.encrypt(token);

        assertThat(encrypted).startsWith("v1:").hasSizeLessThanOrEqualTo(255);
        assertThat(encrypted).doesNotContain(token);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(token);
    }

    @Test
    @DisplayName("같은 원문도 매번 다른 암호문으로 저장한다")
    void encryptsSamePlaintextToDifferentCiphertexts() {
        String first = cipher.encrypt("same-token");
        String second = cipher.encrypt("same-token");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("same-token");
        assertThat(cipher.decrypt(second)).isEqualTo("same-token");
    }

    @Test
    @DisplayName("같은 키를 사용하는 다른 인스턴스에서도 복원한다")
    void decryptsAcrossInstancesUsingSameKey() {
        String encrypted = cipher.encrypt("restart-test-token");
        InvitationTokenCipher restartedCipher = new InvitationTokenCipher(key);

        assertThat(restartedCipher.decrypt(encrypted)).isEqualTo("restart-test-token");
    }

    @Test
    @DisplayName("다른 키로는 복호화하지 못한다")
    void rejectsDecryptionWithDifferentKey() {
        String encrypted = cipher.encrypt("test-token");
        InvitationTokenCipher differentCipher = new InvitationTokenCipher(generateKey());

        assertThatThrownBy(() -> differentCipher.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("암호문을 변조하면 복호화를 거부한다")
    void rejectsTamperedCiphertext() {
        String tampered = tamper(cipher.encrypt("test-token"), 12);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("iv를 변조해도 복호화를 거부한다")
    void rejectsTamperedInitializationVector() {
        String tampered = tamper(cipher.encrypt("test-token"), 0);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not-base64!", "c2hvcnQ="})
    @DisplayName("잘못된 설정키는 거부한다")
    void rejectsInvalidConfiguredKey(String invalidKey) {
        assertThatThrownBy(() -> new InvitationTokenCipher(invalidKey))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"v2:AAAA", "v1:not-base64!", "v1:AAAA"})
    @DisplayName("잘못된 암호문 형식은 거부한다")
    void rejectsInvalidCiphertextFormat(String invalidCiphertext) {
        assertThatThrownBy(() -> cipher.decrypt(invalidCiphertext))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("비어있는 원문은 암호화하지 않는다")
    void rejectsEncryptionOfBlankPlaintext(String token) {
        assertThatThrownBy(() -> cipher.encrypt(token))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String generateKey() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private String tamper(String encryptedToken, int byteIndex) {
        byte[] payload = Base64.getUrlDecoder().decode(encryptedToken.substring(3));
        payload[byteIndex] ^= 1;
        return "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
    }
}
