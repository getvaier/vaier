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
    // Social-login (#305) middleware + service names — the proven step-1 two-stage chain.
    public static final String OAUTH2 = "oauth2";
    public static final String DEX = "dex";
    public static final String OAUTH2_SIGNIN_MIDDLEWARE = "oauth2-signin";
    public static final String OAUTH2_AUTHN_MIDDLEWARE = "oauth2-authn";
    public static final String VAIER_AUTHZ_MIDDLEWARE = "vaier-authz";
    public static final String OAUTH2_PROXY_SERVICE = "oauth2-proxy-svc";
    public static final String ERROR_PAGES_MIDDLEWARE = "vaier-errors";
    public static final String ERROR_PAGES_SERVICE = "vaier-error-pages";
    public static final String CERT_RESOLVER = "letsencrypt";
    public static final String ENTRY_POINT_WEBSECURE = "websecure";

    public static final String DEFAULT_WG_PORT = "51820";
    public static final String DEFAULT_ADMIN_USERNAME = "admin";
}
