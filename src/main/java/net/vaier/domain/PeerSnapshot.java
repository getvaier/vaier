package net.vaier.domain;

public record PeerSnapshot(
    String name,
    MachineType peerType,
    boolean connected,
    long latestHandshakeEpochSeconds,
    String lanAddress
) {}
