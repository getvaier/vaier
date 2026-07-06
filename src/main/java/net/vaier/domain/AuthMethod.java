package net.vaier.domain;

/** How a {@link HostCredential} authenticates to its machine: a password, or an SSH private key. */
public enum AuthMethod {
    PASSWORD,
    PRIVATE_KEY
}
