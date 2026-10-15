package dev.erezalkalay.jobhunter.common.exception;

/**
 * Signals a semantically invalid request that passed bean validation, e.g. an
 * unknown application status code or an invalid status transition.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
