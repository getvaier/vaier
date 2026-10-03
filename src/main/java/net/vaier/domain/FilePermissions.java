package net.vaier.domain;

/**
 * An entry's permission bits and owner, as SFTP reported them on the listing. {@code mode} keeps only the
 * permission bits (the setuid, setgid and sticky bits and the nine rwx bits), never the file-type bits.
 */
public record FilePermissions(int mode, int uid, int gid) {

    private static final int PERMISSION_BITS = 07777;
    private static final int STICKY = 01000;
    private static final int WRITE_AND_SEARCH = 03;

    public FilePermissions {
        mode = mode & PERMISSION_BITS;
    }

    boolean sticky() {
        return (mode & STICKY) != 0;
    }

    /**
     * Whether {@code user} may write in this folder and search it. Exactly one class applies, owner before
     * group before other, as the kernel decides it: an owner without the bits is not rescued by the group's.
     */
    boolean letsWriteAndSearch(EffectiveUserIds user) {
        int shift = user.uid() == uid ? 6 : user.groups().contains(gid) ? 3 : 0;
        return ((mode >> shift) & WRITE_AND_SEARCH) == WRITE_AND_SEARCH;
    }
}
