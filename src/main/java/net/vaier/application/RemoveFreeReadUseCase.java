package net.vaier.application;

/** Take one path off a published service's <b>free reads</b>, so Marvin asks for it again. */
public interface RemoveFreeReadUseCase {

    void removeFreeRead(String host, String pathPrefix, String path);
}
