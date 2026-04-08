package net.vaier.domain;

import java.time.Instant;

public record ContainerUpdateStatus(
        String image,
        String tag,
        String runningDigest,
        String remoteDigest,
        boolean updateAvailable,
        boolean latestTag,
        Instant checkedAt
) {}
