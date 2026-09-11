package net.vaier.domain;

import net.vaier.domain.ReverseProxyConfig.ConfiguredMiddleware;
import net.vaier.domain.ReverseProxyConfig.ConfiguredRouter;
import net.vaier.domain.ReverseProxyConfig.ConfiguredService;
import net.vaier.domain.ReverseProxyConfig.Protocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The judgement #354 asked for: Vaier reading back the reverse proxy config it writes itself and naming what no
 * route can reach. Each invariant gets its own defect here, and — the one that matters most — a healthy
 * config gets a test of its own, because a check that cries wolf is worse than no check.
 */
class ReverseProxyAuditTest {

    private static ConfiguredRouter router(String name, String service, String... middlewares) {
        return ConfiguredRouter.builder()
            .protocol(Protocol.HTTP).name(name).serviceName(service)
            .middlewareNames(List.of(middlewares)).build();
    }

    private static ConfiguredService service(String name) {
        return new ConfiguredService(Protocol.HTTP, name);
    }

    private static ConfiguredMiddleware redirect(String name, String regex, String replacement) {
        return ConfiguredMiddleware.builder()
            .protocol(Protocol.HTTP).name(name).redirectRegex(regex).redirectReplacement(replacement).build();
    }

    private static ConfiguredMiddleware plain(String name) {
        return ConfiguredMiddleware.builder().protocol(Protocol.HTTP).name(name).build();
    }

    @Test
    void aHealthyConfigReportsNothingAtAll() {
        ReverseProxyConfig config = ReverseProxyConfig.builder()
            .routers(List.of(router("a-router", "a-service", "a-redirect")))
            .services(List.of(service("a-service")))
            .middlewares(List.of(redirect("a-redirect", "^https://a\\.example\\.com/?$",
                "https://a.example.com/admin")))
            .build();

        ReverseProxyAudit audit = ReverseProxyAudit.of(config);

        assertThat(audit.findings()).isEmpty();
        assertThat(audit.isClean()).isTrue();
    }

    @Test
    void anEmptyConfigReportsNothing() {
        assertThat(ReverseProxyAudit.of(ReverseProxyConfig.empty()).findings()).isEmpty();
    }

    @Test
    void namesAMiddlewareNoRouterReferences() {
        ReverseProxyConfig config = ReverseProxyConfig.builder()
            .routers(List.of(router("a-router", "a-service")))
            .services(List.of(service("a-service")))
            .middlewares(List.of(plain("orphaned-redirect")))
            .build();

        List<ReverseProxyFinding> findings = ReverseProxyAudit.of(config).findings();

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).kind()).isEqualTo(ReverseProxyFinding.Kind.UNREFERENCED_MIDDLEWARE);
        assertThat(findings.get(0).entryName()).isEqualTo("orphaned-redirect");
        assertThat(findings.get(0).message()).contains("orphaned-redirect").contains("no router");
    }

    @Test
    void namesARouterPointingAtAMiddlewareThatDoesNotExist() {
        ReverseProxyConfig config = ReverseProxyConfig.builder()
            .routers(List.of(router("a-router", "a-service", "gone-redirect")))
            .services(List.of(service("a-service")))
            .middlewares(List.of())
            .build();

        List<ReverseProxyFinding> findings = ReverseProxyAudit.of(config).findings();

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).kind())
            .isEqualTo(ReverseProxyFinding.Kind.DANGLING_MIDDLEWARE_REFERENCE);
        assertThat(findings.get(0).entryName()).isEqualTo("a-router");
        assertThat(findings.get(0).message()).contains("gone-redirect");
    }

    @Test
    void namesARedirectWhoseReplacementItsOwnPatternMatches() {
        // The 4c21bba bug, in one line: bare host in, bare host out, forever.
        ReverseProxyConfig config = ReverseProxyConfig.builder()
            .routers(List.of(router("a-router", "a-service", "a-redirect")))
            .services(List.of(service("a-service")))
            .middlewares(List.of(redirect("a-redirect", "^https://a\\.example\\.com/?$",
                "https://a.example.com/")))
            .build();

        List<ReverseProxyFinding> findings = ReverseProxyAudit.of(config).findings();

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).kind())
            .isEqualTo(ReverseProxyFinding.Kind.SELF_REFERENTIAL_REDIRECT);
        assertThat(findings.get(0).entryName()).isEqualTo("a-redirect");
        assertThat(findings.get(0).message()).contains("redirects forever");
    }

    @Test
    void anUnparseableRedirectPatternIsNotReportedAsALoop() {
        ReverseProxyConfig config = ReverseProxyConfig.builder()
            .routers(List.of(router("a-router", "a-service", "a-redirect")))
            .services(List.of(service("a-service")))
            .middlewares(List.of(redirect("a-redirect", "^https://a[", "https://a.example.com/")))
            .build();

        assertThat(ReverseProxyAudit.of(config).findings()).isEmpty();
    }

    @Test
    void namesARouterWhoseServiceDoesNotExist() {
        ReverseProxyConfig config = ReverseProxyConfig.builder()
            .routers(List.of(router("a-router", "gone-service")))
            .services(List.of())
            .middlewares(List.of())
            .build();

        List<ReverseProxyFinding> findings = ReverseProxyAudit.of(config).findings();

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).kind()).isEqualTo(ReverseProxyFinding.Kind.ROUTER_WITHOUT_SERVICE);
        assertThat(findings.get(0).entryName()).isEqualTo("a-router");
        assertThat(findings.get(0).message()).contains("gone-service");
    }

    @Test
    void namesARouterThatNamesNoServiceAtAll() {
        ReverseProxyConfig config = ReverseProxyConfig.builder()
            .routers(List.of(router("a-router", null)))
            .services(List.of())
            .middlewares(List.of())
            .build();

        List<ReverseProxyFinding> findings = ReverseProxyAudit.of(config).findings();

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).kind()).isEqualTo(ReverseProxyFinding.Kind.ROUTER_WITHOUT_SERVICE);
        assertThat(findings.get(0).message()).contains("names no service");
    }

    @Test
    void namesAServiceNothingRoutesTo() {
        ReverseProxyConfig config = ReverseProxyConfig.builder()
            .routers(List.of(router("a-router", "a-service")))
            .services(List.of(service("a-service"), service("stranded-service")))
            .middlewares(List.of())
            .build();

        List<ReverseProxyFinding> findings = ReverseProxyAudit.of(config).findings();

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).kind()).isEqualTo(ReverseProxyFinding.Kind.UNROUTED_SERVICE);
        assertThat(findings.get(0).entryName()).isEqualTo("stranded-service");
    }

    @Test
    void aServiceAnErrorMiddlewareSendsToIsRoutedTo() {
        // vaier-error-pages has no router of its own; the vaier-errors middleware is what reaches it.
        ReverseProxyConfig config = ReverseProxyConfig.builder()
            .routers(List.of(router("a-router", "a-service", "an-errors-middleware")))
            .services(List.of(service("a-service"), service("an-error-page-service")))
            .middlewares(List.of(ConfiguredMiddleware.builder()
                .protocol(Protocol.HTTP).name("an-errors-middleware")
                .errorsServiceName("an-error-page-service").build()))
            .build();

        assertThat(ReverseProxyAudit.of(config).findings()).isEmpty();
    }

    @Test
    void aProviderQualifiedMiddlewareReferenceIsNotDangling() {
        // crowdsec-bouncer@file and vaier-frame-guard@file are declared elsewhere by design — they are not
        // this file's to define, and reporting them would make every router look broken.
        ReverseProxyConfig config = ReverseProxyConfig.builder()
            .routers(List.of(router("a-router", "a-service", "vaier-frame-guard@file", "crowdsec@docker")))
            .services(List.of(service("a-service")))
            .middlewares(List.of())
            .build();

        assertThat(ReverseProxyAudit.of(config).findings()).isEmpty();
    }

    @Test
    void vaiersOwnConsoleEntriesAreNeverReportedAsOrphans() {
        // Vaier keeps the console's own chain present at every startup whether or not anything is published,
        // so an unpublished fleet would otherwise report four findings on a perfectly healthy file.
        ReverseProxyConfig config = ReverseProxyConfig.builder()
            .routers(List.of())
            .services(List.of(service("oauth2-proxy-svc"), service("vaier-error-pages")))
            .middlewares(List.of(plain("oauth2-signin"), plain("oauth2-authn"), plain("vaier-authz"),
                plain("vaier-errors")))
            .build();

        assertThat(ReverseProxyAudit.of(config).findings()).isEmpty();
    }

    @Test
    void aTcpRouterIsJudgedAgainstTcpServicesOnly() {
        ReverseProxyConfig config = ReverseProxyConfig.builder()
            .routers(List.of(ConfiguredRouter.builder()
                .protocol(Protocol.TCP).name("a-stream-router").serviceName("a-service")
                .middlewareNames(List.of()).build()))
            .services(List.of(service("a-service")))
            .middlewares(List.of())
            .build();

        List<ReverseProxyFinding> findings = ReverseProxyAudit.of(config).findings();

        assertThat(findings).extracting(ReverseProxyFinding::kind)
            .containsExactlyInAnyOrder(ReverseProxyFinding.Kind.ROUTER_WITHOUT_SERVICE,
                ReverseProxyFinding.Kind.UNROUTED_SERVICE);
        assertThat(findings).anyMatch(f -> f.message().contains("TCP"));
    }

    @Test
    void findingsAreReportedInAStableOrder() {
        ReverseProxyConfig config = ReverseProxyConfig.builder()
            .routers(List.of(router("z-router", "gone-service"), router("a-router", "gone-service")))
            .services(List.of())
            .middlewares(List.of(plain("z-orphan"), plain("a-orphan")))
            .build();

        assertThat(ReverseProxyAudit.of(config).findings()).extracting(ReverseProxyFinding::entryName)
            .containsExactly("a-orphan", "z-orphan", "a-router", "z-router");
    }

    @Test
    void theSignatureIsTheSetOfBrokenEntriesAndIgnoresTheirOrder() {
        ReverseProxyConfig one = ReverseProxyConfig.builder()
            .routers(List.of()).services(List.of())
            .middlewares(List.of(plain("a-orphan"), plain("b-orphan"))).build();
        ReverseProxyConfig other = ReverseProxyConfig.builder()
            .routers(List.of()).services(List.of())
            .middlewares(List.of(plain("b-orphan"), plain("a-orphan"))).build();

        assertThat(ReverseProxyAudit.of(one).signature())
            .isEqualTo(ReverseProxyAudit.of(other).signature());
        assertThat(ReverseProxyAudit.of(ReverseProxyConfig.empty()).signature()).isEmpty();
    }

    @Test
    void theEmailSaysWhatIsWrongAndThatVaierChangedNothing() {
        ReverseProxyConfig config = ReverseProxyConfig.builder()
            .routers(List.of()).services(List.of())
            .middlewares(List.of(plain("orphaned-redirect"))).build();
        ReverseProxyAudit audit = ReverseProxyAudit.of(config);

        assertThat(audit.findingsSubject()).startsWith("[Vaier] ").contains("1 entry");
        assertThat(audit.findingsBody("example.com"))
            .contains("orphaned-redirect")
            .contains("changed nothing")
            .contains("example.com");
        assertThat(ReverseProxyAudit.of(ReverseProxyConfig.empty()).recoverySubject())
            .containsIgnoringCase("clear");
    }

    @Test
    void theLeadSentenceIsTheDomainsOwnWords() {
        // Said once, here, so the email and Settings can never phrase the same policy two ways.
        ReverseProxyConfig config = ReverseProxyConfig.builder()
            .routers(List.of()).services(List.of())
            .middlewares(List.of(plain("a-orphan"), plain("b-orphan"))).build();

        assertThat(ReverseProxyAudit.of(config).summary())
            .startsWith("2 entries").contains("are not reachable")
            .contains("Vaier changed nothing");
        assertThat(ReverseProxyAudit.of(ReverseProxyConfig.builder()
            .middlewares(List.of(plain("a-orphan"))).build()).summary())
            .startsWith("1 entry").contains("is not reachable");
    }

    @Test
    void aCleanConfigHasNoSummaryToGive() {
        assertThat(ReverseProxyAudit.of(ReverseProxyConfig.empty()).summary()).isEmpty();
    }
}
