package net.vaier.domain;

import java.util.List;
import java.util.Optional;
import net.vaier.domain.DnsRecord.DnsRecordType;
import net.vaier.domain.port.ForInitialisingUserService;
import net.vaier.domain.port.ForPersistingDnsRecords;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForPublishingAutheliaAssets;
import net.vaier.domain.port.ForResolvingPublicHost;
import net.vaier.domain.port.ForResolvingPublicHost.PublicHost;
import net.vaier.domain.port.ForRestartingContainers;
import net.vaier.domain.port.ForWritingBootstrapCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LifecycleTest {

    @Mock ForInitialisingUserService forInitialisingUserService;
    @Mock ForPersistingUsers forPersistingUsers;
    @Mock ForPersistingDnsRecords forPersistingDnsRecords;
    @Mock ForRestartingContainers containerRestarter;
    @Mock ForWritingBootstrapCredentials bootstrapCredentialsWriter;
    @Mock ForPublishingAutheliaAssets autheliaAssetsPublisher;
    @Mock ForResolvingPublicHost publicHostResolver;

    private Lifecycle lifecycle() {
        return new Lifecycle(forInitialisingUserService, forPersistingUsers, forPersistingDnsRecords, containerRestarter,
                bootstrapCredentialsWriter, autheliaAssetsPublisher, publicHostResolver,
                "test.example.com", "admin", "authelia", "vaier", "login");
    }

    @Test
    void initUsers_createsAdminWithRandomPasswordOnFirstStartup() {
        when(forInitialisingUserService.initialiseConfiguration()).thenReturn(false);
        when(forPersistingUsers.isDatabaseInitialised()).thenReturn(false);

        lifecycle().initUsers();

        ArgumentCaptor<String> passwordCaptor = ArgumentCaptor.forClass(String.class);
        verify(forPersistingUsers).addUser(eq("admin"), passwordCaptor.capture(), any(), any(), any());
        assertThat(passwordCaptor.getValue()).isNotEqualTo("admin");
        assertThat(passwordCaptor.getValue()).hasSizeGreaterThanOrEqualTo(12);
    }

    @Test
    void initUsers_writesBootstrapPasswordToFileInsteadOfLogging() {
        when(forInitialisingUserService.initialiseConfiguration()).thenReturn(false);
        when(forPersistingUsers.isDatabaseInitialised()).thenReturn(false);

        lifecycle().initUsers();

        ArgumentCaptor<String> usernameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> passwordCaptor = ArgumentCaptor.forClass(String.class);
        verify(bootstrapCredentialsWriter)
            .writeBootstrapPassword(usernameCaptor.capture(), passwordCaptor.capture());
        assertThat(usernameCaptor.getValue()).isEqualTo("admin");
        assertThat(passwordCaptor.getValue()).hasSizeGreaterThanOrEqualTo(12);
    }

    @Test
    void initUsers_doesNotWriteBootstrapPasswordWhenAdminAlreadyExists() {
        when(forInitialisingUserService.initialiseConfiguration()).thenReturn(false);
        when(forPersistingUsers.isDatabaseInitialised()).thenReturn(true);

        lifecycle().initUsers();

        verify(bootstrapCredentialsWriter, never()).writeBootstrapPassword(any(), any());
    }

    @Test
    void initUsers_doesNotCreateAdminOnSubsequentStartup() {
        when(forInitialisingUserService.initialiseConfiguration()).thenReturn(false);
        when(forPersistingUsers.isDatabaseInitialised()).thenReturn(true);

        lifecycle().initUsers();

        verify(forPersistingUsers, never()).addUser(any(), any(), any(), any(), any());
    }

    @Test
    void initUsers_restartsAutheliaWhenAdminIsCreated() {
        when(forInitialisingUserService.initialiseConfiguration()).thenReturn(false);
        when(forPersistingUsers.isDatabaseInitialised()).thenReturn(false);

        lifecycle().initUsers();

        verify(containerRestarter).restartContainer("authelia");
    }

    @Test
    void initUsers_restartsAutheliaWhenConfigChanged() {
        when(forInitialisingUserService.initialiseConfiguration()).thenReturn(true);
        when(forPersistingUsers.isDatabaseInitialised()).thenReturn(true);

        lifecycle().initUsers();

        verify(containerRestarter).restartContainer("authelia");
    }

    @Test
    void initUsers_doesNotRestartAutheliaWhenNothingChanged() {
        when(forInitialisingUserService.initialiseConfiguration()).thenReturn(false);
        when(forPersistingUsers.isDatabaseInitialised()).thenReturn(true);

        lifecycle().initUsers();

        verify(containerRestarter, never()).restartContainer(any());
    }

    @Test
    void initUsers_publishesBrandingAssetsBeforeAutheliaRestart() {
        when(forInitialisingUserService.initialiseConfiguration()).thenReturn(true);
        when(forPersistingUsers.isDatabaseInitialised()).thenReturn(true);

        lifecycle().initUsers();

        var inOrder = inOrder(autheliaAssetsPublisher, containerRestarter);
        inOrder.verify(autheliaAssetsPublisher).publishAssets();
        inOrder.verify(containerRestarter).restartContainer("authelia");
    }

    @Test
    void initDns_autoCreatesVaierCnameRecord_whenMissingAndResolverReturnsHostname() {
        DnsZone zone = new DnsZone("test.example.com");
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of());
        when(publicHostResolver.resolve())
            .thenReturn(Optional.of(new PublicHost("ec2-1-2-3-4.compute.amazonaws.com", DnsRecordType.CNAME)));

        lifecycle().start();

        ArgumentCaptor<DnsRecord> captor = ArgumentCaptor.forClass(DnsRecord.class);
        verify(forPersistingDnsRecords, atLeastOnce()).addDnsRecord(captor.capture(), eq(zone));
        List<DnsRecord> added = captor.getAllValues();
        assertThat(added).anyMatch(r -> r.name().equals("vaier.test.example.com")
            && r.type() == DnsRecordType.CNAME
            && r.values().equals(List.of("ec2-1-2-3-4.compute.amazonaws.com")));
    }

    @Test
    void initDns_autoCreatesVaierARecord_whenMissingAndResolverReturnsIp() {
        DnsZone zone = new DnsZone("test.example.com");
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of());
        when(publicHostResolver.resolve())
            .thenReturn(Optional.of(new PublicHost("203.0.113.10", DnsRecordType.A)));

        lifecycle().start();

        ArgumentCaptor<DnsRecord> captor = ArgumentCaptor.forClass(DnsRecord.class);
        verify(forPersistingDnsRecords, atLeastOnce()).addDnsRecord(captor.capture(), eq(zone));
        assertThat(captor.getAllValues()).anyMatch(r -> r.name().equals("vaier.test.example.com")
            && r.type() == DnsRecordType.A
            && r.values().equals(List.of("203.0.113.10")));
    }

    @Test
    void initDns_doesNotThrow_whenVaierRecordMissingAndResolverEmpty() {
        DnsZone zone = new DnsZone("test.example.com");
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of());
        when(publicHostResolver.resolve()).thenReturn(Optional.empty());

        assertThatCode(() -> lifecycle().start()).doesNotThrowAnyException();

        verify(forPersistingDnsRecords, never()).addDnsRecord(any(), any());
    }

    @Test
    void initDns_stillCreatesAuthCnameRecord_afterAutoCreatingVaierRecord() {
        DnsZone zone = new DnsZone("test.example.com");
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of());
        when(publicHostResolver.resolve())
            .thenReturn(Optional.of(new PublicHost("ec2-1-2-3-4.compute.amazonaws.com", DnsRecordType.CNAME)));

        lifecycle().start();

        ArgumentCaptor<DnsRecord> captor = ArgumentCaptor.forClass(DnsRecord.class);
        verify(forPersistingDnsRecords, atLeastOnce()).addDnsRecord(captor.capture(), eq(zone));
        assertThat(captor.getAllValues()).anyMatch(r -> r.name().equals("login.test.example.com")
            && r.type() == DnsRecordType.CNAME
            && r.values().equals(List.of("vaier.test.example.com")));
    }

    @Test
    void initDns_doesNotConsultResolver_whenVaierRecordAlreadyExists() {
        DnsZone zone = new DnsZone("test.example.com");
        DnsRecord vaierRecord = new DnsRecord("vaier.test.example.com", DnsRecordType.CNAME, 300L,
            List.of("existing.example.com"));
        when(forPersistingDnsRecords.getDnsZones()).thenReturn(List.of(zone));
        when(forPersistingDnsRecords.getDnsRecords(zone)).thenReturn(List.of(vaierRecord));

        lifecycle().start();

        verify(publicHostResolver, never()).resolve();
    }
}
