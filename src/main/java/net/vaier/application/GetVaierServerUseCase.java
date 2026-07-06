package net.vaier.application;

import net.vaier.domain.Machine;

public interface GetVaierServerUseCase {

    /**
     * The Vaier server host as a singleton synthetic machine (#311), carrying its effective SSH
     * access. Lets the Infrastructure page render the server's own card with the same SSH-access
     * toggle and host-credential control every other machine has.
     */
    Machine getVaierServerMachine();
}
