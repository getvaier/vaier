package net.vaier.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether Vaier's sign-in can write in a folder: remove or rename an entry of it, or add one to it. Decided
 * from the folder's own mode and owner, the entry's owner, and the effective user's ids — never guessed.
 */
class FolderAccessTest {

    private static final Instant WHEN = Instant.parse("2026-07-13T10:15:30Z");

    private static final int GEIR = 1000;
    private static final int USERS = 100;
    private static final int ROOT = 0;

    private static final EffectiveUserIds geir = new EffectiveUserIds(GEIR, Set.of(GEIR, USERS));

    private static FilePermissions perms(int mode, int uid, int gid) {
        return new FilePermissions(mode, uid, gid);
    }

    private static FileEntry ownedBy(int uid) {
        return FileEntry.in("/srv", "notes.txt", false, 120, WHEN, perms(0644, uid, uid));
    }

    @Test
    void removingAnEntry_isAQuestionAboutItsFolder_answeredByTheOneClassTheUserFallsIn() {
        record Row(String why, FilePermissions folder, EffectiveUserIds user, FileEntry entry, Writable expected) {}
        for (Row row : new Row[] {
            new Row("owner with write+search", perms(0755, GEIR, GEIR), geir, ownedBy(ROOT), Writable.YES),
            new Row("owner without write", perms(0555, GEIR, GEIR), geir, ownedBy(GEIR), Writable.NO),
            new Row("owner without search", perms(0600, GEIR, GEIR), geir, ownedBy(GEIR), Writable.NO),
            // The owner class decides alone: a group or other bit cannot make up for the owner's missing one.
            new Row("owner class wins over group", perms(0577, GEIR, USERS), geir, ownedBy(GEIR), Writable.NO),
            new Row("group member, group writable", perms(0775, ROOT, USERS), geir, ownedBy(ROOT), Writable.YES),
            new Row("group member, group read-only", perms(0757, ROOT, USERS), geir, ownedBy(ROOT), Writable.NO),
            new Row("other, other writable", perms(0777, ROOT, ROOT), geir, ownedBy(ROOT), Writable.YES),
            new Row("other, other read-only (/etc)", perms(0755, ROOT, ROOT), geir, ownedBy(ROOT), Writable.NO),
            new Row("root can remove anything", perms(0555, GEIR, GEIR),
                new EffectiveUserIds(ROOT, Set.of(ROOT)), ownedBy(GEIR), Writable.YES),
            // A sticky folder (/tmp): writable, but only the entry's owner or the folder's owner may remove.
            new Row("sticky, own entry", perms(01777, ROOT, ROOT), geir, ownedBy(GEIR), Writable.YES),
            new Row("sticky, someone else's entry", perms(01777, ROOT, ROOT), geir, ownedBy(ROOT), Writable.NO),
            new Row("sticky, own folder", perms(01777, GEIR, GEIR), geir, ownedBy(ROOT), Writable.YES),
            new Row("sticky, entry's owner unknown", perms(01777, ROOT, ROOT), geir,
                FileEntry.in("/tmp", "x", false, 1, WHEN), Writable.UNKNOWN),
            // Unknown is not no: the machine's own refusal still speaks.
            new Row("effective user not learned yet", perms(0755, ROOT, ROOT), null, ownedBy(ROOT), Writable.UNKNOWN),
            new Row("folder's attributes not reported", null, geir, ownedBy(ROOT), Writable.UNKNOWN),
        }) {
            assertThat(FolderAccess.of(row.folder(), row.user()).toRemove(row.entry())).as(row.why())
                .isEqualTo(row.expected());
        }
    }

    @Test
    void addingToAFolder_needsWriteAndSearchOnIt_andTheStickyBitDoesNotMatter() {
        record Row(String why, FilePermissions folder, EffectiveUserIds user, Writable expected) {}
        for (Row row : new Row[] {
            new Row("own home", perms(0750, GEIR, GEIR), geir, Writable.YES),
            new Row("/etc as geir", perms(0755, ROOT, ROOT), geir, Writable.NO),
            new Row("/tmp", perms(01777, ROOT, ROOT), geir, Writable.YES),
            new Row("/etc as root", perms(0755, ROOT, ROOT), new EffectiveUserIds(ROOT, Set.of(ROOT)), Writable.YES),
            new Row("effective user not learned yet", perms(0755, ROOT, ROOT), null, Writable.UNKNOWN),
            new Row("folder's attributes not reported", null, geir, Writable.UNKNOWN),
        }) {
            assertThat(FolderAccess.of(row.folder(), row.user()).toAdd()).as(row.why()).isEqualTo(row.expected());
        }
    }
}
