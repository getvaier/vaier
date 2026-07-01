package net.vaier.application;

import net.vaier.domain.AccessEntry;
import net.vaier.domain.Role;
import net.vaier.domain.port.ForPersistingAccessEntries;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AccessGroupMigrationTest {

    /** In-memory access store keyed by email, so we can observe exactly what the migration upserts. */
    private static class InMemoryAccessStore implements ForPersistingAccessEntries {
        private final List<AccessEntry> entries = new ArrayList<>();
        int upserts = 0;

        InMemoryAccessStore(AccessEntry... seed) {
            for (AccessEntry e : seed) entries.add(e);
        }

        @Override public List<AccessEntry> getEntries() {
            return new ArrayList<>(entries);
        }

        @Override public Optional<AccessEntry> findByEmail(String email) {
            return entries.stream().filter(e -> e.getEmail().equals(email)).findFirst();
        }

        @Override public void upsert(AccessEntry entry) {
            upserts++;
            entries.removeIf(e -> e.getEmail().equals(entry.getEmail()));
            entries.add(entry);
        }

        @Override public void delete(String email) {
            entries.removeIf(e -> e.getEmail().equals(email));
        }
    }

    private static AccessEntry entry(String email, Role role, List<String> groups) {
        return AccessEntry.builder().email(email).role(role).groups(groups).build();
    }

    @Test
    void stripsRoleMirroringGroupsFromEntriesThatHaveThem() {
        InMemoryAccessStore store = new InMemoryAccessStore(
                entry("geir@example.com", Role.ADMIN, List.of("devs", "admins")),
                entry("turid@example.com", Role.USER, List.of("users")));

        new AccessGroupMigration(store).stripRoleMirroringGroupsOnStartup();

        assertThat(store.findByEmail("geir@example.com"))
                .map(AccessEntry::getGroups).contains(List.of("devs"));
        assertThat(store.findByEmail("turid@example.com"))
                .map(AccessEntry::getGroups).contains(List.of());
    }

    @Test
    void leavesEntriesWithoutRoleMirroringGroupsUntouched() {
        InMemoryAccessStore store = new InMemoryAccessStore(
                entry("clean@example.com", Role.USER, List.of("family")));

        new AccessGroupMigration(store).stripRoleMirroringGroupsOnStartup();

        assertThat(store.upserts).isZero();
        assertThat(store.findByEmail("clean@example.com"))
                .map(AccessEntry::getGroups).contains(List.of("family"));
    }

    @Test
    void isIdempotent_secondRunDoesNothing() {
        InMemoryAccessStore store = new InMemoryAccessStore(
                entry("geir@example.com", Role.ADMIN, List.of("devs", "admins")));

        AccessGroupMigration migration = new AccessGroupMigration(store);
        migration.stripRoleMirroringGroupsOnStartup();
        int afterFirst = store.upserts;
        migration.stripRoleMirroringGroupsOnStartup();

        assertThat(afterFirst).isEqualTo(1);
        assertThat(store.upserts).isEqualTo(1); // no further writes on the second run
    }
}
