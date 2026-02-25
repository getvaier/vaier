package com.wireweave.domain;

public record VpnClient(
    String publicKey,
    String allowedIps,
    String endpointIp,
    String endpointPort,
    String latestHandshake,
    String transferRx,
    String transferTx
) {
}
