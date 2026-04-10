package net.vaier.adapter.driven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.config.ServiceNames;
import net.vaier.domain.ReverseProxyRoute;
import net.vaier.domain.port.ForManagingIgnoredServices;
import net.vaier.domain.port.ForPersistingReverseProxyRoutes;
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
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Slf4j
public class TraefikReverseProxyAdapter implements ForPersistingReverseProxyRoutes, ForManagingIgnoredServices {

    private final Yaml yaml;
    private final Yaml dumper;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private Map<String, Object> config;
    private final String configFilePath;
    private final String traefikApiUrl;
    private final String vaierDomain;

    @org.springframework.beans.factory.annotation.Autowired
    public TraefikReverseProxyAdapter(net.vaier.config.ConfigResolver configResolver) {
        this(
            System.getenv("TRAEFIK_CONFIG_PATH") + "/remote-apps.yml",
            System.getenv().getOrDefault("TRAEFIK_API_URL", "http://localhost:8080"),
            configResolver.getDomain() != null ? configResolver.getDomain() : ""
        );
    }

    TraefikReverseProxyAdapter(String configFilePath, String traefikApiUrl, String vaierDomain) {
        this.configFilePath = configFilePath;
        this.traefikApiUrl = traefikApiUrl;
        this.vaierDomain = vaierDomain;
        this.yaml = new Yaml();
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        this.dumper = new Yaml(options);

        File configFile = new File(configFilePath);
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
        // File routes are always authoritative for our published services — reads are instantaneous
        // and unaffected by Traefik's config reload cycle.
        List<ReverseProxyRoute> fileRoutes = getReverseProxyRoutesFromFile();
        Set<String> fileRouteDomains = new HashSet<>();
        for (ReverseProxyRoute r : fileRoutes) fileRouteDomains.add(r.getDomainName());

        List<ReverseProxyRoute> result = new ArrayList<>(fileRoutes);

        try {
            // Add routes from Traefik API that are NOT in our file (e.g. Docker label routes).
            // During Traefik config reload the API may briefly return an empty list; in that case
            // we simply omit the extra API-only routes rather than clobbering our file routes.
            List<ReverseProxyRoute> apiRoutes = new ArrayList<>();

            JsonNode routersData = fetchFromTraefikApi("/api/http/routers");
            JsonNode servicesData = fetchFromTraefikApi("/api/http/services");
            Map<String, Object> routers = convertTraefikArrayToMap(routersData);
            Map<String, Object> services = convertTraefikArrayToMap(servicesData);
            if (routers != null && services != null) {
                apiRoutes.addAll(extractHttpRoutesFromApi(routers, services));
            }

            JsonNode tcpRoutersData = fetchFromTraefikApi("/api/tcp/routers");
            JsonNode tcpServicesData = fetchFromTraefikApi("/api/tcp/services");
            Map<String, Object> tcpRouters = convertTraefikArrayToMap(tcpRoutersData);
            Map<String, Object> tcpServices = convertTraefikArrayToMap(tcpServicesData);
            if (tcpRouters != null && tcpServices != null) {
                apiRoutes.addAll(extractTcpRoutesFromApi(tcpRouters, tcpServices));
            }

            apiRoutes.stream()
                .filter(r -> !fileRouteDomains.contains(r.getDomainName()))
                .forEach(result::add);

            log.info("Returning {} routes ({} from file, {} API-only)",
                result.size(), fileRoutes.size(), result.size() - fileRoutes.size());
        } catch (Exception apiException) {
            log.warn("Failed to fetch from Traefik API, returning file routes only", apiException);
        }

        return result;
    }

    /**
     * Read routes directly from the configuration file.
     * This is a fallback when the API is not available.
     */
    private List<ReverseProxyRoute> getReverseProxyRoutesFromFile() {
        List<ReverseProxyRoute> routes = new ArrayList<>();
        File configFile = new File(configFilePath);

        try (FileInputStream inputStream = new FileInputStream(configFile)) {
            Map<String, Object> config = new Yaml().load(inputStream);

            if (config == null) {
                return routes;
            }

            // Extract HTTP routers
            Map<String, Object> http = getNestedMap(config, "http");
            if (http != null) {
                Map<String, Object> routers = getNestedMap(http, "routers");
                Map<String, Object> services = getNestedMap(http, "services");

                if (routers != null && services != null) {
                    routes.addAll(extractHttpRoutes(routers, services, config));
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
            .uri(URI.create(traefikApiUrl + endpoint))
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

                // Check if any middleware indicates authentication (forwardAuth, basicAuth, digestAuth)
                ReverseProxyRoute.AuthInfo authInfo = extractAuthInfoFromMiddlewareNames(routerMiddlewares);

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

    private List<ReverseProxyRoute> extractHttpRoutes(Map<String, Object> routers, Map<String, Object> services, Map<String, Object> fullConfig) {
        List<ReverseProxyRoute> routes = new ArrayList<>();

        Map<String, Object> http = fullConfig != null ? getNestedMap(fullConfig, "http") : null;
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
                        String rootRedirectPath = extractRootRedirectPath(routerName, routerMiddlewares, middlewares, domainName);
                        routes.addAll(extractServiceUrls(routerName, domainName, serviceName, authInfo, entryPoints, tlsConfig, routerMiddlewares, serviceConfig, rootRedirectPath));
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
                                                        Map<String, Object> serviceConfig, String rootRedirectPath) {
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
                            addressPort.port, serviceName, authInfo, entryPoints, tlsConfig, middlewares, rootRedirectPath));
                    }
                }
            }
        }

        return routes;
    }

    /**
     * If the router has a redirect middleware (ending in "-redirect"), look up its replacement URL
     * in the middlewares config and extract the path portion (everything after the domain).
     */
    private String extractRootRedirectPath(String routerName, List<String> routerMiddlewares,
                                           Map<String, Object> middlewares, String domainName) {
        if (routerMiddlewares == null || middlewares == null) return null;
        String expectedMiddlewareName = routerName.replace("-router", "-redirect");
        if (!routerMiddlewares.contains(expectedMiddlewareName)) return null;

        Map<String, Object> mw = castToMap(middlewares.get(expectedMiddlewareName));
        if (mw == null) return null;
        Map<String, Object> redirectRegex = castToMap(mw.get("redirectRegex"));
        if (redirectRegex == null) return null;

        String replacement = (String) redirectRegex.get("replacement");
        if (replacement == null) return null;

        String prefix = "https://" + domainName;
        if (replacement.startsWith(prefix)) {
            return replacement.substring(prefix.length());
        }
        return null;
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
     * Extract authentication info from middleware names when fetching from API.
     * Checks if any middleware name suggests authentication (contains auth-related keywords).
     */
    private ReverseProxyRoute.AuthInfo extractAuthInfoFromMiddlewareNames(List<String> middlewareNames) {
        if (middlewareNames == null || middlewareNames.isEmpty()) {
            return null;
        }

        // Check for common auth middleware patterns
        for (String middlewareName : middlewareNames) {
            String lowerName = middlewareName.toLowerCase();
            if (lowerName.contains("auth") || lowerName.contains("authelia") ||
                lowerName.contains("oauth") || lowerName.contains("sso")) {
                // Extract provider name from middleware name if possible
                String provider = middlewareName.contains("@")
                    ? middlewareName.substring(0, middlewareName.indexOf("@"))
                    : "middleware";
                return new ReverseProxyRoute.AuthInfo("forwardAuth", provider, null);
            }
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
    public void addReverseProxyRoute(String dnsName, String address, int port, boolean requiresAuth, String rootRedirectPath) {
        loadConfig();

        if (config == null) {
            config = new LinkedHashMap<>();
        }

        // Generate router name and service name from DNS name
        String routerName = generateRouterName(dnsName);
        String serviceName = generateServiceName(dnsName);
        String redirectMiddlewareName = routerName.replace("-router", "-redirect");

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
        entryPoints.add(ServiceNames.ENTRY_POINT_WEBSECURE);
        routerConfig.put("entryPoints", entryPoints);

        routerConfig.put("service", serviceName);

        // Standard TLS configuration with letsencrypt
        Map<String, Object> tlsMap = new LinkedHashMap<>();
        tlsMap.put("certResolver", ServiceNames.CERT_RESOLVER);
        routerConfig.put("tls", tlsMap);

        // Build middleware list
        List<String> middlewareList = new ArrayList<>();
        if (requiresAuth) middlewareList.add(ServiceNames.AUTH_MIDDLEWARE);
        if (rootRedirectPath != null) middlewareList.add(redirectMiddlewareName);
        if (!middlewareList.isEmpty()) routerConfig.put("middlewares", middlewareList);

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

        // Add middlewares (after routers and services to maintain order)
        if (requiresAuth) ensureAuthMiddlewareExists(http);
        if (rootRedirectPath != null) {
            Map<String, Object> middlewaresSection = getOrCreateNestedMapOrdered(http, "middlewares");
            Map<String, Object> redirectRegex = new LinkedHashMap<>();
            redirectRegex.put("regex", "^https://" + dnsName.replace(".", "\\.") + "/?$");
            redirectRegex.put("replacement", "https://" + dnsName + rootRedirectPath);
            Map<String, Object> redirectMiddleware = new LinkedHashMap<>();
            redirectMiddleware.put("redirectRegex", redirectRegex);
            middlewaresSection.put(redirectMiddlewareName, redirectMiddleware);
        }

        saveConfig();
    }

    /**
     * Generate router name from DNS name.
     * Example: "portainer.eilertsen.family" -> "portainer-router"
     * Example: "code.apalveien5.eilertsen.family" -> "code-apalveien5-router"
     */
    private String generateRouterName(String dnsName) {
        return dnsName.replace(".", "-") + "-router";
    }

    private String generateServiceName(String dnsName) {
        return dnsName.replace(".", "-") + "-service";
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

    @Override
    public void setRouteAuthentication(String dnsName, boolean requiresAuth) {
        loadConfig();

        Map<String, Object> http = getNestedMap(config, "http");
        if (http == null) throw new RuntimeException("No HTTP configuration found");

        Map<String, Object> routers = getNestedMap(http, "routers");
        String routerName = generateRouterName(dnsName);
        if (routers == null || !routers.containsKey(routerName)) {
            throw new RuntimeException("Router not found: " + routerName);
        }

        Map<String, Object> routerConfig = castToMap(routers.get(routerName));
        if (requiresAuth) {
            routerConfig.put("middlewares", new ArrayList<>(List.of(ServiceNames.AUTH_MIDDLEWARE)));
            ensureAuthMiddlewareExists(http);
        } else {
            routerConfig.remove("middlewares");
        }

        saveConfig();
    }

    /**
     * Load configuration from file.
     */
    private void loadConfig() {
        File configFile = new File(configFilePath);

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
        Path configPath = Path.of(configFilePath);
        try {
            Path tempFile = Files.createTempFile(configPath.getParent(), ".remote-apps-", ".tmp");
            try (Writer writer = new OutputStreamWriter(Files.newOutputStream(tempFile), StandardCharsets.UTF_8)) {
                dumper.dump(config, writer);
            }
            Files.move(tempFile, configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write Traefik configuration file: " + configFilePath, e);
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
        if (!middlewares.containsKey(ServiceNames.AUTH_MIDDLEWARE)) {
            // Create standard auth-middleware with forwardAuth to Authelia
            Map<String, Object> authMiddleware = new LinkedHashMap<>();
            Map<String, Object> forwardAuth = new LinkedHashMap<>();

            forwardAuth.put("address", "http://authelia:9091/api/verify?rd=https://login." + vaierDomain + "/");
            forwardAuth.put("trustForwardHeader", true);

            List<String> authResponseHeaders = new ArrayList<>();
            authResponseHeaders.add("X-Forwarded-User");
            authResponseHeaders.add("X-Forwarded-Groups");
            authResponseHeaders.add("X-Forwarded-Email");
            forwardAuth.put("authResponseHeaders", authResponseHeaders);

            authMiddleware.put("forwardAuth", forwardAuth);
            middlewares.put(ServiceNames.AUTH_MIDDLEWARE, authMiddleware);
        }
    }

    // --- ForManagingIgnoredServices ---

    private static final String IGNORED_KEY = "x-vaier-ignored";

    @Override
    public Set<String> getIgnoredServiceKeys() {
        loadConfig();
        if (config == null) return Set.of();
        Object raw = config.get(IGNORED_KEY);
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).collect(java.util.stream.Collectors.toSet());
        }
        return Set.of();
    }

    @Override
    public void ignoreService(String key) {
        loadConfig();
        if (config == null) config = new LinkedHashMap<>();
        Object raw = config.get(IGNORED_KEY);
        List<String> ignored = (raw instanceof List<?> list)
            ? new ArrayList<>(list.stream().map(Object::toString).toList())
            : new ArrayList<>();
        if (!ignored.contains(key)) {
            ignored.add(key);
            config.put(IGNORED_KEY, ignored);
            saveConfig();
        }
    }

    @Override
    public void unignoreService(String key) {
        loadConfig();
        if (config == null) return;
        Object raw = config.get(IGNORED_KEY);
        if (!(raw instanceof List<?> list)) return;
        List<String> ignored = new ArrayList<>(list.stream().map(Object::toString).toList());
        if (ignored.remove(key)) {
            config.put(IGNORED_KEY, ignored);
            saveConfig();
        }
    }

}
