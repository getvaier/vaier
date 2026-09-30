package net.vaier.domain.port;

import java.util.Optional;
import net.vaier.domain.WindowsApp;

/** Reads the Windows <b>Vaier app</b> installer this deployment serves. None in the image is an empty answer. */
public interface ForReadingWindowsApp {

    /**
     * The app to serve, or empty when there is none.
     *
     * @param servedHost this Vaier's own host name, stamped onto the installer; null or blank serves it as built
     */
    Optional<WindowsApp> readApp(String servedHost);
}
