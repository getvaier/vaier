package net.vaier.application;

import java.util.Set;
import net.vaier.config.ServiceNames;

public final class PublishingConstants {

    private PublishingConstants() {}

    public static final Set<String> MANDATORY_SUBDOMAINS = Set.of(ServiceNames.VAIER, ServiceNames.AUTH);
}
