package net.vaier.domain;

/**
 * What Vaier's sign-in may do in one folder: the folder's own {@link FilePermissions} and the
 * {@link EffectiveUserIds} Vaier signs in as. Removing or renaming an entry is a question about its
 * <b>folder</b> — write and search on it, and, where the folder is sticky, owning the entry or the folder —
 * not about the entry's own mode. Root may do either. When either half is not known, the answer is
 * {@link Writable#UNKNOWN} and the verb stays offered.
 *
 * <p>Judged from mode bits alone: an ACL, a read-only mount or a capability can still make the machine say
 * otherwise, and then its own refusal speaks, as it always has.
 */
public record FolderAccess(FilePermissions folder, EffectiveUserIds user) {

    public static final FolderAccess UNKNOWN = new FolderAccess(null, null);

    public static FolderAccess of(FilePermissions folder, EffectiveUserIds user) {
        return new FolderAccess(folder, user);
    }

    /** Whether the sign-in may remove (or rename) {@code entry} from this folder. */
    public Writable toRemove(FileEntry entry) {
        Writable add = toAdd();
        if (add != Writable.YES || user.root() || !folder.sticky() || user.uid() == folder.uid()) {
            return add;
        }
        if (entry.permissions() == null) {
            return Writable.UNKNOWN;
        }
        return entry.permissions().uid() == user.uid() ? Writable.YES : Writable.NO;
    }

    /** Whether the sign-in may add an entry to this folder — an upload or a paste landing here. */
    public Writable toAdd() {
        if (folder == null || user == null) {
            return Writable.UNKNOWN;
        }
        return user.root() || folder.letsWriteAndSearch(user) ? Writable.YES : Writable.NO;
    }
}
