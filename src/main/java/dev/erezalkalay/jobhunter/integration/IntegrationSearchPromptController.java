package dev.erezalkalay.jobhunter.integration;

import dev.erezalkalay.jobhunter.searchprompt.NoSearchPromptException;
import dev.erezalkalay.jobhunter.searchprompt.SearchPromptService;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only bearer-token view of the token user's saved Search Prompt (the
 * job-search instructions from Settings → Job Search → Search Prompt), verbatim. Read from
 * the database on every request, so an edit in the UI is visible at once.
 * Authenticated by the integration filter chain only; the owner is always the
 * token's user and there are no parameters. 404 with code
 * {@code SEARCH_PROMPT_NOT_FOUND} when none is configured. There is
 * deliberately no write endpoint: the prompt is edited only in the UI.
 */
@RestController
@RequestMapping("/api/integrations")
public class IntegrationSearchPromptController {

    public record IntegrationSearchPromptResponse(String prompt, Instant updatedAt) {
    }

    private final SearchPromptService service;

    public IntegrationSearchPromptController(SearchPromptService service) {
        this.service = service;
    }

    @GetMapping("/search-prompt")
    public IntegrationSearchPromptResponse getSearchPrompt() {
        return service.getForCurrentUser()
                .map(p -> new IntegrationSearchPromptResponse(p.getPrompt(), p.getUpdatedAt()))
                .orElseThrow(NoSearchPromptException::new);
    }
}
