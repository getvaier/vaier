package net.vaier.domain.port;

public interface ForRestartingContainers {
    void restartContainer(String containerName);
}
