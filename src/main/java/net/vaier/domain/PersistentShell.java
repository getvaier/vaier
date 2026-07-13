package net.vaier.domain;

/**
 * The single place in Vaier that knows how to open a shell on a machine so the shell <em>outlives the
 * Vaier process</em>. When the operator redeploys the Vaier container (a "hot deploy" run from a web
 * terminal on the Vaier host), the JVM dies, the WebSocket dies, and a bare login shell's PTY dies with
 * it — losing the cwd, history, scrollback, and the deploy's remaining output and exit code. Wrapping
 * the shell in a <b>persistent shell</b> (a tmux session on the target machine) fixes that: the tmux
 * server keeps the shell running across the drop, and a reconnect <b>reattaches</b> to it rather than
 * starting fresh.
 *
 * <p>Mirrors the {@code BorgCommand} house pattern — a pure, IO-free builder of the exact shell strings
 * sent over SSH, plus the tiny parsers that read a probe's output back into a domain decision. The
 * orchestration in the service and the adapter never grow tmux knowledge.
 *
 * <p><b>Attach-or-create.</b> {@link #attachOrCreateCommand} runs {@code tmux new-session -A -D}: {@code
 * -A} attaches when the pane's session already exists and creates it otherwise, and {@code -D} detaches
 * any other (stale) client still attached so the live client alone drives the window size — the resize
 * after a reattach then sizes the window correctly instead of letterboxing to a dead client. If tmux is
 * not installed on the target machine the command falls back to a plain login shell, so a terminal never
 * fails to open just because tmux is missing.
 *
 * <p><b>Truthful continuity.</b> Because {@code new-session -A} is an atomic attach-or-create it does not
 * itself say whether it attached or created, so before opening the shell the service runs {@link
 * #probeCommand} and reads it with {@link #readProbe}: {@link Continuity#REATTACHED} when the session
 * already existed, {@link Continuity#NEW} when it did not, {@link Continuity#PLAIN} when tmux is absent.
 * That is what lets the reconnect banner tell the truth rather than always claiming continuity.
 *
 * <p><b>Safe session names.</b> The pane id comes from the browser, so it is treated as untrusted and
 * reduced to a safe identifier ({@link #sessionName}) before it ever reaches the shell — only letters,
 * digits, {@code _} and {@code -} survive, and the name is single-quoted on the command line as well.
 */
public final class PersistentShell {

    /** The reserved prefix that namespaces Vaier's tmux sessions apart from the operator's own. */
    private static final String SESSION_PREFIX = "vaier-";
    private static final String DEFAULT_SUFFIX = "default";
    private static final int MAX_SUFFIX_LENGTH = 60;

    /** Markers the probe echoes; parsed by {@link #readProbe}. Chosen to be unmistakable in output. */
    private static final String MARKER_ABSENT = "VAIER_TMUX_ABSENT";
    private static final String MARKER_ATTACH = "VAIER_TMUX_ATTACH";
    private static final String MARKER_NEW = "VAIER_TMUX_NEW";

    private PersistentShell() {
    }

    /**
     * Whether opening a persistent shell resumed an existing session or started a fresh one — the fact the
     * reconnect banner must state truthfully. {@link #REATTACHED} the pane's tmux session already existed
     * and was resumed (cwd/history/scrollback preserved); {@link #NEW} tmux is present but no session
     * existed yet, so a new one was created; {@link #PLAIN} tmux is not installed on the machine, so a
     * plain, non-persistent login shell was opened. Deciding which of these a probe produced is a domain
     * rule, so it lives here.
     */
    public enum Continuity { REATTACHED, NEW, PLAIN }

    /**
     * The safe tmux session name for a browser-supplied {@code paneId}: the {@value #SESSION_PREFIX}
     * prefix followed by the pane id reduced to the identifier charset ({@code A–Z a–z 0–9 _ -}), so a
     * hostile pane id can neither break out of the command line nor collide with an operator session.
     * Falls back to {@code vaier-default} when nothing safe remains. Stable for a given pane id, so a
     * reconnect for the same pane resolves to the same session and reattaches.
     */
    public static String sessionName(String paneId) {
        String safe = paneId == null ? "" : paneId.replaceAll("[^A-Za-z0-9_-]", "");
        if (safe.isEmpty()) {
            safe = DEFAULT_SUFFIX;
        } else if (safe.length() > MAX_SUFFIX_LENGTH) {
            safe = safe.substring(0, MAX_SUFFIX_LENGTH);
        }
        return SESSION_PREFIX + safe;
    }

    /**
     * The command run in the PTY to open the pane's persistent shell: attach-or-create a tmux session
     * named for the pane ({@code -A} attach-or-create, {@code -D} detach stale clients), falling back to a
     * plain login shell when tmux is absent. {@code exec} in both branches replaces the login shell so the
     * remote process tree stays clean and the session ends cleanly when tmux/the shell exits.
     *
     * <p>The attach is chained (tmux's {@code \;}) with {@code set-option status off} scoped to this
     * session by name: the web terminal draws its own chrome, so tmux's default (green) status bar only
     * steals a row and reads as clutter — especially on a phone. Scoping it by {@code -t <name>} keeps it to
     * Vaier's own {@value #SESSION_PREFIX} session; the operator's other tmux sessions keep their status bar.
     */
    public static String attachOrCreateCommand(String paneId) {
        String name = singleQuote(sessionName(paneId));
        return "if command -v tmux >/dev/null 2>&1; then "
            + "exec tmux new-session -A -D -s " + name + " \\; set-option -t " + name + " status off; "
            + "else exec \"${SHELL:-/bin/sh}\" -l; fi";
    }

    /**
     * The command that ends a pane's persistent shell for good: {@code tmux kill-session} for that pane's
     * session, and nothing else.
     *
     * <p><b>Ending is not the same as disconnecting.</b> A dropped WebSocket must leave the session alive —
     * that is the whole point of a persistent shell, and it is what lets a reconnect (or a Vaier redeploy)
     * reattach. But closing a pane is the operator saying "I am done with this shell", and without this
     * command that shell would stay detached on the machine forever, still running whatever was in it. The
     * two cases are indistinguishable from the socket alone, so the browser has to say which one it means.
     *
     * <p>Idempotent: the session may already be gone (the host rebooted, the operator killed it by hand), so
     * the command swallows the failure and still exits 0 — ending a shell that is already ended is success.
     */
    public static String endCommand(String paneId) {
        String name = singleQuote(sessionName(paneId));
        return "tmux kill-session -t " + name + " 2>/dev/null || true";
    }

    /**
     * The probe run just before opening the shell: it reports whether tmux is installed and, if so,
     * whether this pane's session already exists — echoing exactly one of {@value #MARKER_ABSENT},
     * {@value #MARKER_ATTACH}, {@value #MARKER_NEW}. Read with {@link #readProbe}. Runs over the ordinary
     * exec path (not a PTY) inside the SSH command cap.
     */
    public static String probeCommand(String paneId) {
        String name = singleQuote(sessionName(paneId));
        return "command -v tmux >/dev/null 2>&1 || { echo " + MARKER_ABSENT + "; exit 0; }; "
            + "tmux has-session -t " + name + " 2>/dev/null && echo " + MARKER_ATTACH
            + " || echo " + MARKER_NEW;
    }

    /**
     * Read a {@link #probeCommand} run's output into a {@link Continuity}. Like the borg probes, it never
     * optimistically claims continuity it cannot prove: only an explicit {@value #MARKER_ATTACH} reads as
     * {@link Continuity#REATTACHED} and only {@value #MARKER_ABSENT} as {@link Continuity#PLAIN}; a blank,
     * garbled, or {@value #MARKER_NEW} output reads as {@link Continuity#NEW}. Never throws.
     */
    public static Continuity readProbe(String output) {
        String text = output == null ? "" : output;
        if (text.contains(MARKER_ABSENT)) {
            return Continuity.PLAIN;
        }
        if (text.contains(MARKER_ATTACH)) {
            return Continuity.REATTACHED;
        }
        return Continuity.NEW;
    }

    /**
     * Single-quote {@code value} for the shell, escaping any embedded single quote with the {@code '\''}
     * idiom. Belt-and-suspenders over {@link #sessionName}'s charset reduction — the name is already safe,
     * but it is never interpolated unquoted.
     */
    private static String singleQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
