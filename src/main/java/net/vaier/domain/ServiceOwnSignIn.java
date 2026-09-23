package net.vaier.domain;

/** What one published route's backend asks for by itself, as last seen, and what to do about it (null: nothing). */
public record ServiceOwnSignIn(String dnsName, String pathPrefix, OwnSignIn ownSignIn, OwnSignIn.Advice advice) {
}
