package net.vaier.config;

public final class ServiceNames {

    private ServiceNames() {}

    public static final String VAIER = "vaier";
    public static final String AUTH = "login";
    public static final String AUTHELIA = "authelia";
    public static final String WIREGUARD = "wireguard";
    public static final String WIREGUARD_MASQUERADE = "wireguard-masquerade";
    public static final String REDIS = "redis";
    public static final String TRAEFIK = "traefik";

    public static final String AUTH_MIDDLEWARE = "auth-middleware";
    public static final String CERT_RESOLVER = "letsencrypt";
    public static final String ENTRY_POINT_WEBSECURE = "websecure";

    public static final String DEFAULT_WG_PORT = "51820";
    public static final String DEFAULT_ADMIN_USERNAME = "admin";
}
