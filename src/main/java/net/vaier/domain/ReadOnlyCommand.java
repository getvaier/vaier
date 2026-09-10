package net.vaier.domain;

import net.vaier.domain.port.ForRunningSshCommands;
import net.vaier.domain.port.ForTrackingHostKeys;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A <b>Read-only command</b> (#360): one command line the model may run on a machine through <b>Chat</b>.
 *
 * <p>"run_on_machine can look, never change" is the promise this tool makes; this is the mechanism that keeps it. It is
 * a list of what is allowed — never of what is forbidden — judged word by word: the first word of every
 * pipe stage must be a looking program, a program with verbs (apt, docker, systemctl) must be given a
 * looking verb, and a looking program's own writing flags ({@code find -delete}, {@code ip link set}) are
 * refused too. Chaining, redirecting and subshells are refused outright, because the first word would then
 * be the only one read.
 *
 * <p>The other half is where secrets live. {@code cat} is a looking command; {@code cat} of a key is still
 * a leak to the model and to Anthropic. So a path-shaped word that names the usual home of a secret — and
 * the places Vaier itself puts one, like the borg passphrase under {@code .vaier-backup} — is refused.
 * Only path-shaped words are judged, so a container called "wireguard" can still have its logs read.
 */
public record ReadOnlyCommand(String line) {

    /** Said to the model, so it knows what to reach for and what not to try. */
    public static final String WHAT_IS_ALLOWED = "listing and reading files, disk, memory, processes, uptime, "
        + "network state, journalctl and other logs, package and update state (apt, dpkg, dnf, apk, pacman, "
        + "zypper, rpm, snap), docker ps, images, logs, top and stats, systemctl status, wg show, and a pipe "
        + "between any of these";

    private static final String LOOK_NEVER_CHANGE = "run_on_machine can look, never change";
    private static final String ONE_AT_A_TIME = LOOK_NEVER_CHANGE + ", and it runs one command at a time: "
        + "no ;, &&, ||, &, redirects or subshells. A pipe between looking commands is fine.";
    private static final String CHAINING = ";&<>`\n\r";

    /** Programs that only ever look, whatever they are given — bar the flags in {@link #WRITING_WORDS}. */
    private static final Set<String> LOOKING = Set.of(
        "uptime", "uname", "hostname", "hostnamectl", "timedatectl", "date", "id", "whoami", "who", "w",
        "last", "cat", "head", "tail", "ls", "stat", "file", "readlink", "realpath", "which", "wc", "grep",
        "egrep", "fgrep", "zgrep", "sort", "uniq", "cut", "tr", "column", "diff", "tree", "md5sum",
        "sha256sum", "df", "du", "free", "ps", "pgrep", "lsblk", "findmnt", "lscpu", "lsusb", "lspci",
        "lsof", "nproc", "dmesg", "vmstat", "iostat", "sensors", "dmidecode", "ip", "ss", "netstat", "getent",
        "dig", "nslookup", "host", "journalctl", "dpkg-query", "find");

    /** Programs that both look and change, by the verb they are given; only these verbs look. */
    private static final Map<String, Set<String>> LOOKING_VERBS = Map.ofEntries(
        Map.entry("apt", Set.of("list", "show", "search", "policy", "depends", "rdepends")),
        Map.entry("apt-cache", Set.of("policy", "show", "search", "showpkg", "madison", "stats", "depends",
            "rdepends", "pkgnames")),
        Map.entry("apt-mark", Set.of("showmanual", "showauto", "showhold")),
        Map.entry("dpkg", Set.of("-l", "-L", "-s", "-S", "-V", "-C", "--list", "--status", "--listfiles",
            "--search", "--verify", "--audit", "--get-selections", "--print-architecture")),
        Map.entry("dnf", Set.of("check-update", "list", "info", "repoquery", "search", "repolist",
            "updateinfo", "provides", "deplist")),
        Map.entry("yum", Set.of("check-update", "list", "info", "repoquery", "search", "repolist",
            "updateinfo", "provides", "deplist")),
        Map.entry("apk", Set.of("list", "info", "version", "search", "policy", "stats", "audit")),
        Map.entry("zypper", Set.of("lu", "list-updates", "lp", "list-patches", "se", "search", "if", "info",
            "pa", "packages", "lr", "repos", "ps")),
        Map.entry("snap", Set.of("list", "info", "changes", "version", "find", "services", "warnings")),
        Map.entry("flatpak", Set.of("list", "info", "remote-ls", "history", "ps")),
        Map.entry("docker", Set.of("ps", "images", "logs", "version", "info", "top", "port", "stats", "diff",
            "history")),
        Map.entry("systemctl", Set.of("status", "list-units", "list-timers", "list-unit-files",
            "list-dependencies", "list-sockets", "list-jobs", "is-active", "is-enabled", "is-failed",
            "is-system-running", "show", "cat", "get-default")),
        Map.entry("wg", Set.of("show")),
        Map.entry("git", Set.of("status", "log", "diff", "show", "remote", "describe", "rev-parse",
            "ls-files")),
        Map.entry("crontab", Set.of("-l")),
        Map.entry("ufw", Set.of("status")));

    /** docker's own sub-verbs. {@code inspect} and {@code compose config} print environments — where secrets live. */
    private static final Map<String, Set<String>> DOCKER_VERBS = Map.of(
        "system", Set.of("df", "info", "version"),
        "compose", Set.of("ls", "ps", "logs", "top", "images", "version"),
        "volume", Set.of("ls"),
        "network", Set.of("ls"),
        "image", Set.of("ls", "history"),
        "container", Set.of("ls", "logs", "top", "port", "stats", "diff"),
        "context", Set.of("ls", "show"));

    /** The words that turn a looking program into a changing one. */
    private static final Map<String, Set<String>> WRITING_WORDS = Map.of(
        "find", Set.of("-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls"),
        "ip", Set.of("add", "del", "delete", "set", "change", "replace", "flush", "append", "exec"),
        "dmesg", Set.of("-C", "--clear", "-c", "--read-clear"),
        "sort", Set.of("-o", "--output"),
        "date", Set.of("-s", "--set"));
    private static final Map<String, List<String>> WRITING_PREFIXES = Map.of(
        "journalctl", List.of("--vacuum", "--rotate", "--flush", "--sync", "--relinquish", "--setup-keys"),
        "hostnamectl", List.of("set-"),
        "timedatectl", List.of("set-"),
        "sort", List.of("--output="),
        "date", List.of("--set="));

    /** The usual homes of a secret, and the places Vaier itself puts one. Matched inside path-shaped words. */
    private static final List<String> SECRET_HOMES = List.of(
        ".ssh", ".vaier", "shadow", ".env", ".netrc", ".gnupg", ".aws", ".docker", "id_rsa", "id_ed25519",
        "id_ecdsa", "id_dsa", ".pem", ".key", ".pass", "wireguard", "wg0", "credential", "secret", "token",
        "environ", ".htpasswd", ".pgpass", ".my.cnf", "access.yml");

    public ReadOnlyCommand {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("Say what to run.");
        }
        for (String stage : stages(line)) {
            allow(words(stage));
        }
    }

    public static ReadOnlyCommand of(String line) {
        return new ReadOnlyCommand(line);
    }

    /** Runs it and pins the host on first use, exactly as the disk reading does. */
    public CommandOutcome runOn(SshTarget target, ForRunningSshCommands ssh, ForTrackingHostKeys hostKeys) {
        CommandResult result = ssh.run(target, line);
        target.pinOnFirstUse(result.hostKeyFingerprint(), hostKeys);
        return CommandOutcome.of(result);
    }

    // --- the judgement ------------------------------------------------------------------------------

    private static void allow(List<String> words) {
        if (words.isEmpty()) {
            throw new IllegalArgumentException(ONE_AT_A_TIME);
        }
        String program = basename(words.get(0));
        String verb = words.size() > 1 ? words.get(1) : "";
        List<String> rest = words.subList(1, words.size());
        for (String word : rest) {
            refuseSecretHome(word);
        }
        if (Set.of("sudo", "doas", "su").contains(program)) {
            throw new IllegalArgumentException(LOOK_NEVER_CHANGE
                + ", and it runs as Vaier's login user without sudo.");
        }
        if (LOOKING.contains(program)) {
            allowFlags(program, rest);
        } else if (program.equals("docker")) {
            allowDocker(verb, words.size() > 2 ? words.get(2) : "");
        } else if (program.equals("pacman")) {
            if (!(verb.startsWith("-Q") || Set.of("-Ss", "-Si", "-Sl", "-Sg", "-Sp").contains(verb))) {
                refuse(program + " " + verb);
            }
        } else if (program.equals("rpm")) {
            if (!(verb.startsWith("-q") || verb.startsWith("--query"))) {
                refuse(program + " " + verb);
            }
        } else if (LOOKING_VERBS.containsKey(program)) {
            if (!LOOKING_VERBS.get(program).contains(verb)) {
                refuse((program + " " + verb).trim());
            }
        } else {
            refuse(program);
        }
    }

    private static void allowFlags(String program, List<String> rest) {
        Set<String> words = WRITING_WORDS.getOrDefault(program, Set.of());
        List<String> prefixes = WRITING_PREFIXES.getOrDefault(program, List.of());
        for (String word : rest) {
            if (words.contains(word) || prefixes.stream().anyMatch(word::startsWith)) {
                refuse(program + " " + word);
            }
            // `hostname` with a name sets it; every flag only asks.
            if (program.equals("hostname") && !word.startsWith("-")) {
                refuse(program + " " + word);
            }
        }
    }

    private static void allowDocker(String verb, String sub) {
        if (DOCKER_VERBS.containsKey(verb)) {
            if (!DOCKER_VERBS.get(verb).contains(sub)) {
                refuse(("docker " + verb + " " + sub).trim());
            }
        } else if (!LOOKING_VERBS.get("docker").contains(verb)) {
            refuse(("docker " + verb).trim());
        }
    }

    private static void refuse(String what) {
        throw new IllegalArgumentException(LOOK_NEVER_CHANGE + ": " + what + " is not a looking command. "
            + "It can run " + WHAT_IS_ALLOWED + ".");
    }

    private static void refuseSecretHome(String word) {
        boolean pathShaped = word.contains("/") || word.startsWith(".") || word.startsWith("~");
        if (!pathShaped) {
            return;
        }
        String lower = word.toLowerCase(Locale.ROOT);
        for (String home : SECRET_HOMES) {
            if (lower.contains(home)) {
                throw new IllegalArgumentException("Chat never reads where secrets live, and " + word
                    + " looks like such a place.");
            }
        }
    }

    private static String basename(String word) {
        int slash = word.lastIndexOf('/');
        return slash < 0 ? word : word.substring(slash + 1);
    }

    // --- reading the line ---------------------------------------------------------------------------

    /** The pipe stages, and a refusal for anything outside quotes that chains, redirects or subshells. */
    private static List<String> stages(String line) {
        List<String> stages = new ArrayList<>();
        int start = 0;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (CHAINING.indexOf(c) >= 0 || (c == '$' && line.startsWith("(", i + 1))) {
                throw new IllegalArgumentException(ONE_AT_A_TIME);
            }
            if (c == '|') {
                if (line.startsWith("|", i + 1) || line.startsWith("&", i + 1)) {
                    throw new IllegalArgumentException(ONE_AT_A_TIME);
                }
                stages.add(line.substring(start, i));
                start = i + 1;
            }
        }
        if (quote != 0) {
            throw new IllegalArgumentException("Chat could not read that command: a quote is not closed.");
        }
        stages.add(line.substring(start));
        return stages;
    }

    /** One stage's words, with quotes and backslashes read the way a shell reads them. */
    private static List<String> words(String stage) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean open = false;
        char quote = 0;
        for (int i = 0; i < stage.length(); i++) {
            char c = stage.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    word.append(c);
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                open = true;
            } else if (c == '\\' && i + 1 < stage.length()) {
                word.append(stage.charAt(++i));
                open = true;
            } else if (Character.isWhitespace(c)) {
                if (open || word.length() > 0) {
                    words.add(word.toString());
                    word.setLength(0);
                    open = false;
                }
            } else {
                word.append(c);
                open = true;
            }
        }
        if (open || word.length() > 0) {
            words.add(word.toString());
        }
        return words;
    }
}
