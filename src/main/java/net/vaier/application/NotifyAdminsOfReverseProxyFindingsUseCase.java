package net.vaier.application;

import net.vaier.domain.ReverseProxyAudit;

public interface NotifyAdminsOfReverseProxyFindingsUseCase {

    /** Tell admins what the reverse proxy audit found — and that Vaier changed nothing about it. */
    void notifyAdminsOfReverseProxyFindings(ReverseProxyAudit audit);

    /** Tell admins, once, that the reverse proxy config is clear again. */
    void notifyAdminsOfReverseProxyAuditRecovery(ReverseProxyAudit audit);
}
