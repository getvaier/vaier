package net.vaier.rest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Explorer shell (#323 slice A): the fleet as one tree, at a page of its own.
 *
 * <p>The shell is the first Vaier page that is not a section inside {@code admin.html}'s iframe — it carries
 * its own topbar, its own tree, and the terminal dock itself. That inversion is the point of the epic (the
 * iframe existed only to keep live SSH sessions alive across tab switches), so the invariants that make it a
 * shell rather than another section are worth pinning: the dock is really in the page, the old pages really do
 * still work, nothing polls, and no endpoint was opened to make it possible.
 *
 * <p>There is no JS test harness in this project, so — as with {@code TerminalDockShellLifetimeTest} — the
 * invariants are asserted on the shipped assets themselves.
 */
class ExplorerShellTest {

    private static final Path STATIC = Path.of("src/main/resources/static");

    private static String read(String name) throws IOException {
        return Files.readString(STATIC.resolve(name));
    }

    // --- 1. the name is freed --------------------------------------------------------------------------

    @Test
    void theAdminExplorerTab_opensTheFileBrowserAtItsNewName() throws IOException {
        // explorer.html is now the shell. The file browser shipped in #321 keeps working under its own name,
        // as the backup while the shell is built — so admin.html must point at that name, not at the shell.
        String admin = read("admin.html");
        assertThat(admin).contains("data-page=\"explorer-files.html\"");
        assertThat(admin).doesNotContain("data-page=\"explorer.html\"");
        assertThat(Files.exists(STATIC.resolve("explorer-files.html"))).isTrue();
    }

    @Test
    void theOldFileBrowser_stillCarriesItsOwnAssets() throws IOException {
        String files = read("explorer-files.html");
        assertThat(files).contains("explorer.css");
        assertThat(files).contains("explorer.js");
    }

    @Test
    void everyAdminSection_stillResolvesToAPageThatExists() throws IOException {
        // The old pages are the backup while the shell is built; a tab pointing at a page that no longer
        // exists would be a silent hole in it.
        Matcher m = Pattern.compile("data-page=\"([^\"]+)\"").matcher(read("admin.html"));
        while (m.find()) {
            assertThat(Files.exists(STATIC.resolve(m.group(1))))
                .as("admin.html section %s", m.group(1)).isTrue();
        }
    }

    // --- 2. the shell is a page, not a fragment --------------------------------------------------------

    @Test
    void theShell_isAWholePageWithItsOwnTopbar() throws IOException {
        String shell = read("explorer.html");
        assertThat(shell).contains("<!DOCTYPE html>");
        assertThat(shell).contains("explorer-shell.css");
        assertThat(shell).contains("explorer-shell.js");
        assertThat(shell).contains("class=\"topbar\"");
    }

    @Test
    void theShell_isNeverLoadedIntoTheAdminIframe() throws IOException {
        // If the shell were a section of admin.html it would be inside the very iframe it exists to retire.
        assertThat(read("admin.html")).doesNotContain("explorer.html");
    }

    // --- 3. the dock moved in ---------------------------------------------------------------------------

    @Test
    void theTerminalDock_livesInTheShellItself() throws IOException {
        // The whole reason admin.html iframes its sections is that the dock must survive navigation. In the
        // shell there is nothing to navigate away from: the dock is a row of the page, mounted directly.
        String shell = read("explorer.html");
        assertThat(shell).contains("terminal-dock.js");
        assertThat(shell).contains("id=\"terminalPanes\"");   // the dock's mount points, unchanged
        assertThat(shell).contains("id=\"terminalEmpty\"");
        assertThat(shell).contains("id=\"terminalKeys\"");
        assertThat(shell).doesNotContain("<iframe src=\"terminal");
    }

    @Test
    void aShellEntryOnAMachine_opensTheDockOnThatMachine() throws IOException {
        // "shell" is an entry under a machine now, not a button on a card in another page.
        String js = read("explorer-shell.js");
        assertThat(js).contains("TerminalDock.open(");
        assertThat(js).contains("'shell'");
    }

    @Test
    void aRepaint_neverSpawnsASecondShell() throws IOException {
        // TerminalDock.open() is not idempotent by design — every call is another shell, which is what an
        // operator wants when they ask for one twice. So it may only be reached by an explicit selection.
        // Reached from the Inspector's renderer instead, every repaint (a machine coming online, say) would
        // silently open another tmux session on the host, forever.
        String js = read("explorer-shell.js");
        assertThat(js.split("TerminalDock\\.open\\(", -1).length - 1).isEqualTo(1);

        int go = js.indexOf("function go(path) {");
        assertThat(go).isPositive();
        String body = js.substring(go, js.indexOf("\n    }", go));
        assertThat(body).as("the one call site is navigation, not rendering").contains("TerminalDock.open(");
    }

    // --- 4. the frontend never polls --------------------------------------------------------------------

    @Test
    void theShell_learnsMachineLivenessFromTheEventStream_notFromAPollLoop() throws IOException {
        // Hard project rule: the backend polls and pushes; the browser only ever listens.
        String js = read("explorer-shell.js");
        assertThat(js).contains("new EventSource('/vpn/peers/events')");
        assertThat(js).contains("peers-stats");
        assertThat(js).doesNotContain("setInterval");
    }

    // --- 5. no endpoint was opened ----------------------------------------------------------------------

    @Test
    void theShell_callsOnlyEndpointsThatAlreadyExist() throws IOException {
        // Slice A is a new front for the API Vaier already has. A fetch to anything else would mean an
        // endpoint was opened to make the tree work — which is exactly what this slice must not do.
        List<String> allowed = List.of("/machines", "/vpn/peers", "/lan-servers", "/users/me");
        Matcher m = Pattern.compile("fetch\\('([^']+)'").matcher(read("explorer-shell.js"));
        int found = 0;
        while (m.find()) {
            found++;
            String url = m.group(1);
            assertThat(allowed).as("fetch(%s) — an endpoint the shell must not need", url)
                .anySatisfy(prefix -> assertThat(url).startsWith(prefix));
        }
        assertThat(found).isPositive();
    }

    // --- 6. the ubiquitous language ---------------------------------------------------------------------

    @Test
    void theShell_saysMachineAndEntry_neverNode() throws IOException {
        // UBIQUITOUS_LANGUAGE.md §11 bans "node": a thing in the fleet is a machine, and a thing in the tree
        // is an entry.
        Pattern node = Pattern.compile("\\bnodes?\\b", Pattern.CASE_INSENSITIVE);
        for (String asset : List.of("explorer.html", "explorer-shell.js", "explorer-shell.css",
                                    "explorer-listing.js")) {
            assertThat(node.matcher(read(asset)).find()).as("\"node\" in %s", asset).isFalse();
        }
    }

    // --- 7. the listing is lifted, not rewritten --------------------------------------------------------

    @Test
    void bothExplorers_readADirectoryThroughTheSameCode() throws IOException {
        // Two copies of "list a directory over SFTP" would be two places the size humanising, the clock
        // format, the newest-listing-wins guard and the server's own error message could drift apart.
        assertThat(read("explorer-files.html")).contains("explorer-listing.js");
        assertThat(read("explorer.html")).contains("explorer-listing.js");

        String shared = read("explorer-listing.js");
        assertThat(shared).contains("hour12: false");           // one clock format
        assertThat(shared).contains("['B', 'K', 'M', 'G', 'T']"); // one size scale
        assertThat(shared).contains("ticket !== inFlight");     // one newest-listing-wins guard
        assertThat(shared).contains("err.message");             // the server's message, verbatim

        // and neither page keeps a second copy of any of it
        for (String asset : List.of("explorer.js", "explorer-shell.js")) {
            assertThat(read(asset)).as("a second copy in %s", asset)
                .doesNotContain("hour12: false")
                .doesNotContain("['B', 'K', 'M', 'G', 'T']");
        }
    }

    // --- 8. the bridge is temporary, and says so --------------------------------------------------------

    @Test
    void theSectionsNotYetPorted_areBridgedIntoTheTreeAndMarkedTransitional() throws IOException {
        String js = read("explorer-shell.js");
        for (String page : List.of("vpn-peers.html", "backups.html", "users.html", "settings.html",
                                   "concepts.html")) {
            assertThat(js).as("bridge to %s", page).contains(page);
        }
        // A bridge nobody labelled is a bridge nobody deletes.
        assertThat(js).containsIgnoringCase("transitional");
    }

    // --- 9. the latent height bug (#321) ---------------------------------------------------------------

    @Test
    void theFileBrowser_doesNotSubtractATopbarItDoesNotHave() throws IOException {
        // explorer.css subtracted 35px for a topbar that is neither in the page nor 35px tall (the real one
        // is 48px, and it lives in the shell around the frame, not in the frame).
        assertThat(read("explorer.css")).doesNotContain("100vh - 35px");
    }

    // --- 10. tokens -------------------------------------------------------------------------------------

    @Test
    void theRailAndRadiusTokens_areDefinedOnceAndUsedByTheNewAssets() throws IOException {
        String styles = read("styles.css");
        assertThat(styles).contains("--rail:");
        assertThat(styles).contains("--radius-1:");
        assertThat(styles).contains("--radius-2:");

        String css = read("explorer-shell.css");
        assertThat(css).contains("var(--rail)");
        // Every corner in the new stylesheet comes from a token — no eighth raw radius value.
        Matcher m = Pattern.compile("border-radius:\\s*([^;]+);").matcher(css);
        while (m.find()) {
            String value = m.group(1).trim();
            assertThat(value).as("raw radius %s", value)
                .matches("(var\\(--radius-[12]\\)|50%|9999px)");
        }
    }

    // --- 11. liveness is the whole fleet's, not just the peers' ----------------------------------------
    //
    // The bug: livenessOf() only knew WireGuard peers, so of the fleet's machines only the four that are
    // peers could ever go green. Every LAN server — the NAS, the NUCs, the Roon boxes, the ones the operator
    // actually SSHes into — fell through to grey, and grey read as "Vaier has no idea", which was a lie:
    // Vaier probes them on a schedule and already knows.

    @Test
    void aLanServer_reportsItsLiveness_fromTheStatusTheDomainAlreadyComputed() throws IOException {
        // /lan-servers already carries `status`, a MachineStatus computed in the domain
        // (MachineStatus.forLanServer). The shell asks for it and maps it — it never recombines the
        // signals (reachability + Docker scrape) in the browser.
        String js = read("explorer-shell.js");
        assertThat(js).contains("fetch('/lan-servers')");
        assertThat(js).contains("'OK'");
        assertThat(js).contains("is-up");
    }

    @Test
    void theDot_neverPaintsAnUnknownStatusGreen() throws IOException {
        // The honest mapping. UNKNOWN means "no probe has run yet" — that is idle, not up. A green dot
        // for UNKNOWN would be Vaier claiming to know something it does not, which is worse than grey.
        // DOWN is red, and DEGRADED (on the network, but its Docker scrape is failing) is its own colour:
        // flattening it into green would throw away a signal the fleet page already shows.
        String js = read("explorer-shell.js");
        int from = js.indexOf("STATUS_DOT");
        assertThat(from).as("the MachineStatus -> dot mapping table").isPositive();
        String table = js.substring(from, js.indexOf("};", from));

        assertThat(table).contains("'OK'").contains("'DOWN'").contains("'UNKNOWN'").contains("'DEGRADED'");
        assertThat(table).matches("(?s).*'UNKNOWN':\\s*'is-idle'.*");
        assertThat(table).matches("(?s).*'DOWN':\\s*'is-down'.*");
        assertThat(table).matches("(?s).*'OK':\\s*'is-up'.*");
        // and the two states that are not "up" are certainly not painted up
        assertThat(table).doesNotMatch("(?s).*'UNKNOWN':\\s*'is-up'.*");
        assertThat(table).doesNotMatch("(?s).*'DOWN':\\s*'is-up'.*");
    }

    @Test
    void theVaierServer_isUpBecauseItIsServingThePage_andIsNeverProbed() throws IOException {
        // The third case. The Vaier server is the machine rendering this tree: if the operator can see the
        // rail at all, it is up. Probing it would be asking a question we are standing inside the answer to.
        String js = read("explorer-shell.js");
        assertThat(js).contains("const VAIER_SERVER = 'Vaier server'");   // LanAnchor.VAIER_SERVER_NAME

        int from = js.indexOf("function livenessOf(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        // it is answered from the constant, not from a probe or a cache of one
        assertThat(body).contains("VAIER_SERVER").contains("is-up");
    }

    @Test
    void lanLiveness_refreshesOnTheStreamTheShellIsAlreadyOn_neverOnATimer() throws IOException {
        // LanServerReachabilityService and LanServerScrapeService both publish `lan-servers-updated` on the
        // `vpn-peers` topic (pinned by LanServerReachabilityServiceTest and LanServerScrapeServiceTest) —
        // the very stream the shell already holds open. So LAN liveness costs no new endpoint, no new topic
        // and no poll: one more addEventListener on the EventSource that is already there.
        String js = read("explorer-shell.js");
        assertThat(js).contains("lan-servers-updated");
        assertThat(js).doesNotContain("setInterval");
        assertThat(js).doesNotContain("setTimeout");
        // exactly one stream — a second EventSource would be a second connection for data already on the first
        assertThat(js.split("new EventSource\\(", -1).length - 1).isEqualTo(1);
    }

    // --- 12. slice B: directories are entries -----------------------------------------------------------

    @Test
    void aDirectory_isReadLazilyWhenExpanded_neverEagerlyAndNeverRecursively() throws IOException {
        // The fleet is on the far side of a VPN. A tree that walks it eagerly is a tree that hangs, so the
        // rail's children come from a cache that only an expand fills — childrenOf() reads, it never fetches.
        String js = read("explorer-shell.js");
        // one directory per expand, read on demand
        assertThat(js).contains("async function readDir(");

        int from = js.indexOf("function childrenOf(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        // the rail's children are whatever the cache already holds — reading it can never start a read,
        // so no repaint of the tree can trigger an SFTP walk of the fleet
        assertThat(body).contains("S.dirs");
        assertThat(body).as("childrenOf must never reach for the network").doesNotContain("fetch(")
            .doesNotContain("readDir(").doesNotContain("await");
    }

    @Test
    void onlyDirectories_becomeEntriesInTheRail() throws IOException {
        // The rail carries structure; the Inspector lists the contents. Duplicating every file into the rail
        // would drown the structure the rail exists to show.
        String js = read("explorer-shell.js");
        assertThat(js).contains(".filter((e) => e.directory)");
    }

    @Test
    void aDirectoryIsReadOnce_andCachedByMachineAndPath() throws IOException {
        // Collapsing and re-expanding must not re-hit SFTP. The cache is keyed by machine *and* path: two
        // machines both have a /home, and they are not the same directory.
        String js = read("explorer-shell.js");
        assertThat(js).contains("dirKey");
        int from = js.indexOf("const dirKey");
        assertThat(from).isPositive();
        String line = js.substring(from, js.indexOf('\n', from));
        assertThat(line).contains("machine").contains("path");
    }

    @Test
    void aMachineLeavingTheFleet_doesNotStrandItsCachedDirectories() throws IOException {
        // A fleet reshape (peers-updated) that dropped a machine while its directories stayed in the cache
        // would leave the rail holding entries for a machine that no longer exists.
        String js = read("explorer-shell.js");
        assertThat(js).contains("function pruneDirs(");
    }

    @Test
    void aDirectoryThatCannotBeRead_failsVisiblyAndLocally() throws IOException {
        // ExplorerService already surfaces the real reason verbatim ("Not allowed to read /root as geir.").
        // The row must wear the failure — not pretend to be empty, and not spin forever — and the message
        // itself reuses the .ex-note.is-error affordance that is already in the stylesheet.
        String js = read("explorer-shell.js");
        assertThat(js).contains("'error'");
        assertThat(js).contains("is-failed");

        String css = read("explorer-shell.css");
        assertThat(css).contains(".ex-row.is-failed");
        assertThat(css).contains(".ex-note.is-error");   // the existing affordance, reused, not reinvented
    }

    @Test
    void everyDirectoryOwnsTheListingTicket_soConcurrentExpandsCannotPaintOverEachOther() throws IOException {
        // The race guard matters more here than anywhere. Each directory owns its own VaierListing browser,
        // and therefore its own monotonic ticket: a re-read of a directory supersedes the read before it,
        // while three directories expanded at once are three independent reads that all land. A single
        // shared ticket would be worse than none — the earlier expands would be declared stale and spin
        // forever, which is exactly the hang this guard exists to prevent.
        String js = read("explorer-shell.js");
        assertThat(js).contains("VaierListing.createBrowser()");
        assertThat(js).contains("result.stale");
        // the slot-identity check: a late answer whose cache slot was dropped under it must not resurrect it
        assertThat(js).contains("S.dirs.get(k) !== entry");
        // and no second race mechanism was rolled by hand
        assertThat(js).doesNotContain("inFlight");
    }

    @Test
    void thePalette_findsExpandedDirectories_withoutCrawlingTheFleetOverSftp() throws IOException {
        // Directories are entries now, so ⌘K must find them — but the index is built by walking childrenOf,
        // which only ever reads the cache. The palette can therefore see every directory the operator has
        // already opened, and cannot touch one they have not.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function index(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("childrenOf(");
        assertThat(body).as("the palette must never reach for the network").doesNotContain("fetch(")
            .doesNotContain("readDir(");
    }
}
