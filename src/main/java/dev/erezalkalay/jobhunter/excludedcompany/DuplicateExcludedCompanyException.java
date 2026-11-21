package dev.erezalkalay.jobhunter.excludedcompany;

/** The user already excludes a company with this name (ignoring case); answered as 409 on the {@code name} field. */
public class DuplicateExcludedCompanyException extends RuntimeException {

    public DuplicateExcludedCompanyException(String name) {
        super("\"" + name + "\" is already in your excluded companies");
    }
}
