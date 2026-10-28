package withoutjjin.withoutjjin.domain.invitation.token;

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

    @BeforeEach
    void setUp() {
        key = generateKey();
        cipher = new InvitationTokenCipher(key);
    }

    @Test
    void 암호화한_토큰을_다시_복호화한다() {
        String token = new InvitationTokenGenerator().generateToken();
        String encrypted = cipher.encrypt(token);

        assertThat(encrypted).startsWith("v1:").hasSizeLessThanOrEqualTo(255);
        assertThat(encrypted).doesNotContain(token);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(token);
    }

    @Test
    void 같은_원문도_매번_다른_암호문으로_저장한다() {
        String first = cipher.encrypt("same-token");
        String second = cipher.encrypt("same-token");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("same-token");
        assertThat(cipher.decrypt(second)).isEqualTo("same-token");
    }

    @Test
    void 같은_키를_사용하는_다른_인스턴스에서도_복원한다() {
        String encrypted = cipher.encrypt("restart-test-token");
        InvitationTokenCipher restartedCipher = new InvitationTokenCipher(key);

        assertThat(restartedCipher.decrypt(encrypted)).isEqualTo("restart-test-token");
    }

    @Test
    void 다른_키로는_복호화하지_못한다() {
        String encrypted = cipher.encrypt("test-token");
        InvitationTokenCipher differentCipher = new InvitationTokenCipher(generateKey());

        assertThatThrownBy(() -> differentCipher.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 암호문을_변조하면_복호화를_거부한다() {
        String tampered = tamper(cipher.encrypt("test-token"), 12);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void iv를_변조해도_복호화를_거부한다() {
        String tampered = tamper(cipher.encrypt("test-token"), 0);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not-base64!", "c2hvcnQ="})
    void 잘못된_설정키는_거부한다(String invalidKey) {
        assertThatThrownBy(() -> new InvitationTokenCipher(invalidKey))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"v2:AAAA", "v1:not-base64!", "v1:AAAA"})
    void 잘못된_암호문_형식은_거부한다(String invalidCiphertext) {
        assertThatThrownBy(() -> cipher.decrypt(invalidCiphertext))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void 비어있는_원문은_암호화하지_않는다(String token) {
        assertThatThrownBy(() -> cipher.encrypt(token))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String generateKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private String tamper(String encryptedToken, int byteIndex) {
        byte[] payload = Base64.getUrlDecoder().decode(encryptedToken.substring(3));
        payload[byteIndex] ^= 1;
        return "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
    }
}
