package dev.erezalkalay.jobhunter.integration;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Management of the current user's integration tokens, for the normal
 * logged-in browser session (session + CSRF) only — a bearer token cannot
 * reach it. Responses never contain a digest; the raw secret appears only in
 * the response of the create and regenerate calls.
 */
@RestController
@RequestMapping("/api/settings/integrations/tokens")
public class IntegrationTokenController {

    /** Body of create and rename. A null, missing or blank name means "unnamed". */
    public record TokenNameRequest(String name) {
    }

    /** A token as listed: no secret, no digest. */
    public record TokenResponse(Long id, String name, Instant createdAt, Instant regeneratedAt, String suffix) {
        static TokenResponse of(IntegrationToken token) {
            return new TokenResponse(token.getId(), token.getName(), token.getCreatedAt(), token.getRegeneratedAt(),
                    token.getTokenSuffix());
        }
    }

    /** Returned only by create and regenerate: the token plus its new raw secret, shown once. */
    public record IssuedTokenResponse(Long id, String name, Instant createdAt, Instant regeneratedAt, String suffix, String token) {
        static IssuedTokenResponse of(IntegrationTokenService.IssuedToken issued) {
            IntegrationToken token = issued.token();
            return new IssuedTokenResponse(token.getId(), token.getName(), token.getCreatedAt(), token.getRegeneratedAt(),
                    token.getTokenSuffix(), issued.rawToken());
        }
    }

    private final IntegrationTokenService service;

    public IntegrationTokenController(IntegrationTokenService service) {
        this.service = service;
    }

    @GetMapping
    public List<TokenResponse> list() {
        return service.listForCurrentUser().stream().map(TokenResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssuedTokenResponse create(@RequestBody(required = false) TokenNameRequest request) {
        return IssuedTokenResponse.of(service.createForCurrentUser(request == null ? null : request.name()));
    }

    @PatchMapping("/{id}")
    public TokenResponse rename(@PathVariable Long id, @RequestBody TokenNameRequest request) {
        return TokenResponse.of(service.renameForCurrentUser(id, request.name()));
    }

    @PostMapping("/{id}/regenerate")
    public IssuedTokenResponse regenerate(@PathVariable Long id) {
        return IssuedTokenResponse.of(service.regenerateForCurrentUser(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable Long id) {
        service.revokeForCurrentUser(id);
        return ResponseEntity.noContent().build();
    }
}
