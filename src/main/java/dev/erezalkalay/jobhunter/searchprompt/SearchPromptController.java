package dev.erezalkalay.jobhunter.searchprompt;

import java.time.Instant;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings → Job Search → Search Prompt, for the normal logged-in browser session (session
 * + CSRF) only; a bearer token cannot reach it, so the prompt can only be
 * edited in the application UI. MCP clients read it via
 * {@code GET /api/integrations/search-prompt}.
 */
@RestController
@RequestMapping("/api/settings/search-prompt")
public class SearchPromptController {

    /** Body of save: the complete text, stored verbatim. Empty or whitespace-only clears the prompt. */
    public record SearchPromptRequest(String prompt) {
    }

    /** {@code prompt} is "" and {@code updatedAt} null when no prompt is configured. */
    public record SearchPromptResponse(String prompt, Instant updatedAt) {

        static final SearchPromptResponse NONE = new SearchPromptResponse("", null);

        static SearchPromptResponse of(Optional<SearchPrompt> prompt) {
            return prompt.map(p -> new SearchPromptResponse(p.getPrompt(), p.getUpdatedAt())).orElse(NONE);
        }
    }

    private final SearchPromptService service;

    public SearchPromptController(SearchPromptService service) {
        this.service = service;
    }

    @GetMapping
    public SearchPromptResponse get() {
        return SearchPromptResponse.of(service.getForCurrentUser());
    }

    @PutMapping
    public SearchPromptResponse save(@RequestBody SearchPromptRequest request) {
        return SearchPromptResponse.of(service.saveForCurrentUser(request.prompt()));
    }

    @DeleteMapping
    public ResponseEntity<Void> clear() {
        service.clearForCurrentUser();
        return ResponseEntity.noContent().build();
    }
}
