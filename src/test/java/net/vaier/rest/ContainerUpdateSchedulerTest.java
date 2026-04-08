package net.vaier.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.vaier.application.CheckContainerUpdatesUseCase;
import net.vaier.application.ForPublishingEvents;
import net.vaier.domain.ContainerUpdateStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ContainerUpdateSchedulerTest {

    CheckContainerUpdatesUseCase useCase;
    ForPublishingEvents eventPublisher;
    ContainerUpdateScheduler scheduler;

    @BeforeEach
    void setUp() {
        useCase = mock(CheckContainerUpdatesUseCase.class);
        eventPublisher = mock(ForPublishingEvents.class);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        scheduler = new ContainerUpdateScheduler(useCase, eventPublisher, objectMapper);
    }

    @Test
    void checkForUpdates_publishesContainerUpdatesEvent() {
        when(useCase.checkAll()).thenReturn(List.of(
                new ContainerUpdateStatus("nginx", "1.25", "sha256:aaa", "sha256:bbb",
                        true, false, Instant.now())));

        scheduler.checkForUpdates();

        verify(eventPublisher).publish(eq("vpn-peers"), eq("container-updates"), contains("nginx"));
    }

    @Test
    void checkForUpdates_noUpdates_publishesEmptyArray() {
        when(useCase.checkAll()).thenReturn(List.of());

        scheduler.checkForUpdates();

        verify(eventPublisher).publish(eq("vpn-peers"), eq("container-updates"), eq("[]"));
    }

    @Test
    void checkForUpdates_whenUseCaseFails_doesNotThrow() {
        when(useCase.checkAll()).thenThrow(new RuntimeException("check failed"));

        assertThatCode(() -> scheduler.checkForUpdates()).doesNotThrowAnyException();
    }
}
