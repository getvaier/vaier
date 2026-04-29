package net.vaier.application.service;

import net.vaier.application.GetUsersUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.PeerSnapshot;
import net.vaier.domain.MachineType;
import net.vaier.domain.User;
import net.vaier.domain.VaierConfig;
import net.vaier.domain.port.ForPersistingAppConfiguration;
import net.vaier.domain.port.ForReadingStoredSmtpPassword;
import net.vaier.domain.port.ForSendingNotificationEmail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock GetUsersUseCase getUsersUseCase;
    @Mock ForPersistingAppConfiguration configPersistence;
    @Mock ForReadingStoredSmtpPassword storedPasswordReader;
    @Mock ForSendingNotificationEmail emailSender;
    @Mock ConfigResolver configResolver;

    @InjectMocks NotificationService service;

    private VaierConfig smtpConfigured() {
        return VaierConfig.builder()
                .domain("example.com")
                .smtpHost("smtp.example.com")
                .smtpPort(587)
                .smtpUsername("vaier@example.com")
                .smtpSender("noreply@example.com")
                .build();
    }

    private User admin(String name, String email) {
        return new User(name, name, email, List.of("admins"));
    }

    private PeerSnapshot snapshot(boolean connected) {
        return new PeerSnapshot("file-server", MachineType.UBUNTU_SERVER, connected, 1700000000L, "192.168.1.50");
    }

    @Test
    void notifyAdmins_sendsEmailToEveryAdminUser() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("smtpPass"));
        when(getUsersUseCase.getUsers()).thenReturn(List.of(
                admin("alice", "alice@example.com"),
                admin("bob", "bob@example.com"),
                new User("carol", "carol", "carol@example.com", List.of("users"))
        ));

        service.notifyAdmins(snapshot(false));

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                recipients.capture(), any(), any());
        assertThat(recipients.getValue()).containsExactlyInAnyOrder("alice@example.com", "bob@example.com");
    }

    @Test
    void notifyAdmins_subjectIncludesPeerNameAndNewState_disconnected() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(getUsersUseCase.getUsers()).thenReturn(List.of(admin("alice", "alice@example.com")));

        service.notifyAdmins(snapshot(false));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                anyList(), subject.capture(), any());
        assertThat(subject.getValue()).isEqualTo("[Vaier] file-server is now disconnected");
    }

    @Test
    void notifyAdmins_subjectIncludesPeerNameAndNewState_connected() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(getUsersUseCase.getUsers()).thenReturn(List.of(admin("alice", "alice@example.com")));

        service.notifyAdmins(snapshot(true));

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                anyList(), subject.capture(), any());
        assertThat(subject.getValue()).isEqualTo("[Vaier] file-server is now connected");
    }

    @Test
    void notifyAdmins_bodyIncludesPeerDetails_andLinkToVaier() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(getUsersUseCase.getUsers()).thenReturn(List.of(admin("alice", "alice@example.com")));
        when(configResolver.getDomain()).thenReturn("example.com");

        service.notifyAdmins(snapshot(false));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                anyList(), any(), body.capture());
        String b = body.getValue();
        assertThat(b).contains("file-server");
        assertThat(b).contains("UBUNTU_SERVER");
        assertThat(b).contains("192.168.1.50");
        assertThat(b).contains("vaier.example.com");
    }

    @Test
    void notifyAdmins_skipsWhenSmtpHostNotConfigured() {
        when(configPersistence.load()).thenReturn(Optional.of(VaierConfig.builder()
                .domain("example.com")
                .build()));

        service.notifyAdmins(snapshot(false));

        verify(emailSender, never()).sendEmail(any(), anyInt(), any(), any(), any(),
                anyList(), any(), any());
    }

    @Test
    void notifyAdmins_skipsWhenSmtpPasswordNotStored() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.empty());

        service.notifyAdmins(snapshot(false));

        verify(emailSender, never()).sendEmail(any(), anyInt(), any(), any(), any(),
                anyList(), any(), any());
    }

    @Test
    void notifyAdmins_skipsWhenNoAdminUsers() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(getUsersUseCase.getUsers()).thenReturn(List.of(
                new User("carol", "carol", "carol@example.com", List.of("users"))
        ));

        service.notifyAdmins(snapshot(false));

        verify(emailSender, never()).sendEmail(any(), anyInt(), any(), any(), any(),
                anyList(), any(), any());
    }

    @Test
    void notifyAdmins_skipsAdminsWithBlankEmail() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(getUsersUseCase.getUsers()).thenReturn(List.of(
                admin("alice", "alice@example.com"),
                admin("ghost", "")
        ));

        service.notifyAdmins(snapshot(false));

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                recipients.capture(), any(), any());
        assertThat(recipients.getValue()).containsExactly("alice@example.com");
    }

    @Test
    void notifyAdmins_swallowsSenderExceptionsSoSchedulerKeepsRunning() {
        when(configPersistence.load()).thenReturn(Optional.of(smtpConfigured()));
        when(storedPasswordReader.readStoredPassword()).thenReturn(Optional.of("p"));
        when(getUsersUseCase.getUsers()).thenReturn(List.of(admin("alice", "alice@example.com")));
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                .when(emailSender).sendEmail(any(), anyInt(), any(), any(), any(),
                        anyList(), any(), any());

        org.assertj.core.api.Assertions.assertThatCode(() -> service.notifyAdmins(snapshot(false)))
                .doesNotThrowAnyException();
    }
}
