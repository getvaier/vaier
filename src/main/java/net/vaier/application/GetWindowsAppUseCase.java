package net.vaier.application;

import java.util.Optional;
import net.vaier.domain.WindowsApp;

/** Get the Windows <b>Vaier app</b> this deployment serves (#370), or empty when the image carries none. */
public interface GetWindowsAppUseCase {

    Optional<WindowsApp> windowsApp();
}
