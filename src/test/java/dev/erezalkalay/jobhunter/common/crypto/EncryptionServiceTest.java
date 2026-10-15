package dev.erezalkalay.jobhunter.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EncryptionServiceTest {

    @Test
    void encryptedValueDecryptsBackToThePlaintext() {
        EncryptionService service = new EncryptionService("a-test-master-secret");

        String ciphertext = service.encrypt("sk-anthropic-abc123");

        assertThat(ciphertext).isNotEqualTo("sk-anthropic-abc123");
        assertThat(service.decrypt(ciphertext)).isEqualTo("sk-anthropic-abc123");
    }

    @Test
    void sameValueEncryptsDifferentlyEachTime() {
        EncryptionService service = new EncryptionService("a-test-master-secret");

        String first = service.encrypt("same-secret");
        String second = service.encrypt("same-secret");

        // Random IV per call — ciphertexts must differ even for identical input.
        assertThat(first).isNotEqualTo(second);
        assertThat(service.decrypt(first)).isEqualTo("same-secret");
        assertThat(service.decrypt(second)).isEqualTo("same-secret");
    }

    @Test
    void aValueThatCannotBeDecryptedIsReportedWithoutLeakingIt() {
        String foreign = new EncryptionService("the-old-master-secret").encrypt("sk-anthropic-abc123");
        EncryptionService service = new EncryptionService("a-test-master-secret");

        for (String unreadable : new String[] {foreign, "not base64 !!", "c2hvcnQ="}) {
            assertThatThrownBy(() -> service.decrypt(unreadable))
                    .isInstanceOf(StoredSecretUnreadableException.class)
                    .hasMessageNotContaining(unreadable)
                    .hasMessageContaining("Enter the API key again")
                    .hasNoCause();
        }
    }

    @Test
    void withoutAMasterSecretEncryptionIsRefused() {
        EncryptionService service = new EncryptionService("");

        assertThat(service.isConfigured()).isFalse();
        assertThatThrownBy(() -> service.encrypt("anything")).isInstanceOf(EncryptionNotConfiguredException.class);
    }
}
