package net.vaier.domain;

/**
 * The path asked for lies outside the machine's {@link SftpRoot} — above the jail its SFTP subsystem is
 * chrooted into, so no SFTP call can ever reach it. On the NAS, {@code /volume2} is such a path: {@code df}
 * and the web terminal see it, SFTP cannot.
 *
 * <p>It exists so that "I cannot reach that" is never rendered as "there is nothing there". Answering an
 * unreachable path with an empty listing — or worse, with the jail's own contents under the name {@code /} —
 * would be Vaier lying about the machine's shape, which is the very bug this concept was introduced to end.
 * The message names both the path and the root, because the operator's next move is to ask for a path under
 * that root.
 *
 * <p>An {@link IllegalArgumentException}: the path is a perfectly valid path, it is simply not an argument
 * <em>this machine</em> can be asked about — so it surfaces as a {@code 400} carrying its own sentence.
 */
public class PathOutsideSftpRootException extends IllegalArgumentException {

    public PathOutsideSftpRootException(String path, String rootPath) {
        super(path + " is not reachable over SFTP; this machine's SFTP service is rooted at " + rootPath + ".");
    }
}
