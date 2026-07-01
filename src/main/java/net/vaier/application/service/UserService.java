package net.vaier.application.service;

import net.vaier.application.AddUserUseCase;
import net.vaier.application.AssignGroupsUseCase;
import net.vaier.application.DeleteGroupUseCase;
import net.vaier.application.DeleteUserUseCase;
import net.vaier.application.GetGroupsUseCase;
import net.vaier.application.GetUsersUseCase;
import net.vaier.application.GrantRoleUseCase;
import net.vaier.application.ListAccessEntriesUseCase;
import net.vaier.application.RevokeAccessUseCase;
import net.vaier.application.UpdateUserDisplayNameUseCase;
import net.vaier.application.UpdateUserEmailUseCase;
import net.vaier.application.UpdateUserGroupsUseCase;
import net.vaier.application.VerifyAccessUseCase;
import net.vaier.config.ConfigResolver;
import net.vaier.domain.AccessDecision;
import net.vaier.domain.AccessEntry;
import net.vaier.domain.Role;
import net.vaier.domain.User;
import net.vaier.domain.VaierHostnames;
import net.vaier.domain.port.ForGettingUsers;
import net.vaier.domain.port.ForNotifyingAdmins;
import net.vaier.domain.port.ForPersistingAccessEntries;
import net.vaier.domain.port.ForPersistingUsers;
import net.vaier.domain.port.ForResolvingServiceGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@Slf4j
public class UserService implements AddUserUseCase, DeleteUserUseCase,
        UpdateUserEmailUseCase, UpdateUserDisplayNameUseCase, GetUsersUseCase, ForGettingUsers,
        GetGroupsUseCase, UpdateUserGroupsUseCase, DeleteGroupUseCase,
        VerifyAccessUseCase, ListAccessEntriesUseCase, GrantRoleUseCase, AssignGroupsUseCase,
        RevokeAccessUseCase {

    private final ForPersistingUsers forPersistingUsers;
    private final ForPersistingAccessEntries forPersistingAccessEntries;
    private final ForResolvingServiceGroup forResolvingServiceGroup;
    private final ForNotifyingAdmins forNotifyingAdmins;
    private final ConfigResolver configResolver;

    public UserService(ForPersistingUsers forPersistingUsers,
                       ForPersistingAccessEntries forPersistingAccessEntries,
                       ForResolvingServiceGroup forResolvingServiceGroup,
                       // @Lazy breaks the construction-time cycle: NotificationService resolves admin
                       // recipients via ForGettingUsers (this service), and this service notifies via
                       // ForNotifyingAdmins (NotificationService). The lazy proxy is resolved on first
                       // notification, never on the forward-auth hot path's critical timing.
                       @Lazy ForNotifyingAdmins forNotifyingAdmins,
                       ConfigResolver configResolver) {
        this.forPersistingUsers = forPersistingUsers;
        this.forPersistingAccessEntries = forPersistingAccessEntries;
        this.forResolvingServiceGroup = forResolvingServiceGroup;
        this.forNotifyingAdmins = forNotifyingAdmins;
        this.configResolver = configResolver;
    }

    @Override
    public List<User> getUsers() {
        return forPersistingUsers.getUsers();
    }

    @Override
    public void addUser(String username, String password, String email, String displayname, List<String> groups) {
        User.validateUsername(username);
        User.validatePassword(password);
        User.validateEmail(email);
        List<String> normalised = normaliseGroups(groups);
        forPersistingUsers.addUser(username, password, email, displayname, normalised);
    }

    @Override
    public void deleteUser(String username) {
        User.validateUsername(username);
        forPersistingUsers.deleteUser(username);
    }

    @Override
    public void updateEmail(String username, String email) {
        User.validateUsername(username);
        User.validateEmail(email);
        forPersistingUsers.updateEmail(username, email);
    }

    @Override
    public void updateDisplayName(String username, String displayname) {
        User.validateUsername(username);
        User.validateDisplayname(displayname);
        forPersistingUsers.updateDisplayName(username, displayname);
    }

    @Override
    public List<String> getGroups() {
        return forPersistingUsers.getUsers().stream()
                .flatMap(u -> u.getGroups() == null ? java.util.stream.Stream.<String>empty() : u.getGroups().stream())
                .filter(g -> g != null && !g.isBlank())
                .map(String::trim)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    @Override
    public void updateUserGroups(String username, List<String> groups) {
        User.validateUsername(username);
        List<String> normalised = normaliseGroups(groups);
        forPersistingUsers.setUserGroups(username, normalised);
    }

    @Override
    public void deleteGroup(String groupName) {
        User.validateGroupName(groupName);
        for (User user : forPersistingUsers.getUsers()) {
            List<String> existing = user.getGroups();
            if (existing == null || !existing.contains(groupName)) {
                continue;
            }
            List<String> remaining = existing.stream()
                    .filter(g -> !groupName.equals(g))
                    .toList();
            forPersistingUsers.setUserGroups(user.getName(), remaining);
        }
    }

    // === Social-login authorization (AccessEntry domain) ===

    @Override
    public AccessDecision verify(String email, String host, String name) {
        if (email == null || email.isBlank()) {
            return AccessDecision.deny();
        }
        String normalised = normaliseEmail(email);

        Optional<AccessEntry> existing = forPersistingAccessEntries.findByEmail(normalised);
        if (existing.isEmpty()) {
            // First sighting of a Google identity: record it as pending so it surfaces for the
            // admin to action — capturing the display name it presented — then deny ("awaiting
            // approval").
            AccessEntry pending = AccessEntry.builder()
                    .email(normalised).role(Role.PENDING).groups(List.of()).build();
            forPersistingAccessEntries.upsert(pending.toBuilder().name(pending.resolvedName(name)).build());
            notifyAdminsOfNewPendingIdentity(normalised);
            return AccessDecision.deny();
        }

        AccessEntry entry = refreshName(existing.get(), name);
        if (entry.isPending()) {
            return AccessDecision.deny();
        }

        boolean allowed;
        if (isConsoleHost(host)) {
            allowed = entry.mayAccessConsole();
        } else {
            String requiredGroup = forResolvingServiceGroup.requiredGroupForHost(host).orElse(null);
            allowed = entry.mayAccessService(requiredGroup);
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
     * Persist a refreshed display name when this sign-in brought a new one. The "what name should
     * this entry carry" decision lives on {@link AccessEntry#resolvedName}; the service only writes
     * the result through the port when it actually changed — so a blank/absent header never causes
     * a wiping write.
     */
    private AccessEntry refreshName(AccessEntry entry, String incomingName) {
        String resolved = entry.resolvedName(incomingName);
        if (java.util.Objects.equals(resolved, entry.getName())) {
            return entry;
        }
        AccessEntry updated = entry.toBuilder().name(resolved).build();
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
    public void grantRole(String email, Role role) {
        validateEmail(email);
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        String normalised = normaliseEmail(email);
        Optional<AccessEntry> current = forPersistingAccessEntries.findByEmail(normalised);
        List<String> groups = current.map(AccessEntry::getGroups).orElse(List.of());
        forPersistingAccessEntries.upsert(AccessEntry.builder()
                .email(normalised).role(role).groups(groups != null ? groups : List.of())
                .name(current.map(AccessEntry::getName).orElse(null)).build());
    }

    @Override
    public void assignGroups(String email, List<String> groups) {
        validateEmail(email);
        String normalised = normaliseEmail(email);
        Optional<AccessEntry> current = forPersistingAccessEntries.findByEmail(normalised);
        Role role = current.map(AccessEntry::getRole).orElse(Role.PENDING);
        forPersistingAccessEntries.upsert(AccessEntry.builder()
                .email(normalised).role(role).groups(normaliseGroups(groups))
                .name(current.map(AccessEntry::getName).orElse(null)).build());
    }

    @Override
    public void revokeAccess(String email) {
        validateEmail(email);
        forPersistingAccessEntries.delete(normaliseEmail(email));
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
