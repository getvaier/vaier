package com.wireweave.adapter.driven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wireweave.domain.ReverseProxyRoute;
import com.wireweave.domain.port.ForPersistingReverseProxyRoutes;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class TraefikReverseProxyAdapter implements ForPersistingReverseProxyRoutes {

    private final Yaml yaml;
    private final Yaml dumper;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private Map<String, Object> config;
    private static final String CONFIG_FILE_PATH = System.getenv("TRAEFIK_CONFIG_PATH") + "/dynamic_conf/remote-apps.yml";
    private static final String TRAEFIK_API_URL = System.getenv().getOrDefault("TRAEFIK_API_URL", "http://localhost:8080");

    public TraefikReverseProxyAdapter() {
        this.yaml = new Yaml();
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        this.dumper = new Yaml(options);

        File configFile = new File(CONFIG_FILE_PATH);
        File configFolder = configFile.getParentFile();
        if (!configFolder.exists()) {
            configFolder.mkdirs();
            log.info("Created Traefik config folder: {}", configFolder.getAbsolutePath());
        }
        if (!configFile.exists()) {
            try {
                configFile.createNewFile();
                log.info("Created Traefik config file: {}", configFile.getAbsolutePath());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Fetch routes from Traefik API.
     * Queries the /api/http/routers and /api/http/services endpoints to extract all routes.
     *
     * @return List of ReverseProxyRoute objects containing route information
     */
    public List<ReverseProxyRoute> getReverseProxyRoutes() {
        List<ReverseProxyRoute> routes = new ArrayList<>();

        try {
            // First, try to fetch from Traefik API (gets all active routes including Docker labels)
            try {
                // Fetch HTTP routers from API
                JsonNode routersData = fetchFromTraefikApi("/api/http/routers");
                JsonNode servicesData = fetchFromTraefikApi("/api/http/services");

                // Convert JsonNode to Map - Traefik API returns arrays, so we need to convert them
                Map<String, Object> routers = convertTraefikArrayToMap(routersData);
                Map<String, Object> services = convertTraefikArrayToMap(servicesData);

                if (routers != null && services != null) {
                    routes.addAll(extractHttpRoutesFromApi(routers, services));
                }

                // Fetch TCP routers from API
                JsonNode tcpRoutersData = fetchFromTraefikApi("/api/tcp/routers");
                JsonNode tcpServicesData = fetchFromTraefikApi("/api/tcp/services");

                Map<String, Object> tcpRouters = convertTraefikArrayToMap(tcpRoutersData);
                Map<String, Object> tcpServices = convertTraefikArrayToMap(tcpServicesData);

                if (tcpRouters != null && tcpServices != null) {
                    routes.addAll(extractTcpRoutesFromApi(tcpRouters, tcpServices));
                }

                log.info("Fetched {} routes from Traefik API", routes.size());
            } catch (Exception apiException) {
                log.warn("Failed to fetch from Traefik API, falling back to file reading", apiException);

                // Fallback: Read directly from configuration file
                routes.addAll(getReverseProxyRoutesFromFile());
            }

        } catch (Exception e) {
            log.error("Failed to fetch routes", e);
            throw new RuntimeException("Failed to fetch routes from Traefik", e);
        }

        return routes;
    }

    /**
     * Read routes directly from the configuration file.
     * This is a fallback when the API is not available.
     */
    private List<ReverseProxyRoute> getReverseProxyRoutesFromFile() {
        List<ReverseProxyRoute> routes = new ArrayList<>();
        File configFile = new File(CONFIG_FILE_PATH);

        try (FileInputStream inputStream = new FileInputStream(configFile)) {
            Map<String, Object> config = yaml.load(inputStream);

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

            log.info("Read {} routes from configuration file", routes.size());

        } catch (IOException e) {
            log.error("Failed to read Traefik configuration file: " + configFile, e);
        }

        return routes;
    }

    /**
     * Fetch data from Traefik API endpoint.
     */
    private JsonNode fetchFromTraefikApi(String endpoint) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(TRAEFIK_API_URL + endpoint))
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Traefik API returned status " + response.statusCode() + " for " + endpoint);
        }

        return objectMapper.readTree(response.body());
    }

    /**
     * Convert Traefik API array response to a map keyed by name.
     * Traefik API returns arrays like: [{"name": "router1", ...}, {"name": "router2", ...}]
     * We convert to: {"router1": {...}, "router2": {...}}
     */
    private Map<String, Object> convertTraefikArrayToMap(JsonNode arrayNode) {
        Map<String, Object> resultMap = new HashMap<>();

        if (arrayNode == null) {
            return resultMap;
        }

        if (arrayNode.isArray()) {
            for (JsonNode item : arrayNode) {
                String name = item.has("name") ? item.get("name").asText() : null;
                if (name != null) {
                    Map<String, Object> itemMap = objectMapper.convertValue(item, Map.class);
                    resultMap.put(name, itemMap);
                }
            }
        } else if (arrayNode.isObject()) {
            // If it's already an object, convert it directly
            resultMap = objectMapper.convertValue(arrayNode, Map.class);
        }

        return resultMap;
    }

    /**
     * Extract HTTP routes from API response.
     * API returns routers as a flat map with provider info.
     */
    private List<ReverseProxyRoute> extractHttpRoutesFromApi(Map<String, Object> routers, Map<String, Object> services) {
        List<ReverseProxyRoute> routes = new ArrayList<>();

        for (Map.Entry<String, Object> routerEntry : routers.entrySet()) {
            String routerName = routerEntry.getKey();
            Map<String, Object> routerConfig = castToMap(routerEntry.getValue());

            if (routerConfig != null) {
                String serviceName = (String) routerConfig.get("service");
                String domainName = extractDomainFromRule(routerConfig);
                List<String> entryPoints = extractEntryPoints(routerConfig);
                ReverseProxyRoute.TlsConfig tlsConfig = extractTlsConfig(routerConfig);
                List<String> routerMiddlewares = extractMiddlewareList(routerConfig);

                // Note: Auth middleware resolution from API requires fetching /api/http/middlewares
                // For now, we'll pass null and rely on middleware names only
                ReverseProxyRoute.AuthInfo authInfo = null;

                if (serviceName != null) {
                    // Try to find service with exact name first
                    Map<String, Object> serviceConfig = castToMap(services.get(serviceName));

                    // If not found and service name doesn't contain @, try appending provider suffix
                    if (serviceConfig == null && !serviceName.contains("@")) {
                        String provider = (String) routerConfig.get("provider");
                        if (provider != null) {
                            String serviceNameWithProvider = serviceName + "@" + provider;
                            serviceConfig = castToMap(services.get(serviceNameWithProvider));
                        }
                    }

                    if (serviceConfig != null) {
                        routes.addAll(extractServiceUrlsFromApi(routerName, domainName, serviceName, authInfo, entryPoints, tlsConfig, routerMiddlewares, serviceConfig));
                    }
                }
            }
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

    /**
     * Extract TCP routes from API response.
     */
    private List<ReverseProxyRoute> extractTcpRoutesFromApi(Map<String, Object> routers, Map<String, Object> services) {
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
                        routes.addAll(extractTcpServiceAddressesFromApi(routerName, domainName, serviceName, null, serviceConfig));
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

    /**
     * Extract service URLs from API response.
     * API service format includes serverStatus and other metadata.
     */
    private List<ReverseProxyRoute> extractServiceUrlsFromApi(String routerName, String domainName, String serviceName,
                                                               ReverseProxyRoute.AuthInfo authInfo, List<String> entryPoints,
                                                               ReverseProxyRoute.TlsConfig tlsConfig, List<String> middlewares,
                                                               Map<String, Object> serviceConfig) {
        List<ReverseProxyRoute> routes = new ArrayList<>();

        // Handle loadBalancer configuration from API
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

    /**
     * Extract TCP service addresses from API response.
     */
    private List<ReverseProxyRoute> extractTcpServiceAddressesFromApi(String routerName, String domainName, String serviceName, ReverseProxyRoute.AuthInfo authInfo, Map<String, Object> serviceConfig) {
        List<ReverseProxyRoute> routes = new ArrayList<>();

        // Handle loadBalancer configuration from API
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
     * Add a new reverse proxy route to the Traefik configuration with sensible defaults.
     * Generates router name and service name from DNS name.
     * Automatically configures standard settings: websecure entrypoint, letsencrypt cert resolver.
     *
     * @param dnsName The full DNS name (e.g., "portainer.eilertsen.family")
     * @param address The backend address (e.g., "10.10.10.4")
     * @param port The backend port (e.g., 9000)
     * @param requiresAuth Whether to add the auth-middleware
     */
    @Override
    public void addReverseProxyRoute(String dnsName, String address, int port, boolean requiresAuth) {
        loadConfig();

        if (config == null) {
            config = new LinkedHashMap<>();
        }

        // Generate router name and service name from DNS name
        String routerName = generateRouterName(dnsName);
        String serviceName = generateServiceName(dnsName);

        // Create HTTP section if it doesn't exist (using LinkedHashMap to preserve order)
        Map<String, Object> http = getOrCreateNestedMap(config, "http");

        // Ensure sections are created in the correct order: routers, services, middlewares
        Map<String, Object> routers = getOrCreateNestedMapOrdered(http, "routers");
        Map<String, Object> services = getOrCreateNestedMapOrdered(http, "services");

        // Add router configuration with standard defaults
        Map<String, Object> routerConfig = new LinkedHashMap<>();
        routerConfig.put("rule", "Host(`" + dnsName + "`)");

        // Standard entryPoints
        List<String> entryPoints = new ArrayList<>();
        entryPoints.add("websecure");
        routerConfig.put("entryPoints", entryPoints);

        routerConfig.put("service", serviceName);

        // Standard TLS configuration with letsencrypt
        Map<String, Object> tlsMap = new LinkedHashMap<>();
        tlsMap.put("certResolver", "letsencrypt");
        routerConfig.put("tls", tlsMap);

        // Add auth middleware reference if required
        boolean authRequired = requiresAuth;
        if (authRequired) {
            List<String> middlewares = new ArrayList<>();
            middlewares.add("auth-middleware");
            routerConfig.put("middlewares", middlewares);
        }

        routers.put(routerName, routerConfig);

        // Add service configuration
        Map<String, Object> serviceConfig = new LinkedHashMap<>();
        Map<String, Object> loadBalancer = new LinkedHashMap<>();
        List<Map<String, Object>> servers = new ArrayList<>();

        Map<String, Object> server = new LinkedHashMap<>();
        String url = "http://" + address + ":" + port;
        server.put("url", url);
        servers.add(server);

        loadBalancer.put("servers", servers);
        serviceConfig.put("loadBalancer", loadBalancer);
        services.put(serviceName, serviceConfig);

        // Ensure auth-middleware exists AFTER routers and services to maintain order
        if (authRequired) {
            ensureAuthMiddlewareExists(http);
        }

        saveConfig();
    }

    /**
     * Generate router name from DNS name.
     * Example: "portainer.eilertsen.family" -> "portainer-router"
     * Example: "code.apalveien5.eilertsen.family" -> "code-apalveien5-router"
     */
    private String generateRouterName(String dnsName) {
        // Remove the base domain and keep the subdomain parts
        String nameWithoutDomain = dnsName.replace(".eilertsen.family", "");
        String routerName = nameWithoutDomain.replace(".", "-") + "-router";
        return routerName;
    }

    /**
     * Generate service name from DNS name.
     * Example: "portainer.eilertsen.family" -> "portainer-service"
     * Example: "code.apalveien5.eilertsen.family" -> "code-apalveien5-service"
     */
    private String generateServiceName(String dnsName) {
        // Remove the base domain and keep the subdomain parts
        String nameWithoutDomain = dnsName.replace(".eilertsen.family", "");
        String serviceName = nameWithoutDomain.replace(".", "-") + "-service";
        return serviceName;
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
     * Delete a reverse proxy route by DNS name.
     * Generates the router name from the DNS name and deletes it.
     *
     * @param dnsName The full DNS name (e.g., "portainer.eilertsen.family")
     */
    @Override
    public void deleteReverseProxyRouteByDnsName(String dnsName) {
        String routerName = generateRouterName(dnsName);
        deleteReverseProxyRoute(routerName);
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
            nested = new LinkedHashMap<>();
            map.put(key, nested);
        }
        return nested;
    }

    /**
     * Get or create a nested map with LinkedHashMap to preserve insertion order.
     */
    private Map<String, Object> getOrCreateNestedMapOrdered(Map<String, Object> map, String key) {
        Map<String, Object> nested = getNestedMap(map, key);
        if (nested == null) {
            nested = new LinkedHashMap<>();
            map.put(key, nested);
        }
        return nested;
    }

    /**
     * Ensure the auth-middleware exists in the middlewares section.
     * Creates it with standard forwardAuth configuration if it doesn't exist.
     * Must be called AFTER routers and services are created to maintain order.
     */
    private void ensureAuthMiddlewareExists(Map<String, Object> http) {
        Map<String, Object> middlewares = getOrCreateNestedMapOrdered(http, "middlewares");

        // Check if auth-middleware already exists
        if (!middlewares.containsKey("auth-middleware")) {
            // Create standard auth-middleware with forwardAuth to Authelia
            Map<String, Object> authMiddleware = new LinkedHashMap<>();
            Map<String, Object> forwardAuth = new LinkedHashMap<>();

            forwardAuth.put("address", "http://authelia:9091/api/verify?rd=https://auth.eilertsen.family/");
            forwardAuth.put("trustForwardHeader", true);

            List<String> authResponseHeaders = new ArrayList<>();
            authResponseHeaders.add("X-Forwarded-User");
            authResponseHeaders.add("X-Forwarded-Groups");
            authResponseHeaders.add("X-Forwarded-Email");
            forwardAuth.put("authResponseHeaders", authResponseHeaders);

            authMiddleware.put("forwardAuth", forwardAuth);
            middlewares.put("auth-middleware", authMiddleware);
        }
    }

    public static void main(String[] args) {
        TraefikReverseProxyAdapter adapter = new TraefikReverseProxyAdapter();

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
