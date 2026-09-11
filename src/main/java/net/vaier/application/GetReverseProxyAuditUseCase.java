package net.vaier.application;

import net.vaier.domain.ReverseProxyAudit;

public interface GetReverseProxyAuditUseCase {

    /**
     * Read the reverse proxy config back and judge it, without touching what admins have been told. The read a
     * surface asks: opening Settings must never be able to move a notification latch.
     */
    ReverseProxyAudit getReverseProxyAudit();
}
