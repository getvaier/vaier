package net.vaier.rest;

import java.util.List;
import java.util.Optional;
import net.vaier.application.AuditReverseProxyConfigUseCase;
import net.vaier.application.NotifyAdminsOfReverseProxyFindingsUseCase;
import net.vaier.application.SyncLanRoutesUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.config.SetupStateHolder;
import net.vaier.config.WildcardDnsStatusHolder;
import net.vaier.domain.ReverseProxyAudit;
import net.vaier.domain.ReverseProxyAuditTracker;
import net.vaier.domain.ReverseProxyConfig;
import net.vaier.domain.WildcardDnsStatus;
import net.vaier.domain.port.ForInitialisingVpnRouting;
import net.vaier.domain.port.ForResolvingDns;
import net.vaier.domain.port.ForResolvingPublicHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StartupLifecycleRunnerTest {

    @Mock ForInitialisingVpnRouting forInitialisingVpnRouting;
    @Mock ForResolvingPublicHost publicHostResolver;
    @Mock ForResolvingDns dnsResolver;
    @Mock SetupStateHolder setupStateHolder;
    @Mock WildcardDnsStatusHolder wildcardDnsStatusHolder;
    @Mock ConfigResolver configResolver;
    @Mock SyncLanRoutesUseCase syncLanRoutesUseCase;
    @Mock AuditReverseProxyConfigUseCase reverseProxyAudit;
    @Mock NotifyAdminsOfReverseProxyFindingsUseCase reverseProxyAuditNotifier;
    @Mock ApplicationReadyEvent event;

    private StartupLifecycleRunner runner() {
        return new StartupLifecycleRunner(
            forInitialisingVpnRouting,
            publicHostResolver,
            dnsResolver,
            setupStateHolder,
            wildcardDnsStatusHolder,
            configResolver,
            syncLanRoutesUseCase,
            reverseProxyAudit,
            reverseProxyAuditNotifier
        );
    }

    private void configured() {
        when(setupStateHolder.isConfigured()).thenReturn(true);
        when(configResolver.getDomain()).thenReturn("example.com");
    }

    @Test
    void skipsLifecycleWhenUnconfigured() {
        when(setupStateHolder.isConfigured()).thenReturn(false);

        runner().handle(event);

        verify(forInitialisingVpnRouting, never()).setupVpnRouting();
        verify(syncLanRoutesUseCase, never()).syncLanRoutes();
        verifyNoInteractions(dnsResolver, wildcardDnsStatusHolder);
    }

    @Test
    void runsSyncLanRoutesOnReadyWhenConfigured() {
        configured();
        when(dnsResolver.resolveAddresses(any())).thenReturn(List.of("52.29.74.114"));
        when(publicHostResolver.resolvePublicIp()).thenReturn(Optional.of("52.29.74.114"));

        runner().handle(event);

        verify(syncLanRoutesUseCase).syncLanRoutes();
    }

    @Test
    void recordsTheWildcardVerdictSoTheSettingsPaneCanStateIt() {
        configured();
        when(dnsResolver.resolveAddresses(any())).thenReturn(List.of());
        when(publicHostResolver.resolvePublicIp()).thenReturn(Optional.of("52.29.74.114"));

        runner().handle(event);

        verify(wildcardDnsStatusHolder)
            .record(argThat(r -> r.status() == WildcardDnsStatus.NOT_RESOLVING));
    }

    /**
     * The probe has to be two labels deep: that is the depth Vaier publishes at, and a wildcard matches
     * by closest encloser, so a one-label probe would report success over a zone where every
     * machine-qualified service was dead. Both labels are random, and independently so.
     */
    @Test
    void probesTwoIndependentRandomLabelsDeep() {
        configured();
        when(dnsResolver.resolveAddresses(any())).thenReturn(List.of("52.29.74.114"));
        when(publicHostResolver.resolvePublicIp()).thenReturn(Optional.of("52.29.74.114"));

        runner().handle(event);

        ArgumentCaptor<String> probed = ArgumentCaptor.forClass(String.class);
        verify(dnsResolver).resolveAddresses(probed.capture());

        String[] labels = probed.getValue().split("\\.");
        assertThat(labels).hasSize(4);
        assertThat(probed.getValue()).endsWith(".example.com");
        assertThat(labels[0]).isNotEqualTo(labels[1]);
    }

    // --- the reverse proxy audit at boot (#354) ---

    @Test
    void boot_mailsTheReverseProxyFindingsWhenTheDomainSaysItIsNews() {
        configured();
        when(reverseProxyAudit.auditReverseProxyConfig()).thenReturn(
            new ReverseProxyAuditTracker.Verdict(ReverseProxyAuditTracker.Outcome.ALERT,
                ReverseProxyAudit.of(ReverseProxyConfig.builder()
                    .middlewares(List.of(ReverseProxyConfig.ConfiguredMiddleware.builder()
                        .protocol(ReverseProxyConfig.Protocol.HTTP).name("orphaned-redirect").build()))
                    .build())));

        runner().handle(event);

        verify(reverseProxyAuditNotifier).notifyAdminsOfReverseProxyFindings(any());
    }

    @Test
    void boot_saysNothingAboutAReverseProxyConfigTheDomainCallsQuiet() {
        configured();
        when(reverseProxyAudit.auditReverseProxyConfig()).thenReturn(
            new ReverseProxyAuditTracker.Verdict(ReverseProxyAuditTracker.Outcome.QUIET,
                ReverseProxyAudit.of(ReverseProxyConfig.empty())));

        runner().handle(event);

        verifyNoInteractions(reverseProxyAuditNotifier);
    }

    @Test
    void boot_isNeverStoppedByAReverseProxyAuditThatFails() {
        configured();
        when(reverseProxyAudit.auditReverseProxyConfig())
            .thenThrow(new RuntimeException("config unreadable"));

        runner().handle(event);

        verify(syncLanRoutesUseCase).syncLanRoutes();
    }
}
