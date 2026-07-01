package net.vaier.adapter.driven;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.vaier.config.ServiceNames;
import net.vaier.domain.AuthMode;
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

    public TraefikReverseProxyAdapter(String configFilePath, String traefikApiUrl, String vaierDomain) {
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

            Set<String> directUrlDisabled = readDirectUrlDisabledDomains(config);
            Set<String> hiddenFromLaunchpad = readHiddenFromLaunchpadRouters(config);
            Map<String, String> launchpadAliases = readLaunchpadAliases(config);
            Map<String, String> lanMarkers = readLanServiceMarkers(config);
            Map<String, Map<String, String>> versionEndpoints = readVersionEndpoints(config);
            if (!directUrlDisabled.isEmpty() || !hiddenFromLaunchpad.isEmpty()
                || !launchpadAliases.isEmpty() || !lanMarkers.isEmpty() || !versionEndpoints.isEmpty()) {
                routes = routes.stream()
                    .map(r -> {
                        ReverseProxyRoute current = r;
                        // Match either by router name (new path-aware format) or by FQDN
                        // (legacy format — applies to host-only routes for backward compat).
                        boolean disabled = directUrlDisabled.contains(r.getName())
                            || (r.getPathPrefix() == null && directUrlDisabled.contains(r.getDomainName()));
                        if (disabled) {
                            current = applyDirectUrlDisabledFlag(current, true);
                        }
                        if (hiddenFromLaunchpad.contains(r.getName())) {
                            current = applyHiddenFromLaunchpadFlag(current, true);
                        }
                        if (launchpadAliases.containsKey(r.getName())) {
                            current = applyLaunchpadAlias(current, launchpadAliases.get(r.getName()));
                        }
                        if (lanMarkers.containsKey(r.getDomainName())) {
                            current = applyLanServiceMarker(current, lanMarkers.get(r.getDomainName()));
                        }
                        if (versionEndpoints.containsKey(r.getName())) {
                            Map<String, String> v = versionEndpoints.get(r.getName());
                            current = applyVersionEndpoint(current, v.get("endpoint"), v.get("property"));
                        }
                        return current;
                    })
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
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
                        String pathPrefix = extractPathPrefixFromRule(routerConfig);
                        routes.addAll(extractServiceUrlsFromApi(routerName, domainName, serviceName, authInfo, entryPoints, tlsConfig, routerMiddlewares, serviceConfig, pathPrefix));
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
                        String pathPrefix = extractPathPrefixFromRule(routerConfig);
                        routes.addAll(extractServiceUrls(routerName, domainName, serviceName, authInfo, entryPoints, tlsConfig, routerMiddlewares, serviceConfig, rootRedirectPath, pathPrefix));
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
                                                               Map<String, Object> serviceConfig, String pathPrefix) {
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
                            addressPort.port, serviceName, authInfo, entryPoints, tlsConfig, middlewares,
                            null, false, false, null, pathPrefix));
                    }
                }
            }
        }

        return routes;
    }

    private List<ReverseProxyRoute> extractServiceUrls(String routerName, String domainName, String serviceName,
                                                        ReverseProxyRoute.AuthInfo authInfo, List<String> entryPoints,
                                                        ReverseProxyRoute.TlsConfig tlsConfig, List<String> middlewares,
                                                        Map<String, Object> serviceConfig, String rootRedirectPath,
                                                        String pathPrefix) {
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
                            addressPort.port, serviceName, authInfo, entryPoints, tlsConfig, middlewares,
                            rootRedirectPath, false, false, null, pathPrefix));
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
     * Extract authentication info from middleware names when fetching from API. The "is this an
     * auth middleware" predicate lives on {@link ReverseProxyRoute.AuthInfo#isAuthMiddlewareName}
     * — the adapter only walks the list and pulls the provider label off the matching entry.
     */
    private ReverseProxyRoute.AuthInfo extractAuthInfoFromMiddlewareNames(List<String> middlewareNames) {
        if (middlewareNames == null || middlewareNames.isEmpty()) return null;

        for (String middlewareName : middlewareNames) {
            if (!ReverseProxyRoute.AuthInfo.isAuthMiddlewareName(middlewareName)) continue;
            String provider = middlewareName.contains("@")
                ? middlewareName.substring(0, middlewareName.indexOf("@"))
                : "middleware";
            return new ReverseProxyRoute.AuthInfo("forwardAuth", provider, null);
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
    public void addReverseProxyRoute(String dnsName, String address, int port, AuthMode authMode,
                                     String rootRedirectPath, String pathPrefix) {
        loadConfig();

        if (config == null) {
            config = new LinkedHashMap<>();
        }

        // Generate router name and service name from DNS name + optional path slug
        String routerName = generateRouterName(dnsName, pathPrefix);
        String serviceName = generateServiceName(dnsName, pathPrefix);
        String redirectMiddlewareName = routerName.replace("-router", "-redirect");

        // Create HTTP section if it doesn't exist (using LinkedHashMap to preserve order)
        Map<String, Object> http = getOrCreateNestedMap(config, "http");

        // Ensure sections are created in the correct order: routers, services, middlewares
        Map<String, Object> routers = getOrCreateNestedMapOrdered(http, "routers");
        Map<String, Object> services = getOrCreateNestedMapOrdered(http, "services");

        // Add router configuration with standard defaults
        Map<String, Object> routerConfig = new LinkedHashMap<>();
        routerConfig.put("rule", pathPrefix == null
            ? "Host(`" + dnsName + "`)"
            : "Host(`" + dnsName + "`) && PathPrefix(`" + pathPrefix + "`)");

        // Standard entryPoints
        List<String> entryPoints = new ArrayList<>();
        entryPoints.add(ServiceNames.ENTRY_POINT_WEBSECURE);
        routerConfig.put("entryPoints", entryPoints);

        routerConfig.put("service", serviceName);

        // Standard TLS configuration with letsencrypt
        Map<String, Object> tlsMap = new LinkedHashMap<>();
        tlsMap.put("certResolver", ServiceNames.CERT_RESOLVER);
        routerConfig.put("tls", tlsMap);

        // Build middleware list. The auth chain (which the AuthMode owns) comes first, then any
        // redirect, then the errors middleware last so a backend failure lands on Vaier's branded
        // offline page.
        List<String> middlewareList = new ArrayList<>(authMode.authMiddlewareNames());
        if (rootRedirectPath != null) middlewareList.add(redirectMiddlewareName);
        middlewareList.add(ServiceNames.ERROR_PAGES_MIDDLEWARE);
        routerConfig.put("middlewares", middlewareList);

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
        ensureAuthInfraExists(http, authMode, dnsName);
        if (rootRedirectPath != null) {
            Map<String, Object> middlewaresSection = getOrCreateNestedMapOrdered(http, "middlewares");
            Map<String, Object> redirectRegex = new LinkedHashMap<>();
            redirectRegex.put("regex", "^https://" + dnsName.replace(".", "\\.") + "/?$");
            redirectRegex.put("replacement", "https://" + dnsName + rootRedirectPath);
            Map<String, Object> redirectMiddleware = new LinkedHashMap<>();
            redirectMiddleware.put("redirectRegex", redirectRegex);
            middlewaresSection.put(redirectMiddlewareName, redirectMiddleware);
        }
        ensureErrorPagesInfraExists(http);

        saveConfig();
    }

    /**
     * Add a Traefik route that forwards to a LAN backend (no Docker container).
     * The backend URL uses the supplied protocol (http or https) and the LAN host:port
     * directly. Persists an {@code x-vaier-lan-service} marker so reads can identify
     * this route as a LAN-typed one.
     */
    @Override
    public void addLanReverseProxyRoute(String dnsName, String host, int port, String protocol,
                                        AuthMode authMode, boolean directUrlDisabled, String rootRedirectPath,
                                        String pathPrefix) {
        loadConfig();
        if (config == null) config = new LinkedHashMap<>();

        String routerName = generateRouterName(dnsName, pathPrefix);
        String serviceName = generateServiceName(dnsName, pathPrefix);
        String redirectMiddlewareName = routerName.replace("-router", "-redirect");

        Map<String, Object> http = getOrCreateNestedMap(config, "http");
        Map<String, Object> routers = getOrCreateNestedMapOrdered(http, "routers");
        Map<String, Object> services = getOrCreateNestedMapOrdered(http, "services");

        Map<String, Object> routerConfig = new LinkedHashMap<>();
        routerConfig.put("rule", pathPrefix == null
            ? "Host(`" + dnsName + "`)"
            : "Host(`" + dnsName + "`) && PathPrefix(`" + pathPrefix + "`)");
        List<String> entryPoints = new ArrayList<>();
        entryPoints.add(ServiceNames.ENTRY_POINT_WEBSECURE);
        routerConfig.put("entryPoints", entryPoints);
        routerConfig.put("service", serviceName);
        Map<String, Object> tlsMap = new LinkedHashMap<>();
        tlsMap.put("certResolver", ServiceNames.CERT_RESOLVER);
        routerConfig.put("tls", tlsMap);
        List<String> middlewareList = new ArrayList<>(authMode.authMiddlewareNames());
        if (rootRedirectPath != null) middlewareList.add(redirectMiddlewareName);
        middlewareList.add(ServiceNames.ERROR_PAGES_MIDDLEWARE);
        routerConfig.put("middlewares", middlewareList);
        routers.put(routerName, routerConfig);

        Map<String, Object> serviceConfig = new LinkedHashMap<>();
        Map<String, Object> loadBalancer = new LinkedHashMap<>();
        List<Map<String, Object>> servers = new ArrayList<>();
        Map<String, Object> server = new LinkedHashMap<>();
        String scheme = (protocol == null || protocol.isBlank()) ? "http" : protocol;
        server.put("url", scheme + "://" + host + ":" + port);
        servers.add(server);
        loadBalancer.put("servers", servers);
        serviceConfig.put("loadBalancer", loadBalancer);
        services.put(serviceName, serviceConfig);

        ensureAuthInfraExists(http, authMode, dnsName);
        if (rootRedirectPath != null) {
            Map<String, Object> middlewaresSection = getOrCreateNestedMapOrdered(http, "middlewares");
            Map<String, Object> redirectRegex = new LinkedHashMap<>();
            redirectRegex.put("regex", "^https://" + dnsName.replace(".", "\\.") + "/?$");
            redirectRegex.put("replacement", "https://" + dnsName + rootRedirectPath);
            Map<String, Object> redirectMiddleware = new LinkedHashMap<>();
            redirectMiddleware.put("redirectRegex", redirectRegex);
            middlewaresSection.put(redirectMiddlewareName, redirectMiddleware);
        }
        ensureErrorPagesInfraExists(http);

        addLanServiceMarker(dnsName, scheme);
        if (directUrlDisabled) {
            // setRouteDirectUrlDisabled does its own loadConfig+saveConfig, so save first.
            saveConfig();
            setRouteDirectUrlDisabled(dnsName, pathPrefix, true);
            return;
        }

        saveConfig();
    }

    @SuppressWarnings("unchecked")
    private void addLanServiceMarker(String dnsName, String protocol) {
        Object raw = config.get(LAN_SERVICE_KEY);
        Map<String, String> markers = (raw instanceof Map<?, ?> m)
            ? new LinkedHashMap<>((Map<String, String>) m)
            : new LinkedHashMap<>();
        markers.put(dnsName, protocol);
        config.put(LAN_SERVICE_KEY, markers);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readLanServiceMarkers(Map<String, Object> cfg) {
        if (cfg == null) return Map.of();
        Object raw = cfg.get(LAN_SERVICE_KEY);
        if (raw instanceof Map<?, ?> m) {
            Map<String, String> result = new LinkedHashMap<>();
            for (var e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    result.put(e.getKey().toString(), e.getValue().toString());
                }
            }
            return result;
        }
        return Map.of();
    }

    /**
     * Generate router name from DNS name.
     * Example: "portainer.eilertsen.family" -> "portainer-router"
     * Example: "code.apalveien5.eilertsen.family" -> "code-apalveien5-router"
     */
    private String generateRouterName(String dnsName) {
        return ReverseProxyRoute.routerName(dnsName, null);
    }

    private String generateServiceName(String dnsName) {
        return ReverseProxyRoute.serviceName(dnsName, null);
    }

    private String generateRouterName(String dnsName, String pathPrefix) {
        return ReverseProxyRoute.routerName(dnsName, pathPrefix);
    }

    private String generateServiceName(String dnsName, String pathPrefix) {
        return ReverseProxyRoute.serviceName(dnsName, pathPrefix);
    }

    /**
     * Extract the {@code PathPrefix(`/...`)} value from a router rule, if present.
     */
    private String extractPathPrefixFromRule(Map<String, Object> routerConfig) {
        String rule = (String) routerConfig.get("rule");
        if (rule == null) return null;
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("PathPrefix\\s*\\(\\s*`([^`]+)`\\s*\\)");
        java.util.regex.Matcher matcher = pattern.matcher(rule);
        return matcher.find() ? matcher.group(1) : null;
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
     * Delete a reverse proxy route from the Traefik configuration: the router, its associated
     * service, and all Vaier sidecar metadata for that router (launchpad alias, hidden-from-launchpad,
     * direct-url-disabled, version-endpoint) plus the LAN-service marker. Middlewares are NOT removed
     * as they may be shared between multiple routers.
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

        // Read the FQDN and whether the route is path-scoped from its own rule. This is authoritative
        // and correct for every caller — deleteReverseProxyRouteByDnsName, path-based deletes, and the
        // rollback paths that delete host-only routes by router name — whereas the router name alone
        // can't tell host-only from path-scoped apart.
        Map<String, Object> routerConfig = castToMap(routers.get(routeName));
        String serviceName = routerConfig != null ? (String) routerConfig.get("service") : null;
        String fqdn = routerConfig != null ? extractDomainFromRule(routerConfig) : null;
        boolean hostOnly = routerConfig != null && extractPathPrefixFromRule(routerConfig) == null;
        boolean wasSocial = routerConfig != null
            && AuthMode.fromMiddlewareNames(extractMiddlewareList(routerConfig)) == AuthMode.SOCIAL;

        // Remove router
        routers.remove(routeName);

        // Remove associated service if it exists and is not shared
        // Note: This assumes services are not shared. If they are, you may need additional logic.
        if (serviceName != null && services != null) {
            services.remove(serviceName);
        }

        // A social route owns a per-host /oauth2/ helper router. Remove it once no social route
        // remains on the host — otherwise it would linger as an orphan and (sharing the host's
        // FQDN) block CNAME cleanup by looking like a surviving sibling. The shared oauth2-proxy-svc
        // service and the social middlewares are intentionally left, like auth-middleware, since
        // other hosts may still use them.
        if (wasSocial && fqdn != null && noSocialRouteRemainsOnHost(routers, fqdn)) {
            routers.remove(ReverseProxyRoute.oauth2EndpointsRouterName(fqdn));
        }

        // Do NOT remove middlewares as they are typically shared (e.g., auth-middleware)

        // Router-name-keyed sidecars are this route's own — strip them on every delete.
        removeRouterSidecarMetadata(routeName);
        // FQDN-keyed state (the LAN-service marker and the legacy bare-FQDN direct-url-disabled entry)
        // is shared by every route on the host, so only clear it for a host-only route — a path-scoped
        // delete must leave its siblings' host-level state intact.
        if (hostOnly && fqdn != null) {
            removeLanServiceMarker(fqdn);
            removeFromListSidecar(DIRECT_URL_DISABLED_KEY, fqdn);
        }

        saveConfig();
    }

    /**
     * Whether no remaining router on {@code fqdn} still carries the social auth chain (ignoring the
     * {@code /oauth2/} helper router itself). Used to decide when the per-host helper router can be
     * torn down.
     */
    private boolean noSocialRouteRemainsOnHost(Map<String, Object> routers, String fqdn) {
        if (routers == null) return true;
        for (Map.Entry<String, Object> e : routers.entrySet()) {
            if (e.getKey().equals(ReverseProxyRoute.oauth2EndpointsRouterName(fqdn))) continue;
            Map<String, Object> rc = castToMap(e.getValue());
            if (rc == null) continue;
            if (!fqdn.equals(extractDomainFromRule(rc))) continue;
            if (AuthMode.fromMiddlewareNames(extractMiddlewareList(rc)) == AuthMode.SOCIAL) {
                return false;
            }
        }
        return true;
    }

    /**
     * Strip every per-router sidecar metadata entry for a deleted router. The launchpad-alias
     * (display name), hidden-from-launchpad, direct-url-disabled and version-endpoint collections
     * are keyed by router name, which is deterministic from the FQDN — so without this a service
     * re-published on the same FQDN would silently inherit the deleted route's display name, hidden
     * flag, direct-URL-disabled flag or version endpoint.
     */
    private void removeRouterSidecarMetadata(String routerName) {
        if (config == null) return;
        removeFromMapSidecar(LAUNCHPAD_ALIAS_KEY, routerName);
        removeFromMapSidecar(VERSION_ENDPOINT_KEY, routerName);
        removeFromListSidecar(HIDDEN_FROM_LAUNCHPAD_KEY, routerName);
        removeFromListSidecar(DIRECT_URL_DISABLED_KEY, routerName);
    }

    @SuppressWarnings("unchecked")
    private void removeFromMapSidecar(String key, String entryKey) {
        Object raw = config.get(key);
        if (!(raw instanceof Map<?, ?> m)) return;
        Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) m);
        // Decide on containsKey, not the remove() return value: an entry with an explicit null value
        // (manual edits / partial writes) would otherwise be left behind, since remove() returns null.
        if (map.containsKey(entryKey)) {
            map.remove(entryKey);
            if (map.isEmpty()) config.remove(key);
            else config.put(key, map);
        }
    }

    private void removeFromListSidecar(String key, String... entries) {
        Object raw = config.get(key);
        if (!(raw instanceof List<?> l)) return;
        // Tolerate hand-edited YAML: skip null elements (Object::toString would NPE) and remove every
        // occurrence of each entry, not just the first, so duplicates can't leave stale metadata.
        List<String> list = l.stream()
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        boolean changed = false;
        for (String e : entries) {
            if (e != null) changed |= list.removeIf(e::equals);
        }
        if (changed) {
            if (list.isEmpty()) config.remove(key);
            else config.put(key, list);
        }
    }

    @SuppressWarnings("unchecked")
    private void removeLanServiceMarker(String dnsName) {
        if (dnsName == null || config == null) return;
        Object raw = config.get(LAN_SERVICE_KEY);
        if (!(raw instanceof Map<?, ?> m)) return;
        Map<String, Object> markers = new LinkedHashMap<>((Map<String, Object>) m);
        if (markers.remove(dnsName) != null) {
            if (markers.isEmpty()) config.remove(LAN_SERVICE_KEY);
            else config.put(LAN_SERVICE_KEY, markers);
        }
    }

    /**
     * Delete a reverse proxy route by DNS name.
     * Generates the router name from the DNS name and deletes it.
     *
     * @param dnsName The full DNS name (e.g., "portainer.eilertsen.family")
     */
    @Override
    public void deleteReverseProxyRouteByDnsName(String dnsName) {
        deleteReverseProxyRoute(generateRouterName(dnsName));
    }

    @Override
    public void setRouteAuthMode(String dnsName, String pathPrefix, AuthMode authMode) {
        loadConfig();

        Map<String, Object> http = getNestedMap(config, "http");
        if (http == null) throw new RuntimeException("No HTTP configuration found");

        Map<String, Object> routers = getNestedMap(http, "routers");
        String routerName = generateRouterName(dnsName, pathPrefix);
        if (routers == null || !routers.containsKey(routerName)) {
            throw new RuntimeException("Router not found: " + routerName);
        }

        Map<String, Object> routerConfig = castToMap(routers.get(routerName));

        // Strip every known auth link (any mode's), preserving redirect/errors, then prepend the
        // new mode's chain in order. Stripping the whole union means a mode switch never leaves a
        // stale link from the prior gateway behind.
        @SuppressWarnings("unchecked")
        List<String> existing = routerConfig.get("middlewares") instanceof List
            ? new ArrayList<>((List<String>) routerConfig.get("middlewares"))
            : new ArrayList<>();
        existing.removeAll(AuthMode.allAuthMiddlewareNames());
        List<String> chain = authMode.authMiddlewareNames();
        for (int i = chain.size() - 1; i >= 0; i--) {
            existing.add(0, chain.get(i));
        }
        if (existing.isEmpty()) routerConfig.remove("middlewares");
        else routerConfig.put("middlewares", existing);

        ensureAuthInfraExists(http, authMode, dnsName);

        saveConfig();
    }

    @Override
    public void setRouteRootRedirectPath(String dnsName, String pathPrefix, String rootRedirectPath) {
        loadConfig();

        Map<String, Object> http = getNestedMap(config, "http");
        if (http == null) throw new RuntimeException("No HTTP configuration found");

        Map<String, Object> routers = getNestedMap(http, "routers");
        String routerName = generateRouterName(dnsName, pathPrefix);
        if (routers == null || !routers.containsKey(routerName)) {
            throw new RuntimeException("Router not found: " + routerName);
        }

        Map<String, Object> routerConfig = castToMap(routers.get(routerName));
        String redirectMiddlewareName = routerName.replace("-router", "-redirect");

        // Update middleware list on router
        @SuppressWarnings("unchecked")
        List<String> middlewares = routerConfig.get("middlewares") instanceof List
            ? new ArrayList<>((List<String>) routerConfig.get("middlewares"))
            : new ArrayList<>();

        Map<String, Object> middlewaresSection = getNestedMap(http, "middlewares");

        if (rootRedirectPath != null) {
            // Add or update redirect middleware
            if (!middlewares.contains(redirectMiddlewareName)) {
                middlewares.add(redirectMiddlewareName);
            }
            if (!middlewares.isEmpty()) {
                routerConfig.put("middlewares", middlewares);
            }

            if (middlewaresSection == null) {
                middlewaresSection = getOrCreateNestedMapOrdered(http, "middlewares");
            }
            Map<String, Object> redirectRegex = new LinkedHashMap<>();
            redirectRegex.put("regex", "^https://" + dnsName.replace(".", "\\.") + "/?$");
            redirectRegex.put("replacement", "https://" + dnsName + rootRedirectPath);
            Map<String, Object> redirectMiddleware = new LinkedHashMap<>();
            redirectMiddleware.put("redirectRegex", redirectRegex);
            middlewaresSection.put(redirectMiddlewareName, redirectMiddleware);
        } else {
            // Remove redirect middleware
            middlewares.remove(redirectMiddlewareName);
            if (middlewares.isEmpty()) {
                routerConfig.remove("middlewares");
            } else {
                routerConfig.put("middlewares", middlewares);
            }
            if (middlewaresSection != null) {
                middlewaresSection.remove(redirectMiddlewareName);
            }
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
     * Ensure the shared infrastructure a route's {@link AuthMode} needs exists. Authelia routes need
     * the single {@code auth-middleware}; social routes need the proven two-stage middlewares plus a
     * higher-priority per-host {@code /oauth2/} router pointing at oauth2-proxy (without which the
     * "Sign in with Google" button's relative {@code /oauth2/start} loops back into the auth chain).
     * No-op for {@link AuthMode#NONE}. Must be called AFTER routers and services are created.
     */
    private void ensureAuthInfraExists(Map<String, Object> http, AuthMode authMode, String dnsName) {
        switch (authMode) {
            case AUTHELIA -> ensureAuthMiddlewareExists(http);
            case SOCIAL -> {
                ensureSocialAuthMiddlewaresExist(http);
                ensureOauth2EndpointsRouterExists(http, dnsName);
            }
            case NONE -> { /* public route — no auth infrastructure */ }
        }
    }

    /**
     * Ensure the oauth2-proxy backend service and the two-stage social middlewares
     * ({@code oauth2-signin} errors page, {@code oauth2-authn} Google forward-auth,
     * {@code vaier-authz} Vaier forward-auth) exist — reproducing exactly the config proven in
     * step 1 ({@code traefik/config/oauth2-test.yml}). Idempotent.
     */
    private void ensureSocialAuthMiddlewaresExist(Map<String, Object> http) {
        Map<String, Object> services = getOrCreateNestedMapOrdered(http, "services");
        if (!services.containsKey(ServiceNames.OAUTH2_PROXY_SERVICE)) {
            Map<String, Object> serviceConfig = new LinkedHashMap<>();
            Map<String, Object> loadBalancer = new LinkedHashMap<>();
            List<Map<String, Object>> servers = new ArrayList<>();
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("url", "http://oauth2-proxy:4180");
            servers.add(server);
            loadBalancer.put("servers", servers);
            serviceConfig.put("loadBalancer", loadBalancer);
            services.put(ServiceNames.OAUTH2_PROXY_SERVICE, serviceConfig);
        }

        Map<String, Object> middlewares = getOrCreateNestedMapOrdered(http, "middlewares");

        // The three social middleware definitions are deterministic desired-state, so they are
        // (re)written every time — not create-if-missing — so a definition change (e.g. a new
        // forwarded header) propagates to configs generated by an older Vaier. Bare blocks keep
        // each definition's local variables scoped.
        {
            // On a 401 from the auth stage, serve oauth2-proxy's sign-in page (Google button ->
            // /oauth2/start, routed by the per-host endpoints router).
            Map<String, Object> errors = new LinkedHashMap<>();
            List<String> statuses = new ArrayList<>();
            statuses.add("401");
            errors.put("status", statuses);
            errors.put("service", ServiceNames.OAUTH2_PROXY_SERVICE);
            errors.put("query", "/oauth2/sign_in?rd={url}");
            Map<String, Object> signin = new LinkedHashMap<>();
            signin.put("errors", errors);
            middlewares.put(ServiceNames.OAUTH2_SIGNIN_MIDDLEWARE, signin);
        }

        {
            Map<String, Object> forwardAuth = new LinkedHashMap<>();
            forwardAuth.put("address", "http://oauth2-proxy:4180/oauth2/auth");
            forwardAuth.put("trustForwardHeader", true);
            List<String> headers = new ArrayList<>();
            headers.add("X-Auth-Request-Email");
            headers.add("X-Auth-Request-User");
            // The display-name claim oauth2-proxy forwards (X-Auth-Request-Name) must reach
            // /authz/verify so Vaier can capture it on the access entry.
            headers.add("X-Auth-Request-Name");
            forwardAuth.put("authResponseHeaders", headers);
            Map<String, Object> authn = new LinkedHashMap<>();
            authn.put("forwardAuth", forwardAuth);
            middlewares.put(ServiceNames.OAUTH2_AUTHN_MIDDLEWARE, authn);
        }

        {
            Map<String, Object> forwardAuth = new LinkedHashMap<>();
            forwardAuth.put("address", "http://" + ServiceNames.VAIER + ":8080/authz/verify");
            forwardAuth.put("trustForwardHeader", true);
            List<String> headers = new ArrayList<>();
            headers.add("Remote-User");
            headers.add("Remote-Email");
            headers.add("Remote-Groups");
            // The display name Vaier resolves from the access entry, so the console topbar / My Page
            // can greet a social user by name rather than email.
            headers.add("Remote-Name");
            forwardAuth.put("authResponseHeaders", headers);
            Map<String, Object> authz = new LinkedHashMap<>();
            authz.put("forwardAuth", forwardAuth);
            middlewares.put(ServiceNames.VAIER_AUTHZ_MIDDLEWARE, authz);
        }
    }

    /**
     * Ensure a higher-priority router exists that sends {@code Host(host) && PathPrefix(/oauth2/)}
     * straight to oauth2-proxy (no auth, so the sign-in / callback / sign_out endpoints are
     * reachable). Keyed per host so each social-gated host gets its own. Idempotent.
     */
    private void ensureOauth2EndpointsRouterExists(Map<String, Object> http, String dnsName) {
        Map<String, Object> routers = getOrCreateNestedMapOrdered(http, "routers");
        String endpointsRouterName = ReverseProxyRoute.oauth2EndpointsRouterName(dnsName);
        if (routers.containsKey(endpointsRouterName)) return;

        Map<String, Object> routerConfig = new LinkedHashMap<>();
        routerConfig.put("rule", "Host(`" + dnsName + "`) && PathPrefix(`/oauth2/`)");
        List<String> entryPoints = new ArrayList<>();
        entryPoints.add(ServiceNames.ENTRY_POINT_WEBSECURE);
        routerConfig.put("entryPoints", entryPoints);
        routerConfig.put("service", ServiceNames.OAUTH2_PROXY_SERVICE);
        routerConfig.put("priority", 100);
        Map<String, Object> tlsMap = new LinkedHashMap<>();
        tlsMap.put("certResolver", ServiceNames.CERT_RESOLVER);
        routerConfig.put("tls", tlsMap);
        routers.put(endpointsRouterName, routerConfig);
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

    /**
     * Ensure the shared offline-page infrastructure exists: an http service pointing at the Vaier
     * container plus an {@code errors} middleware that catches 502/503/504 and serves Vaier's
     * branded offline page. Idempotent — created once and reused by every router. Must be called
     * AFTER routers and services are created to keep the routers/services/middlewares order.
     */
    private void ensureErrorPagesInfraExists(Map<String, Object> http) {
        Map<String, Object> services = getOrCreateNestedMapOrdered(http, "services");
        if (!services.containsKey(ServiceNames.ERROR_PAGES_SERVICE)) {
            Map<String, Object> serviceConfig = new LinkedHashMap<>();
            Map<String, Object> loadBalancer = new LinkedHashMap<>();
            List<Map<String, Object>> servers = new ArrayList<>();
            Map<String, Object> server = new LinkedHashMap<>();
            // Traefik's errors middleware calls this URL directly, bypassing routers/auth, so it
            // targets the Vaier container straight on its internal port.
            server.put("url", "http://" + ServiceNames.VAIER + ":8080");
            servers.add(server);
            loadBalancer.put("servers", servers);
            serviceConfig.put("loadBalancer", loadBalancer);
            services.put(ServiceNames.ERROR_PAGES_SERVICE, serviceConfig);
        }

        Map<String, Object> middlewares = getOrCreateNestedMapOrdered(http, "middlewares");
        if (!middlewares.containsKey(ServiceNames.ERROR_PAGES_MIDDLEWARE)) {
            Map<String, Object> errors = new LinkedHashMap<>();
            List<String> statuses = new ArrayList<>();
            statuses.add("502");
            statuses.add("503");
            statuses.add("504");
            errors.put("status", statuses);
            errors.put("service", ServiceNames.ERROR_PAGES_SERVICE);
            errors.put("query", "/error-pages/{status}");
            Map<String, Object> errorsMiddleware = new LinkedHashMap<>();
            errorsMiddleware.put("errors", errors);
            middlewares.put(ServiceNames.ERROR_PAGES_MIDDLEWARE, errorsMiddleware);
        }
    }

    /**
     * Backfill the offline-page middleware onto every existing http router that lacks it, and ensure
     * the shared service+middleware exist. Idempotent and additive: a router's existing middleware
     * list (auth, redirects) is preserved and {@code vaier-errors} is appended only if missing;
     * load-balancer servers and {@code x-vaier-*} metadata are never touched. Run on startup so
     * routes that predate the offline page benefit immediately.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void backfillErrorPagesOnStartup() {
        try {
            backfillErrorPages();
        } catch (Exception e) {
            log.warn("Offline-page backfill on startup failed", e);
        }
    }

    /**
     * Refresh the social middleware definitions on startup when social auth is in use, so a config
     * generated by an older Vaier picks up definition changes (e.g. a newly-forwarded header)
     * without needing a manual route edit. No-op when no social route exists.
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void backfillSocialMiddlewaresOnStartup() {
        try {
            loadConfig();
            if (config == null) return;
            Map<String, Object> http = getNestedMap(config, "http");
            if (http == null) return;
            Map<String, Object> middlewares = getNestedMap(http, "middlewares");
            if (middlewares == null || !middlewares.containsKey(ServiceNames.OAUTH2_AUTHN_MIDDLEWARE)) {
                return; // social auth not in use — nothing to refresh
            }
            ensureSocialAuthMiddlewaresExist(http); // idempotent rewrite picks up new headers/defs
            saveConfig();
        } catch (Exception e) {
            log.warn("Social-middleware backfill on startup failed", e);
        }
    }

    public void backfillErrorPages() {
        loadConfig();
        if (config == null) return;
        Map<String, Object> http = getNestedMap(config, "http");
        if (http == null) return;
        Map<String, Object> routers = getNestedMap(http, "routers");
        if (routers == null || routers.isEmpty()) return;

        boolean changed = false;
        for (Object value : routers.values()) {
            Map<String, Object> routerConfig = castToMap(value);
            if (routerConfig == null) continue;
            List<String> middlewares = extractMiddlewareList(routerConfig);
            if (middlewares == null) middlewares = new ArrayList<>();
            if (!middlewares.contains(ServiceNames.ERROR_PAGES_MIDDLEWARE)) {
                middlewares.add(ServiceNames.ERROR_PAGES_MIDDLEWARE);
                routerConfig.put("middlewares", middlewares);
                changed = true;
            }
        }

        // Ensure shared infra regardless — a config could have routers already pointing at it but
        // be missing the definition (or this is the first backfill on a fresh config).
        int sizeBefore = sizeOfNested(http, "services") + sizeOfNested(http, "middlewares");
        ensureErrorPagesInfraExists(http);
        int sizeAfter = sizeOfNested(http, "services") + sizeOfNested(http, "middlewares");

        if (changed || sizeAfter != sizeBefore) {
            saveConfig();
        }
    }

    private int sizeOfNested(Map<String, Object> map, String key) {
        Map<String, Object> nested = getNestedMap(map, key);
        return nested == null ? 0 : nested.size();
    }

    // --- ForManagingIgnoredServices ---

    private static final String IGNORED_KEY = "x-vaier-ignored";
    private static final String DIRECT_URL_DISABLED_KEY = "x-vaier-direct-url-disabled";
    private static final String HIDDEN_FROM_LAUNCHPAD_KEY = "x-vaier-hidden-from-launchpad";
    private static final String LAUNCHPAD_ALIAS_KEY = "x-vaier-launchpad-alias";
    private static final String LAN_SERVICE_KEY = "x-vaier-lan-service";
    private static final String VERSION_ENDPOINT_KEY = "x-vaier-version-endpoint";

    @Override
    public void setRouteDirectUrlDisabled(String dnsName, String pathPrefix, boolean directUrlDisabled) {
        loadConfig();
        if (config == null) config = new LinkedHashMap<>();
        Object raw = config.get(DIRECT_URL_DISABLED_KEY);
        List<String> disabled = (raw instanceof List<?> list)
            ? new ArrayList<>(list.stream().map(Object::toString).toList())
            : new ArrayList<>();
        // Key by router name (path-aware). For host-only routes that's the same shape as the
        // legacy "<fqdn>-router" name. On flip-on, also strip any legacy bare-FQDN entry to
        // avoid two stale representations of the same route in the YAML.
        String routerName = generateRouterName(dnsName, pathPrefix);
        boolean changed;
        if (directUrlDisabled) {
            boolean removedLegacy = pathPrefix == null && disabled.remove(dnsName);
            boolean added = !disabled.contains(routerName) && disabled.add(routerName);
            changed = removedLegacy || added;
        } else {
            boolean removedLegacy = pathPrefix == null && disabled.remove(dnsName);
            boolean removedNew = disabled.remove(routerName);
            changed = removedLegacy || removedNew;
        }
        if (changed) {
            if (disabled.isEmpty()) config.remove(DIRECT_URL_DISABLED_KEY);
            else config.put(DIRECT_URL_DISABLED_KEY, disabled);
            saveConfig();
        }
    }

    private Set<String> readDirectUrlDisabledDomains(Map<String, Object> cfg) {
        if (cfg == null) return Set.of();
        Object raw = cfg.get(DIRECT_URL_DISABLED_KEY);
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).collect(java.util.stream.Collectors.toSet());
        }
        return Set.of();
    }

    private ReverseProxyRoute applyDirectUrlDisabledFlag(ReverseProxyRoute r, boolean disabled) {
        if (r.isDirectUrlDisabled() == disabled) return r;
        return new ReverseProxyRoute(
            r.getName(), r.getDomainName(), r.getAddress(), r.getPort(), r.getService(),
            r.getAuthInfo(), r.getEntryPoints(), r.getTlsConfig(), r.getMiddlewares(),
            r.getRootRedirectPath(), disabled, r.isLanService(), r.getProtocol(), r.getPathPrefix(),
            r.isHiddenFromLaunchpad(), r.getLaunchpadAlias(), r.getVersionEndpoint(), r.getVersionProperty()
        );
    }

    @Override
    public void setRouteHiddenFromLaunchpad(String dnsName, String pathPrefix, boolean hiddenFromLaunchpad) {
        loadConfig();
        if (config == null) config = new LinkedHashMap<>();
        Object raw = config.get(HIDDEN_FROM_LAUNCHPAD_KEY);
        List<String> hidden = (raw instanceof List<?> list)
            ? new ArrayList<>(list.stream().map(Object::toString).toList())
            : new ArrayList<>();
        String routerName = generateRouterName(dnsName, pathPrefix);
        boolean changed;
        if (hiddenFromLaunchpad) {
            changed = !hidden.contains(routerName) && hidden.add(routerName);
        } else {
            changed = hidden.remove(routerName);
        }
        if (changed) {
            if (hidden.isEmpty()) config.remove(HIDDEN_FROM_LAUNCHPAD_KEY);
            else config.put(HIDDEN_FROM_LAUNCHPAD_KEY, hidden);
            saveConfig();
        }
    }

    private Set<String> readHiddenFromLaunchpadRouters(Map<String, Object> cfg) {
        if (cfg == null) return Set.of();
        Object raw = cfg.get(HIDDEN_FROM_LAUNCHPAD_KEY);
        if (raw instanceof List<?> list) {
            return list.stream().map(Object::toString).collect(java.util.stream.Collectors.toSet());
        }
        return Set.of();
    }

    private ReverseProxyRoute applyHiddenFromLaunchpadFlag(ReverseProxyRoute r, boolean hidden) {
        if (r.isHiddenFromLaunchpad() == hidden) return r;
        return new ReverseProxyRoute(
            r.getName(), r.getDomainName(), r.getAddress(), r.getPort(), r.getService(),
            r.getAuthInfo(), r.getEntryPoints(), r.getTlsConfig(), r.getMiddlewares(),
            r.getRootRedirectPath(), r.isDirectUrlDisabled(), r.isLanService(), r.getProtocol(),
            r.getPathPrefix(), hidden, r.getLaunchpadAlias(), r.getVersionEndpoint(), r.getVersionProperty()
        );
    }

    @Override
    public void setRouteLaunchpadAlias(String dnsName, String pathPrefix, String launchpadAlias) {
        loadConfig();
        if (config == null) config = new LinkedHashMap<>();
        Object raw = config.get(LAUNCHPAD_ALIAS_KEY);
        Map<String, Object> aliases = (raw instanceof Map<?, ?> map)
            ? new LinkedHashMap<>(map.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                e -> e.getKey().toString(), Map.Entry::getValue, (a, b) -> b, LinkedHashMap::new)))
            : new LinkedHashMap<>();
        String routerName = generateRouterName(dnsName, pathPrefix);
        String alias = (launchpadAlias == null || launchpadAlias.isBlank()) ? null : launchpadAlias.trim();
        boolean changed;
        if (alias == null) {
            changed = aliases.remove(routerName) != null;
        } else {
            Object previous = aliases.put(routerName, alias);
            changed = !alias.equals(previous);
        }
        if (changed) {
            if (aliases.isEmpty()) config.remove(LAUNCHPAD_ALIAS_KEY);
            else config.put(LAUNCHPAD_ALIAS_KEY, aliases);
            saveConfig();
        }
    }

    private Map<String, String> readLaunchpadAliases(Map<String, Object> cfg) {
        if (cfg == null) return Map.of();
        Object raw = cfg.get(LAUNCHPAD_ALIAS_KEY);
        if (raw instanceof Map<?, ?> map) {
            Map<String, String> result = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k != null && v != null) result.put(k.toString(), v.toString());
            });
            return result;
        }
        return Map.of();
    }

    private ReverseProxyRoute applyLaunchpadAlias(ReverseProxyRoute r, String alias) {
        if (java.util.Objects.equals(r.getLaunchpadAlias(), alias)) return r;
        return new ReverseProxyRoute(
            r.getName(), r.getDomainName(), r.getAddress(), r.getPort(), r.getService(),
            r.getAuthInfo(), r.getEntryPoints(), r.getTlsConfig(), r.getMiddlewares(),
            r.getRootRedirectPath(), r.isDirectUrlDisabled(), r.isLanService(), r.getProtocol(),
            r.getPathPrefix(), r.isHiddenFromLaunchpad(), alias, r.getVersionEndpoint(), r.getVersionProperty()
        );
    }

    @Override
    public void setRouteVersionEndpoint(String dnsName, String pathPrefix, String versionEndpoint,
                                        String versionProperty) {
        loadConfig();
        if (config == null) config = new LinkedHashMap<>();
        Object raw = config.get(VERSION_ENDPOINT_KEY);
        Map<String, Object> endpoints = (raw instanceof Map<?, ?> map)
            ? new LinkedHashMap<>(map.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                e -> e.getKey().toString(), Map.Entry::getValue, (a, b) -> b, LinkedHashMap::new)))
            : new LinkedHashMap<>();
        String routerName = generateRouterName(dnsName, pathPrefix);
        String endpoint = (versionEndpoint == null || versionEndpoint.isBlank()) ? null : versionEndpoint.trim();
        String property = (versionProperty == null || versionProperty.isBlank()) ? null : versionProperty.trim();
        boolean changed;
        // Both halves are required — a probe needs an endpoint to GET and a property to read.
        if (endpoint == null || property == null) {
            changed = endpoints.remove(routerName) != null;
        } else {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("endpoint", endpoint);
            entry.put("property", property);
            changed = !entry.equals(endpoints.put(routerName, entry));
        }
        if (changed) {
            if (endpoints.isEmpty()) config.remove(VERSION_ENDPOINT_KEY);
            else config.put(VERSION_ENDPOINT_KEY, endpoints);
            saveConfig();
        }
    }

    private Map<String, Map<String, String>> readVersionEndpoints(Map<String, Object> cfg) {
        if (cfg == null) return Map.of();
        Object raw = cfg.get(VERSION_ENDPOINT_KEY);
        if (!(raw instanceof Map<?, ?> map)) return Map.of();
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            if (k != null && v instanceof Map<?, ?> entry) {
                Object endpoint = entry.get("endpoint");
                Object property = entry.get("property");
                if (endpoint != null && property != null) {
                    Map<String, String> parsed = new LinkedHashMap<>();
                    parsed.put("endpoint", endpoint.toString());
                    parsed.put("property", property.toString());
                    result.put(k.toString(), parsed);
                }
            }
        });
        return result;
    }

    private ReverseProxyRoute applyVersionEndpoint(ReverseProxyRoute r, String endpoint, String property) {
        return new ReverseProxyRoute(
            r.getName(), r.getDomainName(), r.getAddress(), r.getPort(), r.getService(),
            r.getAuthInfo(), r.getEntryPoints(), r.getTlsConfig(), r.getMiddlewares(),
            r.getRootRedirectPath(), r.isDirectUrlDisabled(), r.isLanService(), r.getProtocol(),
            r.getPathPrefix(), r.isHiddenFromLaunchpad(), r.getLaunchpadAlias(), endpoint, property
        );
    }

    private ReverseProxyRoute applyLanServiceMarker(ReverseProxyRoute r, String protocol) {
        return new ReverseProxyRoute(
            r.getName(), r.getDomainName(), r.getAddress(), r.getPort(), r.getService(),
            r.getAuthInfo(), r.getEntryPoints(), r.getTlsConfig(), r.getMiddlewares(),
            r.getRootRedirectPath(), r.isDirectUrlDisabled(), true, protocol, r.getPathPrefix(),
            r.isHiddenFromLaunchpad(), r.getLaunchpadAlias(), r.getVersionEndpoint(), r.getVersionProperty()
        );
    }

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
