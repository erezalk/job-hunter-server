package dev.erezalkalay.jobhunter.integration;

import dev.erezalkalay.jobhunter.auth.CurrentUserProvider;
import dev.erezalkalay.jobhunter.user.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal MCP integration token: one per user, no expiry, valid until the
 * user revokes or replaces it. Only the SHA-256 digest is persisted — the
 * token is 256 bits of CSPRNG output, so a fast unsalted digest is sufficient
 * (there is nothing to brute-force) and allows an indexed lookup.
 */
@Service
@Transactional(readOnly = true)
public class IntegrationTokenService {

    static final String TOKEN_PREFIX = "jh_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final IntegrationTokenRepository repository;
    private final CurrentUserProvider currentUserProvider;

    public IntegrationTokenService(IntegrationTokenRepository repository, CurrentUserProvider currentUserProvider) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
    }

    public record GeneratedToken(String rawToken, IntegrationToken token) {
    }

    public Optional<IntegrationToken> statusForCurrentUser() {
        return repository.findByUserId(currentUserProvider.getCurrentUser().getId());
    }

    /** Replaces any existing token of the current user; the previous one stops working immediately. */
    @Transactional
    public GeneratedToken generateForCurrentUser() {
        User user = currentUserProvider.getCurrentUser();
        repository.deleteByUserId(user.getId());
        repository.flush();

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        IntegrationToken saved = repository.saveAndFlush(
                new IntegrationToken(user, hash(raw), raw.substring(raw.length() - 4)));
        return new GeneratedToken(raw, saved);
    }

    @Transactional
    public void revokeForCurrentUser() {
        repository.deleteByUserId(currentUserProvider.getCurrentUser().getId());
    }

    /** The owner of a presented raw token, or empty when it is unknown, replaced or revoked. */
    public Optional<User> resolveOwner(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        return repository.findByTokenHashWithUser(hash(rawToken)).map(IntegrationToken::getUser);
    }

    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
