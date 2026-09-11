package net.vaier.application;

import net.vaier.domain.ReverseProxyAuditTracker;

public interface AuditReverseProxyConfigUseCase {

    /**
     * Read the reverse proxy config back, judge it, and record what this sweep found against what admins were last
     * told. The sweep's read — the verdict says whether there is anything to say, and the caller decides
     * whom to tell.
     */
    ReverseProxyAuditTracker.Verdict auditReverseProxyConfig();
}
