package net.vaier.application;

/**
 * A Vaier app says, through its tunnel, that it is about to disconnect. WireGuard has no goodbye of its own, so without this the
 * peer reads as connected until its handshake ages out. Same proof as {@link CheckStandingUseCase}.
 */
public interface SayGoodbyeUseCase {
    /**
     * @return true when the fleet holds a peer these two keys prove; false for an unknown key and a
     *         wrong preshared key alike, so nothing is learned by trying
     */
    boolean sayGoodbye(String publicKey, String presharedKey);
}
