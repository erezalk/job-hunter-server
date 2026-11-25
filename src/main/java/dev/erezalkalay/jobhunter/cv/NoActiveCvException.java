package dev.erezalkalay.jobhunter.cv;

import dev.erezalkalay.jobhunter.common.exception.ResourceNotFoundException;

/**
 * The current user has no active CV. A {@link ResourceNotFoundException}
 * (404), additionally tagged with {@link #CODE} in the error body so an
 * integration client can tell "no CV uploaded" apart from an unknown route.
 */
public class NoActiveCvException extends ResourceNotFoundException {

    public static final String CODE = "CV_NOT_FOUND";

    public NoActiveCvException() {
        super("No active CV");
    }
}
