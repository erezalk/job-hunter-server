package dev.erezalkalay.jobhunter.searchprompt;

import dev.erezalkalay.jobhunter.auth.CurrentUserProvider;
import dev.erezalkalay.jobhunter.common.exception.FieldValidationException;
import dev.erezalkalay.jobhunter.user.User;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The current user's search prompt. The text is stored and returned exactly as
 * entered — never trimmed, normalized or rewritten (line breaks, Markdown and
 * any Unicode are preserved). Saving a blank text (empty or whitespace only)
 * is the same as clearing: the prompt is removed. Every operation is scoped to
 * the current user; there is no id or user parameter anywhere.
 */
@Service
@Transactional(readOnly = true)
public class SearchPromptService {

    private final SearchPromptRepository repository;
    private final CurrentUserProvider currentUserProvider;

    public SearchPromptService(SearchPromptRepository repository, CurrentUserProvider currentUserProvider) {
        this.repository = repository;
        this.currentUserProvider = currentUserProvider;
    }

    public Optional<SearchPrompt> getForCurrentUser() {
        return repository.findByUserId(currentUserProvider.getCurrentUser().getId());
    }

    /** Creates or replaces the prompt; a blank text clears it (then empty is returned). */
    @Transactional
    public Optional<SearchPrompt> saveForCurrentUser(String prompt) {
        if (prompt == null) {
            throw new FieldValidationException("prompt", "Prompt is required (send an empty text to clear it)");
        }
        if (prompt.length() > SearchPrompt.PROMPT_MAX_LENGTH) {
            throw new FieldValidationException("prompt",
                    "Prompt must be at most " + String.format("%,d", SearchPrompt.PROMPT_MAX_LENGTH) + " characters");
        }
        User user = currentUserProvider.getCurrentUser();
        if (prompt.isBlank()) {
            repository.deleteByUserId(user.getId());
            return Optional.empty();
        }
        SearchPrompt saved = repository.findByUserId(user.getId())
                .map(existing -> {
                    existing.replaceText(prompt);
                    return existing;
                })
                .orElseGet(() -> new SearchPrompt(user, prompt));
        return Optional.of(repository.saveAndFlush(saved));
    }

    @Transactional
    public void clearForCurrentUser() {
        repository.deleteByUserId(currentUserProvider.getCurrentUser().getId());
    }
}
