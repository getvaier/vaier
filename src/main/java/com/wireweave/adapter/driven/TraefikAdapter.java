package com.wireweave.adapter.driven;

import com.wireweave.domain.ReverseProxyRoute;
import com.wireweave.domain.port.ForGettingReverseProxyRoutes;
import com.wireweave.domain.port.ForPersistingReverseProxyRoutes;
import java.io.File;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;

import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class TraefikAdapter implements ForGettingReverseProxyRoutes, ForPersistingReverseProxyRoutes {

    private final Yaml yaml;
    private final Yaml dumper;
    private Map<String, Object> config;
    private static final String CONFIG_FILE_PATH = "c:/tmp/traefik/remote-apps.yml";

    public TraefikAdapter() {
        this.yaml = new Yaml();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        this.dumper = new Yaml(options);
    }

    /**
     * Extract routes from a Traefik YAML configuration file.
     * Parses the dynamic configuration and extracts router-service mappings with addresses and ports.
     *
     * @return List of TraefikRoute objects containing route information
     */
    public List<ReverseProxyRoute> getReverseProxyRoutes() {
        List<ReverseProxyRoute> routes = new ArrayList<>();

        File configFile = new File(CONFIG_FILE_PATH);

        try (FileInputStream inputStream = new FileInputStream(configFile)) {
            this.config = yaml.load(inputStream);

            if (config == null) {
                return routes;
            }

            // Extract HTTP routers
            Map<String, Object> http = getNestedMap(config, "http");
            if (http != null) {
                Map<String, Object> routers = getNestedMap(http, "routers");
                Map<String, Object> services = getNestedMap(http, "services");

                if (routers != null && services != null) {
                    routes.addAll(extractHttpRoutes(routers, services));
                }
            }

            // Extract TCP routers
            Map<String, Object> tcp = getNestedMap(config, "tcp");
            if (tcp != null) {
                Map<String, Object> routers = getNestedMap(tcp, "routers");
                Map<String, Object> services = getNestedMap(tcp, "services");

                if (routers != null && services != null) {
                    routes.addAll(extractTcpRoutes(routers, services));
                }
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to read Traefik configuration file: " + configFile, e);
        }

        return routes;
    }

    private List<ReverseProxyRoute> extractHttpRoutes(Map<String, Object> routers, Map<String, Object> services) {
        List<ReverseProxyRoute> routes = new ArrayList<>();

        Map<String, Object> http = getNestedMap(config, "http");
        Map<String, Object> middlewares = http != null ? getNestedMap(http, "middlewares") : null;

        for (Map.Entry<String, Object> routerEntry : routers.entrySet()) {
            String routerName = routerEntry.getKey();
            Map<String, Object> routerConfig = castToMap(routerEntry.getValue());

            if (routerConfig != null) {
                String serviceName = (String) routerConfig.get("service");
                String domainName = extractDomainFromRule(routerConfig);
                ReverseProxyRoute.AuthInfo authInfo = extractAuthInfo(routerConfig, middlewares);
                List<String> entryPoints = extractEntryPoints(routerConfig);
                ReverseProxyRoute.TlsConfig tlsConfig = extractTlsConfig(routerConfig);
                List<String> routerMiddlewares = extractMiddlewareList(routerConfig);

                if (serviceName != null && services.containsKey(serviceName)) {
                    Map<String, Object> serviceConfig = castToMap(services.get(serviceName));

                    if (serviceConfig != null) {
                        routes.addAll(extractServiceUrls(routerName, domainName, serviceName, authInfo, entryPoints, tlsConfig, routerMiddlewares, serviceConfig));
                    }
                }
            }
        }

        return routes;
    }

    private List<ReverseProxyRoute> extractTcpRoutes(Map<String, Object> routers, Map<String, Object> services) {
        List<ReverseProxyRoute> routes = new ArrayList<>();

        for (Map.Entry<String, Object> routerEntry : routers.entrySet()) {
            String routerName = routerEntry.getKey();
            Map<String, Object> routerConfig = castToMap(routerEntry.getValue());

            if (routerConfig != null) {
                String serviceName = (String) routerConfig.get("service");
                String domainName = extractDomainFromRule(routerConfig);

                if (serviceName != null && services.containsKey(serviceName)) {
                    Map<String, Object> serviceConfig = castToMap(services.get(serviceName));

                    if (serviceConfig != null) {
                        routes.addAll(extractTcpServiceAddresses(routerName, domainName, serviceName, null, serviceConfig));
                    }
                }
            }
        }

        return routes;
    }

    private List<ReverseProxyRoute> extractServiceUrls(String routerName, String domainName, String serviceName,
                                                        ReverseProxyRoute.AuthInfo authInfo, List<String> entryPoints,
                                                        ReverseProxyRoute.TlsConfig tlsConfig, List<String> middlewares,
                                                        Map<String, Object> serviceConfig) {
        List<ReverseProxyRoute> routes = new ArrayList<>();

        // Handle loadBalancer configuration
        Map<String, Object> loadBalancer = getNestedMap(serviceConfig, "loadBalancer");
        if (loadBalancer != null) {
            List<Map<String, Object>> servers = getNestedList(loadBalancer, "servers");

            if (servers != null) {
                for (Map<String, Object> server : servers) {
                    String url = (String) server.get("url");

                    if (url != null) {
                        AddressPort addressPort = parseUrl(url);
                        routes.add(new ReverseProxyRoute(routerName, domainName, addressPort.address,
                            addressPort.port, serviceName, authInfo, entryPoints, tlsConfig, middlewares));
                    }
                }
            }
        }

        return routes;
    }

    private List<ReverseProxyRoute> extractTcpServiceAddresses(String routerName, String domainName, String serviceName, ReverseProxyRoute.AuthInfo authInfo, Map<String, Object> serviceConfig) {
        List<ReverseProxyRoute> routes = new ArrayList<>();

        // Handle loadBalancer configuration
        Map<String, Object> loadBalancer = getNestedMap(serviceConfig, "loadBalancer");
        if (loadBalancer != null) {
            List<Map<String, Object>> servers = getNestedList(loadBalancer, "servers");

            if (servers != null) {
                for (Map<String, Object> server : servers) {
                    String address = (String) server.get("address");

                    if (address != null) {
                        AddressPort addressPort = parseAddress(address);
                        routes.add(new ReverseProxyRoute(routerName, domainName, addressPort.address, addressPort.port, serviceName, authInfo));
                    }
                }
            }
        }

        return routes;
    }

    /**
     * Parse URL in format: http://host:port or https://host:port
     */
    private AddressPort parseUrl(String url) {
        try {
            // Remove protocol
            String withoutProtocol = url.replaceFirst("^https?://", "");

            // Split host and port
            int colonIndex = withoutProtocol.lastIndexOf(':');
            if (colonIndex > 0) {
                String host = withoutProtocol.substring(0, colonIndex);
                int port = Integer.parseInt(withoutProtocol.substring(colonIndex + 1));
                return new AddressPort(host, port);
            }

            // Default ports if not specified
            if (url.startsWith("https://")) {
                return new AddressPort(withoutProtocol, 443);
            } else {
                return new AddressPort(withoutProtocol, 80);
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse URL: " + url, e);
        }
    }

    /**
     * Parse address in format: host:port
     */
    private AddressPort parseAddress(String address) {
        try {
            int colonIndex = address.lastIndexOf(':');
            if (colonIndex > 0) {
                String host = address.substring(0, colonIndex);
                int port = Integer.parseInt(address.substring(colonIndex + 1));
                return new AddressPort(host, port);
            }
            throw new IllegalArgumentException("Address must be in format host:port");
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse address: " + address, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castToMap(Object obj) {
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getNestedMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return castToMap(value);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getNestedList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        return null;
    }

    /**
     * Extract domain name from router rule.
     * Supports Host() and HostSNI() rules.
     * Examples:
     *   "Host(`example.com`)" -> "example.com"
     *   "Host(`example.com`) && PathPrefix(`/api`)" -> "example.com"
     *   "HostSNI(`example.com`)" -> "example.com"
     */
    private String extractDomainFromRule(Map<String, Object> routerConfig) {
        String rule = (String) routerConfig.get("rule");

        if (rule == null) {
            return null;
        }

        // Try to match Host(`domain`) or HostSNI(`domain`)
        // Pattern matches: Host(`domain`) or HostSNI(`domain`)
        String hostPattern = "Host(?:SNI)?\\s*\\(\\s*`([^`]+)`\\s*\\)";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(hostPattern);
        java.util.regex.Matcher matcher = pattern.matcher(rule);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    /**
     * Extract entryPoints from router configuration.
     */
    @SuppressWarnings("unchecked")
    private List<String> extractEntryPoints(Map<String, Object> routerConfig) {
        Object entryPointsObj = routerConfig.get("entryPoints");
        if (entryPointsObj instanceof List) {
            return new ArrayList<>((List<String>) entryPointsObj);
        }
        return null;
    }

    /**
     * Extract TLS configuration from router.
     */
    private ReverseProxyRoute.TlsConfig extractTlsConfig(Map<String, Object> routerConfig) {
        Map<String, Object> tlsMap = getNestedMap(routerConfig, "tls");
        if (tlsMap == null) {
            return null;
        }

        String certResolver = (String) tlsMap.get("certResolver");
        Map<String, Object> additionalConfig = new HashMap<>(tlsMap);
        additionalConfig.remove("certResolver");

        return new ReverseProxyRoute.TlsConfig(certResolver, additionalConfig.isEmpty() ? null : additionalConfig);
    }

    /**
     * Extract middleware list from router configuration.
     */
    @SuppressWarnings("unchecked")
    private List<String> extractMiddlewareList(Map<String, Object> routerConfig) {
        Object middlewareObj = routerConfig.get("middlewares");

        if (middlewareObj instanceof List) {
            return new ArrayList<>((List<String>) middlewareObj);
        } else if (middlewareObj instanceof String) {
            List<String> list = new ArrayList<>();
            list.add((String) middlewareObj);
            return list;
        }

        return null;
    }

    /**
     * Extract authentication info from router middlewares.
     * Supports basicAuth, digestAuth, and forwardAuth middlewares.
     */
    private ReverseProxyRoute.AuthInfo extractAuthInfo(Map<String, Object> routerConfig, Map<String, Object> middlewares) {
        if (middlewares == null) {
            return null;
        }

        // Get middleware list from router
        Object middlewareObj = routerConfig.get("middlewares");
        List<String> routerMiddlewares = new ArrayList<>();

        if (middlewareObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) middlewareObj;
            routerMiddlewares = list;
        } else if (middlewareObj instanceof String) {
            routerMiddlewares.add((String) middlewareObj);
        }

        // Check each middleware for auth configuration
        for (String middlewareName : routerMiddlewares) {
            Map<String, Object> middlewareConfig = castToMap(middlewares.get(middlewareName));

            if (middlewareConfig != null) {
                // Check for basicAuth
                Map<String, Object> basicAuth = getNestedMap(middlewareConfig, "basicAuth");
                if (basicAuth != null) {
                    String realm = (String) basicAuth.get("realm");
                    Object usersObj = basicAuth.get("users");
                    String username = extractUsernameFromUsers(usersObj);
                    return new ReverseProxyRoute.AuthInfo("basicAuth", username, realm);
                }

                // Check for digestAuth
                Map<String, Object> digestAuth = getNestedMap(middlewareConfig, "digestAuth");
                if (digestAuth != null) {
                    String realm = (String) digestAuth.get("realm");
                    Object usersObj = digestAuth.get("users");
                    String username = extractUsernameFromUsers(usersObj);
                    return new ReverseProxyRoute.AuthInfo("digestAuth", username, realm);
                }

                // Check for forwardAuth
                Map<String, Object> forwardAuth = getNestedMap(middlewareConfig, "forwardAuth");
                if (forwardAuth != null) {
                    String address = (String) forwardAuth.get("address");
                    String authProvider = extractAuthProvider(address);
                    return new ReverseProxyRoute.AuthInfo("forwardAuth", authProvider, null);
                }
            }
        }

        return null;
    }

    /**
     * Extract username from users list.
     * Users are typically in format: "username:hashedPassword"
     */
    private String extractUsernameFromUsers(Object usersObj) {
        if (usersObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> users = (List<String>) usersObj;
            if (!users.isEmpty()) {
                String firstUser = users.get(0);
                int colonIndex = firstUser.indexOf(':');
                if (colonIndex > 0) {
                    return firstUser.substring(0, colonIndex);
                }
            }
        }
        return null;
    }

    /**
     * Extract auth provider name from forwardAuth address.
     * Example: "http://authelia:9091/api/verify?rd=..." -> "authelia"
     */
    private String extractAuthProvider(String address) {
        if (address == null) {
            return "unknown";
        }

        try {
            // Remove protocol
            String withoutProtocol = address.replaceFirst("^https?://", "");

            // Extract hostname (before : or /)
            int colonIndex = withoutProtocol.indexOf(':');
            int slashIndex = withoutProtocol.indexOf('/');

            int endIndex;
            if (colonIndex > 0 && slashIndex > 0) {
                endIndex = Math.min(colonIndex, slashIndex);
            } else if (colonIndex > 0) {
                endIndex = colonIndex;
            } else if (slashIndex > 0) {
                endIndex = slashIndex;
            } else {
                endIndex = withoutProtocol.length();
            }

            return withoutProtocol.substring(0, endIndex);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private record AddressPort(String address, int port) {}

    /**
     * Add a new reverse proxy route to the Traefik configuration.
     */
    @Override
    public void addReverseProxyRoute(ReverseProxyRoute route) {
        loadConfig();

        if (config == null) {
            config = new HashMap<>();
        }

        // Create HTTP section if it doesn't exist
        Map<String, Object> http = getOrCreateNestedMap(config, "http");
        Map<String, Object> routers = getOrCreateNestedMap(http, "routers");
        Map<String, Object> services = getOrCreateNestedMap(http, "services");

        // Add router configuration
        Map<String, Object> routerConfig = new HashMap<>();
        routerConfig.put("rule", "Host(`" + route.getDomainName() + "`)");
        routerConfig.put("service", route.getService());

        // Add entryPoints if provided
        if (route.getEntryPoints() != null && !route.getEntryPoints().isEmpty()) {
            routerConfig.put("entryPoints", new ArrayList<>(route.getEntryPoints()));
        }

        // Add TLS configuration if provided
        if (route.getTlsConfig() != null) {
            Map<String, Object> tlsMap = new HashMap<>();
            if (route.getTlsConfig().getCertResolver() != null) {
                tlsMap.put("certResolver", route.getTlsConfig().getCertResolver());
            }
            if (route.getTlsConfig().getAdditionalConfig() != null) {
                tlsMap.putAll(route.getTlsConfig().getAdditionalConfig());
            }
            routerConfig.put("tls", tlsMap);
        }

        // Add middlewares if provided (use the route's middleware list directly)
        if (route.getMiddlewares() != null && !route.getMiddlewares().isEmpty()) {
            routerConfig.put("middlewares", new ArrayList<>(route.getMiddlewares()));
        }

        routers.put(route.getName(), routerConfig);

        // Add service configuration
        Map<String, Object> serviceConfig = new HashMap<>();
        Map<String, Object> loadBalancer = new HashMap<>();
        List<Map<String, Object>> servers = new ArrayList<>();

        Map<String, Object> server = new HashMap<>();
        String url = "http://" + route.getAddress() + ":" + route.getPort();
        server.put("url", url);
        servers.add(server);

        loadBalancer.put("servers", servers);
        serviceConfig.put("loadBalancer", loadBalancer);
        services.put(route.getService(), serviceConfig);

        saveConfig();
    }

    /**
     * Update an existing reverse proxy route in the Traefik configuration.
     * Preserves existing properties that are not being updated.
     */
    @Override
    public void updateReverseProxyRoute(String routeName, ReverseProxyRoute updatedRoute) {
        loadConfig();

        if (config == null) {
            throw new RuntimeException("Configuration file not found or empty");
        }

        Map<String, Object> http = getNestedMap(config, "http");
        if (http == null) {
            throw new RuntimeException("No HTTP configuration found");
        }

        Map<String, Object> routers = getNestedMap(http, "routers");
        Map<String, Object> services = getNestedMap(http, "services");

        if (routers == null || !routers.containsKey(routeName)) {
            throw new RuntimeException("Router not found: " + routeName);
        }

        // Update router configuration while preserving existing properties
        Map<String, Object> routerConfig = castToMap(routers.get(routeName));
        if (routerConfig != null) {
            // Update basic properties
            routerConfig.put("rule", "Host(`" + updatedRoute.getDomainName() + "`)");
            routerConfig.put("service", updatedRoute.getService());

            // Update or preserve entryPoints
            if (updatedRoute.getEntryPoints() != null) {
                if (updatedRoute.getEntryPoints().isEmpty()) {
                    routerConfig.remove("entryPoints");
                } else {
                    routerConfig.put("entryPoints", new ArrayList<>(updatedRoute.getEntryPoints()));
                }
            }
            // If updatedRoute.getEntryPoints() is null, keep existing value

            // Update or preserve TLS configuration
            if (updatedRoute.getTlsConfig() != null) {
                Map<String, Object> tlsMap = new HashMap<>();
                if (updatedRoute.getTlsConfig().getCertResolver() != null) {
                    tlsMap.put("certResolver", updatedRoute.getTlsConfig().getCertResolver());
                }
                if (updatedRoute.getTlsConfig().getAdditionalConfig() != null) {
                    tlsMap.putAll(updatedRoute.getTlsConfig().getAdditionalConfig());
                }
                if (!tlsMap.isEmpty()) {
                    routerConfig.put("tls", tlsMap);
                }
            }
            // If updatedRoute.getTlsConfig() is null, keep existing value

            // Update or preserve middlewares
            if (updatedRoute.getMiddlewares() != null) {
                if (updatedRoute.getMiddlewares().isEmpty()) {
                    routerConfig.remove("middlewares");
                } else {
                    routerConfig.put("middlewares", new ArrayList<>(updatedRoute.getMiddlewares()));
                }
            }
            // If updatedRoute.getMiddlewares() is null, keep existing value
        }

        // Update service configuration
        if (services != null) {
            String oldServiceName = (String) routerConfig.get("service");

            // Remove old service if it's different
            if (oldServiceName != null && !oldServiceName.equals(updatedRoute.getService())) {
                services.remove(oldServiceName);
            }

            Map<String, Object> serviceConfig = new HashMap<>();
            Map<String, Object> loadBalancer = new HashMap<>();
            List<Map<String, Object>> servers = new ArrayList<>();

            Map<String, Object> server = new HashMap<>();
            String url = "http://" + updatedRoute.getAddress() + ":" + updatedRoute.getPort();
            server.put("url", url);
            servers.add(server);

            loadBalancer.put("servers", servers);
            serviceConfig.put("loadBalancer", loadBalancer);
            services.put(updatedRoute.getService(), serviceConfig);
        }

        saveConfig();
    }

    /**
     * Delete a reverse proxy route from the Traefik configuration.
     * Only removes the router and its associated service.
     * Middlewares are NOT removed as they may be shared between multiple routers.
     */
    @Override
    public void deleteReverseProxyRoute(String routeName) {
        loadConfig();

        if (config == null) {
            throw new RuntimeException("Configuration file not found or empty");
        }

        Map<String, Object> http = getNestedMap(config, "http");
        if (http == null) {
            throw new RuntimeException("No HTTP configuration found");
        }

        Map<String, Object> routers = getNestedMap(http, "routers");
        Map<String, Object> services = getNestedMap(http, "services");

        if (routers == null || !routers.containsKey(routeName)) {
            throw new RuntimeException("Router not found: " + routeName);
        }

        // Get service name before removing router
        Map<String, Object> routerConfig = castToMap(routers.get(routeName));
        String serviceName = routerConfig != null ? (String) routerConfig.get("service") : null;

        // Remove router
        routers.remove(routeName);

        // Remove associated service if it exists and is not shared
        // Note: This assumes services are not shared. If they are, you may need additional logic.
        if (serviceName != null && services != null) {
            services.remove(serviceName);
        }

        // Do NOT remove middlewares as they are typically shared (e.g., auth-middleware)

        saveConfig();
    }

    /**
     * Load configuration from file.
     */
    private void loadConfig() {
        File configFile = new File(CONFIG_FILE_PATH);

        try (FileInputStream inputStream = new FileInputStream(configFile)) {
            this.config = yaml.load(inputStream);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read Traefik configuration file: " + configFile, e);
        }
    }

    /**
     * Save configuration to file.
     */
    private void saveConfig() {
        File configFile = new File(CONFIG_FILE_PATH);

        try (FileWriter writer = new FileWriter(configFile)) {
            dumper.dump(config, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write Traefik configuration file: " + configFile, e);
        }
    }

    /**
     * Get or create a nested map in the configuration.
     */
    private Map<String, Object> getOrCreateNestedMap(Map<String, Object> map, String key) {
        Map<String, Object> nested = getNestedMap(map, key);
        if (nested == null) {
            nested = new HashMap<>();
            map.put(key, nested);
        }
        return nested;
    }

    public static void main(String[] args) {
        TraefikAdapter adapter = new TraefikAdapter();

        List<ReverseProxyRoute> routes = adapter.getReverseProxyRoutes();

        System.out.println("\nFound " + routes.size() + " routes:");
        routes.forEach(route -> {
            System.out.println("\nRoute: " + route.getName());
            System.out.println("  Domain: " + route.getDomainName());
            System.out.println("  Service: " + route.getService());
            System.out.println("  Address: " + route.getAddress() + ":" + route.getPort());
            if (route.getAuthInfo() != null) {
                System.out.println("  Auth: " + route.getAuthInfo().getType() +
                    " (user: " + route.getAuthInfo().getUsername() +
                    ", realm: " + route.getAuthInfo().getRealm() + ")");
            }
        });
    }
}
