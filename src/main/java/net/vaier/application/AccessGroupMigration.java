package net.vaier.application;

import lombok.extern.slf4j.Slf4j;
import net.vaier.domain.AccessEntry;
import net.vaier.domain.port.ForPersistingAccessEntries;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * One-off, idempotent startup migration that strips the role-mirroring names ({@code admins}/{@code
 * users}) out of every {@link AccessEntry}'s groups. Those names duplicated what {@link
 * net.vaier.domain.Role} already decides; groups are now purely per-service access tags. Which names
 * are reserved is a domain decision ({@link AccessEntry#hasRoleMirroringGroups()} /
 * {@link AccessEntry#withoutRoleMirroringGroups()}) — this component only orchestrates through the
 * {@link ForPersistingAccessEntries} port. Mirrors the {@code backfill*OnStartup} pattern.
 */
@Component
@Slf4j
public class AccessGroupMigration {

    private final ForPersistingAccessEntries accessStore;

    public AccessGroupMigration(ForPersistingAccessEntries accessStore) {
        this.accessStore = accessStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void stripRoleMirroringGroupsOnStartup() {
        try {
            for (AccessEntry entry : accessStore.getEntries()) {
                if (entry.hasRoleMirroringGroups()) {
                    log.info("Stripping role-mirroring groups from access entry '{}'", entry.getEmail());
                    accessStore.upsert(entry.withoutRoleMirroringGroups());
                }
            }
        } catch (Exception e) {
            log.warn("Role-mirroring-group migration on startup failed", e);
        }
    }
}
