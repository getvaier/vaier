package net.vaier.application.service;

import net.vaier.domain.port.ForManagingIgnoredServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class IgnorePublishableServiceServiceTest {

    @Mock
    ForManagingIgnoredServices forManagingIgnoredServices;

    @InjectMocks
    IgnorePublishableServiceService service;

    @Test
    void ignoreService_delegatesToPort() {
        service.ignoreService("my-app");

        verify(forManagingIgnoredServices).ignoreService("my-app");
    }
}
