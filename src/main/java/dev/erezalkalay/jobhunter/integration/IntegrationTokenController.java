package dev.erezalkalay.jobhunter.integration;

import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Token management for the normal logged-in browser session (session + CSRF). */
@RestController
@RequestMapping("/api/settings/integrations/mcp-token")
public class IntegrationTokenController {

    public record TokenStatusResponse(boolean configured, Instant createdAt, String suffix) {
        static TokenStatusResponse of(IntegrationToken token) {
            return new TokenStatusResponse(true, token.getCreatedAt(), token.getTokenSuffix());
        }

        static TokenStatusResponse none() {
            return new TokenStatusResponse(false, null, null);
        }
    }

    /** Returned only by the generate call; never by any status endpoint. */
    public record GeneratedTokenResponse(String token, Instant createdAt, String suffix) {
    }

    private final IntegrationTokenService service;

    public IntegrationTokenController(IntegrationTokenService service) {
        this.service = service;
    }

    @GetMapping
    public TokenStatusResponse status() {
        return service.statusForCurrentUser().map(TokenStatusResponse::of).orElseGet(TokenStatusResponse::none);
    }

    @PostMapping
    public GeneratedTokenResponse generate() {
        IntegrationTokenService.GeneratedToken generated = service.generateForCurrentUser();
        return new GeneratedTokenResponse(
                generated.rawToken(), generated.token().getCreatedAt(), generated.token().getTokenSuffix());
    }

    @DeleteMapping
    public ResponseEntity<Void> revoke() {
        service.revokeForCurrentUser();
        return ResponseEntity.noContent().build();
    }
}
