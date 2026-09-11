package net.vaier.rest;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import net.vaier.application.AuditReverseProxyConfigUseCase;
import net.vaier.application.NotifyAdminsOfReverseProxyFindingsUseCase;
import net.vaier.application.SyncLanRoutesUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.config.SetupStateHolder;
import net.vaier.config.WildcardDnsStatusHolder;
import net.vaier.domain.Lifecycle;
import net.vaier.domain.ReverseProxyAuditTracker;
import net.vaier.domain.WildcardDnsReport;
import net.vaier.domain.port.ForInitialisingVpnRouting;
import net.vaier.domain.port.ForResolvingDns;
import net.vaier.domain.port.ForResolvingPublicHost;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Vaier's boot sequence, run once the Spring context is up: verify the wildcard DNS record, bring up
 * VPN routing, sync LAN routes, and judge the reverse proxy config Vaier writes itself (#354).
 *
 * <p>This is a <em>driving adapter</em>, not an application service, and that distinction is why it
 * lives here beside {@code StateRefreshScheduler} and {@code RemoteDiskWatcher} rather than in
 * {@code application/service/}. The actor driving it is the application-ready event — an external
 * trigger — exactly as a scheduler is driven by a clock and a controller by an HTTP request. Calling a
 * {@code *UseCase} is what a driving adapter is <em>for</em>; the same call from a real {@code *Service}
 * would be the service-to-service coupling the architecture forbids. It was named
 * {@code LifecycleService} and filed with the application services for a while, which made a perfectly
 * ordinary driving adapter read as a rule violation.
 */
@Component
@Slf4j
public class StartupLifecycleRunner {

    /**
     * How many hex characters of a fresh UUID make the probe label — unguessable enough that no
     * resolver can be holding a cached answer for it, short enough to read in a log line.
     */
    private static final int PROBE_LABEL_LENGTH = 12;

    private final ForInitialisingVpnRouting forInitialisingVpnRouting;
    private final ForResolvingPublicHost publicHostResolver;
    private final ForResolvingDns dnsResolver;
    private final SetupStateHolder setupStateHolder;
    private final WildcardDnsStatusHolder wildcardDnsStatusHolder;
    private final ConfigResolver configResolver;
    private final SyncLanRoutesUseCase syncLanRoutesUseCase;
    private final AuditReverseProxyConfigUseCase reverseProxyAudit;
    private final NotifyAdminsOfReverseProxyFindingsUseCase reverseProxyAuditNotifier;

    public StartupLifecycleRunner(
        ForInitialisingVpnRouting forInitialisingVpnRouting,
        ForResolvingPublicHost publicHostResolver,
        ForResolvingDns dnsResolver,
        SetupStateHolder setupStateHolder,
        WildcardDnsStatusHolder wildcardDnsStatusHolder,
        ConfigResolver configResolver,
        SyncLanRoutesUseCase syncLanRoutesUseCase,
        AuditReverseProxyConfigUseCase reverseProxyAudit,
        NotifyAdminsOfReverseProxyFindingsUseCase reverseProxyAuditNotifier
    ) {
        this.forInitialisingVpnRouting = forInitialisingVpnRouting;
        this.publicHostResolver = publicHostResolver;
        this.dnsResolver = dnsResolver;
        this.setupStateHolder = setupStateHolder;
        this.wildcardDnsStatusHolder = wildcardDnsStatusHolder;
        this.configResolver = configResolver;
        this.syncLanRoutesUseCase = syncLanRoutesUseCase;
        this.reverseProxyAudit = reverseProxyAudit;
        this.reverseProxyAuditNotifier = reverseProxyAuditNotifier;
    }

    @EventListener
    public void handle(ApplicationReadyEvent event) {
        if (!setupStateHolder.isConfigured()) {
            log.info("Vaier is not configured. Set VAIER_DOMAIN in .env, create one DNS record "
                + "(*.<your domain> A <this server's public IP>), and restart the stack.");
            return;
        }

        log.info("Application is ready, starting lifecycle...");
        runLifecycle();
    }

    public void runLifecycle() {
        configResolver.reload();
        WildcardDnsReport report = new Lifecycle(
            publicHostResolver,
            dnsResolver,
            configResolver.getDomain()
        ).start(probeLabel(), probeLabel());
        wildcardDnsStatusHolder.record(report);

        forInitialisingVpnRouting.setupVpnRouting();
        syncLanRoutesUseCase.syncLanRoutes();
        auditReverseProxyConfig();
    }

    /**
     * Judge the reverse proxy config once at boot, as well as on every five-minute sweep. Boot is when a hand
     * edit made while Vaier was down first becomes visible, and it is the cheapest possible moment to
     * notice — the file is already on disk and nothing is asked of the fleet.
     *
     * <p>In its own try/catch: a config read that fails must not stop VPN routing coming up behind it, and
     * an audit is never the reason a boot fails.
     */
    private void auditReverseProxyConfig() {
        try {
            ReverseProxyAuditTracker.Verdict verdict = reverseProxyAudit.auditReverseProxyConfig();
            switch (verdict.outcome()) {
                case ALERT -> reverseProxyAuditNotifier
                    .notifyAdminsOfReverseProxyFindings(verdict.audit());
                case RECOVERED -> reverseProxyAuditNotifier
                    .notifyAdminsOfReverseProxyAuditRecovery(verdict.audit());
                case QUIET -> { /* clear, or trouble admins already know about */ }
            }
        } catch (Exception e) {
            log.debug("Could not audit the reverse proxy config at startup: {}", e.getMessage());
        }
    }

    /**
     * A random label to look up under the domain. Generated here, not in the domain, so the check
     * itself stays deterministic and testable. Called twice per run — the probe is two labels deep and
     * the two slices must be independent, so that neither label can be a name the zone actually has.
     */
    private String probeLabel() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, PROBE_LABEL_LENGTH);
    }
}
