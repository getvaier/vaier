package net.vaier.domain.port;

import net.vaier.domain.Bundle;

import java.util.Optional;

/** Driven port holding the <b>Bundle</b>s Chat has offered (#360). Ephemeral: an hour, and never past the process. */
public interface ForHoldingBundles {

    void hold(Bundle bundle);

    Optional<Bundle> find(String id);
}
