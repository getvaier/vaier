package net.vaier.domain.port;

import net.vaier.domain.DiskUsage;

/** Driven port for reading free space on the monitored host filesystem. */
public interface ForReadingDiskUsage {
    DiskUsage readHostDiskUsage();
}
