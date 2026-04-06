package net.vaier.application.service;

import net.vaier.domain.port.ForManagingIgnoredServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UnignorePublishableServiceServiceTest {

    @Mock
    ForManagingIgnoredServices forManagingIgnoredServices;

    @InjectMocks
    UnignorePublishableServiceService service;

    @Test
    void unignoreService_delegatesToPort() {
        service.unignoreService("my-app");

        verify(forManagingIgnoredServices).unignoreService("my-app");
    }
}
