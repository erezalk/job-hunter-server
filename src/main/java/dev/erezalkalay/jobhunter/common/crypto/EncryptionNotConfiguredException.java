package dev.erezalkalay.jobhunter.common.crypto;

public class EncryptionNotConfiguredException extends RuntimeException {

    public EncryptionNotConfiguredException() {
        super("Secure storage for AI API keys is not configured on the server. "
                + "Set APP_ENCRYPTION_SECRET (for example in a .env file next to pom.xml) and restart the server.");
    }
}
