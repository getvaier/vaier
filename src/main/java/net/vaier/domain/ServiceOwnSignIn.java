package net.vaier.domain;

/**
 * What one published route's backend asks for by itself, as last seen; what to do about it (null: nothing); and
 * whether that makes it an open service, and one the operator said is meant to be public.
 */
public record ServiceOwnSignIn(String dnsName, String pathPrefix, OwnSignIn ownSignIn, OwnSignIn.Advice advice,
                               boolean open, boolean meantToBePublic) {
}
