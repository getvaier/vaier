package net.vaier.application.service;

import net.vaier.application.GetHostDiskUsageUseCase;
import net.vaier.domain.DiskUsage;
import net.vaier.domain.port.ForReadingDiskUsage;
import org.springframework.stereotype.Service;

/**
 * Host monitoring domain — observes the Vaier server's own resources (currently host disk free
 * space). Orchestrates only: reads through the {@link ForReadingDiskUsage} driven port and hands
 * the {@link DiskUsage} entity back; every decision about fullness lives on the entity.
 */
@Service
public class HostMonitoringService implements GetHostDiskUsageUseCase {

    private final ForReadingDiskUsage forReadingDiskUsage;

    public HostMonitoringService(ForReadingDiskUsage forReadingDiskUsage) {
        this.forReadingDiskUsage = forReadingDiskUsage;
    }

    @Override
    public DiskUsage getHostDiskUsage() {
        return forReadingDiskUsage.readHostDiskUsage();
    }
}
