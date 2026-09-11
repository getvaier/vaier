package net.vaier.application.service;

import net.vaier.application.AddReverseProxyRouteUseCase;
import net.vaier.application.AuditReverseProxyConfigUseCase;
import net.vaier.application.DeleteReverseProxyRouteUseCase;
import net.vaier.application.GetReverseProxyAuditUseCase;
import net.vaier.application.GetReverseProxyRoutesUseCase;
import net.vaier.domain.ReverseProxyAudit;
import net.vaier.domain.ReverseProxyAuditTracker;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.port.ForPersistingReverseProxyAuditState;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
import net.vaier.domain.port.ForReadingReverseProxyConfig;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReverseProxyService implements
    AddReverseProxyRouteUseCase,
    DeleteReverseProxyRouteUseCase,
    GetReverseProxyRoutesUseCase,
    GetReverseProxyAuditUseCase,
    AuditReverseProxyConfigUseCase {

    private final ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes;
    private final ForReadingReverseProxyConfig forReadingReverseProxyConfig;
    private final ReverseProxyAuditTracker auditTracker;

    public ReverseProxyService(ForPersistingReverseProxyRoutes forPersistingReverseProxyRoutes,
                               ForReadingReverseProxyConfig forReadingReverseProxyConfig,
                               ForPersistingReverseProxyAuditState reverseProxyAuditState) {
        this.forPersistingReverseProxyRoutes = forPersistingReverseProxyRoutes;
        this.forReadingReverseProxyConfig = forReadingReverseProxyConfig;
        // The domain owns the port call; this service only hands it in. On disk, so a redeploy cannot wipe
        // what admins were told and turn a transition-only alert into a per-deploy one.
        this.auditTracker = new ReverseProxyAuditTracker(reverseProxyAuditState);
    }

    @Override
    public void addReverseProxyRoute(ReverseProxyRouteUco route) {
        ReverseProxyRoute.validateForPublication(route.dnsName(), route.address(), route.port());
        forPersistingReverseProxyRoutes.addReverseProxyRoute(
            route.dnsName(),
            route.address(),
            route.port(),
            route.requiresAuth(),
            route.rootRedirectPath()
        );
    }

    @Override
    public void deleteReverseProxyRoute(String dnsName) {
        ReverseProxyRoute.validateDnsName(dnsName);
        forPersistingReverseProxyRoutes.deleteReverseProxyRouteByDnsName(dnsName);
    }

    @Override
    public List<ReverseProxyRoute> getReverseProxyRoutes() {
        return forPersistingReverseProxyRoutes.getReverseProxyRoutes();
    }

    @Override
    public ReverseProxyAudit getReverseProxyAudit() {
        return ReverseProxyAudit.of(forReadingReverseProxyConfig.getReverseProxyConfig());
    }

    @Override
    public ReverseProxyAuditTracker.Verdict auditReverseProxyConfig() {
        return auditTracker.observe(getReverseProxyAudit());
    }
}
