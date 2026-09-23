package net.vaier.domain.port;

import net.vaier.domain.FirstRunPassword;

import java.util.Optional;

/** Reads the first-run password dex-init left for Vaier, if the stack is still on one. */
public interface ForReadingFirstRunPassword {
    Optional<FirstRunPassword> read();
}
