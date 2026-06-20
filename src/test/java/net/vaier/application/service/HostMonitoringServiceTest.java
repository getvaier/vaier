package net.vaier.application.service;

import net.vaier.domain.DiskUsage;
import net.vaier.domain.port.ForReadingDiskUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HostMonitoringServiceTest {

    @Mock ForReadingDiskUsage forReadingDiskUsage;

    @InjectMocks HostMonitoringService service;

    @Test
    void getHostDiskUsage_returnsTheReadingFromThePort() {
        DiskUsage reading = new DiskUsage("/host", 100L, 10L);
        when(forReadingDiskUsage.readHostDiskUsage()).thenReturn(reading);

        assertThat(service.getHostDiskUsage()).isSameAs(reading);
    }
}
