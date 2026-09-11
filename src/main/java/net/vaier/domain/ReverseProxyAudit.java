package net.vaier.domain;

import net.vaier.config.ServiceNames;
import net.vaier.domain.ReverseProxyConfig.ConfiguredMiddleware;
import net.vaier.domain.ReverseProxyConfig.ConfiguredRouter;
import net.vaier.domain.ReverseProxyConfig.ConfiguredService;
import net.vaier.domain.ReverseProxyConfig.Protocol;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * <b>Vaier judging its own reverse proxy config.</b> Every other check Vaier makes is about the world outside —
 * is the host up, is the disk filling, is a newer image being served. None of them asks <em>is the config I
 * wrote still coherent?</em>, so a defect in a write path stayed invisible until an operator opened the one
 * URL that happened to break (#354): four orphaned redirect middlewares, three of them loops, left behind
 * by unpublished services and sitting in the file for an unknown number of weeks.
 *
 * <p>Five invariants, all of them one-liners over a file that is parsed on every read anyway — see
 * {@link ReverseProxyFinding.Kind}. A clean config yields no finding at all, which is not a detail: a check
 * that reports something every sweep is a heartbeat, and this project does not send those.
 *
 * <p><b>It reports; it never repairs.</b> Deleting a middleware Vaier did not write is a destructive act on
 * the operator's file, and a hand-added entry referenced from somewhere Vaier cannot see would be
 * collateral. So every finding is a sentence, and the file is left exactly as it was found.
 *
 * <p><b>What is deliberately not a finding.</b> Two classes of entry look broken from this file's point of
 * view and are not:
 * <ul>
 *   <li>a <em>provider-qualified</em> middleware reference ({@code vaier-frame-guard@file},
 *       {@code crowdsec-bouncer@file}, anything {@code @docker} or {@code @internal}) is declared outside
 *       this file by design — reporting it would make every router look broken; and</li>
 *   <li>the console's own chain ({@code oauth2-signin}, {@code oauth2-authn}, {@code vaier-authz},
 *       {@code vaier-errors} and the two services behind them), which Vaier keeps present at every startup
 *       whether or not anything is published — so a fleet with nothing published would otherwise report six
 *       findings on a perfectly healthy file.</li>
 * </ul>
 *
 * @param findings every defect found, in {@link ReverseProxyFinding.Kind} order and by entry name within it
 */
public record ReverseProxyAudit(List<ReverseProxyFinding> findings) {

    /** Middlewares Vaier keeps present for its own console, referenced or not. */
    private static final Set<String> CONSOLE_MIDDLEWARES = Set.of(
        ServiceNames.OAUTH2_SIGNIN_MIDDLEWARE,
        ServiceNames.OAUTH2_AUTHN_MIDDLEWARE,
        ServiceNames.VAIER_AUTHZ_MIDDLEWARE,
        ServiceNames.ERROR_PAGES_MIDDLEWARE);

    /** Services Vaier keeps present for its own console, reached from a middleware rather than a router. */
    private static final Set<String> CONSOLE_SERVICES = Set.of(
        ServiceNames.OAUTH2_PROXY_SERVICE,
        ServiceNames.ERROR_PAGES_SERVICE);

    public ReverseProxyAudit {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /** Judge {@code config} against all five invariants. */
    public static ReverseProxyAudit of(ReverseProxyConfig config) {
        List<ReverseProxyFinding> found = new ArrayList<>();
        found.addAll(unreferencedMiddlewares(config));
        found.addAll(danglingMiddlewareReferences(config));
        found.addAll(selfReferentialRedirects(config));
        found.addAll(routersWithoutService(config));
        found.addAll(unroutedServices(config));
        found.sort(Comparator.comparing((ReverseProxyFinding f) -> f.kind().ordinal())
            .thenComparing(ReverseProxyFinding::entryName));
        return new ReverseProxyAudit(found);
    }

    /** Nothing to say — the healthy state, and the one that paints nothing and mails nothing. */
    public boolean isClean() {
        return findings.isEmpty();
    }

    /**
     * The set of broken entries, order-independent. This is what decides whether a sweep is news: the same
     * entries broken the same way are not, a newly broken one is.
     */
    public Set<String> signature() {
        return findings.stream().map(ReverseProxyFinding::signature)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public String findingsSubject() {
        return "[Vaier] Reverse proxy config: " + countPhrase() + " no route can reach";
    }

    /**
     * The one line an operator reads above the findings themselves — how many entries, and the fact that
     * Vaier left the file alone. Said here rather than assembled by whatever is drawing it, so the console
     * and the email cannot end up phrasing the same policy two different ways. Empty for a clean config:
     * there is no summary of nothing, and the surface draws nothing at all for it.
     */
    public String summary() {
        if (isClean()) {
            return "";
        }
        return countPhrase() + " in Vaier's reverse proxy config " + (findings.size() == 1 ? "is" : "are")
            + " not reachable by any route. Vaier changed nothing — removing an entry is yours to decide.";
    }

    public String findingsBody(String domain) {
        StringBuilder body = new StringBuilder();
        body.append("Vaier read back the reverse proxy config it writes itself (remote-apps.yml) and found ")
            .append(countPhrase()).append(":\n\n");
        for (ReverseProxyFinding finding : findings) {
            body.append("  - ").append(finding.message()).append('\n');
        }
        body.append("\nVaier changed nothing. Removing an entry it may not have written is the operator's "
            + "call, not Vaier's — a middleware added by hand could be referenced from somewhere Vaier "
            + "cannot see.\n\n");
        body.append("Settings, at ").append(consoleUrl(domain)).append(", lists the same findings.\n");
        return body.toString();
    }

    public String recoverySubject() {
        return "[Vaier] Reverse proxy config is clear again";
    }

    public String recoveryBody(String domain) {
        return "Vaier read back the reverse proxy config it writes itself (remote-apps.yml) and everything it "
            + "declares is reachable again. Nothing more to do.\n\n"
            + "Settings, at " + consoleUrl(domain) + ", says the same.\n";
    }

    private String countPhrase() {
        return findings.size() + (findings.size() == 1 ? " entry" : " entries");
    }

    private static String consoleUrl(String domain) {
        return "https://" + ServiceNames.VAIER + "." + (domain == null ? "" : domain);
    }

    private static List<ReverseProxyFinding> unreferencedMiddlewares(ReverseProxyConfig config) {
        Set<String> referenced = config.routers().stream()
            .flatMap(router -> router.middlewareNames().stream().map(name -> key(router.protocol(), name)))
            .collect(Collectors.toSet());
        return config.middlewares().stream()
            .filter(middleware -> !CONSOLE_MIDDLEWARES.contains(middleware.name()))
            .filter(middleware -> !referenced.contains(key(middleware.protocol(), middleware.name())))
            .map(middleware -> new ReverseProxyFinding(
                ReverseProxyFinding.Kind.UNREFERENCED_MIDDLEWARE, middleware.name(),
                "The " + middleware.protocol().name() + " middleware \"" + middleware.name()
                    + "\" is defined but no router references it — most likely left behind by a service "
                    + "that was unpublished."))
            .toList();
    }

    private static List<ReverseProxyFinding> danglingMiddlewareReferences(ReverseProxyConfig config) {
        Set<String> defined = config.middlewares().stream()
            .map(middleware -> key(middleware.protocol(), middleware.name()))
            .collect(Collectors.toSet());
        List<ReverseProxyFinding> found = new ArrayList<>();
        for (ConfiguredRouter router : config.routers()) {
            for (String reference : router.middlewareNames()) {
                if (declaredElsewhere(reference) || defined.contains(key(router.protocol(), reference))) {
                    continue;
                }
                found.add(new ReverseProxyFinding(
                    ReverseProxyFinding.Kind.DANGLING_MIDDLEWARE_REFERENCE, router.name(),
                    "The " + router.protocol().name() + " router \"" + router.name()
                        + "\" references the middleware \"" + reference
                        + "\", which is not defined — Traefik refuses the route outright."));
            }
        }
        return found;
    }

    /**
     * A {@code redirectRegex} whose replacement its own pattern matches: the request comes back, matches
     * again, and redirects again. {@code find} rather than a full match, to mirror Go's {@code MatchString}
     * — Traefik's own semantics — and an unparseable pattern is left alone rather than guessed at.
     */
    private static List<ReverseProxyFinding> selfReferentialRedirects(ReverseProxyConfig config) {
        List<ReverseProxyFinding> found = new ArrayList<>();
        for (ConfiguredMiddleware middleware : config.middlewares()) {
            if (middleware.redirectRegex() == null || middleware.redirectReplacement() == null) {
                continue;
            }
            try {
                if (!Pattern.compile(middleware.redirectRegex())
                        .matcher(middleware.redirectReplacement()).find()) {
                    continue;
                }
            } catch (PatternSyntaxException e) {
                continue;
            }
            found.add(new ReverseProxyFinding(
                ReverseProxyFinding.Kind.SELF_REFERENTIAL_REDIRECT, middleware.name(),
                "The " + middleware.protocol().name() + " middleware \"" + middleware.name()
                    + "\" redirects to \"" + middleware.redirectReplacement()
                    + "\", which its own pattern matches — a request there redirects forever."));
        }
        return found;
    }

    private static List<ReverseProxyFinding> routersWithoutService(ReverseProxyConfig config) {
        Set<String> defined = config.services().stream()
            .map(service -> key(service.protocol(), service.name()))
            .collect(Collectors.toSet());
        List<ReverseProxyFinding> found = new ArrayList<>();
        for (ConfiguredRouter router : config.routers()) {
            String service = router.serviceName();
            if (service != null && !service.isBlank()
                    && defined.contains(key(router.protocol(), service))) {
                continue;
            }
            String what = service == null || service.isBlank()
                ? "\" names no service at all — nothing answers for it."
                : "\" points at the service \"" + service + "\", which is not defined — nothing answers "
                    + "for it.";
            found.add(new ReverseProxyFinding(
                ReverseProxyFinding.Kind.ROUTER_WITHOUT_SERVICE, router.name(),
                "The " + router.protocol().name() + " router \"" + router.name() + what));
        }
        return found;
    }

    /**
     * A service nothing sends to. A router is the usual way in, but an {@code errors} middleware is a real
     * reference too — {@code vaier-error-pages} has no router of its own and is reached only that way.
     */
    private static List<ReverseProxyFinding> unroutedServices(ReverseProxyConfig config) {
        Set<String> reached = new HashSet<>();
        for (ConfiguredRouter router : config.routers()) {
            if (router.serviceName() != null) {
                reached.add(key(router.protocol(), router.serviceName()));
            }
        }
        for (ConfiguredMiddleware middleware : config.middlewares()) {
            if (middleware.errorsServiceName() != null) {
                reached.add(key(middleware.protocol(), middleware.errorsServiceName()));
            }
        }
        List<ReverseProxyFinding> found = new ArrayList<>();
        for (ConfiguredService service : config.services()) {
            if (CONSOLE_SERVICES.contains(service.name())
                    || reached.contains(key(service.protocol(), service.name()))) {
                continue;
            }
            found.add(new ReverseProxyFinding(
                ReverseProxyFinding.Kind.UNROUTED_SERVICE, service.name(),
                "The " + service.protocol().name() + " service \"" + service.name()
                    + "\" is defined but no router and no middleware sends anything to it."));
        }
        return found;
    }

    /** A middleware declared by another Traefik provider — this file neither defines nor owns it. */
    private static boolean declaredElsewhere(String reference) {
        return reference != null && reference.contains("@");
    }

    private static String key(Protocol protocol, String name) {
        return protocol.name() + "/" + name;
    }
}
