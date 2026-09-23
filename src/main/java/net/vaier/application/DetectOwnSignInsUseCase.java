package net.vaier.application;

public interface DetectOwnSignInsUseCase {

    /** Look again at every published service whose last look is due, and remember what each asks for. */
    void detectOwnSignIns();
}
