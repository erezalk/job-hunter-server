package dev.erezalkalay.jobhunter.jobdata;

/** The import body exceeded the configured size limit (413). */
public class ImportTooLargeException extends RuntimeException {

    public ImportTooLargeException(long maxBytes) {
        super("The import file is too large (maximum " + (maxBytes / (1024 * 1024)) + " MB)");
    }
}
