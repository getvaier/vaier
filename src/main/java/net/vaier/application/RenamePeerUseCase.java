package net.vaier.application;

public interface RenamePeerUseCase {

    /**
     * Renames a VPN peer. {@code newName} is sanitised the same way new peer names are; the rename
     * is rejected if the sanitised name collides with an existing peer.
     */
    void renamePeer(String currentName, String newName);
}
