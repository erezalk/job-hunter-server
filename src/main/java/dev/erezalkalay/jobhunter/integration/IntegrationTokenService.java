package dev.erezalkalay.jobhunter.integration;

import dev.erezalkalay.jobhunter.auth.CurrentUserProvider;
import dev.erezalkalay.jobhunter.common.exception.FieldValidationException;
import dev.erezalkalay.jobhunter.common.exception.ResourceNotFoundException;
import dev.erezalkalay.jobhunter.user.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Personal integration tokens: any number per user, each with an optional
 * name, no expiry, valid until the user revokes or regenerates that one token.
 * Only the SHA-256 digest is persisted — a token is 256 bits of CSPRNG output,
 * so a fast unsalted digest is sufficient (there is nothing to brute-force) and
 * allows an indexed lookup. Every management operation is scoped to the
 * current user: another user's token id behaves exactly like a missing one.
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

    /** A token together with its raw secret; only ever returned by create and regenerate. */
    public record IssuedToken(String rawToken, IntegrationToken token) {
    }

    /** The current user's tokens, oldest first. */
    public List<IntegrationToken> listForCurrentUser() {
        return repository.findAllForUser(currentUserId());
    }

    /** Adds a token; the user's other tokens are not touched. */
    @Transactional
    public IssuedToken createForCurrentUser(String name) {
        User user = currentUserProvider.getCurrentUser();
        String raw = newRawToken();
        IntegrationToken saved = repository.saveAndFlush(new IntegrationToken(user, normalizeName(name), hash(raw), suffix(raw)));
        return new IssuedToken(raw, saved);
    }

    /** Sets or (null/blank) clears the name. The secret is unchanged. */
    @Transactional
    public IntegrationToken renameForCurrentUser(Long id, String name) {
        String normalized = normalizeName(name);
        IntegrationToken token = lockOwned(id);
        token.rename(normalized);
        return repository.saveAndFlush(token);
    }

    /**
     * Replaces the token's secret in place: same id, name and creation time, a
     * new raw token, and the previous secret stops working as soon as this
     * commits. Runs under the token's row lock, so concurrent regenerations of
     * the same token are applied one after the other (the last one's secret is
     * the valid one) and a concurrent revoke either wins (404 here) or removes
     * the regenerated token afterwards.
     */
    @Transactional
    public IssuedToken regenerateForCurrentUser(Long id) {
        IntegrationToken token = lockOwned(id);
        String raw = newRawToken();
        token.replaceSecret(hash(raw), suffix(raw), Instant.now());
        return new IssuedToken(raw, repository.saveAndFlush(token));
    }

    /** Deletes this one token; the user's other tokens keep working. */
    @Transactional
    public void revokeForCurrentUser(Long id) {
        repository.delete(lockOwned(id));
        repository.flush();
    }

    /** The owner of a presented raw token, or empty when it is unknown, regenerated or revoked. */
    public Optional<User> resolveOwner(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(TOKEN_PREFIX)) {
            return Optional.empty();
        }
        return repository.findByTokenHashWithUser(hash(rawToken)).map(IntegrationToken::getUser);
    }

    /**
     * Trimmed name, or null when absent or blank (an unnamed token). The UI shows
     * its own placeholder for null; that placeholder is never stored.
     */
    static String normalizeName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) > IntegrationToken.NAME_MAX_LENGTH) {
            throw new FieldValidationException("name", "Name must be at most " + IntegrationToken.NAME_MAX_LENGTH + " characters");
        }
        return trimmed;
    }

    static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private IntegrationToken lockOwned(Long id) {
        return repository.lockByIdForUser(id, currentUserId())
                .orElseThrow(() -> ResourceNotFoundException.of("Integration token", id));
    }

    private Long currentUserId() {
        return currentUserProvider.getCurrentUser().getId();
    }

    private static String newRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String suffix(String rawToken) {
        return rawToken.substring(rawToken.length() - 4);
    }
}
