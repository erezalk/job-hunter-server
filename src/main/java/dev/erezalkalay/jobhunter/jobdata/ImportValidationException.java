package dev.erezalkalay.jobhunter.jobdata;

import java.util.Map;

/** The import file parsed but violates the format's rules; nothing was written (400). */
public class ImportValidationException extends RuntimeException {

    private final Map<String, String> errors;

    public ImportValidationException(Map<String, String> errors) {
        super("The import file is invalid");
        this.errors = Map.copyOf(errors);
    }

    /** Location in the file (e.g. {@code jobs[2].application.status}) to message. */
    public Map<String, String> getErrors() {
        return errors;
    }
}
