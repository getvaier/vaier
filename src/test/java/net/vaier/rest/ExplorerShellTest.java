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
        // If the shell were a section of admin.html it would be inside the very iframe it exists to retire. A
        // top-level redirect to it (a stale #infrastructure bookmark leaving admin for the shell, now that
        // Infrastructure is native) is the opposite of embedding, and allowed — so the guard is against
        // embedding the shell as a section, not against naming it in a navigation.
        String admin = read("admin.html");
        assertThat(admin).doesNotContain("data-page=\"explorer.html\"");
        assertThat(admin).doesNotContain("src=\"explorer.html\"");
        assertThat(admin).doesNotContain("src='explorer.html'");
    }

    // --- 3. one shell model: pop-out windows, no embedded dock ------------------------------------------

    @Test
    void theExplorer_hasNoEmbeddedDock_shellsAreWindowsOnly() throws IOException {
        // The Explorer opens a machine's shell in its own pop-out window (terminal-window.js), the single shell
        // model. The old embedded dock is gone from here entirely — its script, its mount points, and its
        // wiring — so there are never two shell systems to confuse. (terminal-dock.js itself stays for admin.html.)
        String shell = read("explorer.html");
        assertThat(shell).doesNotContain("terminal-dock.js");
        assertThat(shell).doesNotContain("id=\"terminalPanel\"");
        assertThat(shell).doesNotContain("id=\"terminalPanes\"");
        assertThat(shell).doesNotContain("<iframe src=\"terminal");
        String js = read("explorer-shell.js");
        assertThat(js).doesNotContain("TerminalDock");        // no dock wiring left in the Explorer
        assertThat(js).doesNotContain("watchDock");
    }

    @Test
    void aMachinesSshAccessSection_opensTheShellInItsOwnWindow() throws IOException {
        // The shell is no longer a tree entry — it opens from a machine's SSH-access section, beside the
        // credential it uses (a terminal is the most direct thing SSH access is for). It opens in its own
        // browser window; the Explorer has no bottom dock.
        String js = read("explorer-shell.js");
        assertThat(js).contains("function openShellWindow(");
        assertThat(js).contains("terminal.html?machine=");
        assertThat(js).doesNotContain("TerminalDock.open(");
        // Opened from the SSH-access section's own button.
        assertThat(js).contains("selVerb('shell', 'Open shell'");
        // The shell is not a navigable tree kind any more: no 'shell' child, no renderShell pane.
        assertThat(js).doesNotContain("kind: 'shell'");
        assertThat(js).doesNotContain("function renderShell(");
        // The primary shell window always reattaches to the machine's one stable session (deterministic, never
        // a scavenged orphan) — VaierPanes.primary carries that id in the window's URL.
        assertThat(js).contains("VaierPanes.primary");
    }

    @Test
    void aRepaint_neverSpawnsAShell() throws IOException {
        // A shell window opens only from an explicit click — the Open shell button in a machine's SSH-access
        // section. It must never be opened from go() or at the top level of a render function, or every repaint
        // (a machine coming online, a stats push) would open a window on its own.
        String js = read("explorer-shell.js");
        assertThat(js).contains("function openShellWindow(");

        int go = js.indexOf("function go(path) {");
        assertThat(go).isPositive();
        String goBody = js.substring(go, js.indexOf("\n    }", go));
        assertThat(goBody).as("navigating never opens a shell — that is an explicit click now")
            .doesNotContain("openShellWindow(");

        // Every call to openShellWindow (other than its definition) is a click handler, never an imperative call
        // made as a pane renders.
        for (String line : js.split("\n")) {
            if (line.contains("openShellWindow(") && !line.contains("function openShellWindow(")) {
                assertThat(line).as("openShellWindow reached only through a handler: %s", line)
                    .contains("=> openShellWindow(");
            }
        }
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
        // The shell is a new front for the API Vaier already has. Slice A opened nothing; slice C opened
        // exactly one endpoint (GET /machines/{machine}/disk); slice 2 (Move) opens the transfers side —
        // GET/POST /transfers — because a cross-machine copy is a genuinely new operation Vaier could not do
        // before. The backup-server designation moving into the tree adds the two backup endpoints it needs —
        // /backup-servers (list, PUT to designate/edit, DELETE to remove) and /backup-repositories (read-only,
        // to show what lives on the server) — both already there for the Backups page. Everything else here was
        // already reachable, and a fetch to anything outside this list would mean an endpoint was invented to
        // make the tree look finished. (The download is an <a href>, not a fetch, so it does not appear here.)
        List<String> allowed = List.of("/machines", "/vpn/peers", "/lan-servers", "/users/me",
                                       "/docker-services", "/published-services", "/access/services",
                                       "/transfers", "/backup-servers", "/backup-repositories", "/backup-jobs",
                                       "/settings", "/license", "/lan-scan");
        String js = read("explorer-shell.js");
        Matcher m = Pattern.compile("fetch\\([`']([^`']+)[`']").matcher(js);
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
        // Only two Vaier-wide globals are still framed: Users and Concepts. Settings is native (no settings.html),
        // Infrastructure is native (no vpn-peers.html), and Backups is native now too — the last fleet-level
        // bridge is gone, so backups.html is deleted and no longer framed.
        for (String page : List.of("users.html", "concepts.html")) {
            assertThat(js).as("bridge to %s", page).contains(page);
        }
        assertThat(js).doesNotContain("settings.html");   // ported to a native entry, not framed
        assertThat(js).doesNotContain("vpn-peers.html");  // Infrastructure is native — the bridge is gone
        assertThat(js).doesNotContain("backups.html");    // Backups is native — the bridge is gone (#323)
    }

    // --- 8b. the backup server's operations are native, not on a deleted page ---------------------------

    @Test
    void theBackupServerOperations_areNativeInTheShell_notFramed() throws IOException {
        String js = read("explorer-shell.js");
        // The three ops that used to live only on backups.html — provision the server (awaiting the settle the
        // backend pushes, never polled), authorize a host, download the setup script — are native on the backup
        // server's own entry now. Their endpoints and the provision-settled stream event prove it.
        assertThat(js).contains("/provision");
        assertThat(js).contains("provision-settled");
        assertThat(js).contains("/authorize/");
        assertThat(js).contains("/setup.sh");
        assertThat(js).contains("Server operations");
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
        // LAN liveness in particular still costs no second connection: it rides the vpn-peers stream that is
        // already open. (Slice C adds a second EventSource for a *different* topic, published-services — the
        // stream count itself is pinned by theShell_holdsExactlyTwoStreams below.)
        int fleetStream = js.indexOf("new EventSource('/vpn/peers/events')");
        assertThat(fleetStream).isPositive();
        assertThat(js.indexOf("lan-servers-updated")).isGreaterThan(fleetStream);
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

    // --- 13. slice C: containers, services and disk are entries ----------------------------------------

    @Test
    void aMachineGrowsOnlyTheEntriesVaierCanActuallyReach() throws IOException {
        // The tree must be honest about a machine rather than uniform. Files and disk ride on a held SSH
        // credential, so a machine with none grows neither — showing them off the SSH-access toggle alone
        // would open onto a red "no login" wall until a refresh. A machine that runs no Docker must not grow an
        // empty `containers` entry that opens onto nothing. /machines carries both facts (hasCredential,
        // runsDocker) — the tree asks them, it does not guess.
        // (The shell is not a tree entry — it opens from the machine's SSH-access section — so it is absent here.)
        String js = read("explorer-shell.js");
        int from = js.indexOf("if (kind === 'machine') {");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n        }", from));

        assertThat(body).contains("hasCredential");
        assertThat(body).contains("runsDocker");
        assertThat(body).contains("'files'").contains("'containers'").contains("'services'").contains("'disk'");
        assertThat(body).doesNotContain("'shell'");   // moved to the SSH-access section
    }

    @Test
    void noContainerVerbIsShipped_becauseNoEndpointBacksOne() throws IOException {
        // The point of the slice, and the precedent #321 set: shipping a control dead is a lie about what
        // works. DockerServiceRestController exposes only @GetMappings — Vaier cannot start, stop or restart
        // a container, and cannot fetch its logs. So the Inspector shows what Vaier knows and offers nothing
        // it cannot do. (Adding those endpoints is its own change, with its own security thinking.)
        String js = read("explorer-shell.js");
        // "Stop container" not bare "Stop": backups legitimately ship "Stop backing up" (a real DELETE endpoint
        // backs it), and the container guard is about container control that has NO endpoint. The endpoint
        // checks below (/stop, a /docker-services POST, /containers/) are the real teeth regardless.
        for (String verb : List.of("Restart", "Stop container", "Start container", "Logs", "/restart", "/stop",
                                   "/start")) {
            assertThat(js).as("a container verb (%s) with no endpoint behind it", verb).doesNotContain(verb);
        }
        // and nothing mutating is ever sent at a container
        assertThat(js).doesNotContain("'/docker-services', {").doesNotContain("/containers/");
    }

    @Test
    void theOnlyMutatingCallsInTheShell_areTheOnesThatReallyExist() throws IOException {
        // Four mutating verbs ship now, each backed by a real endpoint. DELETE unpublishes a service, deletes a
        // file/folder, removes the backup-server designation, deletes a repository, stops a machine's backup,
        // removes protected paths, and (regenerate) drops a peer before recreating it. PUT sets a disk watch
        // (#325), designates/edits the backup server, saves a repository, and replaces a service's allowed
        // groups. POST copies across the fleet (/transfers), starts a backup run, protects paths, adds a peer or
        // a LAN server, reissues a config, and publishes a service — publishing is native now, no longer a
        // bridge. PATCH is the Infrastructure edits ported in: a machine's name/description/LAN/device-category,
        // its SSH-access flag, and a published service's auth mode, alias, redirect, version probe and launchpad
        // visibility. The distinct verb set is pinned here; an invented one shows up.
        String js = read("explorer-shell.js");

        Matcher m = Pattern.compile("method:\\s*'([A-Z]+)'").matcher(js);
        List<String> methods = new java.util.ArrayList<>();
        while (m.find()) {
            methods.add(m.group(1));
        }
        assertThat(methods.stream().distinct().toList()).as("the shell's mutating verbs")
            .containsExactlyInAnyOrder("PUT", "DELETE", "POST", "PATCH");
        assertThat(js).contains("/published-services/");
        assertThat(js).contains("/disk/watch");
        assertThat(js).contains("'/transfers'");
        assertThat(js).contains("/files?path=");   // DELETE a file or folder (slice 5)

        // and it asks before it does it — unpublishing tears down a route and a DNS record
        int from = js.indexOf("async function unpublish(");
        assertThat(from).isPositive();
        assertThat(js.substring(from, js.indexOf("\n    }", from))).contains("confirm(");

        // deleting is destructive and irreversible, so it goes through the typed-name gate, not a bare confirm
        int del = js.indexOf("async function deleteEntry(");
        assertThat(del).isPositive();
        assertThat(js.substring(del, js.indexOf("\n    }", del))).contains("confirmTyped(");
    }

    @Test
    void aPublishedService_isFiledUnderTheMachineItRunsOn_byTheRuleTheInfrastructurePageAlreadyUses()
            throws IOException {
        // A published service is one thing with three homes: a container on a machine, a Traefik route and a
        // DNS record. Which machine it belongs to is decided exactly as the Infrastructure page decided it — a LAN
        // service by its LAN server (falling back to the relay peer when no registered LAN server matches),
        // and everything else by its host name, with the hub's own routes on the Vaier server. A second rule
        // here would put the same service under two different machines in two different pages.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function machineOfService(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).contains("isLanService");
        assertThat(body).contains("lanServerName").contains("hostName");
        assertThat(body).contains("VAIER_SERVER");
    }

    @Test
    void theInspectorForAService_saysWhereItsThreeHomesAre() throws IOException {
        // The single namespace exists precisely to hold this relationship together: the route, the machine
        // it is backed by, and the DNS record are three faces of one service. The Inspector names all three.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderService(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    function", from));

        assertThat(body).contains("dnsAddress");       // the DNS record
        assertThat(body).contains("hostAddress");      // the backend it routes to
        assertThat(body).contains("image");            // the container behind it
    }

    @Test
    void serviceLiveness_arrivesOnTheStreamThatAlreadyCarriesIt_neverOnAPoll() throws IOException {
        // published-services is an existing SSE topic: PublishingService, the controller and DockerEventListener
        // all publish on it. The shell listens. It does not poll — that is a hard project rule, and it is why
        // a second EventSource is right here and a setInterval never is.
        String js = read("explorer-shell.js");
        assertThat(js).contains("new EventSource('/published-services/events')");
        assertThat(js).contains("service-updated");
        assertThat(js).doesNotContain("setInterval");
    }

    @Test
    void theShell_holdsFourStreams_theFleet_itsServices_itsTransfers_andItsBackups() throws IOException {
        // Slice A held one; slice C added `published-services` (a real, existing topic); slice 2 (Move) added
        // `transfers`, a copy's live progress. Moving jobs onto their machines adds the conscious fourth —
        // `backups`, carrying run-settled so a launched backup's outcome arrives pushed, not polled. Each is a
        // real backend topic listened to, never invented. Four is the ceiling now. And still no clock of the
        // shell's own — no setInterval, and no setTimeout (the toast lives out a CSS animation, not a JS timer).
        String js = read("explorer-shell.js");
        assertThat(js.split("new EventSource\\(", -1).length - 1).isEqualTo(4);
        assertThat(js).contains("new EventSource('/transfers/events')");
        assertThat(js).contains("new EventSource('/backup-jobs/events')");
        assertThat(js).doesNotContain("setInterval");
        assertThat(js).doesNotContain("setTimeout");
    }

    @Test
    void everyFilesystem_isListed_notJustTheRootOne() throws IOException {
        // #325. The disk pane used to render one reading, because the server only ever took one: `df -P /`.
        // On the NAS that is the DSM system partition (88% by design) and /volume1 — 11.6 TB of borg backups
        // — was invisible. The pane walks the server's list now.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderDisk(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    function", from));
        assertThat(body).contains("held.filesystems.forEach");
    }

    @Test
    void eachFilesystem_saysWhatItIsJudgedAgainst_andTheVerdictIsTheServers() throws IOException {
        // The threshold and the verdict come from the server (the domain's RemoteDiskUsage.breaches, the same
        // predicate the alert email is sent from) — the browser renders them, it never recomputes "under
        // pressure" from the percentage.
        String js = read("explorer-shell.js");
        assertThat(js).contains("/disk");

        int from = js.indexOf("function filesystemBlock(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    function", from));
        assertThat(body).contains("thresholdPercent");
        assertThat(body).contains("aboveThreshold");
        assertThat(body).contains("mountPoint");
        assertThat(body).contains("size").contains("available");   // a percentage alone means nothing
        assertThat(body).as("the verdict is the server's").doesNotContain(" > ");
    }

    @Test
    void changingAWatch_reReadsFromTheServer_ratherThanRecomputingTheVerdict() throws IOException {
        // The breach verdict is the domain's. A new threshold has to come back FROM the server, or the pane
        // and the alert email would each be deciding "under pressure" for themselves — and they would drift.
        // The mount point travels in the body: a mount point is full of slashes.
        String js = read("explorer-shell.js");
        int from = js.indexOf("async function saveWatch(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    function", from));
        assertThat(body).contains("'PUT'");
        assertThat(body).contains("/disk/watch");
        assertThat(body).contains("mountPoint");             // in the body, never in the path
        assertThat(body).contains("loadDisk(machine)");      // the server re-decides
        assertThat(body).as("the verdict is never recomputed here").doesNotContain(" > ");
    }

    @Test
    void aDiskThatCannotBeRead_saysSo_andIsNeverPaintedAsAnEmptyDisk() throws IOException {
        // DiskUnreadableException -> 502 carrying its own sentence ("Vaier could not read the disk on ...").
        // An asleep machine must read as "Vaier cannot tell", never as 0% — a disk Vaier failed to read is
        // not a disk with room on it.
        String js = read("explorer-shell.js");
        int from = js.indexOf("async function loadDisk(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("error");        // the server's own message is kept, verbatim
        assertThat(body).doesNotContain("usedPercent: 0");
        assertThat(body).doesNotContain("filesystems: []");   // never "this machine has no disks"
    }

    @Test
    void thePalette_findsContainersAndServices_becauseTheyAreEntriesNow() throws IOException {
        // ⌘K walks childrenOf, so anything that is an entry is findable by its path. Containers and services
        // are entries now, which is the whole claim of the slice: one namespace, one search.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function childrenOf(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).contains("'container'");
        assertThat(body).contains("'service'");
        // and childrenOf still never reaches for the network — the palette cannot start a fleet-wide scrape
        assertThat(body).doesNotContain("fetch(").doesNotContain("await");
    }

    @Test
    void aPeersContainers_areFiledUnderItsMachineName_notItsWireGuardDirectoryName() throws IOException {
        // The three-way identity split, which slice A already met on the stats stream: /docker-services/peers
        // keys containers by the peer's *id* — the WireGuard directory name ("apalveien5") — while the tree,
        // /machines and every SSH lookup use the canonical machine name ("Apalveien 5"). Filing containers
        // under the id would put them under a machine that does not exist in the tree, and every peer would
        // show an empty `containers` entry while Vaier could see its containers perfectly well.
        //
        // The shell already holds the id -> name map (S.peerNames, built in loadFleet for exactly this
        // reason). loadContainers must go through it.
        String js = read("explorer-shell.js");
        int from = js.indexOf("async function loadContainers(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).as("peer containers must be keyed by machine name, via the id -> name map")
            .contains("peerNames");
    }

    @Test
    void theInfrastructurePage_isDeleted_itsFunctionPortedNatively() throws IOException {
        // Parity is real: everything vpn-peers.html owned — machine creation and editing, the LAN scan, the
        // world map, SSH credentials and access, setup scripts, config reissue and regeneration, publishing
        // with its advanced fields, the published-service editor and allowed groups, and discovered candidates
        // — is a native entry in the tree now. So the page and its assets are gone, and the bridge with them.
        assertThat(Files.exists(STATIC.resolve("vpn-peers.html"))).isFalse();
        assertThat(Files.exists(STATIC.resolve("vpn-peers.js"))).isFalse();
        assertThat(Files.exists(STATIC.resolve("vpn-peers-map.js"))).isFalse();
        assertThat(Files.exists(STATIC.resolve("vpn-peers-helpers.js"))).isFalse();
        assertThat(Files.exists(STATIC.resolve("vpn-peers.css"))).isFalse();

        // The native controls that replaced the page, pinned so a regression that quietly drops one is caught.
        String js = read("explorer-shell.js");
        assertThat(js).contains("function editMachineForm");     // machine editing
        assertThat(js).contains("function toggleSshAccess");     // SSH access, distinct from the credential
        assertThat(js).contains("function regenerateMachine");   // keypair rotation (#202)
        assertThat(js).contains("function lanSetupScript");      // the LAN host setup script
        assertThat(js).contains("function createLanServer");     // add a LAN server by hand
        assertThat(js).contains("function allowedGroupsEditor"); // per-service access groups
        assertThat(js).contains("function publishAdvanced");     // path prefix / redirect / direct-URL
    }

    // --- 14. #326: a machine's file tree begins at its SFTP root ----------------------------------------
    //
    // The NAS chroots its SFTP subsystem into /volume1 while its exec channel sees the real root, so one
    // directory has two names. The browser cannot deduce which — it has to be told, on every listing — and
    // until it is told it must not assume "/". Both Explorers read directories through the one reader, so the
    // invariant is asserted on all three assets.

    @Test
    void theOneDirectoryReader_asksForNoPathUntilTheMachineHasSaidWhereItsTreeBegins() throws IOException {
        String js = read("explorer-listing.js");

        // The old reader always sent ?path=..., so opening a machine meant sending "/". On the NAS "/" is the
        // one path SFTP cannot answer — it is above the jail. Omitting the parameter is the question "where
        // does this machine's tree begin?", and only the machine can answer it.
        assertThat(js).doesNotContain("/files?path=");
        assertThat(js).contains("path == null");
    }

    @Test
    void theOneDirectoryReader_carriesTheRootAndTheResolvedPathBack_notABareArrayOfEntries() throws IOException {
        String js = read("explorer-listing.js");

        // The listing is now { root, path, entries }: a bare array had nowhere to carry the root, and a
        // browser that assumed "/" opened the NAS on a path it cannot answer.
        assertThat(js).contains("body.root");
        assertThat(js).contains("body.path");
        assertThat(js).contains("body.entries");
    }

    @Test
    void theFileBrowser_opensAMachineAtItsOwnRoot_notAtASlashItAssumed() throws IOException {
        String js = read("explorer.js");

        // The backup file browser must keep working, and it must not keep the assumption that broke: no
        // hardcoded root constant, and the root it paints its crumbs from is the one the machine reported.
        assertThat(js).doesNotContain("const ROOT = '/'");
        assertThat(js).contains("result.root");
    }

    @Test
    void theShell_opensAMachineAtItsOwnRoot_andRemembersWhereEachMachinesTreeBegins() throws IOException {
        String js = read("explorer-shell.js");

        // A tree path under `files` is no longer just the machine path with a slash in front: it hangs off
        // the machine's root. The old one-line assumption is gone, and the root is remembered per machine —
        // one machine's jail must never be pinned onto another's paths.
        assertThat(js).doesNotContain("const remotePath = (path) => '/' + path.slice(3).join('/');");
        assertThat(js).contains("roots");
        assertThat(js).contains("result.root");
    }

    @Test
    void aPathOutsideTheJail_isShownAsTheServersOwnSentence_neverAsAnEmptyFolder() throws IOException {
        // The reader already passes Vaier's ApiError message through verbatim, and that is exactly what must
        // happen to "/volume2 is not reachable over SFTP...". No asset may turn a refusal into an empty
        // listing — so neither Explorer is allowed to paint entries when the read came back an error.
        String listing = read("explorer-listing.js");
        assertThat(listing).contains("err.message");

        assertThat(read("explorer.js")).contains("result.error");
        assertThat(read("explorer-shell.js")).contains("entry.error");
    }

    // --- 15. #57 slice 2: the Update available mark -----------------------------------------------------
    //
    // A stale vaultwarden image on apalveien5 broke Bitwarden sync with no signal to the operator. Slice 1
    // taught the domain to notice; this slice is the mark on the page. The whole risk of the slice is that the
    // browser starts deciding — comparing tags, or reading digests — so most of what is pinned here is what
    // the shell must NOT do.

    /** The helper that turns the backend's verdict into a mark, isolated so the rule can be read off it. */
    private static String updateMarkBody(String js) {
        int from = js.indexOf("function updateMark(");
        assertThat(from).as("the verdict -> mark helper").isPositive();
        return js.substring(from, js.indexOf("\n    }", from));
    }

    @Test
    void theShell_neverDecidesForItselfWhetherAnUpdateIsAvailable() throws IOException {
        // THE test of the slice, and hex rule 1. UpdateAvailability.compare() in the domain is the one place
        // the two digests are ever weighed — the sweep that raises the admin email and the mark on this page
        // must reach the same verdict from the same fact, and the only way to guarantee that is for the
        // browser never to hold the inputs at all. So the shell does not read imageDigest, does not know what
        // a sha256 is, and does not compare an image or a tag to anything: it reads one enum and paints it.
        String js = read("explorer-shell.js");
        assertThat(js).as("the digest is an input to the decision, and the decision is not the browser's")
            .doesNotContain("imageDigest");
        assertThat(js).doesNotContain("sha256");
        assertThat(js).doesNotContain("RepoDigests");

        // and the mark helper itself weighs nothing. It equality-checks one enum — which is the whole of its
        // logic — and it never touches the facts the verdict was computed FROM. If it ever reads an image or a
        // version, someone has started deciding here. (An == on the verdict is fine and is the point; what is
        // banned is string surgery on a tag, which is how a browser-side rule always begins.)
        String body = updateMarkBody(js);
        assertThat(body).contains("updateAvailable");
        assertThat(body).as("the inputs to the decision have no business in the browser")
            .doesNotContain(".image").doesNotContain(".version").doesNotContain("digest");
        assertThat(body).as("no tag string surgery — that is a rule in disguise")
            .doesNotContain("indexOf").doesNotContain("split").doesNotContain("startsWith");
    }

    @Test
    void aContainerWithAnUpdateAvailable_wearsAMarkInTheRail() throws IOException {
        // Container rows are first-class entries, so the rail is where an operator scanning the fleet sees it
        // without opening anything. Until now the machine's liveness dot was the only per-row mark.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function branch(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    function", from));
        assertThat(body).as("the rail's container rows carry the mark").contains("'container'");
        assertThat(body).contains("updateMark(");
    }

    @Test
    void anUnknownVerdict_paintsNoMarkAtAll() throws IOException {
        // UNKNOWN is the resting state, not an exception: the registry is unreachable, there is no egress, or
        // no sweep has run yet. A grey smudge on every container row for the first 24 hours of a deployment is
        // how an operator learns to stop reading the column — so unknown is silent, and only UPDATE_AVAILABLE
        // ever draws. (The nuance that silence is not a promise of "up to date" is carried in the single
        // container's Inspector, which has room for the honest sentence — see below.)
        String body = updateMarkBody(read("explorer-shell.js"));
        assertThat(body).contains("'UPDATE_AVAILABLE'");
        assertThat(body).as("unknown is not a state the mark renders").doesNotContain("'UNKNOWN'");
        assertThat(body).as("nor is up-to-date — a mark for it would be noise on every healthy row")
            .doesNotContain("'UP_TO_DATE'");
    }

    @Test
    void theMark_isAdvisory_neverAnAlarm() throws IOException {
        // Nothing is broken when an update exists — the container is running fine. Red is reserved for down
        // and failed, and it has to keep meaning that or the fleet's colours stop carrying information. The
        // mark takes the yellow the degraded dot already uses.
        String css = read("explorer-shell.css");
        int from = css.indexOf(".ex-update");
        assertThat(from).as("the mark's own rule").isPositive();
        String block = css.substring(from, css.indexOf("}", from));
        assertThat(block).contains("var(--yellow)");
        assertThat(block).doesNotContain("var(--red)");
    }

    @Test
    void theMark_saysWhatTheOperatorDoes_notWhatVaierWillDo() throws IOException {
        // Vaier is read-only for containers: there is no endpoint to pull an image or restart a container, and
        // shipping a mark that reads like a promise would be the same lie as shipping a dead button. The
        // tooltip names the operator's own action, and the canonical term is used exactly
        // (UBIQUITOUS_LANGUAGE.md) — not "outdated", not "stale", not "needs upgrade".
        String js = read("explorer-shell.js");
        String body = updateMarkBody(js);
        assertThat(body).contains("Update available");
        assertThat(body).contains("title");
        for (String banned : List.of("outdated", "stale", "drift", "needs upgrade", "Outdated", "Stale")) {
            assertThat(body).as("a near-synonym of \"Update available\" (%s)", banned).doesNotContain(banned);
        }
        // and the slice opened no verb: still only the three mutating methods, none of them aimed at a container
        assertThat(js).doesNotContain("/pull").doesNotContain("Pull now").doesNotContain("Update now");
    }

    @Test
    void theSingleContainer_saysWhichOfTheThreeVerdictsItIs_includingCannotTell() throws IOException {
        // Absence of a mark in the rail must never be read as a promise that the image is current. The rail
        // has no room to say so; the Inspector does, so this is where UNKNOWN is spoken aloud rather than
        // silently collapsed into "up to date" — which is precisely the lie #57 was filed about.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderContainer(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    // --- services", from));

        assertThat(body).contains("'Update'");
        assertThat(body).contains("updateAvailable");
        // all three verdicts are nameable here, and the third one is honest about not knowing
        assertThat(js).contains("Update available");
        assertThat(js).contains("Up to date");
        assertThat(js).containsIgnoringCase("cannot tell");
    }

    @Test
    void theContainerList_carriesTheSameMarkAsTheRail_fromTheSameHelper() throws IOException {
        // One verdict, one helper, two places it is drawn. A second copy of "when do we draw this" is a second
        // place it can drift from the domain.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderContainers(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    function renderContainer(", from));
        assertThat(body).contains("updateMark(");

        // drawn in exactly the two places, off the one helper
        assertThat(js.split("updateMark\\(", -1).length - 1)
            .as("the helper, and its two call sites").isEqualTo(3);
    }

    @Test
    void theMark_doesNotAppearWhenBrowsingTheArchive() throws IOException {
        // Same reasoning as the liveness dot, which the stylesheet hides in the past: an archive is a photo of
        // a filesystem, and "there is a newer image in the registry" is a fact about now. Reporting today's
        // registry against a container as it stood in March would be a claim about a moment that has passed.
        // The shield does this with an !S.at gate in JS, and the mark follows the nearer precedent.
        assertThat(updateMarkBody(read("explorer-shell.js"))).contains("S.at");
    }

    // --- 16. #57 slice 3: checking the registries on demand --------------------------------------------
    //
    // Slice 2 put the mark on the page but left it up to 24h behind the operator. They read the rollup mail,
    // SSH in, pull, and then want Vaier to agree — now. A mark you know is wrong is a mark you learn to
    // ignore, so a stale mark corrodes the whole feature. This is the button that settles it.
    //
    // The control is legitimate only because of what it does NOT do: it acts on Vaier's own knowledge and
    // never on a container. Most of what is pinned here is that distinction holding.

    private static String checkForUpdatesBody(String js) {
        int from = js.indexOf("async function checkForUpdates(");
        assertThat(from).as("the update check's handler").isPositive();
        return js.substring(from, js.indexOf("\n    }", from));
    }

    @Test
    void theShell_canAskVaierToCheckTheRegistriesNow() throws IOException {
        // The endpoint is the one the backend opened, and it is a POST: the check really goes and asks every
        // registry, which is a side effect with a rate limit behind it.
        String js = read("explorer-shell.js");
        assertThat(js).contains("/docker-services/image-updates/check");
        assertThat(checkForUpdatesBody(js)).contains("POST");
    }

    @Test
    void theCheckButton_saysItChecks_andCouldNotBeReadAsAPromiseToUpdate() throws IOException {
        // THE copy rule of the slice, and the reason the container Inspector offers no verbs at all: Vaier has
        // no endpoint to pull an image or restart a container, so a control that hinted otherwise would be a
        // dead button — the exact lie renderContainer() exists to refuse. "Check for updates" is the phrasing
        // every OS updater uses immediately before installing something, and that connotation is precisely
        // what must not attach here. The label names the read.
        String js = read("explorer-shell.js");
        assertThat(js).contains("Check the registries now");
        // and the slice still opens no verb aimed at a container
        assertThat(js).doesNotContain("/pull").doesNotContain("Pull now").doesNotContain("Update now")
            .doesNotContain("Update all").doesNotContain("Restart");
    }

    @Test
    void theCheckButton_isHonestWhileItWorksAndAfterwards() throws IOException {
        // Clicking and seeing nothing happen is the failure this button exists to avoid, so it says it is
        // working; and "nothing new" is a real answer rather than a failure, so it says that too. Neither
        // sentence may imply Vaier changed anything — it read, and that is all it ever does.
        String js = read("explorer-shell.js");
        assertThat(js).contains("Checking the registries…");
        assertThat(js).containsIgnoringCase("nothing new");
    }

    @Test
    void aCoalescedCheck_saysVaierDidNotCheck_ratherThanClaimingItDid() throws IOException {
        // The floor's honesty rule reaching the page. The backend may refuse to re-ask the registries (a
        // click-spammed forced check is a direct route to a 429, which would degrade every image to unknown
        // and blind the fleet). When it refuses, the UI must not paint "Checked!" over a check that never
        // happened — that is the same species of lie as the stale mark, told faster.
        String js = read("explorer-shell.js");
        assertThat(js).contains(".checked");
        assertThat(js).as("it reports when Vaier last really looked").contains("lastCheckedAt");
    }

    @Test
    void theCoalescedMessage_readsTheFact_ratherThanRestatingTheFloorsLength() throws IOException {
        // The floor's duration is a domain constant (UpdateCheckFloor). Spelling it out in English here —
        // "checked less than a minute ago" — would copy that decision into the browser, where changing
        // UpdateCheckFloor to five minutes leaves the sentence confidently false. That is precisely the
        // wrong-but-confident claim this whole feature exists to stop making, so it must not be reintroduced
        // by the fix for it. lastCheckedAt is on the wire for exactly this reason; the UI renders that.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function updateCheckNote(");
        assertThat(from).as("the helper that speaks the outcome").isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).as("the fact the backend sent, not a copy of the rule").contains("lastCheckedAt");
        assertThat(body).as("no duration restated in prose")
            .doesNotContain("a minute").doesNotContain("60").doesNotContain("minutes ago");
    }

    @Test
    void theCheckResult_isPushed_notPolled() throws IOException {
        // Hard project rule, and slice 2's known gap: a settled sweep did not repaint an open Explorer. The
        // backend publishes on `published-services`/`service-updated` — the topic the container payloads
        // already ride — and watchServices() already re-reads containers on it, so the repaint needs no new
        // stream and above all no poll.
        String js = read("explorer-shell.js");
        assertThat(js).doesNotContain("setInterval");
        assertThat(js).doesNotContain("setTimeout(checkForUpdates");
        int from = js.indexOf("function watchServices(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).as("the stream that carries a re-checked verdict home").contains("service-updated");
    }

    @Test
    void theShell_stillNeverDecidesWhetherAnUpdateIsAvailable() throws IOException {
        // Slice 2's central invariant, re-pinned now that the browser can trigger the decision. Triggering a
        // check is not making one: the handler asks the backend and reads the answer, and must not acquire a
        // taste for digests on the way.
        String body = checkForUpdatesBody(read("explorer-shell.js"));
        assertThat(body).doesNotContain("digest").doesNotContain("sha256");
        assertThat(body).as("the verdict is the domain's; the browser only asks for it to be re-taken")
            .doesNotContain("UPDATE_AVAILABLE");
    }

    @Test
    void theCheck_isOneControl_whereTheOperatorLandsAfterPulling() throws IOException {
        // Judgement, pinned so it is argued rather than drifted into. The operator's move is
        // `docker compose pull && up -d` on ONE machine and then a look at that machine's containers — so the
        // control belongs on that list, not on each container's Inspector (they pulled a whole stack, not one
        // image) and not in three places. The check it triggers is fleet-wide because the backend's sweep is:
        // it re-scrapes and re-asks for everything Vaier can see, and a per-machine button would be a lie
        // about what actually happens.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderContainers(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    function renderContainer(", from));
        assertThat(body).as("the control sits on the machine's containers list").contains("checkForUpdates");

        assertThat(js.split("checkForUpdates\\(", -1).length - 1)
            .as("the handler, its one call site, and nothing else").isEqualTo(2);
    }

    @Test
    void theCheck_isNotOfferedWhileBrowsingTheArchive() throws IOException {
        // Same rule as the mark it re-evaluates: the registry's answer is a fact about now, and offering to
        // re-check it against a filesystem as it stood in March would be a claim about a moment that passed.
        assertThat(checkForUpdatesBody(read("explorer-shell.js"))).contains("S.at");
    }

    @Test
    void theCheckReceipt_doesNotFollowTheOperatorAround() throws IOException {
        // "Checked just now" is true for about a minute. It is the receipt for an action, not a fact about the
        // fleet, so navigating away drops it — otherwise it sits there going quietly stale on a pane the
        // operator never checked, which is precisely the class of lie this whole feature exists to stop
        // telling. The verdicts the check settled are on the container rows themselves and do persist.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function go(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).as("go() drops the receipt, as it already drops the selection")
            .contains("_updateCheck = null");
    }

    @Test
    void stoppingABackup_reportsWhatReallyStopped_neverWhatTheBrowserAskedFor() throws IOException {
        // The bug this whole change exists for ended here: the browser counted the paths it SENT, so a request
        // that removed nothing still said "Stopped backing up 1 item." about a folder borg kept backing up
        // every night. The count now comes from the backend's own account of what stopped, and a request that
        // changed nothing says exactly that.
        String js = read("explorer-shell.js");
        int from = js.indexOf("async function selUnbackup(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).as("the count is the backend's, read off the response").contains(".stopped");
        assertThat(body).as("a no-op is reported as a no-op").contains("Nothing changed");
        // The only honest use of "everything I asked for" is the 204: the whole job is gone, so it all stopped.
        assertThat(body.split("paths\\.length", -1).length - 1)
            .as("the sent-path count survives only in the job-deleted branch").isEqualTo(1);
        assertThat(body.indexOf("paths.length")).isGreaterThan(body.indexOf("204"));
    }

    @Test
    void aJobsExcludedPaths_areShownBesideWhatItProtects() throws IOException {
        // An exclude is the operator's own "stop backing this up" made durable. Listing the protected paths
        // while hiding the holes carved out of them would overstate what is in the archives.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderOneJob(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    function ", from + 10));
        assertThat(body).contains("job.excludes");
        assertThat(body).as("named in the operator's words, not borg's").contains("Not backed up");
    }

    @Test
    void stopBackingUp_staysOfferedOnAFolderThatIsBackedUpButHoled() throws IOException {
        // A protected folder with an excluded folder inside it now wears the HALF shield (it is not whole), so
        // gating "Stop backing up" on the full shield alone would quietly take the button away from /home the
        // moment anything under it was excluded. The precondition is "is any of this in the archive", which is
        // either shield — and the backend still reports truthfully what actually stopped.
        String js = read("explorer-shell.js");
        int from = js.indexOf("async function selUnbackup(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).as("gated on the shared predicate, not on the full shield").contains("anyBackedUp(s)");

        int bar = js.indexOf("function renderSelectionBar(");
        String barBody = js.substring(bar, js.indexOf("\n    }", bar));
        assertThat(barBody).as("the bar offers the verb on the same rule it is executed on")
            .contains("anyBackedUp");
        // ...and that one predicate is what reads both shields, so the two sites can never drift apart.
        assertThat(js).contains("const anyBackedUp = (s) => !!s.backedUp || !!s.containsBackedUp;");
        // The selection has to carry the half shield for any of that to work.
        assertThat(js).contains("containsBackedUp: !!entry.containsBackedUp");
    }

    @Test
    void backingUpAsRoot_isASettingTheOperatorCanSee_notOneBuriedInTheApi() throws IOException {
        // Colina 27 ran non-root over /home for months, skipping every file another user owned, because the
        // one setting that decides whether a backup of /home is real had no control anywhere in the shell.
        // It has one now, on the machine's backup pane, using the same checkbox idiom as SSH access.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderOneJob(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    function ", from + 10));

        assertThat(body).as("the flag is read off the job").contains("job.backupAsRoot");
        assertThat(body).as("the shell's existing checkbox row, not a new widget").contains("checkRow(");
        assertThat(body).as("the consequence in the operator's words, not the mechanism")
            .contains("owned by other users");
    }

    @Test
    void theBackupAsRootToggle_ridesTheJobEndpointThatAlreadyExists() throws IOException {
        // No endpoint was opened for this: the flag lives on the job spec, so the toggle re-PUTs the whole
        // job with it flipped — the same route the rest of the job's fields already travel.
        String js = read("explorer-shell.js");
        int from = js.indexOf("async function toggleBackupAsRoot(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).contains("'/backup-jobs/' + encodeURIComponent(job.name)");
        assertThat(body).contains("method: 'PUT'");
        assertThat(body).as("every field is carried through, so a toggle never drops the job's paths")
            .contains("backupAsRoot: on");
    }

    @Test
    void anIncompleteRun_readsAsTroubleAndPointsAtTheSettingThatWouldFixIt() throws IOException {
        // INCOMPLETE is a real outcome now (the archive exists but is missing files borg could not read), so
        // the shell must colour it like the trouble it is and say what to do — not leave it as an unstyled
        // status word the operator has to interpret.
        String js = read("explorer-shell.js");
        assertThat(js).as("the run dot knows the outcome").contains("INCOMPLETE:");
        int from = js.indexOf("function renderOneJob(");
        String body = js.substring(from, js.indexOf("\n    function ", from + 10));
        assertThat(body).as("the diagnostics note opens for an incomplete run too").contains("'INCOMPLETE'");
        assertThat(body).as("said plainly, in the operator's words").contains("not backed up");
    }

    // --- the time rail holds its own room ----------------------------------------------------------------

    @Test
    void theTimeRail_takesItsRoomBeforeItsStopsLand() throws IOException {
        // A machine's archive list is fetched after its directory is already on screen. A rail that renders
        // nothing until that lands therefore drops a whole bar into the page mid-read and shoves the rows
        // down under the operator's eyes — the listing moves while they are reading it. Whether a rail is
        // coming is a cheaper question Vaier can already answer at first paint: is there a job backing this
        // machine up? (loadBackup runs at boot.) If so the rail is drawn straight away and the stops fill
        // into a track that is already there, so nothing moves when the answer arrives.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderRail(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).as("a backed-up machine's rail is drawn before its archives land")
            .contains("jobsOn(machine)");
        assertThat(body).as("the rail says it is still waiting rather than claiming an empty past")
            .contains("is-waiting");
    }

    @Test
    void aMachineWithNoPast_stillGrowsNoTimeRail() throws IOException {
        // Holding the room is for machines that will have stops. A machine no job backs up has no past to
        // show and never will, so it keeps the plain listing it has always had — reserving space there would
        // trade one wrong layout for another.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderRail(");
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).as("no job and no archives — nothing is rendered")
            .contains("document.createDocumentFragment()");
    }

    @Test
    void theShell_isNeverWiderThanTheScreen() throws IOException {
        // An implicit grid column is max-content sized, so the shell was as wide as its widest row — and the
        // topbar's crumb trail grows with the path. Standing deep in a tree on a phone made the whole page
        // wider than the screen and every surface under it scrolled sideways.
        String css = read("explorer-shell.css");
        int from = css.indexOf(".ex-app {");
        assertThat(from).isPositive();
        String rule = css.substring(from, css.indexOf('}', from));
        assertThat(rule).as("the shell's one column may be narrower than its contents")
            .contains("grid-template-columns: minmax(0, 1fr)");
    }

    // --- the stylesheets are parseable at all ------------------------------------------------------------

    @Test
    void everyStylesheet_hasBalancedCommentsAndBraces() throws IOException {
        // An edit once left comment prose and a stray `*/` with no opening `/*`. CSS does not fail loudly:
        // the parser took the prose as a selector and silently dropped the whole rule after it, so the
        // listing lost its layout while every neighbouring rule still applied — a page that looked
        // catastrophically broken with every test green. A stylesheet that cannot be parsed is a bug the
        // suite should catch, not the operator.
        for (String sheet : List.of("explorer-shell.css", "explorer.css", "styles.css",
                                    "terminal-window.css")) {
            String css = read(sheet);
            int opens = css.split("/\\*", -1).length - 1;
            int closes = css.split("\\*/", -1).length - 1;
            assertThat(closes).as("%s: %d comment openings, %d closings", sheet, opens, closes)
                .isEqualTo(opens);

            // Every `*/` must be preceded by a `/*` that is still open — a stray closer is the exact shape
            // of the bug, and counting alone would not see it if an edit also dropped an opener elsewhere.
            int depth = 0;
            for (int i = 0; i < css.length() - 1; i++) {
                if (css.charAt(i) == '/' && css.charAt(i + 1) == '*') { depth++; i++; }
                else if (css.charAt(i) == '*' && css.charAt(i + 1) == '/') {
                    depth--;
                    assertThat(depth).as("%s: a `*/` at offset %d closes a comment that was never opened",
                        sheet, i).isNotNegative();
                    i++;
                }
            }

            String code = css.replaceAll("(?s)/\\*.*?\\*/", "");
            assertThat(code.chars().filter(c -> c == '{').count())
                .as("%s: braces balance", sheet)
                .isEqualTo(code.chars().filter(c -> c == '}').count());
        }
    }

    // --- the listing on a phone -------------------------------------------------------------------------

    @Test
    void theRowActions_neverJoinTheGrid_soARowIsAlwaysOneLine() throws IOException {
        // The bug that made every entry wrap: on a touch device the per-row action box dropped to
        // `position: static`, which turned it from an overlay into a FIFTH item in a three-column grid. It
        // wrapped onto a second line and landed in the 26px checkbox column, where three buttons wrapped
        // again. The box is designed as an overlay and must stay one — touch only ever changes how lit it is.
        String css = read("explorer-shell.css");
        int from = css.indexOf("@media (hover: none) { .ex-lactions");
        assertThat(from).as("the touch rule for the action overlay is still there").isPositive();
        String rule = css.substring(from, css.indexOf('}', from));

        assertThat(rule).as("touch changes how lit it is, never where it sits")
            .doesNotContain("position:");
    }

    @Test
    void aPhoneRow_carriesItsFactsOnASecondLine_notInColumns() throws IOException {
        // Columns are a desktop idea: three of them on a 390px screen leaves a filename about twelve
        // characters of room. Every phone file browser worth copying (iOS Files, Files by Google, Dropbox)
        // uses one two-line row instead — name, then its facts underneath, quieter and smaller.
        String js = read("explorer-shell.js");
        String css = read("explorer-shell.css");
        assertThat(js).as("the row carries a secondary line").contains("ex-lsub");
        assertThat(css).as("laid out as two lines, not wrapped by accident").contains("grid-template-areas");
        assertThat(css).as("and the column cells step aside on a phone").contains(".ex-lrow .ex-lmeta");
    }

    @Test
    void theSecondLine_dropsAColumnsPlaceholderDash() throws IOException {
        // A SIZE column can hold "—" for a directory because the heading above says what is missing. On one
        // line there is no heading, so a leading "— ·" is a placeholder for a column that is not there.
        String js = read("explorer-shell.js");
        int from = js.indexOf("sub.textContent = [size.textContent");
        assertThat(from).as("the file browser's second line").isPositive();
        assertThat(js.substring(from, from + 160)).contains("!== '—'");
    }

    @Test
    void everyListing_getsTheSecondLine_notJustTheFileBrowser() throws IOException {
        // The container and published-service listings share the same row builder. Hiding the columns on a
        // phone without giving those rows the second line would not rearrange their facts, it would delete
        // them — a container row would be a name and nothing else, no image and no state.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function listRow(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).as("the shared row carries one too").contains("ex-lsub");

        // …and the wide listings' own column rule outranks a media query, so it is overridden by name.
        String block = read("explorer-shell.css").substring(
            read("explorer-shell.css").indexOf("@media (max-width: 760px)"));
        assertThat(block).contains(".ex-listing.is-wide .ex-lmeta");
    }

    @Test
    void aPhoneRow_hasNoPerRowVerbs_becauseTheSelectionBarCarriesThem() throws IOException {
        // Three icon buttons per row is what made it cramped, and it is not how phone file browsers work.
        // Every one of those verbs — Copy, Download, Delete — is already on the selection bar, which rises
        // the moment anything is ticked. So on a phone the row is a thing, and the bar is the verbs.
        String css = read("explorer-shell.css");
        int phone = css.indexOf("@media (max-width: 760px)");
        assertThat(phone).isPositive();
        String block = css.substring(phone);
        assertThat(block).contains(".ex-lrow .ex-lactions { display: none; }");
    }

    @Test
    void theSelectionBar_sitsAtTheFootOfAPhone_andThePaneKeepsRoomForIt() throws IOException {
        // The rows handed their verbs to this bar, so it has to be somewhere a thumb reaches without
        // scrolling back up. Fixed means out of the flow, which would put it over the last row of the very
        // listing being picked from — so the shell flags that a selection exists and the pane pads itself.
        String css = read("explorer-shell.css");
        String js = read("explorer-shell.js");
        String block = css.substring(css.indexOf("@media (max-width: 760px)"));

        assertThat(block).as("the bar leaves the flow").contains("position: fixed");
        assertThat(block).as("and the pane makes room under it").contains(".ex-app.has-sel .ex-pane-body");
        assertThat(js).as("which only the shell can know").contains("'has-sel'");
    }

    @Test
    void aFolderRow_isTappableAcrossItsWholeWidth_onAPhone() throws IOException {
        // A 14px filename is a thumb-hostile target. Phone file browsers make the whole row open the folder
        // and leave the checkbox as the one thing inside it that does something else.
        String css = read("explorer-shell.css");
        String block = css.substring(css.indexOf("@media (max-width: 760px)"));
        assertThat(block).as("the name's hit area is stretched over the row").contains("button.ex-lname::before");
        assertThat(block).as("and the checkbox stays above it").contains(".ex-lrow .ex-check");
    }

    // --- trouble is visible from the tree ----------------------------------------------------------------

    @Test
    void aMachinesBackupEntry_wearsItsLastOutcomeInTheTree() throws IOException {
        // The point of a tree is that you do not have to walk it. A failed run that is only visible once an
        // operator opens that machine's Backup pane is a failure nobody sees, so the entry carries the dot.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function backupDot(");
        assertThat(from).as("the tree has a dot for a machine's backup").isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).as("coloured by the job's last outcome").contains("lastRunStatus");
        assertThat(body).as("through the one map the job pane already uses, so the two cannot disagree")
            .contains("RUN_DOT[");
    }

    @Test
    void aJobThatHasNeverRun_getsTheIdleDot_notTheGreenOne() throws IOException {
        // "Not yet" is not success. Colouring an unrun job green would make the tree promise data is safe
        // before a single archive exists.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function backupDot(");
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("is-idle");
        assertThat(body).doesNotContain("is-up");
    }

    @Test
    void theBackupEntryDot_isReadFromTheJobListAlreadyLoaded_notANewRead() throws IOException {
        // Painting the tree must not fire a request per machine. The job list lands once at boot and now
        // carries the outcome, so the dot costs nothing to draw.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function backupDot(");
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).as("read off the loaded jobs").contains("jobsOn(");
        assertThat(body).as("and never fetched").doesNotContain("fetch(");
    }

    // --- the operator points at a server and at data, and never learns borg's nouns ----------------------

    @Test
    void theBackupServer_listsTheMachinesItKeeps_notRepositories() throws IOException {
        // "Repository" is a borg noun. The operator's model is that the NAS keeps the backups of machines,
        // and that is true by construction: Vaier creates exactly one store per machine and names it after
        // it. So the store is shown as the machine whose backups are in it, and the job it belongs to is
        // what says which machine that is.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function repoLabel(");
        assertThat(from).as("a store knows how to say whose backups it holds").isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).as("read from the job, which is the truth of the mapping")
            .contains("repositoryName");
        assertThat(body).contains("machineName");
    }

    @Test
    void aStoreNoMachineClaims_saysSo_ratherThanImpersonatingAMachine() throws IOException {
        // Unclaimed stores are real: one adopted from before Vaier, or the leftover of a machine that was
        // renamed. Labelling one with a machine name would invent a machine; labelling it silently would
        // hide backups nobody is watching.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function repoLabel(");
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).as("it falls back to its own name, never to a guess").contains("repoName");
    }

    @Test
    void nothingOffersToCreateABackupStoreByHand() throws IOException {
        // Vaier creates one per machine, behind the Back up verb, with a passphrase nobody types. A "New
        // repository" button asked the operator to do by hand the one thing that already produced a
        // data-loss bug: a second store minted with a fresh passphrase over a live borg repository, after
        // which borg could no longer decrypt what was there.
        String js = read("explorer-shell.js");
        assertThat(js).doesNotContain("New repository");
    }

    @Test
    void theBackupServerEntry_opensOnWhatItKeeps_notOnItsCoordinates() throws IOException {
        // The operator made exactly one decision about this machine: the fleet's backups belong here. The
        // borg user, the paths under it, the port — Vaier chose all of them, and none is a decision to
        // revisit. Provision and Authorize a host are things Vaier already does; on the surface they asked
        // the operator to judge buttons they have no way to judge.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderServerBackup(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body.indexOf("Backups kept here"))
            .as("what it keeps comes first").isLessThan(body.indexOf("Server details"));
        assertThat(body.split("section\\('Backups kept here'\\)", -1).length - 1)
            .as("and it heads the list exactly once").isEqualTo(1);
        assertThat(body).as("and the mechanism folds").contains("disclosure('Server details')");
        assertThat(body.indexOf("'Provision'"))
            .as("the operations live inside the fold, as the fallback they are")
            .isGreaterThan(body.indexOf("disclosure('Server details')"));
    }

    // --- back up is offered only when it can actually work -----------------------------------------------

    @Test
    void backUp_isNotOfferedWhileTheFleetHasNoBackupServer() throws IOException {
        // A verb that cannot work is not a verb. With no backup server designated, "Back up" used to appear,
        // be clicked, and come back as a refusal from the backend — asking the operator to discover by
        // failing. Vaier does everything behind this button without asking; the one thing it cannot decide
        // for them is which machine holds the fleet's data, and that decision has its own nudge.
        String js = read("explorer-shell.js");
        int from = js.indexOf("const backupEligible =");
        assertThat(from).isPositive();
        String rule = js.substring(from, js.indexOf(';', from));

        assertThat(rule).as("a designated server is required, not merely respected")
            .contains("!!S.backupServer");
        assertThat(rule).as("and the server itself is still never a client of itself")
            .contains("machine !== S.backupServer.machineName");
    }

    // --- a failure that names its own fix ----------------------------------------------------------------

    @Test
    void aRunThatFailedForAMissingBorgClient_offersTheOneActionThatFixesIt() throws IOException {
        // The old message named "Prepare client" — a button on the Backups page, which was deleted when the
        // Explorer absorbed it. So a machine could sit failing every night while the fix it named existed
        // nowhere on screen. The action lives where the failure is reported now.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderOneJob(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    function ", from + 10));

        assertThat(body).as("driven by the domain's verdict, not by reading the error text")
            .contains("needsClientReadying");
        assertThat(body).as("and it offers the action in the operator's words")
            .contains("Get this machine ready");
    }

    @Test
    void readyingAMachine_usesThePrepareClientRouteThatAlreadyExists() throws IOException {
        // No endpoint was opened for this: the route survived the page that used to call it.
        String js = read("explorer-shell.js");
        int from = js.indexOf("async function readyClient(");
        assertThat(from).as("the shell can ready a host").isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).contains("/prepare-client");
        assertThat(body).contains("method: 'POST'");
        assertThat(body).as("the three outcomes go through the one handler that already knows them")
            .contains("startReadying");
    }

    @Test
    void aHostVaierCannotGetRootOn_keepsItsCommandOnScreen_notInAToast() throws IOException {
        // Where Vaier cannot gain root it hands over one `sudo bash …` line. A toast is the wrong home for a
        // command someone has to retype into another machine — it is gone before they have read it.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function startReadying(");
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).as("the staged command is kept, not just announced").contains("S.readying");
    }

    // --- the backup server wears its role --------------------------------------------------------------

    @Test
    void theBackupServer_wearsItsRoleAsACapabilityGlyph() throws IOException {
        // The one machine holding the fleet's archives is worth seeing without opening it. The device shape
        // cannot say it: the NAS wears `nas` because it is a NAS, and any machine can be designated the
        // server — so the role gets a glyph of its own beside relay and Docker.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function machineCaps(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).as("the strip asks which machine is the server").contains("S.backupServer");
        assertThat(body).as("and marks it with the role's own glyph").contains("'backupserver'");
        assertThat(js).as("which is in the icon set").contains("backupserver:");
    }

    @Test
    void theBackupServerGlyph_isNotTheDeviceShapeAndNotTheShield() throws IOException {
        // Reusing `nas` would say "storage appliance" on a machine that already says that, and reusing the
        // shield would say "this machine is backed up" — which is the opposite of what a store is.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function machineCaps(");
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).doesNotContain("'nas'");
        assertThat(body).doesNotContain("'shield'");
    }

    @Test
    void theWaitingTimeRail_isStyledToLookUnfinished_notEmpty() throws IOException {
        // The reserved rail is real chrome with nothing on it yet. Without a mark for that state it reads as
        // "this machine has no backups", which is the opposite of true.
        assertThat(read("explorer-shell.css")).contains(".ex-rail.is-waiting");
    }
}
