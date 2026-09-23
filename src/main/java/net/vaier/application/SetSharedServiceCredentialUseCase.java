package net.vaier.application;

public interface SetSharedServiceCredentialUseCase {

    /** Hand {@code host} this login for everyone Vaier lets in who has no personal one. */
    void setSharedServiceCredential(String host, String username, String password);
}
