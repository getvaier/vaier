package net.vaier.application;

/** Drop one fact from Vaier's <b>Memory</b> by its id (#360). Throws {@code NotFoundException} when unknown. */
public interface ForgetUseCase {

    void forget(String id);
}
