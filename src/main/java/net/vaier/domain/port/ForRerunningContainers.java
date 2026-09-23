package net.vaier.domain.port;

import net.vaier.domain.ContainerRun;

/** Starts an existing one-shot container again, with the command it was created with, and waits for it to end. */
public interface ForRerunningContainers {
    ContainerRun rerun(String containerName);
}
