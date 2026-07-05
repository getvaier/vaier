package net.vaier.application.service;

import net.vaier.application.AssignGroupsUseCase;
import net.vaier.application.CaptureViewerIdentityUseCase;
import net.vaier.application.GetServiceAccessRulesUseCase;
import net.vaier.application.GrantRoleUseCase;
import net.vaier.application.ListAccessEntriesUseCase;
import net.vaier.application.ResolveViewerUseCase;
import net.vaier.application.RevokeAccessUseCase;
import net.vaier.application.SetServiceAccessRuleUseCase;
import net.vaier.application.VerifyAccessUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.AccessDecision;
import net.vaier.domain.AccessEntry;
import net.vaier.domain.AccessRoster;
import net.vaier.domain.LastAdminException;
import net.vaier.domain.Role;
import net.vaier.domain.VaierHostnames;
import net.vaier.domain.port.ForNotifyingAdmins;
import net.vaier.domain.port.ForPersistingAccessEntries;
import net.vaier.domain.port.ForPersistingServiceAccessRules;
import net.vaier.domain.port.ForResolvingServiceGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class UserService implements
        VerifyAccessUseCase, ListAccessEntriesUseCase, GrantRoleUseCase, AssignGroupsUseCase,
        RevokeAccessUseCase, SetServiceAccessRuleUseCase, GetServiceAccessRulesUseCase,
        ResolveViewerUseCase, CaptureViewerIdentityUseCase {

    private final ForPersistingAccessEntries forPersistingAccessEntries;
    private final ForResolvingServiceGroup forResolvingServiceGroup;
    private final ForPersistingServiceAccessRules forPersistingServiceAccessRules;
    private final ForNotifyingAdmins forNotifyingAdmins;
    private final ConfigResolver configResolver;

    public UserService(ForPersistingAccessEntries forPersistingAccessEntries,
                       ForResolvingServiceGroup forResolvingServiceGroup,
                       ForPersistingServiceAccessRules forPersistingServiceAccessRules,
                       // @Lazy defers resolving NotificationService until the first notification, so
                       // it never lands on the forward-auth hot path's critical construction timing.
                       @Lazy ForNotifyingAdmins forNotifyingAdmins,
                       ConfigResolver configResolver) {
        this.forPersistingAccessEntries = forPersistingAccessEntries;
        this.forResolvingServiceGroup = forResolvingServiceGroup;
        this.forPersistingServiceAccessRules = forPersistingServiceAccessRules;
        this.forNotifyingAdmins = forNotifyingAdmins;
        this.configResolver = configResolver;
    }

    // === Social-login authorization (AccessEntry domain) ===

    @Override
    public AccessDecision verify(String email, String host, String name, String provider, String providerUserId) {
        if (email == null || email.isBlank()) {
            return AccessDecision.deny();
        }
        String normalised = normaliseEmail(email);

        Optional<AccessEntry> existing = forPersistingAccessEntries.findByEmail(normalised);
        if (existing.isEmpty()) {
            // First sighting of a social identity: record it as pending so it surfaces for the
            // admin to action — capturing the display name, provider, and provider user id it
            // presented — then deny ("awaiting approval").
            AccessEntry pending = AccessEntry.builder()
                    .email(normalised).role(Role.PENDING).groups(List.of()).build();
            forPersistingAccessEntries.upsert(pending.toBuilder()
                    .name(pending.resolvedName(name))
                    .provider(pending.resolvedProvider(provider))
                    .providerUserId(pending.resolvedProviderUserId(providerUserId))
                    .build());
            notifyAdminsOfNewPendingIdentity(normalised);
            return AccessDecision.deny();
        }

        AccessEntry entry = refreshIdentity(existing.get(), name, provider, providerUserId);
        if (entry.isPending()) {
            return AccessDecision.deny();
        }

        boolean allowed;
        if (isConsoleHost(host)) {
            allowed = entry.mayAccessConsole();
        } else {
            // Access rules are keyed by host (matching the forward-auth X-Forwarded-Host). Path-scoped
            // services sharing a host therefore share one rule — acceptable for now (see docs).
            List<String> allowedGroups = forResolvingServiceGroup.allowedGroupsForHost(host);
            allowed = entry.mayAccessService(allowedGroups);
        }
        return allowed ? AccessDecision.allow(entry) : AccessDecision.deny();
    }

    /**
     * Fire-and-forget admin notification for a brand-new pending identity. {@code verify} runs on
     * the Traefik forward-auth path for every request to a social-gated service, so this must never
     * add latency to or throw into the access decision: the notifier send is {@code @Async}, and we
     * additionally swallow (log) any failure so a misbehaving notifier cannot break authorization.
     */
    /**
     * Persist a refreshed display name, last-used identity provider, and/or provider user id when
     * this sign-in brought new ones. The capture decisions live on {@link AccessEntry#resolvedName},
     * {@link AccessEntry#resolvedProvider} and {@link AccessEntry#resolvedProviderUserId}; the
     * service only writes through the port when something actually changed — so a blank/absent/unknown
     * header never causes a wiping write, and a single upsert covers all three fields.
     */
    private AccessEntry refreshIdentity(AccessEntry entry, String incomingName, String incomingProvider,
                                        String incomingProviderUserId) {
        String resolvedName = entry.resolvedName(incomingName);
        String resolvedProvider = entry.resolvedProvider(incomingProvider);
        String resolvedProviderUserId = entry.resolvedProviderUserId(incomingProviderUserId);
        if (java.util.Objects.equals(resolvedName, entry.getName())
                && java.util.Objects.equals(resolvedProvider, entry.getProvider())
                && java.util.Objects.equals(resolvedProviderUserId, entry.getProviderUserId())) {
            return entry;
        }
        AccessEntry updated = entry.toBuilder().name(resolvedName).provider(resolvedProvider)
                .providerUserId(resolvedProviderUserId).build();
        forPersistingAccessEntries.upsert(updated);
        return updated;
    }

    private void notifyAdminsOfNewPendingIdentity(String email) {
        try {
            forNotifyingAdmins.notifyNewPendingIdentity(email);
        } catch (Exception e) {
            log.warn("Failed to notify admins of new pending identity {}: {}", email, e.getMessage());
        }
    }

    private boolean isConsoleHost(String host) {
        String domain = configResolver.getDomain();
        if (domain == null || domain.isBlank() || host == null) {
            return false;
        }
        return new VaierHostnames(domain).vaierServerFqdn().equalsIgnoreCase(host);
    }

    @Override
    public List<AccessEntry> listAccessEntries() {
        return forPersistingAccessEntries.getEntries();
    }

    @Override
    public Optional<AccessEntry> resolveViewer(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return forPersistingAccessEntries.findByEmail(normaliseEmail(email));
    }

    @Override
    public Optional<AccessEntry> captureIdentity(String email, String name, String provider,
                                                 String providerUserId) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        // Refresh an existing viewer's captured identity from what this sign-in presented. First
        // sighting of an unknown identity stays on the /authz/verify path (it decides pending vs
        // approved and notifies admins) — here we only fill in the name/provider a known viewer
        // brought, so merely loading the launchpad keeps their admin card current.
        return forPersistingAccessEntries.findByEmail(normaliseEmail(email))
                .map(entry -> refreshIdentity(entry, name, provider, providerUserId));
    }

    @Override
    public void grantRole(String email, Role role) {
        validateEmail(email);
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        String normalised = normaliseEmail(email);
        if (role != Role.ADMIN
                && new AccessRoster(forPersistingAccessEntries.getEntries()).isOnlyAdmin(normalised)) {
            throw new LastAdminException(
                    "Cannot demote the last administrator — promote another admin first.");
        }
        Optional<AccessEntry> current = forPersistingAccessEntries.findByEmail(normalised);
        List<String> groups = current.map(AccessEntry::getGroups).orElse(List.of());
        forPersistingAccessEntries.upsert(AccessEntry.builder()
                .email(normalised).role(role).groups(groups != null ? groups : List.of())
                .name(current.map(AccessEntry::getName).orElse(null))
                .provider(current.map(AccessEntry::getProvider).orElse(null))
                .providerUserId(current.map(AccessEntry::getProviderUserId).orElse(null)).build());
    }

    @Override
    public void assignGroups(String email, List<String> groups) {
        validateEmail(email);
        String normalised = normaliseEmail(email);
        Optional<AccessEntry> current = forPersistingAccessEntries.findByEmail(normalised);
        Role role = current.map(AccessEntry::getRole).orElse(Role.PENDING);
        forPersistingAccessEntries.upsert(AccessEntry.builder()
                .email(normalised).role(role).groups(normaliseGroups(groups))
                .name(current.map(AccessEntry::getName).orElse(null))
                .provider(current.map(AccessEntry::getProvider).orElse(null))
                .providerUserId(current.map(AccessEntry::getProviderUserId).orElse(null)).build());
    }

    // === Per-service access rules (serviceGroups store) ===

    @Override
    public void setAllowedGroups(String host, List<String> groups) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        // Normalisation (trim/dedupe) and the "empty clears the rule" decision live in the adapter,
        // so the service passes the list straight through.
        forPersistingServiceAccessRules.setAllowedGroups(host, groups);
    }

    @Override
    public Map<String, List<String>> getServiceAccessRules() {
        return forPersistingServiceAccessRules.allServiceAccessRules();
    }

    @Override
    public void revokeAccess(String email) {
        validateEmail(email);
        String normalised = normaliseEmail(email);
        if (new AccessRoster(forPersistingAccessEntries.getEntries()).isOnlyAdmin(normalised)) {
            throw new LastAdminException(
                    "Cannot remove the last administrator — promote another admin first.");
        }
        forPersistingAccessEntries.delete(normalised);
    }

    private static void validateEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
    }

    private static String normaliseEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> normaliseGroups(List<String> groups) {
        if (groups == null) {
            return List.of();
        }
        return groups.stream()
                .filter(g -> g != null && !g.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
