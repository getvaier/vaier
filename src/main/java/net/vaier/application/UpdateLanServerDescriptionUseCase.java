package net.vaier.application;

public interface UpdateLanServerDescriptionUseCase {

    /**
     * Sets (or, with a blank value, clears) the free-text description of a registered LAN
     * server. Throws {@link java.util.NoSuchElementException} if no LAN server has that name.
     */
    void updateDescription(String name, String description);
}
