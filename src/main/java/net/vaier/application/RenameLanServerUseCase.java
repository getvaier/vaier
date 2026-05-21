package net.vaier.application;

public interface RenameLanServerUseCase {

    /**
     * Renames a registered LAN server. The rename is rejected if {@code newName} collides with
     * another LAN server. Published services keep working — LAN routes are keyed by address,
     * not by the LAN server's name.
     */
    void rename(String currentName, String newName);
}
