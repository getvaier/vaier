package net.vaier.application;

import net.vaier.domain.ServiceOwnSignIn;

import java.util.List;

public interface GetOwnSignInsUseCase {

    /** What each published service asks for by itself, for the routes Vaier has looked at so far. */
    List<ServiceOwnSignIn> getOwnSignIns();
}
