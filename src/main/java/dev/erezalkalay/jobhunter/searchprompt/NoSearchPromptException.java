package dev.erezalkalay.jobhunter.searchprompt;

import dev.erezalkalay.jobhunter.common.exception.ResourceNotFoundException;

/**
 * The token user has not configured a search prompt. A 404 like any missing
 * resource, tagged with {@link #CODE} so an integration client can tell it
 * apart from an unknown route (a backend without the endpoint).
 */
public class NoSearchPromptException extends ResourceNotFoundException {

    public static final String CODE = "SEARCH_PROMPT_NOT_FOUND";

    public NoSearchPromptException() {
        super("No search prompt configured");
    }
}
