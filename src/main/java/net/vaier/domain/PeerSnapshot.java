package net.vaier.domain;

public record PeerSnapshot(
    String name,
    PeerType peerType,
    boolean connected,
    long latestHandshakeEpochSeconds,
    String lanAddress
) {}
