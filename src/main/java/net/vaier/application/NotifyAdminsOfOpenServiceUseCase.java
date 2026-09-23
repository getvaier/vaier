package net.vaier.application;

import net.vaier.domain.OpenService;

public interface NotifyAdminsOfOpenServiceUseCase {

    /** Tell admins, once, that a published service is open to anyone, and how to close it. */
    void notifyAdminsOfOpenService(OpenService openService);
}
