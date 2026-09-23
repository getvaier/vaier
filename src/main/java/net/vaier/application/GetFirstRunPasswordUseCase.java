package net.vaier.application;

import net.vaier.domain.FirstRunPassword;

import java.util.Optional;

public interface GetFirstRunPasswordUseCase {

    /** The first-run password the stack is on, or empty once an identity provider is configured. */
    Optional<FirstRunPassword> firstRunPassword();
}
