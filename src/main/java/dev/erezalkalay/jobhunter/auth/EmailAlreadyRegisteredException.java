package dev.erezalkalay.jobhunter.auth;

/** Sign-up for an email that already has an account (compared ignoring case); answered as 409. */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("An account with this email already exists");
    }
}
