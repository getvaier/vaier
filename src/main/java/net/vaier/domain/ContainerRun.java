package net.vaier.domain;

/** How a one-shot container's run ended: its exit code, and the tail of what it wrote when it failed. */
public record ContainerRun(int exitCode, String output) {

    public boolean succeeded() {
        return exitCode == 0;
    }
}
