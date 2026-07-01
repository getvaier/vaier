package net.vaier.adapter.driven;

import net.vaier.domain.AccessEntry;
import net.vaier.domain.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccessFileAdapterTest {

    @TempDir
    Path tempDir;

    private AccessFileAdapter adapter() {
        return new AccessFileAdapter(tempDir.toString(), null);
    }

    private static AccessEntry entry(String email, Role role, List<String> groups) {
        return AccessEntry.builder().email(email).role(role).groups(groups).build();
    }

    private static AccessEntry entry(String email, Role role, List<String> groups, String name) {
        return AccessEntry.builder().email(email).role(role).groups(groups).name(name).build();
    }

    // --- entries: empty / upsert / find / delete ---

    @Test
    void getEntries_emptyWhenFileDoesNotExist() {
        assertThat(adapter().getEntries()).isEmpty();
    }

    @Test
    void upsert_thenGetEntries_returnsTheEntry() {
        AccessFileAdapter a = adapter();
        a.upsert(entry("you@gmail.com", Role.ADMIN, List.of("admins")));

        assertThat(a.getEntries()).containsExactly(entry("you@gmail.com", Role.ADMIN, List.of("admins")));
    }

    @Test
    void upsert_existingEmail_replacesTheEntry() {
        AccessFileAdapter a = adapter();
        a.upsert(entry("p@gmail.com", Role.PENDING, List.of()));
        a.upsert(entry("p@gmail.com", Role.USER, List.of("family")));

        assertThat(a.getEntries()).containsExactly(entry("p@gmail.com", Role.USER, List.of("family")));
    }

    @Test
    void findByEmail_returnsMatch() {
        AccessFileAdapter a = adapter();
        a.upsert(entry("a@gmail.com", Role.USER, List.of("family")));

        assertThat(a.findByEmail("a@gmail.com")).contains(entry("a@gmail.com", Role.USER, List.of("family")));
    }

    @Test
    void findByEmail_emptyWhenUnknown() {
        assertThat(adapter().findByEmail("nobody@gmail.com")).isEmpty();
    }

    @Test
    void delete_removesTheEntry() {
        AccessFileAdapter a = adapter();
        a.upsert(entry("a@gmail.com", Role.USER, List.of()));
        a.upsert(entry("b@gmail.com", Role.ADMIN, List.of("admins")));

        a.delete("a@gmail.com");

        assertThat(a.getEntries()).containsExactly(entry("b@gmail.com", Role.ADMIN, List.of("admins")));
    }

    @Test
    void delete_unknownEmail_isNoOp() {
        AccessFileAdapter a = adapter();
        a.upsert(entry("a@gmail.com", Role.USER, List.of()));

        a.delete("does-not-exist@gmail.com");

        assertThat(a.getEntries()).hasSize(1);
    }

    // --- persistence / file schema ---

    @Test
    void upsert_writesRoleAsLowercaseTokenAndGroups() throws Exception {
        AccessFileAdapter a = adapter();
        a.upsert(entry("you@gmail.com", Role.ADMIN, List.of("admins")));

        Path file = tempDir.resolve("access.yml");
        assertThat(Files.exists(file)).isTrue();
        String contents = Files.readString(file);
        assertThat(contents)
                .contains("entries")
                .contains("you@gmail.com")
                .contains("role: admin")
                .contains("admins");
    }

    @Test
    void getEntries_roundTripsThroughFreshAdapter() {
        AccessFileAdapter a = adapter();
        a.upsert(entry("you@gmail.com", Role.ADMIN, List.of("admins")));
        a.upsert(entry("friend@gmail.com", Role.USER, List.of("family")));
        a.upsert(entry("new@gmail.com", Role.PENDING, List.of()));

        AccessFileAdapter fresh = new AccessFileAdapter(tempDir.toString(), null);

        assertThat(fresh.getEntries()).containsExactlyInAnyOrder(
                entry("you@gmail.com", Role.ADMIN, List.of("admins")),
                entry("friend@gmail.com", Role.USER, List.of("family")),
                entry("new@gmail.com", Role.PENDING, List.of()));
    }

    // --- display name persistence (back-compat: entries with no name read as null) ---

    @Test
    void upsert_persistsTheDisplayName() throws Exception {
        AccessFileAdapter a = adapter();
        a.upsert(entry("you@gmail.com", Role.ADMIN, List.of("admins"), "You Name"));

        String contents = Files.readString(tempDir.resolve("access.yml"));
        assertThat(contents).contains("name: You Name");
    }

    @Test
    void getEntries_roundTripsTheDisplayName() {
        AccessFileAdapter a = adapter();
        a.upsert(entry("you@gmail.com", Role.ADMIN, List.of("admins"), "You Name"));

        AccessFileAdapter fresh = new AccessFileAdapter(tempDir.toString(), null);

        assertThat(fresh.findByEmail("you@gmail.com"))
                .map(AccessEntry::getName).contains("You Name");
    }

    @Test
    void getEntries_entryWithoutNameReadsAsNullName() throws Exception {
        // Back-compat: an entry written before display names existed has no `name` key.
        Files.writeString(tempDir.resolve("access.yml"), """
            entries:
              old@gmail.com:
                role: user
                groups: [family]
            """);

        assertThat(adapter().findByEmail("old@gmail.com"))
                .hasValueSatisfying(e -> assertThat(e.getName()).isNull());
    }

    @Test
    void getEntries_unknownRoleTokenReadsAsPending() throws Exception {
        // access.yml can be hand-edited — a malformed role must never accidentally grant access.
        Files.writeString(tempDir.resolve("access.yml"), """
            entries:
              weird@gmail.com:
                role: superuser
                groups: []
            """);

        assertThat(adapter().findByEmail("weird@gmail.com"))
                .map(AccessEntry::getRole).contains(Role.PENDING);
    }

    // --- service-group resolution ---

    @Test
    void requiredGroupForHost_returnsConfiguredGroup() throws Exception {
        Files.writeString(tempDir.resolve("access.yml"), """
            entries: {}
            serviceGroups:
              plex.example.com: family
              vaier.example.com: admins
            """);

        AccessFileAdapter a = adapter();
        assertThat(a.requiredGroupForHost("plex.example.com")).contains("family");
        assertThat(a.requiredGroupForHost("vaier.example.com")).contains("admins");
    }

    @Test
    void requiredGroupForHost_emptyWhenHostNotConfigured() throws Exception {
        Files.writeString(tempDir.resolve("access.yml"), """
            serviceGroups:
              plex.example.com: family
            """);

        assertThat(adapter().requiredGroupForHost("unknown.example.com")).isEmpty();
    }

    @Test
    void requiredGroupForHost_emptyWhenNoServiceGroupsSection() {
        AccessFileAdapter a = adapter();
        a.upsert(entry("a@gmail.com", Role.USER, List.of()));

        assertThat(a.requiredGroupForHost("plex.example.com")).isEmpty();
    }

    // --- ensuring a configured admin from VAIER_ADMIN_EMAIL (self-heal to keep an admin) ---

    @Test
    void construction_seedsConfiguredAdminWhenStoreEmptyAndAdminEmailSet() {
        AccessFileAdapter a = new AccessFileAdapter(tempDir.toString(), "owner@gmail.com");

        assertThat(a.findByEmail("owner@gmail.com"))
                .hasValueSatisfying(e -> {
                    assertThat(e.getRole()).isEqualTo(Role.ADMIN);
                    // role=ADMIN is enough — the seed must NOT mirror the role into a group.
                    assertThat(e.getGroups()).isEmpty();
                });
    }

    @Test
    void construction_createsConfiguredAdminWhenStoreHasEntriesButNoAdmin() {
        adapter().upsert(entry("existing@gmail.com", Role.USER, List.of()));

        AccessFileAdapter a = new AccessFileAdapter(tempDir.toString(), "owner@gmail.com");

        // Adminless store must self-heal: the configured admin is created so the console can't lock out.
        assertThat(a.findByEmail("owner@gmail.com"))
                .hasValueSatisfying(e -> assertThat(e.getRole()).isEqualTo(Role.ADMIN));
        assertThat(a.getEntries()).hasSize(2);
    }

    @Test
    void construction_promotesExistingNonAdminToAdminPreservingGroupsAndName() {
        adapter().upsert(entry("owner@gmail.com", Role.USER, List.of("family", "devs"), "Owner Name"));

        AccessFileAdapter a = new AccessFileAdapter(tempDir.toString(), "owner@gmail.com");

        assertThat(a.findByEmail("owner@gmail.com"))
                .hasValueSatisfying(e -> {
                    assertThat(e.getRole()).isEqualTo(Role.ADMIN);
                    assertThat(e.getGroups()).containsExactlyInAnyOrder("family", "devs");
                    assertThat(e.getName()).isEqualTo("Owner Name");
                });
        assertThat(a.getEntries()).hasSize(1);
    }

    @Test
    void construction_leavesStoreUntouchedWhenAnAdminAlreadyExists() {
        adapter().upsert(entry("someoneelse@gmail.com", Role.ADMIN, List.of()));

        AccessFileAdapter a = new AccessFileAdapter(tempDir.toString(), "owner@gmail.com");

        // Idempotent: the store already has an admin, so the configured owner is not added or changed.
        assertThat(a.findByEmail("owner@gmail.com")).isEmpty();
        assertThat(a.getEntries()).hasSize(1);
    }

    @Test
    void construction_matchesTheConfiguredAdminCaseInsensitively() {
        adapter().upsert(entry("owner@gmail.com", Role.USER, List.of()));

        AccessFileAdapter a = new AccessFileAdapter(tempDir.toString(), "  Owner@Gmail.com ");

        // The existing lowercase entry is promoted — no duplicate mixed-case entry is created.
        assertThat(a.getEntries()).hasSize(1);
        assertThat(a.findByEmail("owner@gmail.com"))
                .hasValueSatisfying(e -> assertThat(e.getRole()).isEqualTo(Role.ADMIN));
    }

    @Test
    void construction_doesNothingWhenAdminlessAndAdminEmailBlank() {
        adapter().upsert(entry("existing@gmail.com", Role.USER, List.of()));

        AccessFileAdapter a = new AccessFileAdapter(tempDir.toString(), "  ");

        // Nothing to heal with: no admin and no configured email — leave the store as-is (warns).
        assertThat(a.getEntries()).containsExactly(entry("existing@gmail.com", Role.USER, List.of()));
    }

    @Test
    void construction_doesNotSeedWhenAdminEmailBlankAndStoreEmpty() {
        AccessFileAdapter a = new AccessFileAdapter(tempDir.toString(), "  ");

        assertThat(a.getEntries()).isEmpty();
    }
}
