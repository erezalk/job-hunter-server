package dev.erezalkalay.jobhunter.common.crypto;

/**
 * A stored AI API key exists but can no longer be decrypted — typically
 * because APP_ENCRYPTION_SECRET changed since it was saved. The message is
 * safe to show: it carries no ciphertext, key material or cipher details.
 */
public class StoredSecretUnreadableException extends RuntimeException {

    public StoredSecretUnreadableException() {
        super("Your saved AI API key can no longer be read (the server's encryption secret may have changed). "
                + "Enter the API key again in Settings.");
    }
}
