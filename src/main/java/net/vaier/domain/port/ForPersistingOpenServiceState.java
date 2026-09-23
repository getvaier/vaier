package net.vaier.domain.port;

import net.vaier.domain.OpenServiceState;

/** Driven port for {@link OpenServiceState}. An absent store is the healthy state: nothing outstanding. */
public interface ForPersistingOpenServiceState {

    OpenServiceState read();

    void save(OpenServiceState state);
}
