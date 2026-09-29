package net.vaier.application;

public interface SetMarvinsServiceCredentialUseCase {

    /** Hand {@code host} this login on Marvin's service calls, and never to a browser. */
    void setMarvinsServiceCredential(String host, String username, String password);
}
