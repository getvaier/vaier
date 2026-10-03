package net.vaier.domain;

/**
 * Whether Vaier's sign-in can write somewhere: remove or rename an entry, or add one to a folder. Decided by
 * {@link FolderAccess} from what the listing already read; only {@link #NO} withholds a write verb.
 */
public enum Writable {

    YES,

    NO,

    /** The folder's attributes or the {@link EffectiveUserIds} are not known. <b>Not a no.</b> */
    UNKNOWN;

    /** The verdict as the listing carries it: {@code null} when unknown, so nothing is said that was not learned. */
    public Boolean answer() {
        return this == UNKNOWN ? null : this == YES;
    }
}
