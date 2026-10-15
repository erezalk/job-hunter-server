package dev.erezalkalay.jobhunter.jobdata;

/** A valid file that cannot be restored into the current state (e.g. the Board is not empty); nothing was written (409). */
public class ImportConflictException extends RuntimeException {

    public ImportConflictException(String message) {
        super(message);
    }
}
