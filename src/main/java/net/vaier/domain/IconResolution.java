package net.vaier.domain;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure rules for resolving a service's icon: parse the HTML, rank link candidates, pick
 * fallback CDN URLs, and classify what bytes are (and aren't) an image. Has no I/O; an adapter
 * is responsible for the actual HTTP fetches.
 */
public final class IconResolution {

    private static final Pattern LINK_TAG =
        Pattern.compile("<link([^>]+)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern REL_ATTR =
        Pattern.compile("rel=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF_ATTR =
        Pattern.compile("href=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPE_ATTR =
        Pattern.compile("type=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static final Pattern ASSET_HOST =
        Pattern.compile("(?:src|href)=[\"'](?:https?:)?//([A-Za-z0-9.-]+)", Pattern.CASE_INSENSITIVE);
    /** Hosts that serve anybody's assets, so they say nothing about which product a page is. */
    private static final Set<String> GENERIC_VENDORS = Set.of(
        "jsdelivr", "unpkg", "cloudflare", "cdnjs", "googleapis", "gstatic", "google", "googletagmanager",
        "jquery", "bootstrapcdn", "fontawesome", "typekit", "polyfill", "cloudfront", "amazonaws", "azureedge",
        "akamaihd", "akamaized", "fastly", "github", "githubusercontent", "gravatar", "w3", "schema");
    private static final Set<String> SECOND_LEVEL = Set.of("co", "com", "org", "net", "ac", "gov", "edu");

    private IconResolution() {}

    /**
     * The canonical identity under which a resolved icon is cached — both in memory and on disk —
     * so the two never drift. Path-routed services that share a hostname get distinct keys via
     * their {@code pathPrefix}; a null and an empty prefix collapse to the host alone.
     */
    public static String cacheKey(String host, String pathPrefix) {
        return host + (pathPrefix == null ? "" : pathPrefix);
    }

    /**
     * Parse {@code <link rel="...icon...">} entries out of {@code html} and pick the best
     * candidate URL — preferring entries whose {@code type} declares PNG/SVG/WebP over .ico
     * (smaller and crisper on the launchpad tiles). Resolves relative, absolute, and
     * protocol-relative hrefs against {@code baseUrl}.
     */
    public static Optional<String> extractIconUrl(String html, String baseUrl) {
        List<String> candidates = new ArrayList<>();

        Matcher linkMatcher = LINK_TAG.matcher(html);
        while (linkMatcher.find()) {
            String attrs = linkMatcher.group(1);
            Matcher relMatcher = REL_ATTR.matcher(attrs);
            if (!relMatcher.find()) continue;

            String rel = relMatcher.group(1).toLowerCase();
            if (!rel.contains("icon")) continue;

            Matcher hrefMatcher = HREF_ATTR.matcher(attrs);
            if (!hrefMatcher.find()) continue;

            String href = hrefMatcher.group(1).trim();
            if (href.isEmpty() || href.startsWith("data:")) continue;

            String resolved = resolveUrl(href, baseUrl);

            Matcher typeMatcher = TYPE_ATTR.matcher(attrs);
            if (typeMatcher.find()) {
                String type = typeMatcher.group(1).toLowerCase();
                if (type.contains("png") || type.contains("svg") || type.contains("webp")) {
                    candidates.add(0, resolved);
                    continue;
                }
            }
            candidates.add(resolved);
        }

        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
    }

    /**
     * The lookup name used against external icon CDNs (dashboard-icons, simpleicons). Prefers
     * the deepest path-prefix segment when present — for {@code services.example.com/team/grafana}
     * the CDN should look up "grafana", not "services". Falls back to the first DNS label when
     * the path prefix is null/empty. Always lowercase, since the CDN URLs are case-sensitive.
     */
    public static String cdnLookupName(String host, String pathPrefix) {
        if (pathPrefix != null && !pathPrefix.isEmpty()) {
            String trimmed = pathPrefix.replaceAll("^/+", "").replaceAll("/+$", "");
            if (!trimmed.isEmpty()) {
                String[] segments = trimmed.split("/");
                return segments[segments.length - 1].toLowerCase();
            }
        }
        return host.split("\\.")[0].toLowerCase();
    }

    /**
     * The names to look up on the icon CDNs, in order: the service's own (see {@link #cdnLookupName}), then the
     * vendor of any host the page loads its assets from — a device whose page only pulls its UI from
     * {@code ui.opensprinkler.com} is an OpenSprinkler, whatever it was published as.
     */
    public static List<String> cdnLookupNames(String host, String pathPrefix, String html) {
        Set<String> names = new LinkedHashSet<>();
        names.add(cdnLookupName(host, pathPrefix));
        if (html != null) {
            String own = vendorOf(host.toLowerCase());
            Matcher m = ASSET_HOST.matcher(html);
            while (m.find()) {
                String vendor = vendorOf(m.group(1).toLowerCase());
                if (vendor != null && !vendor.equals(own) && !GENERIC_VENDORS.contains(vendor)) names.add(vendor);
            }
        }
        return List.copyOf(names);
    }

    /** The registrable name of a host — {@code opensprinkler} for {@code ui.opensprinkler.com}. */
    private static String vendorOf(String host) {
        if (host.matches("[0-9.]+")) return null;
        String[] labels = host.split("\\.");
        if (labels.length < 2) return null;
        int i = labels.length - 2;
        if (labels.length >= 3 && SECOND_LEVEL.contains(labels[i])) i--;
        return labels[i];
    }

    /** The external icon-CDN URLs to try, in fallback order, for a given service name. */
    public static List<String> internetIconUrls(String serviceName) {
        String name = serviceName.toLowerCase();
        return List.of(
            "https://cdn.jsdelivr.net/gh/walkxcode/dashboard-icons@main/png/" + name + ".png",
            "https://cdn.simpleicons.org/" + name
        );
    }

    /**
     * True when {@code body} is recognisably an image — either by the {@code contentType} header
     * starting with {@code image/}, or by a known magic-byte signature (PNG, GIF, JPEG, ICO, SVG).
     * Used to reject HTML error pages that a remote server returned with a 200 instead of a 404.
     */
    public static boolean looksLikeImage(String contentType, byte[] body) {
        if (contentType != null && contentType.startsWith("image/")) return true;
        if (body == null) return false;
        if (body.length >= 4 && (body[0] & 0xFF) == 0x89 && body[1] == 'P' && body[2] == 'N' && body[3] == 'G') return true;
        if (body.length >= 3 && body[0] == 'G' && body[1] == 'I' && body[2] == 'F') return true;
        if (body.length >= 2 && (body[0] & 0xFF) == 0xFF && (body[1] & 0xFF) == 0xD8) return true;
        if (body.length >= 4 && body[0] == 0 && body[1] == 0 && body[2] == 1 && body[3] == 0) return true;
        if (body.length > 4 && body[0] == '<') return true;
        return false;
    }

    /**
     * The MIME content-type to report to the browser for {@code body}, deduced from its magic
     * bytes. We don't trust the upstream server's content-type because dashboard-icons sometimes
     * serves PNGs with no/incorrect type; the bytes are authoritative.
     */
    public static String contentType(byte[] body) {
        if (body.length >= 4 && body[1] == 'P' && body[2] == 'N' && body[3] == 'G') return "image/png";
        if (body.length >= 3 && body[0] == 'G' && body[1] == 'I' && body[2] == 'F') return "image/gif";
        if (body.length >= 2 && (body[0] & 0xFF) == 0xFF && (body[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (body.length >= 4 && body[0] == '<') return "image/svg+xml";
        return "image/x-icon";
    }

    private static String resolveUrl(String href, String baseUrl) {
        if (href.startsWith("http://") || href.startsWith("https://")) return href;
        if (href.startsWith("//")) return "https:" + href;
        if (href.startsWith("/")) return URI.create(baseUrl).resolve(href).toString();
        return baseUrl + "/" + href;
    }
}
