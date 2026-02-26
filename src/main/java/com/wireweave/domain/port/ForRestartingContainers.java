package com.wireweave.domain.port;

public interface ForRestartingContainers {
    void restartContainer(String containerName);
}
