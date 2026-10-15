package dev.erezalkalay.jobhunter.common.exception;

public class DuplicateJobException extends RuntimeException {

    public DuplicateJobException() {
        super("This job already exists");
    }

    public DuplicateJobException(String message) {
        super(message);
    }
}
