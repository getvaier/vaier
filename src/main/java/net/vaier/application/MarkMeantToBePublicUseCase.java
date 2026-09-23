package net.vaier.application;

public interface MarkMeantToBePublicUseCase {

    /** Say that the published service at {@code dnsName}/{@code pathPrefix} is (or no longer is) meant to be public. */
    void markMeantToBePublic(String dnsName, String pathPrefix, boolean meantToBePublic);
}
