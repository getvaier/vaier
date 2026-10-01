package net.vaier.application;

/**
 * A Vaier app says, through its tunnel, that it has just connected — so the fleet shows it now rather
 * than at the next stats tick. Same proof as {@link CheckStandingUseCase}.
 */
public interface SayHelloUseCase {
    /**
     * @return true when the fleet holds a peer these two keys prove; false for an unknown key and a
     *         wrong preshared key alike, so nothing is learned by trying
     */
    boolean sayHello(String publicKey, String presharedKey);
}
