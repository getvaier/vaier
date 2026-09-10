package net.vaier.domain;

/**
 * Mail was set up and asked for, and the mail server would not take it just now (#360). Not the same as
 * mail not being set up: the settings are fine, the moment was not, and the operator should be told to try
 * again rather than sent to Settings.
 */
public class MailNotSentException extends RuntimeException {

    public MailNotSentException() {
        super("The mail server would not take the mail just now; ask again in a minute.");
    }
}
