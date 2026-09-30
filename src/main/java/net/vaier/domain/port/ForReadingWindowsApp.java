package net.vaier.domain.port;

import java.util.Optional;
import net.vaier.domain.WindowsApp;

/** Reads the Windows <b>Vaier app</b> zip this deployment serves. No zip in the image is an empty answer. */
public interface ForReadingWindowsApp {

    /**
     * The app to serve, or empty when there is none.
     *
     * @param servedHost this Vaier's own host name, stamped into the zip; null or blank serves it as built
     */
    Optional<WindowsApp> readApp(String servedHost);
}
