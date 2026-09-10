package net.vaier.rest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Explorer shell (#323 slice A): the fleet as one address space, at a page of its own.
 *
 * <p>The shell is the first Vaier page that is not a section inside {@code admin.html}'s iframe — it carries
 * its own topbar, its own address bar, and the terminal dock itself. That inversion is the point of the epic (the
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
    void aMachinesShell_opensInItsOwnWindow_fromTheWaysIntoTheMachine() throws IOException {
        // The shell is not an entry, and it is no longer filed under a heading named after its transport
        // either. It is one of the ways INTO a machine — a card beside files, containers, disk and backups —
        // because that is what an operator wants it for; SSH is only how it travels. It opens in its own
        // browser window; the Explorer has no bottom dock.
        String js = read("explorer-shell.js");
        assertThat(js).contains("function openShellWindow(");
        assertThat(js).contains("terminal.html?machine=");
        assertThat(js).doesNotContain("TerminalDock.open(");
        // A door among the doors, in the same grid and greyed by the same two rules as files and disk.
        assertThat(js).contains("card(svg('shell', 'ex-ico'), 'shell',");
        // The shell is not a navigable kind any more: no 'shell' child, no renderShell pane.
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
        // before. The backup-server designation moving into the shell adds the two backup endpoints it needs —
        // /backup-servers (list, PUT to designate/edit, DELETE to remove) and /backup-repositories (read-only,
        // to show what lives on the server) — both already there for the Backups page. Everything else here was
        // already reachable, and a fetch to anything outside this list would mean an endpoint was invented to
        // make the shell look finished. (The download is an <a href>, not a fetch, so it does not appear here.)
        // /survival-kit joins it with the Settings section that writes the fleet's kits: the endpoint is the
        // one the API already had, and the section is a front for it rather than a reason for it to exist.
        // /security is #329 Slice 3's addition, and it is the same shape of justification: CrowdSec has been
        // blocking at the edge since Slice 1 and Vaier has been reading those decisions since Slice 2 — the
        // Security view and the Map's threat layer are a front for an engine that already runs, not an
        // endpoint invented so a view would have something to show.
        // /fleet-credentials is the one entry here with the opposite justification, and it is stated rather
        // than smuggled in: the endpoint did not pre-date its view. A fleet credential is a genuinely new
        // capability, and its REST surface and its Credentials view shipped together — so this list now
        // means "no endpoint was invented to make the shell look finished", which is the rule that was
        // always meant, rather than the stricter "nothing here is new" that happened to hold until now.
        // Claude sign-in adds no entry at all — and it no longer fetches from this file either: the UI
        // moved into the pop-out shell window (claude-sign-in.js), so what the shell keeps is the machine
        // card's mark, drawn from the fleet standings the five-minute sweep already took. Every call the
        // sign-in makes — status, start, code, cancel, sign out — is under /machines, which was already
        // here. It briefly had a /claude-sign-ins fleet read; that was removed because painting one pane
        // must not SSH to the whole fleet, and an endpoint with no caller is exactly the machinery
        // CLAUDE.md says not to carry.
        List<String> allowed = List.of("/machines", "/vpn/peers", "/lan-servers", "/users/me",
                                       "/docker-services", "/published-services", "/access/services",
                                       "/transfers", "/backup-servers", "/backup-repositories", "/backup-jobs",
                                       "/settings", "/lan-scan", "/survival-kit", "/security",
                                       "/fleet-credentials", "/vpn/enrolments", "/ask");
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

    /**
     * The address space stands on machine IDENTITIES, not names (§6.22 step 2c). This is the guard that keeps
     * it that way, because the failure is silent in both directions: a name where an id belongs 404s (it did, for
     * folder selection, for weeks), and an id where a name belongs renders a UUID at a person.
     *
     * <p>What is checked is the crossing, not every call site: there is exactly one function that turns an
     * identity into something to read, and no function at all that turns a name into an identity — except at
     * the moment a machine is created, where the name is all the create response gives back.
     */
    @Test
    void theShell_addressesMachinesByIdentity_andCrossesToANameOnlyToShowOne() throws IOException {
        String js = read("explorer-shell.js");

        // The lookup this refactor exists to delete — now gone in every form. The last one to go was
        // justCreated(), which re-read the fleet after a create and matched on the name the operator had
        // just typed; every create endpoint answers with the machine's identity instead.
        assertThat(js).as("no name -> id lookup anywhere").doesNotContain("function midOf(");
        assertThat(js).doesNotContain("window.vaierMachineIdOf");
        assertThat(js).doesNotContain("function justCreated(");

        // The fleet's own entries: the segment is the identity, the label is the name.
        assertThat(js).contains("{ name: m.id, kind: 'machine', label: m.name }");
        assertThat(js).as("machines are never navigated to by name")
            .doesNotContain("go(['fleet', m.name");

        // And the one crossing back, which every displayed name goes through.
        assertThat(js).contains("function nameOf(machineId)");
        assertThat(js).contains("window.vaierMachineName = nameOf");
    }

    @Test
    void aTickedFile_isRememberedByItsMachinesIdentity_notItsName() throws IOException {
        // A ticked row is a coordinate the bulk verbs act on, and every one of them addresses its machine as
        // /machines/{id}. When the file pane keyed the selection by the display name instead — which it did,
        // because the pane's local for the name was called `machine` and read like an identity — the verbs
        // all sent /machines/Colina%2027, the controller could not parse that as an identity, and every one
        // came back 404. It looked like "Stop backing up does nothing": no request logged, nothing changed.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderDirectory(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).as("ticking a row records the identity").contains("toggleSel(machineId, entry)");
        assertThat(body).as("a row knows it is ticked by identity")
            .contains("isSelected(machineId, entry.path, S.at)");
        assertThat(body).as("the per-row verbs are handed the identity").contains("rowActions(machineId, entry)");
        assertThat(body).as("no local called `machine` — that ambiguity is what caused this")
            .doesNotContain("const machine =");
    }

    // --- 6. the ubiquitous language ---------------------------------------------------------------------

    @Test
    void theShell_saysMachineAndEntry_neverNode() throws IOException {
        // UBIQUITOUS_LANGUAGE.md §11 bans "node": a thing in the fleet is a machine, and a thing at a path
        // is an entry.
        Pattern node = Pattern.compile("\\bnodes?\\b", Pattern.CASE_INSENSITIVE);
        // claude-sign-in.js is Explorer-adjacent shipped prose — the Explorer loads it for the machine
        // card's Claude mark — so it is held to the same vocabulary as the rest of the shell.
        for (String asset : List.of("explorer.html", "explorer-shell.js", "explorer-shell.css",
                                    "explorer-listing.js", "claude-sign-in.js")) {
            assertThat(node.matcher(read(asset)).find()).as("\"node\" in %s", asset).isFalse();
        }
    }

    @Test
    void theShell_speaksNoMechanismAtTheOperator() throws IOException {
        // UBIQUITOUS_LANGUAGE.md §17: a handful of terms are internal vocabulary and never appear in the UI.
        // They stay in the code — identifiers, icon keys and comments are where they belong — so this reads
        // only the prose the operator can actually see: string literals with a space in them. A literal with
        // no space ('relay' as an icon key, '/backup-repositories' as a path) is a key, not a sentence.
        Map<String, String> banned = Map.of(
            "\\brelays?\\b", "the machine a network is reached through, named",
            "\\bCIDR\\b", "\"the network behind it\"",
            "\\b(split|full)[ -]tunnel", "what the peer is for, in plain words",
            "\\bVPN subnet\\b", "\"your VPN\"",
            "\\bAllowedIPs\\b", "nothing — it is never shown",
            "\\bmachine type\\b", "the intent fork in the add flow",
            "\\b(machine|peer) id\\b", "nothing — a machine is named, not numbered",
            "\\brepositor(y|ies)\\b", "whose backups these are (the store label)");

        // The sign-in's prose was covered here while it lived in explorer-shell.js. Moving it to the shell
        // window must not move it out of the guard, or the copy nobody re-reads is the copy that drifts.
        List<String> prose = new ArrayList<>(proseLiterals(read("explorer-shell.js")));
        prose.addAll(proseLiterals(read("claude-sign-in.js")));
        for (String line : prose) {
            for (Map.Entry<String, String> term : banned.entrySet()) {
                assertThat(Pattern.compile(term.getKey(), Pattern.CASE_INSENSITIVE).matcher(line).find())
                    .as("\"%s\" is mechanism — say %s. Found in: %s", term.getKey(), term.getValue(), line)
                    .isFalse();
            }
        }
    }

    /**
     * Every multi-word single-quoted string literal in a JavaScript source — the shell's operator-facing
     * prose. Comments (which are full of the mechanism words on purpose) and identifiers are skipped by
     * walking the source one character at a time rather than pattern-matching it, since the shell's comments
     * are dense with apostrophes that a quote-counting regex would read as string boundaries.
     */
    private static List<String> proseLiterals(String source) {
        List<String> prose = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false, inLineComment = false, inBlockComment = false;
        char opener = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (inLineComment) {
                if (c == '\n') inLineComment = false;
            } else if (inBlockComment) {
                if (c == '*' && next == '/') { inBlockComment = false; i++; }
            } else if (inString) {
                if (c == '\\') { i++; }
                else if (c == opener) {
                    inString = false;
                    if (current.indexOf(" ") >= 0) prose.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            } else if (c == '/' && next == '/') { inLineComment = true; i++; }
            else if (c == '/' && next == '*') { inBlockComment = true; i++; }
            else if (c == '\'' || c == '"' || c == '`') { inString = true; opener = c; }
        }
        return prose;
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

    /**
     * The fleet rail is gone. It drew a second view of what the Inspector already lists — every machine, every
     * entry inside one, every folder read so far — and a phone never had it at all, so the layout it was the
     * exception to is now the only one. What it alone used to carry (a machine's capabilities, its last
     * backup's outcome, containers wanting a pull) had already moved onto the fleet pane's cards. Drilling in
     * is the pane, getting back is the crumb bar, going sideways is ⌘K.
     */
    @Test
    void theShell_hasNoFleetRail_andThePaneTakesTheWholeWidth() throws IOException {
        for (String asset : List.of("explorer.html", "explorer-shell.js", "explorer-shell.css")) {
            assertThat(read(asset)).as("the rail's markup and script in %s", asset)
                .doesNotContain("ex-tree").doesNotContain("exTree").doesNotContain("tree-hidden")
                .doesNotContain("ex-row").doesNotContain("ex-twist").doesNotContain("ex-guide");
        }
        // No remembered preference for a column that cannot be folded away any more.
        assertThat(read("explorer-shell.js")).doesNotContain("vaier.explorer.tree");

        // One column, at every width — not a wide-screen two-column grid with a phone exception.
        String css = read("explorer-shell.css");
        int main = css.indexOf(".ex-main {");
        assertThat(main).isPositive();
        assertThat(css.substring(main, css.indexOf('}', main)))
            .contains("grid-template-columns: minmax(0, 1fr)");
    }

    // --- 8. the bridge is temporary, and says so --------------------------------------------------------

    @Test
    void theSectionsNotYetPorted_areBridgedIntoTheShellAndMarkedTransitional() throws IOException {
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
        assertThat(styles).contains("--radius-0:");
        assertThat(styles).contains("--radius-1:");
        assertThat(styles).contains("--radius-2:");

        assertThat(read("explorer-shell.css")).contains("var(--rail)");
        // Every corner in the new stylesheets comes from a token — no eighth raw radius value. The shell
        // window joined this when the Claude sign-in moved into it: it is now a surface an operator reads,
        // not just a bar over a terminal.
        for (String sheet : List.of("explorer-shell.css", "terminal-window.css")) {
            Matcher m = Pattern.compile("border-radius:\\s*([^;]+);").matcher(read(sheet));
            while (m.find()) {
                String value = m.group(1).trim();
                assertThat(value).as("raw radius %s in %s", value, sheet)
                    .matches("(var\\(--radius-[012]\\)|50%|9999px)");
            }
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
        // The third case. The Vaier server is the machine rendering this page: if the operator can see the
        // shell at all, it is up. Probing it would be asking a question we are standing inside the answer to.
        String js = read("explorer-shell.js");

        int from = js.indexOf("function livenessOf(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        // It is answered by recognising the machine, not by probing it — and recognised by IDENTITY, which
        // /machines marks. It used to be a comparison against the literal name "Vaier server": a name doing
        // an identity's job, so renaming the host made Vaier stop recognising itself.
        assertThat(body).contains("isVaierServerMachine(machineId)").contains("is-up");
        assertThat(js).contains("(S.machines.find((m) => m.vaierServer) || {}).id");
    }

    @Test
    void aPersonalDevice_showsPresence_inItsOwnNameAndIcon_notInADot() throws IOException {
        // A phone or PC that is off is not news, so it never earned red — but painting nothing either way
        // meant the operator could not tell on from off at all. Presence is a third register, and it is
        // said by the machine's own name and icon: normal while it is on the VPN, dimmed while it is away.
        // No dot, and no colour the trouble vocabulary uses. Servers keep the rule that fine paints nothing.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function livenessOf(");
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("is-present").contains("is-away");
        assertThat(body).as("a client peer is no longer painted as 'no idea'")
            .doesNotContain("? 'is-idle' : 'is-down'");

        // The painter hands the answer to the card or pane title around the dot, as it already does for down.
        int paint = js.indexOf("function paintLiveness(");
        String painter = js.substring(paint, js.indexOf("\n    }", paint));
        assertThat(painter).contains("classList.toggle('is-away'");

        String css = read("explorer-shell.css");
        assertThat(css).as("presence is not a dot").doesNotContain(".ex-dot.is-present").doesNotContain(".ex-dot.is-away");
        for (String host : List.of(".ex-card.is-away .ex-card-name", ".ex-pane-title.is-away")) {
            int at = css.indexOf(host);
            assertThat(at).as(host + " dims while away").isPositive();
            String rule = css.substring(at, css.indexOf("}", at));
            assertThat(rule).contains("var(--text-dim)");
            assertThat(rule).as("presence borrows no trouble colour")
                .doesNotContain("--red").doesNotContain("--yellow").doesNotContain("--green");
        }

        // The fleet's "N online" count already counted a connected phone; it must not lose it to the rename.
        int count = js.indexOf("const online = ");
        assertThat(js.substring(count, js.indexOf("\n", count))).contains("is-present");
    }

    @Test
    void aMachinesLiveness_isWornByItsNameAndIcon_theDotIsOnlyTheAnchor() throws IOException {
        // The card already turned red and hid its dot; the same machine's name elsewhere kept a red dot beside
        // it in the ordinary colour, so one answer was drawn two ways. Down is red and degraded is amber on
        // the machine itself — its name — everywhere it appears, and the liveness dot is never painted: it
        // stays only as the anchor the stream repaints from. A run's own dots carry no anchor and keep theirs.
        String js = read("explorer-shell.js");
        int paint = js.indexOf("function paintLiveness(");
        String painter = js.substring(paint, js.indexOf("\n    }", paint));
        assertThat(painter).contains("classList.toggle('is-down'").contains("classList.toggle('is-degraded'");

        String css = read("explorer-shell.css");
        int hidden = css.indexOf(".ex-dot[data-ex-dot] {");
        assertThat(hidden).as("the machine dot is never painted").isPositive();
        assertThat(css.substring(hidden, css.indexOf("}", hidden))).contains("display: none");
        assertThat(hidden).as("after the trouble rules, so it wins").isGreaterThan(css.indexOf(".ex-dot.is-down {"));

        for (String host : List.of(".ex-card.is-down .ex-card-name", ".ex-pane-title.is-down")) {
            int at = css.indexOf(host);
            assertThat(at).as(host + " is red").isPositive();
            assertThat(css.substring(at, css.indexOf("}", at))).contains("var(--red)");
        }
        for (String host : List.of(".ex-card.is-degraded .ex-card-name", ".ex-pane-title.is-degraded")) {
            int at = css.indexOf(host);
            assertThat(at).as(host + " is amber").isPositive();
            assertThat(css.substring(at, css.indexOf("}", at))).contains("var(--yellow)");
        }
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
    void aDirectory_isReadLazilyWhenOpened_neverEagerlyAndNeverRecursively() throws IOException {
        // The fleet is on the far side of a VPN. Walking it eagerly is how a shell hangs, so a path's children
        // come from a cache that only opening one fills — childrenOf() reads, it never fetches.
        String js = read("explorer-shell.js");
        // one directory per expand, read on demand
        assertThat(js).contains("async function readDir(");

        int from = js.indexOf("function childrenOf(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        // a path's children are whatever the cache already holds — reading them can never start a read,
        // so no repaint and no ⌘K index can trigger an SFTP walk of the fleet
        assertThat(body).contains("S.dirs");
        assertThat(body).as("childrenOf must never reach for the network").doesNotContain("fetch(")
            .doesNotContain("readDir(").doesNotContain("await");
    }

    @Test
    void onlyDirectories_becomeEntriesInTheAddressSpace() throws IOException {
        // A directory is a place you can stand; a file is contents the Inspector lists. Growing an entry for
        // every file would make ⌘K a list of the fleet's files rather than of the places in it.
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
        // would leave ⌘K offering paths into a machine that no longer exists.
        String js = read("explorer-shell.js");
        assertThat(js).contains("function pruneDirs(");
    }

    @Test
    void aDirectoryThatCannotBeRead_failsVisiblyAndLocally() throws IOException {
        // ExplorerService already surfaces the real reason verbatim ("Not allowed to read /root as geir.").
        // The Inspector must wear the failure — not pretend to be an empty folder, and not spin forever — and
        // the message itself reuses the .ex-note.is-error affordance that is already in the stylesheet.
        String js = read("explorer-shell.js");
        assertThat(js).contains("'error'");
        int from = js.indexOf("function renderDirectory(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    function", from));
        assertThat(body).as("a directory that failed says so where the operator is standing")
            .contains("entry.state === 'error'").contains("failureNote(");

        String css = read("explorer-shell.css");
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
        // A machine must be honest about itself rather than uniform. Files and disk ride on a held SSH
        // credential, so a machine with none grows neither — showing them off the SSH-access toggle alone
        // would open onto a red "no login" wall until a refresh. A machine that runs no Docker must not grow an
        // empty `containers` entry that opens onto nothing. /machines carries both facts (hasCredential,
        // runsDocker) — childrenOf asks them, it does not guess.
        // (The shell is not an entry — it opens from the machine's SSH-access section — so it is absent here.)
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

        // and it asks before it does it — unpublishing tears down a route, and the name stops answering
        int from = js.indexOf("async function unpublish(");
        assertThat(from).isPositive();
        assertThat(js.substring(from, js.indexOf("\n    }", from))).contains("confirm(");

        // deleting is destructive and irreversible, so it goes through the typed-name gate, not a bare confirm
        int del = js.indexOf("async function deleteEntry(");
        assertThat(del).isPositive();
        assertThat(js.substring(del, js.indexOf("\n    }", del))).contains("confirmTyped(");
    }

    @Test
    void aPublishedService_isFiledUnderTheMachineItRunsOn_byTheBackendsOwnRule() throws IOException {
        // A published service is one thing with three homes: a container on a machine, a Traefik route and a
        // DNS record. Which machine it belongs to is a domain decision — ReverseProxyRoute.hostMachineId —
        // and the feed carries the answer, so the browser matches identities and never re-derives the rule.
        // It used to re-derive it from display names (LAN server name, else host name, else the literal
        // "Vaier server"), which is how the same service could end up under two different machines on two
        // different pages, and under the wrong machine entirely once two machines shared a name.
        String js = read("explorer-shell.js");
        int from = js.indexOf("const machineOfService =");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf(";", from));

        assertThat(body).as("read off the feed, not re-derived").contains("s.machineId");
        assertThat(body).doesNotContain("lanServerName").doesNotContain("hostName");
        assertThat(js).as("and matched as an identity")
            .contains("S.services.filter((s) => machineOfService(s) === machineId)");
    }

    @Test
    void theInspectorForAService_saysWhereItsThreeHomesAre() throws IOException {
        // The single namespace exists precisely to hold this relationship together: the route, the machine
        // it is backed by, and the name it answers on are three faces of one service. The Inspector names all
        // three.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderService(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    function", from));

        assertThat(body).contains("dnsAddress");       // the name it answers on
        assertThat(body).contains("hostAddress");      // the backend it routes to
        assertThat(body).contains("image");            // the container behind it
    }

    @Test
    void theShell_neverTellsTheOperatorThatVaierWritesDns_becauseTheWildcardRecordIsTheirs() throws IOException {
        // #331 took Vaier out of DNS: publishing writes a Traefik route and nothing else, and unpublishing
        // leaves the name resolving under the operator's own wildcard record. Copy that still promises a
        // record is a promise the backend stopped keeping — and it is read at the exact moment the operator
        // is deciding whether to press the button.
        String js = read("explorer-shell.js");

        assertThat(js).as("the publish dialogs").doesNotContain("makes the DNS record");
        assertThat(js).as("the inspector's coordinates").doesNotContain("'DNS record'");
        assertThat(js).as("the three-homes note").doesNotContain("a DNS record at");
        assertThat(js).as("the unpublish confirm").doesNotContain("its DNS record");
        assertThat(js).as("the Vaier server's role").doesNotContain("reverse proxy and DNS");
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
    void theShell_holdsSixStreams_theFleet_itsServices_itsTransfers_itsBackups_itsThreats_andWhoIsWaitingToJoin() throws IOException {
        // Slice A held one; slice C added `published-services` (a real, existing topic); slice 2 (Move) added
        // `transfers`, a copy's live progress. Moving jobs onto their machines adds the conscious fourth —
        // `backups`, carrying run-settled so a launched backup's outcome arrives pushed, not polled. #329
        // Slice 3 adds the conscious fifth — `security`, carrying the active block decisions so the Security
        // view and the Map's threat layer learn of a new ban when it happens. It earns its place the same way
        // the others did: the alternative is a poll, and polling is the rule this test exists to hold. Each is
        // a real backend topic listened to, never invented. #359 slice 1b adds the sixth — `enrolment-requests`,
        // a phone asking to join and the answer it got, from whichever browser gave it. Six is the ceiling now.
        // And still no clock of the shell's own — no setInterval, and no setTimeout (the toast lives out a CSS
        // animation, not a JS timer).
        String js = read("explorer-shell.js");
        assertThat(js.split("new EventSource\\(", -1).length - 1).isEqualTo(6);
        assertThat(js).contains("new EventSource('/vpn/enrolments/events')");
        assertThat(js).contains("new EventSource('/transfers/events')");
        assertThat(js).contains("new EventSource('/backup-jobs/events')");
        assertThat(js).contains("new EventSource('/security/events')");
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
        assertThat(body).contains("loadDisk(machineId)");    // the server re-decides, for that machine
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

    // --- disk pressure on the fleet's machine cards ----------------------------------------------------
    //
    // The ambience the fleet listing was missing. It is deliberately NOT the /machines/{id}/disk read: that
    // one runs df over SSH, and doing it for the whole fleet on page load would wake every sleeping machine
    // to answer a question nobody asked. RemoteDiskWatcher has taken this reading every five minutes for as
    // long as the disk alerts have existed and thrown it away; the card is fed from that, over the stream
    // the page already holds open.

    @Test
    void theFleetsDiskPressure_isReadOnceFromWhatTheSweepAlreadyTook_neverAFleetWideDfOnPageLoad() throws IOException {
        String js = read("explorer-shell.js");
        assertThat(js).contains("'/machines/disk-standings'");

        int from = js.indexOf("async function loadDiskStandings(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        // One request for the whole fleet. A per-machine loop here would be the fleet-wide df in disguise.
        assertThat(body.split("fetch\\(", -1).length - 1).as("exactly one request").isEqualTo(1);
        assertThat(body).doesNotContain("S.machines").doesNotContain("loadDisk(");

        // Read at boot, alongside the other fleet loads — never from render().
        int init = js.indexOf("async function init(");
        assertThat(js.substring(init)).contains("loadDiskStandings()");
        int render = js.indexOf("\n    function render(");
        assertThat(js.substring(render, js.indexOf("\n    function", render + 10)))
            .as("render() must never fetch").doesNotContain("loadDiskStandings(");
    }

    @Test
    void aChangedDiskStanding_arrivesOnTheStreamTheFleetPageAlreadyHoldsOpen() throws IOException {
        // Same shape as ssh-server-presence-changed: the backend publishes on the `vpn-peers` topic only when
        // a machine's standing actually moved, with an empty body, and the browser re-reads the endpoint.
        // No second connection, no timer, and nothing pushed every five minutes for disks sitting still.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function watchFleet(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    // The fleet's second stream", from));
        assertThat(body).contains("'disk-standing-changed'");
        assertThat(body).contains("loadDiskStandings()");
        assertThat(js).doesNotContain("setInterval");
    }

    @Test
    void aMachineCard_wearsItsDiskPressure_tintedByTheLevelTheDomainDecided() throws IOException {
        // The mark sits in the same visual vocabulary as the backup mark beside it: the existing `disk` glyph
        // and the existing .ex-mark tints. The level is the server's own DiskStandingLevel — the browser must
        // not be a second place deciding when a disk is in trouble.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function machineMarks(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    // --- the address bar", from));

        assertThat(body).contains("mark(DISK_MARK[standing.level], 'disk'");
        assertThat(body).contains("standing.level");
        assertThat(body).contains("standing.mountPoint");
        assertThat(body).contains("standing.usedPercent");
        // Never recomputed here — no percentage compared against a threshold anywhere in the marks.
        assertThat(body).doesNotContain("thresholdPercent >").doesNotContain("usedPercent >");

        String css = read("explorer-shell.css");
        assertThat(css).contains(".ex-mark.is-disk").contains(".ex-mark.is-degraded").contains(".ex-mark.is-down");
    }

    @Test
    void aMachineTheSweepHasNotReachedYet_drawsNoDiskMarkAtAll_becauseAbsenceIsNotHealth() throws IOException {
        // The failure this project has already been bitten by: a disk at 89% sat silent for weeks because
        // missing state read as fine. A cold start (up to five minutes), a machine with no SSH, a machine
        // with no credential — none of them may draw a green disk. They draw nothing.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function machineMarks(");
        String body = js.substring(from, js.indexOf("\n    // --- the address bar", from));

        assertThat(body).contains("if (!S.at && standing)");
        // No default, no fallback level, nothing that could turn "not read" into "clear".
        assertThat(body).doesNotContain("|| 'CLEAR'").doesNotContain("'is-up'");

        int loader = js.indexOf("async function loadDiskStandings(");
        String loaderBody = js.substring(loader, js.indexOf("\n    }", loader));
        // A failed read empties the map rather than leaving yesterday's verdicts on the cards.
        assertThat(loaderBody).contains("new Map()");
    }

    // --- where the fleet stands on Claude sign-in, on the same machine cards ---------------------------
    //
    // The fifth fact the five-minute sweep learns, drawn as a fifth mark. It is deliberately NOT the
    // per-machine /machines/{id}/claude-sign-in read: that one SSHes to a machine and asks the CLI, so a
    // card-per-machine version of it would put a fleet-wide SSH sweep behind opening the Explorer.

    @Test
    void theFleetsClaudeStanding_isReadOnceFromWhatTheSweepAlreadyTook_neverAFleetWideSshOnPageLoad()
            throws IOException {
        String js = read("explorer-shell.js");
        assertThat(js).contains("'/machines/claude-standings'");

        int from = js.indexOf("async function loadClaudeStandings(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        // One request for the whole fleet. A per-machine loop here would be the SSH sweep in disguise.
        assertThat(body.split("fetch\\(", -1).length - 1).as("exactly one request").isEqualTo(1);
        assertThat(body).doesNotContain("S.machines").doesNotContain("claude-sign-in");

        // Read at boot, alongside the other fleet loads — never from render().
        int init = js.indexOf("async function init(");
        assertThat(js.substring(init)).contains("loadClaudeStandings()");
        int render = js.indexOf("\n    function render(");
        assertThat(js.substring(render, js.indexOf("\n    function", render + 10)))
            .as("render() must never fetch").doesNotContain("loadClaudeStandings(");
    }

    @Test
    void aChangedClaudeStanding_arrivesOnTheStreamTheFleetPageAlreadyHoldsOpen() throws IOException {
        // Same shape as disk-standing-changed: the backend publishes on the `vpn-peers` topic only when a
        // machine's standing actually moved, with an empty body, and the browser re-reads the one
        // memory-backed endpoint. No second connection, no timer, no five-minute drumbeat.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function watchFleet(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    // The fleet's second stream", from));
        assertThat(body).contains("'claude-standing-changed'");
        assertThat(body).contains("loadClaudeStandings()");
        // A dropped stream re-syncs the standing along with the rest of the fleet, or a card would sit on a
        // sign-in that moved while the browser was disconnected.
        assertThat(body).contains("loadClaudeStandings(), loadContainers()");
    }

    @Test
    void aMachineCard_wearsWhereItStandsOnClaude_inTheOneVocabularyBothSurfacesShare() throws IOException {
        // The machine pane already has a map of the operator's words for each state. The card must tint
        // itself from that same map rather than growing a second one — two state maps is two vocabularies
        // one rename away from disagreeing about what a machine is doing.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function machineMarks(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    // --- the address bar", from));

        assertThat(body).contains("'claude',");
        assertThat(body).contains("claudeWords(");
        // The browser looks the tone up; it never decides which states are a standing. That verdict travels
        // from the domain on the response, like DiskStandingLevel does.
        assertThat(body).contains("claude.saysWhereTheSignInStands");
        assertThat(body).as("no state list in the browser")
            .doesNotContain("'SIGNED_IN'").doesNotContain("'SIGNED_OUT'");
        assertThat(body).as("the account is named when there is one").contains("accountEmail");

        // The map itself moved out with the sign-in, into claude-sign-in.js — which the Explorer now loads
        // for exactly this, the mark's words. So the assertion gets stronger rather than weaker: exactly
        // one state map across every shipped asset, not merely one within this file.
        List<String> withTheMap = new ArrayList<>();
        try (var assets = Files.list(STATIC)) {
            for (Path asset : assets.filter(a -> a.toString().endsWith(".js")).toList()) {
                int found = Files.readString(asset).split("const CLAUDE_STATE = \\{", -1).length - 1;
                assertThat(found).as("%s declares the state map twice", asset.getFileName()).isLessThan(2);
                if (found == 1) withTheMap.add(asset.getFileName().toString());
            }
        }
        assertThat(withTheMap).as("one state map, in the file that owns the sign-in")
            .containsExactly("claude-sign-in.js");
        assertThat(read("explorer-shell.js")).as("the card reads it rather than keeping its own")
            .contains("window.VaierClaude.words(");
        assertThat(read("claude-sign-in.js")).contains("card:");
        assertThat(js).contains("ICON").contains("claude:");
    }

    @Test
    void aMachineTheSweepHasNotReachedYet_drawsNoClaudeMarkAtAll_becauseAbsenceIsNotAVerdict()
            throws IOException {
        // This matters more here than it does for disks. The backend goes to real trouble never to report a
        // SIGNED_OUT the CLI did not say — UNKNOWN exists precisely so "Vaier couldn't tell" never reads as
        // "not signed in" — and a card that invented a mark for a machine nobody asked would undo all of it.
        // NOT_INSTALLED, SKIPPED, UNREACHABLE and UNKNOWN carry no card tone, so they draw nothing either.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function machineMarks(");
        String body = js.substring(from, js.indexOf("\n    // --- the address bar", from));

        assertThat(body).contains("claude.saysWhereTheSignInStands");
        // No fallback tone anywhere: nothing that could turn "not read" into a mark.
        assertThat(body).doesNotContain("|| 'SIGNED").doesNotContain("'is-up'");

        // The one map decides which states are a standing at all — the four quiet ones have no card tone.
        // It lives in claude-sign-in.js since the sign-in moved to the shell window; the mark it feeds is
        // still the Explorer's, which is exactly why one shared map matters.
        String claudeJs = read("claude-sign-in.js");
        int map = claudeJs.indexOf("const CLAUDE_STATE = {");
        String mapBody = claudeJs.substring(map, claudeJs.indexOf("\n    };", map));
        for (String quiet : new String[] {"NOT_INSTALLED", "UNREACHABLE", "UNKNOWN", "SKIPPED"}) {
            int row = mapBody.indexOf(quiet);
            assertThat(row).as("%s is in the shared map", quiet).isPositive();
            int lineEnd = mapBody.indexOf("\n", row);
            assertThat(lineEnd < 0 ? mapBody.substring(row) : mapBody.substring(row, lineEnd))
                .as("%s draws no card mark", quiet).doesNotContain("card:");
        }

        int loader = js.indexOf("async function loadClaudeStandings(");
        String loaderBody = js.substring(loader, js.indexOf("\n    }", loader));
        // A failed read empties the map rather than leaving a sign-in that may have moved on the cards.
        assertThat(loaderBody).contains("new Map()");
    }

    @Test
    void theClaudeMark_wearsClaudesOwnClay_neverTheTroubleLights() throws IOException {
        // Green, amber and red are the disk and backup marks' vocabulary, and they mean trouble. A sign-in
        // is presence, not an outage, so the Claude mark says "Claude" in hue and says whether anyone is
        // signed in by weight — the same clay, worn hollow when signed out.
        // The map moved to claude-sign-in.js with the sign-in; the mark and its CSS stayed here, which is
        // the whole point of the two surfaces sharing one vocabulary.
        String js = read("claude-sign-in.js");
        int map = js.indexOf("const CLAUDE_STATE = {");
        String mapBody = js.substring(map, js.indexOf("\n    };", map));
        assertThat(mapBody).contains("card: 'is-claude'").contains("card: 'is-claude-out'");
        assertThat(mapBody).as("no trouble tint on a sign-in")
            .doesNotContain("'is-up'").doesNotContain("'is-degraded'").doesNotContain("'is-down'");

        // Claude's own clay is a token of its own, deliberately outside the traffic-light palette rather
        // than borrowing --orange, which the warning vocabulary owns.
        assertThat(read("styles.css")).contains("--claude:").contains("#d97757");

        String css = read("explorer-shell.css");
        assertThat(css).contains(".ex-mark.is-claude { color: var(--claude); }");
        assertThat(css).contains(".ex-mark.is-claude-out { color: var(--claude); }");
        // The hollow: same hue at a lighter stroke and a lighter glyph, so it reads as dormant rather than as
        // a second colour saying something else. The fade is on the GLYPH alone — the mark carries a word now,
        // and fading the whole thing put that word at 2.8:1, on the one state an operator is meant to act on.
        assertThat(css).contains(".ex-mark.is-claude-out svg { stroke-width: 1.1; opacity: .5; }");
    }

    // --- what a machine card says it is -----------------------------------------------------------------

    @Test
    void aMachineCard_saysWhatTheMachineIsFor_ratherThanWhereItAnswers() throws IOException {
        // The note used to read "Ubuntu server · 10.13.13.6". An address is Vaier's own plumbing — an
        // operator standing on the fleet can do nothing with it, while the description is the one line that
        // says which machine this is. So the card carries the description, and a machine that has none says
        // just its type rather than a dangling separator.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderFleet(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    // The fleet on a map", from));

        assertThat(body).contains("machineDescription(m)");
        assertThat(body).as("the address is off the card").doesNotContain("tunnelAddress(");

        // Resolved the one way the edit form already resolves it — the peer record, or the LAN server's.
        // A second way to find a description is a second way to get it wrong.
        int resolver = js.indexOf("function machineDescription(");
        assertThat(resolver).isPositive();
        String resolverBody = js.substring(resolver, js.indexOf("\n    }", resolver));
        assertThat(resolverBody).contains("S.peers.get(m.id) || S.lan.get(m.id)");
        assertThat(resolverBody).as("blank or whitespace-only falls back to the type alone").contains("trim()");
    }

    @Test
    void theTunnelAddress_isStillOnTheMachineItself_onlyOffTheCard() throws IOException {
        // Moved, not hidden. renderMachine still lists it under the machine's details, which is where an
        // address belongs — this guard exists so nobody "restores" it to the card later.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderMachine(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    function", from));
        assertThat(body).contains("'Tunnel address'").contains("tunnelAddress(m)");
    }

    @Test
    void aLongDescription_cannotBlowOutTheFleetGrid() throws IOException {
        // Cards sit in a repeat(auto-fill, minmax(210px, 1fr)) grid, so an unbounded note would make one card
        // several rows taller than its neighbours. Clamped to two lines, with the whole description on hover
        // and the "root" tag still flowing inline beside it.
        String css = read("explorer-shell.css");
        int from = css.indexOf(".ex-card-note {");
        assertThat(from).isPositive();
        String rule = css.substring(from, css.indexOf("}", from));
        assertThat(rule).contains("line-clamp");
        assertThat(rule).contains("overflow: hidden");
        assertThat(rule).contains("overflow-wrap");
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
    void aPeersContainers_areFiledUnderTheMachinesIdentity() throws IOException {
        // The three-way identity split that used to bite here: /docker-services/peers keyed its containers
        // by the peer's *id* — the WireGuard directory name ("apalveien5") — while the shell and /machines
        // used the canonical machine name ("Apalveien 5"), so filing containers under either meant a
        // crossing, and one character of disagreement showed a machine with no containers while Vaier could
        // see them perfectly well. The scrape carries the machine's identity now, so there is no crossing
        // left to get wrong: the cache is keyed by the same thing the address stands on.
        String js = read("explorer-shell.js");
        int from = js.indexOf("async function loadContainers(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).as("peer containers are filed by identity").contains("next.set(p.machineId,");
        assertThat(body).as("LAN-server containers likewise").contains("next.set(l.machineId,");
        assertThat(body).as("no crossing from a peer id to a display name is needed any more")
            .doesNotContain("peerDisplayName");
    }

    @Test
    void theInfrastructurePage_isDeleted_itsFunctionPortedNatively() throws IOException {
        // Parity is real: everything vpn-peers.html owned — machine creation and editing, the LAN scan, the
        // world map, SSH credentials and access, setup scripts, config reissue and regeneration, publishing
        // with its advanced fields, the published-service editor and allowed groups, and discovered candidates
        // — is a native entry now. So the page and its assets are gone, and the bridge with them.
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
        // Offered only where the server says the machine can run one — never re-derived from its category.
        assertThat(js).contains("m.acceptsSetupScript");
        assertThat(js).doesNotContain("m.type === 'LAN_SERVER') {\n                acts.appendChild(selVerb('shell'");
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
    void theContainersCard_onTheMachinePage_wearsTheSameUpdatePillAsTheFleetCard() throws IOException {
        // The fleet card says "One update" on its marks strip; open the machine and the containers card,
        // the very door to that container, said nothing. One pill, one helper, both places — a second
        // wording here would be a second place to disagree about how many.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderMachine(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    function", from));
        assertThat(body).as("the containers door carries the count").contains("updateCountMark(");
        assertThat(body).contains("ex-card-marks");
        int fleet = js.indexOf("function machineMarks(");
        assertThat(js.substring(fleet, js.indexOf("\n    }", fleet)))
            .as("the fleet card draws the pill through the same helper").contains("updateCountMark(");
    }

    @Test
    void theBackupCard_onAMachineThatIsBackedUp_doesNotClaimToBeTheBackupServer() throws IOException {
        // Colina's backup card said "The fleet backs up here". Colina is backed up; the NAS is where the
        // fleet backs up. The entry exists for both roles (childrenOf decides that), so its note has to be
        // decided the same way, or every backed-up machine introduces itself as the server.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderMachine(");
        String body = js.substring(from, js.indexOf("\n    function", from));
        assertThat(body).contains("S.backupServer.machineId === m.id");
        assertThat(body).as("the backed-up machine says where it goes").contains("'Backs up to '");
        assertThat(body).contains("'The fleet backs up here'");
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
        // (UBIQUITOUS_LANGUAGE.md) — not "outdated", not "stale", not "needs update".
        String js = read("explorer-shell.js");
        String body = updateMarkBody(js);
        assertThat(body).contains("Update available");
        assertThat(body).contains("title");
        for (String banned : List.of("outdated", "stale", "drift", "needs update", "Outdated", "Stale")) {
            assertThat(body).as("a near-synonym of \"Update available\" (%s)", banned).doesNotContain(banned);
        }
        // and the slice opened no verb: still only the three mutating methods, none of them aimed at a container
        assertThat(js).doesNotContain("/pull").doesNotContain("Pull now").doesNotContain("Update now");
    }

    @Test
    void theSingleContainer_saysWhichOfTheThreeVerdictsItIs_includingCannotTell() throws IOException {
        // Absence of a mark on the list must never be read as a promise that the image is current. A list row
        // has no room to say so; the Inspector does, so this is where UNKNOWN is spoken aloud rather than
        // silently collapsed into "up to date" — which is precisely the lie #57 was filed about.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderContainer(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    // --- services", from));

        assertThat(body).contains("'Update'");
        // #353 moved the wording behind updateSays(), which still answers from the domain's verdict — the
        // Inspector must go on speaking it rather than deciding for itself what a container's images do.
        assertThat(body).contains("updateSays(c)");
        int says = js.indexOf("function updateSays(");
        assertThat(says).isPositive();
        assertThat(js.substring(says, js.indexOf("\n    }", says))).contains("updateAvailable");
        // all three verdicts are nameable here, and the third one is honest about not knowing
        assertThat(js).contains("Update available");
        assertThat(js).contains("Up to date");
        assertThat(js).containsIgnoringCase("cannot tell");
    }

    @Test
    void theContainerList_carriesTheUpdateMark_fromTheOneHelper() throws IOException {
        // One verdict, one helper, one place it is drawn. A second copy of "when do we draw this" would be a
        // second place it can drift from the domain.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderContainers(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    function renderContainer(", from));
        assertThat(body).contains("updateMark(");

        // drawn in exactly the one place, off the one helper
        assertThat(js.split("updateMark\\(", -1).length - 1)
            .as("the helper, and its one call site").isEqualTo(2);
    }

    @Test
    void theMark_doesNotAppearWhenBrowsingTheArchive() throws IOException {
        // Same reasoning as the liveness dot, which the stylesheet hides in the past: an archive is a photo of
        // a filesystem, and "there is a newer image in the registry" is a fact about now. Reporting today's
        // registry against a container as it stood in March would be a claim about a moment that has passed.
        // The shield does this with an !S.at gate in JS, and the mark follows the nearer precedent.
        assertThat(updateMarkBody(read("explorer-shell.js"))).contains("S.at");
    }

    @Test
    void aMovingTagIsNamedOnTheMark_soASilentAlertDoesNotReadAsAMissedOne() throws IOException {
        // netdata:latest IS Docker Hub's :edge, so that row lights up most mornings and no mail ever comes.
        // Without a word for it the operator reads the silence as Vaier having missed it. The word is the
        // glossary's own — "moving tag" — and not a cadence like "nightly", which would promise a rhythm
        // the rule does not measure. The shell decides nothing here either: whether a tag moves is the
        // domain's reading, shipped as a flag like the verdict beside it.
        String body = updateMarkBody(read("explorer-shell.js"));
        assertThat(body).as("the backend's reading, not the browser's").contains("movingTag");
        assertThat(body).contains("'moving'");
        assertThat(body).as("no cadence the rule never measures").doesNotContain("nightly");
        assertThat(body).as("and the tooltip says what the word costs — no mail")
            .contains("never mails about it");
    }

    @Test
    void theMovingWord_isDimAndUnshaped_becauseATagThatMovesIsNotTrouble() throws IOException {
        // Same rule the stream kind follows on a route row: colour and shape are reserved for trouble, and
        // a tag that moves on its own is not trouble. It must not borrow the mark's yellow.
        String css = read("explorer-shell.css");
        int from = css.indexOf(".ex-update .ex-moving");
        assertThat(from).as("the moving word's own rule").isPositive();
        String block = css.substring(from, css.indexOf("}", from));
        assertThat(block).contains("var(--text-dim)");
        assertThat(block).doesNotContain("var(--yellow)").doesNotContain("var(--red)");
        assertThat(block).as("no shape — no border, no background, no radius")
            .doesNotContain("border").doesNotContain("background").doesNotContain("radius");
    }

    @Test
    void theInspector_explainsWhyAMovingTagRaisesNoMail() throws IOException {
        // A list row has room for one word; this is the surface with room for the reason.
        String js = read("explorer-shell.js");
        int says = js.indexOf("function updateSays(");
        assertThat(says).isPositive();
        String body = js.substring(says, js.indexOf("\n    }", says));
        assertThat(body).contains("movingTag");
        assertThat(body).contains("a moving tag");
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
        //
        // Asserted on applyRoute rather than go(), because go() is no longer where navigation lands. Now that
        // the location lives in the browser's address, an operator also arrives by Back, Forward, a bookmark
        // and a pasted link — none of which go() ever sees. applyRoute is the one place every arrival passes
        // through, so dropping the receipt there is what makes the rule hold for all of them rather than only
        // for a click inside the page.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function applyRoute(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).as("applying a route drops the receipt, as it already drops the selection")
            .contains("_updateCheck = null");
    }

    @Test
    void everyNavigation_goesThroughTheAddress_soNothingCanSkipWhatArrivingCosts() throws IOException {
        // The guarantee the test above depends on. go() must not set the location itself: if it did, a click
        // could land somewhere without the address knowing, and the receipt-drop (plus the archive reads a new
        // route triggers) would apply to browser-driven arrivals only. go() decides one thing — whether the
        // archive survives this move — and hands the rest to navigate, which is what writes the address and
        // calls applyRoute.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function go(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).as("go() delegates to navigate rather than moving the shell itself")
            .contains("navigate(path,");
        assertThat(body).as("go() never assigns S.path — the route applier owns that").doesNotContain("S.path =");
        assertThat(body).as("go() never assigns S.at either").doesNotContain("S.at =");
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

        // The selection's verbs merged into the pane head's one action group; the rule they are offered on is
        // unchanged, so this follows the rename rather than being relaxed.
        int verbs = js.indexOf("function selectionVerbs(");
        assertThat(verbs).isPositive();
        String verbsBody = js.substring(verbs, js.indexOf("\n    }", verbs));
        assertThat(verbsBody).as("the verb is offered on the same rule it is executed on")
            .contains("anyBackedUp");
        // ...and that one predicate is what reads both shields, so the two sites can never drift apart.
        assertThat(js).contains("const anyBackedUp = (s) => !!s.backedUp || !!s.containsBackedUp;");
        // The selection has to carry the half shield for any of that to work.
        assertThat(js).contains("containsBackedUp: !!entry.containsBackedUp");
    }

    @Test
    void backingUpAsRoot_isASettingTheOperatorCanSee_butNoLongerAQuestionAskedUpFront() throws IOException {
        // Colina 27 ran non-root over /home for months, skipping every file another user owned, because the
        // one setting that decides whether a backup of /home is real had no control anywhere in the shell.
        // It has one — but as of #334 it is folded, because asked up front it is a question about file
        // ownership inside container volumes and the security envelope of a sudoers rule, put to someone
        // with no evidence either way. The evidence-backed version is the machine's nudge.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderOneJob(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    function ", from + 10));

        assertThat(body).as("the flag is read off the job").contains("job.backupAsRoot");
        assertThat(body).as("the shell's existing checkbox row, not a new widget").contains("checkRow(");
        assertThat(body).as("the consequence in the operator's words, not the mechanism")
            .contains("owned by other users");
        // Retargeted, not weakened: the fold is the same kind of fold — the shell's plain disclosure, holding
        // reference the operator is not asked for — but it is no longer called "Advanced", which said only
        // that they were unlikely to want it and nothing about what was inside. It is named for what it holds,
        // so this pins the new name AND pins the old one out, which the single `contains` never did.
        assertThat(body).as("folded away, using the shell's one disclosure idiom — reachable, not asked")
            .contains("disclosure('Backing up other users’ files')");
        assertThat(body).as("named by what is inside it, never by how advanced it is")
            .doesNotContain("disclosure('Advanced')");
        int fold = body.indexOf("disclosure('Backing up other users’ files')");
        assertThat(body.indexOf("checkRow('Back up files owned by other users'")).as("inside the fold")
            .isGreaterThan(fold);
    }

    @Test
    void turningBackupAsRootOn_isTheOneActionThatAlsoInstallsTheGrant() throws IOException {
        // #334's acceptance criterion. Flipping the flag alone produced a job whose every run died on
        // `sudo -n` — the grant was a separate, undiscoverable step. Turning it ON now goes through the one
        // endpoint that does both and reports back; turning it OFF is still a plain job save, because
        // nothing has to be installed to stop reading as root.
        String js = read("explorer-shell.js");
        int from = js.indexOf("async function toggleBackupAsRoot(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }\n", from));

        assertThat(body).as("turning it on routes through the grant-and-flag action")
            .contains("backUpAsRootNow(job.machineId)");
        assertThat(body).as("turning it off needs nothing installed").contains("stopBackingUpAsRoot(job)");

        int off = js.indexOf("async function stopBackingUpAsRoot(");
        assertThat(off).as("turning it off still rides the job endpoint that already exists").isPositive();
        String offBody = js.substring(off, js.indexOf("\n    }\n", off));
        assertThat(offBody).as("addressed by the machine whose job it is, not by the job's label")
            .contains("'/backup-jobs/' + encodeURIComponent(job.machineId)");
        assertThat(offBody).contains("method: 'PUT'");
        assertThat(offBody).as("every field is carried through, so a toggle never drops the job's paths")
            .contains("backupAsRoot: false");
    }

    @Test
    void acceptingBackUpAsRoot_isHonestWhenTheGrantDidNotLand() throws IOException {
        // The response is compound on purpose — granted / job / provisioning — and the shell must not
        // flatten it into "done". A 200 with granted:false means the machine still cannot read those files.
        String js = read("explorer-shell.js");
        int from = js.indexOf("async function backUpAsRootNow(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }\n", from));

        assertThat(body).contains("'/back-up-as-root'");
        assertThat(body).contains("method: 'POST'");
        assertThat(body).as("the outcome decides what the operator is told, not the status code")
            .contains("granted");
    }

    @Test
    void theBackUpAsRootNudge_hasAnActionAndSaysWhereToReadWhatItMeans() throws IOException {
        // The domain raises BACK_UP_AS_ROOT with the files it lost; the shell only routes it. And because
        // saying yes makes Vaier's login on that machine as powerful as root, the card carries a way to read
        // what that means before answering — the Concepts entry, where the long explanation belongs.
        String js = read("explorer-shell.js");
        int from = js.indexOf("const NUDGE_ACTION = {");
        assertThat(from).isPositive();
        String table = js.substring(from, js.indexOf("};", from));
        assertThat(table).contains("BACK_UP_AS_ROOT:");
        assertThat(table).as("the accept runs the one action, not a separate wizard step")
            .contains("backUpAsRootNow(");
        assertThat(table).contains("back-up-as-root");

        int card = js.indexOf("function nudgeCard(");
        String cardBody = js.substring(card, js.indexOf("\n    }\n", card));
        assertThat(cardBody).as("the card renders the learn-more link when a nudge carries one")
            .contains("concepts.html#");
    }

    @Test
    void theRouteLanNudge_acceptsStraightIntoTheEndpointThatAlreadyRoutesANetwork() throws IOException {
        // #333: the operator answers "should the fleet reach my house?", never "what is your CIDR?". The
        // accept must go through the very endpoint typing the CIDR by hand goes through — that is how
        // "the same routing as before" comes free rather than being reimplemented here.
        String js = read("explorer-shell.js");
        int from = js.indexOf("const NUDGE_ACTION = {");
        assertThat(from).isPositive();
        String table = js.substring(from, js.indexOf("};", from));
        assertThat(table).contains("ROUTE_LAN:");
        assertThat(table).as("the accept takes the CIDR the domain detected, never one the browser derived")
            .contains("routeDetectedLan(m, n.value)");

        int fn = js.indexOf("async function routeDetectedLan(");
        assertThat(fn).isPositive();
        String body = js.substring(fn, js.indexOf("\n    }\n", fn));
        assertThat(body).as("the one existing write path for a LAN CIDR").contains("'/lan-cidr'");
        assertThat(body).contains("loadFleet()");
        assertThat(body).as("the evidence has changed, so the pane re-asks on the next paint")
            .contains("S.nudges.delete(m.id)");
    }

    @Test
    void theEditFormFoldsTheHandTypedCidr_underAFoldThatNamesIt() throws IOException {
        // The CIDR field stays — a machine can front more than one subnet, and nothing detects that — but
        // it is no longer the way an operator is expected to answer. It is the escape hatch, so it lives
        // where escape hatches live: folded, and behind a summary that says what is inside rather than
        // "Advanced", which only ever said the operator was unlikely to want it.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function editMachineForm(");
        String body = js.substring(from, js.indexOf("\n    }\n", from));

        String fold = "disclosure('The network behind this machine')";
        assertThat(body).contains(fold);
        assertThat(body.indexOf("lanCidr", body.indexOf(fold)))
            .as("the CIDR field sits inside the fold").isPositive();
    }

    @Test
    void whetherAMachineCanRelayANetwork_isTheDomainsAnswer_notTheBrowsersOwn() throws IOException {
        // The shell used to ask `S.peers.has(id) && SERVER_TYPES.has(type)` — and that type set includes
        // LAN_SERVER, a machine with no tunnel to route into. Two spellings of one rule, already disagreeing
        // on paper. Machine.canRelayALan settles it and rides on the machine.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function editMachineForm(");
        String body = js.substring(from, js.indexOf("\n    }\n", from));

        assertThat(body).contains("m.canRelayALan");
        assertThat(body).as("no second definition of the rule in the browser").doesNotContain("SERVER_TYPES");

        int save = js.indexOf("async function saveMachineEdits(");
        String saveBody = js.substring(save, js.indexOf("\n    }\n", save));
        assertThat(saveBody).as("the same one answer gates the write, not a parallel type check")
            .contains("m.canRelayALan").doesNotContain("SERVER_TYPES");
    }

    @Test
    void aSettledRun_reopensTheMachinesNudges() throws IOException {
        // Nudges are read once per machine and never re-read, so without this the "this backup is missing 3
        // files" card would not appear until the operator left the machine pane and came back — i.e. exactly
        // when the evidence arrives is exactly when it would be invisible. No polling: the run-settled push
        // already tells us, so it drops the cached answer and the next paint re-asks.
        String js = read("explorer-shell.js");
        int from = js.indexOf("events.addEventListener('run-settled'");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("});", from));

        assertThat(body).contains("S.nudges.delete(d.machineId)");
        assertThat(js).as("still no polling anywhere in the shell").doesNotContain("setInterval(");
    }

    @Test
    void whetherTheLastSelfUpdateIsTrouble_isTheDomainsVerdict_notTheBrowsersOwn() throws IOException {
        // The shell re-derived it from the raw outcome name, so a new trouble outcome would have gone silent
        // in exactly the place silence is worst: a rolled-back Vaier is a running Vaier and looks healthy.
        String js = read("explorer-shell.js");
        int from = js.indexOf("const upd = S.settings.update || {};");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("if (upd.available) {", from));

        assertThat(body).as("the gate is the domain's verdict").contains("upd.trouble");
        assertThat(body).as("no second copy of the rule in JS")
            .doesNotContain("upd.outcome === 'FAILED'");
    }

    @Test
    void openingAMachine_leadsWithWhatItIs_andFoldsTheAddressesAway() throws IOException {
        // The pane used to open on a table of addresses. An operator opening a machine asks what it does and
        // what is inside it, not what its tunnel address is — so the addresses fold, they do not disappear.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderMachine(pane) {");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }\n", from));

        int fold = body.indexOf("disclosure('Connection details')");
        assertThat(fold).as("the addresses fold away behind the shell's own disclosure, not a new component")
            .isPositive();
        assertThat(body.indexOf("'Device category'"))
            .as("what the machine is stays in the open").isPositive().isLessThan(fold);
        for (String mechanism : List.of("'Tunnel address'", "'Endpoint'", "'Transfer'", "'Docker'")) {
            assertThat(body.indexOf(mechanism)).as("%s is folded away", mechanism)
                .isPositive().isGreaterThan(fold);
        }
    }

    @Test
    void ignoringOrPublishingAService_reopensTheMachinesNudges() throws IOException {
        // Nudges are read once per machine, so without dropping the cached answer the card keeps asking about
        // a service the operator just dismissed, for the rest of the session.
        String js = read("explorer-shell.js");
        int from = js.indexOf("async function reloadServices(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }\n", from));

        assertThat(body).contains("S.nudges.delete(machineId)");
        assertThat(js).as("still no polling anywhere in the shell").doesNotContain("setInterval(");
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
            .contains("jobsOn(machineId)");
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
        // topbar's crumb trail grows with the path. Standing deep in the fleet on a phone made the whole page
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
        assertThat(block).as("and the pane makes room under it")
            .contains(".ex-app.has-sel .ex-pane:has(.ex-selverbs) .ex-pane-body");
        assertThat(js).as("which only the shell can know").contains("'has-sel'");

        // Both rules are scoped to the group that actually carries the selection. `has-sel` is set from
        // S.sel.length alone and a Selection outlives navigating to a machine — and a machine's head now
        // carries verbs of its own (Edit details). Unscoped, files ticked in some folder would tear those
        // into a fixed bottom bar dressed as the selection bar, and pad a pane under a bar it never grew.
        assertThat(block).as("only the group holding the selection floats")
            .contains(".ex-app.has-sel .ex-pane-actions:has(.ex-selverbs) {");
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

    // --- trouble is visible from the fleet ---------------------------------------------------------------

    @Test
    void aMachineCard_wearsItsLastBackupOutcome_soAFailedRunIsSeenFromTheFleet() throws IOException {
        // The point of the fleet pane is that you do not have to open anything. A failed run that is only
        // visible once an operator opens that machine's Backup pane is a failure nobody sees, so the card
        // carries the mark.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function machineMarks(");
        assertThat(from).as("the fleet card has a mark for a machine's backup").isPositive();
        String body = js.substring(from, js.indexOf("\n    // --- the address bar", from));

        assertThat(body).as("coloured by the job's last outcome").contains("lastRunStatus");
        assertThat(body).as("through the one map the job pane already uses, so the two cannot disagree")
            .contains("RUN_MARK[");
    }

    @Test
    void aJobThatHasNeverRun_getsTheIdleMark_notTheGreenOne() throws IOException {
        // "Not yet" is not success. Colouring an unrun job green would promise data is safe before a single
        // archive exists.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function machineMarks(");
        String body = js.substring(from, js.indexOf("\n    // --- the address bar", from));
        assertThat(body).contains("|| 'is-idle'");
        assertThat(body).doesNotContain("'is-up'");
    }

    @Test
    void theBackupMark_isReadFromTheJobListAlreadyLoaded_notANewRead() throws IOException {
        // Painting the fleet must not fire a request per machine. The job list lands once at boot and carries
        // the outcome, so the mark costs nothing to draw.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function machineMarks(");
        String body = js.substring(from, js.indexOf("\n    // --- the address bar", from));
        assertThat(body).as("read off the loaded jobs").contains("jobsOn(");
        assertThat(body).as("and never fetched").doesNotContain("fetch(");
    }

    // --- Vaier updating itself ---------------------------------------------------------------------------

    @Test
    void updatingVaier_isOfferedOnSettings_onlyWhenThereIsSomethingToUpdateTo() throws IOException {
        // The button is the whole trigger — nothing updates on a schedule. And it appears only when the
        // registry really serves something newer, so it is never a button that would do nothing.
        String js = read("explorer-shell.js");
        int from = js.indexOf("async function updateVaier(");
        assertThat(from).as("the shell can ask for an update").isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).contains("'/settings/update'");
        assertThat(body).contains("method: 'POST'");
        assertThat(js).as("offered against the domain's verdict, not a version string compare")
            .contains("upd.available");
    }

    @Test
    void theUpdateToast_saysWhatIsAboutToHappen_becauseNoAnswerIsComing() throws IOException {
        // The container serving the request is the one being replaced, so there is no outcome to wait for.
        // A dropped connection is the expected shape of success and must not be reported as a failure.
        String js = read("explorer-shell.js");
        int from = js.indexOf("async function updateVaier(");
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body.indexOf("toast(")).as("it says so before it asks").isLessThan(body.indexOf("fetch("));
        assertThat(body).contains("go quiet");
    }

    @Test
    void aRolledBackUpdate_isSaidOutLoudOnSettings() throws IOException {
        // The one outcome nothing else would reveal: Vaier is up, so it looks healthy — it is just running
        // the build from before. Silence would mean an update reverting every time and nobody knowing.
        assertThat(read("explorer-shell.js")).contains("'ROLLED_BACK'");
    }

    // --- the operator points at a server and at data, and never learns borg's nouns ----------------------

    @Test
    void theBackupServer_listsTheMachinesItKeeps_notRepositories() throws IOException {
        // "Repository" is a borg noun. The operator's model is that the NAS keeps the backups of machines,
        // and that is true by construction: Vaier creates exactly one store per machine and names it after
        // the machine's IDENTITY — so the directory name says nothing to a person, and the label has to
        // come from somewhere. It comes from the backend (BackupStoreLabel), never re-derived here: two
        // surfaces working out which store is which is how they come to disagree, and being wrong about
        // that means restoring the wrong machine's data.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function repoLabel(");
        assertThat(from).as("a store knows how to say whose backups it holds").isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).as("read off the feed").contains("repo.label");
        assertThat(body).as("never re-derived from the jobs").doesNotContain("S.backupJobs");
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
        assertThat(body).as("and the coordinates fold").contains("disclosure('Server details')");
        // Retargeted: the operations still fold away as the fallback they are — that invariant is unchanged
        // and still pinned below — but they no longer share the coordinates' fold. The row ends in Remove
        // designation, which takes the fleet's backup server away, and a fold labelled "Server details" gave
        // an operator no warning that opening it reached a verb like that. So the fold they live in is now
        // the one coloured for consequence, and both facts are asserted: which fold, and that the destructive
        // verb is inside it rather than loose on the surface.
        assertThat(body).as("the operations get the fold that is coloured for consequence")
            .contains("dangerFold('Provision, authorize or remove this backup server')");
        int risky = body.indexOf("dangerFold(");
        assertThat(body.indexOf("'Provision'"))
            .as("the operations live inside that fold, as the fallback they are").isGreaterThan(risky);
        assertThat(body.indexOf("'Remove designation'"))
            .as("and the verb that ends the fleet's backup server is inside it too").isGreaterThan(risky);
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
            .contains("machineId !== S.backupServer.machineId");
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
        int from = js.indexOf("function capabilitiesOf(");
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
        int from = js.indexOf("function capabilitiesOf(");
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

    // --- Open: a viewable file's name is a link ---------------------------------------------------------

    @Test
    void aViewableFile_opensInANewTab_fromItsOwnName() throws IOException {
        String js = read("explorer-shell.js");

        // The listing paints a link for a viewable file, pointing at the view endpoint, opened in a new tab
        // with no handle back to the Explorer.
        assertThat(js).contains("/files/view?");
        assertThat(js).contains("entry.viewable");
        assertThat(js).contains("noopener noreferrer");
        // And the link gets the same affordance a directory's name has, rather than a look of its own.
        assertThat(read("explorer-shell.css")).contains("a.ex-lname");
    }

    @Test
    void theShell_holdsNoAllowlistOfItsOwn_becauseTheServerDecidesWhatItWillDisplay() throws IOException {
        // The allowlist is a security boundary (an inline file runs on Vaier's origin, against the operator's
        // session). A second copy in the browser is a copy that drifts, so the listing carries the server's
        // per-entry verdict and the shell only honours it.
        String js = read("explorer-shell.js");
        assertThat(js).doesNotContain("'.html'");
        assertThat(js).doesNotContain("image/jpeg");
        // Nothing in the shell decides viewability by looking at a filename — the flag arrives with the entry.
        int from = js.indexOf("function viewUrl(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).doesNotContain("entry.name");
    }

    @Test
    void openDoesNotReplaceDownload_everyFileCanStillBeSaved() throws IOException {
        // Opening is an addition, not a swap: the Download button stays on every row, viewable or not, and
        // /files/download is untouched — it always saves.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function rowActions(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).as("Download is unconditional in the row's actions").contains("download(machineId, entry)");
        assertThat(body).doesNotContain("viewable");
    }

    // --- the Security view and the threats on the Map (#329 Slice 3) -----------------------------------

    @Test
    void aSecondNativeGlobal_rendersItselfRatherThanSettings() throws IOException {
        // kindOf answered 'settings' for every native global, which was indistinguishable from correct
        // while Settings was the only one. Security is the second, and under the old rule it would have
        // drawn the Settings pane under its own name — a bug that could only appear once, here.
        String js = read("explorer-shell.js");
        assertThat(js).contains("g.native ? g.name : 'gbridge'");
        assertThat(js).doesNotContain("g.native ? 'settings' : 'gbridge'");
        assertThat(js).contains("if (kind === 'security') return renderSecurity(pane);");
    }

    @Test
    void theMapNeverReDerivesWhetherAThreatCanBeDrawn() throws IOException {
        // The domain already decided it: BlockDecision.locatable() rejects CrowdSec's 0/0 "could not place
        // this" sentinel while deliberately keeping a genuine zero on ONE axis, which is a real place. In
        // JavaScript 0 is falsy, so the obvious `d.latitude && d.longitude` would silently throw away every
        // location on the equator or the prime meridian. The shell must consume the verdict, never re-take it.
        String js = read("explorer-shell.js");
        assertThat(js).contains("S.threats.filter((d) => d.locatable)");
        assertThat(js).doesNotContain("d.latitude && d.longitude");
        assertThat(js).doesNotContain("d.latitude != null && d.longitude != null");
    }

    @Test
    void threatsFanOutWithTheFleetsMarkersInsteadOfHidingUnderThem() throws IOException {
        // A blocked scanner can share a city with one of the operator's own machines, so a threat ping
        // joins the fleet's own cluster — same reasoning, and the same shared repaintIntoCluster, as the
        // access dots. Framing stays safe: the fleet's markers push their coordinates into `coords`, which
        // fitBounds reads, and a threat ping still never joins it — a scanner in Singapore must not zoom a
        // European fleet out to the whole globe on every open just because it now shares a layer.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function paintThreatLayer(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).as("a threat marker is never added to the framing coordinates")
            .doesNotContain("coords");
        assertThat(body).contains("repaintIntoCluster(_threatMarkers");
    }

    @Test
    void theSharedClusterRepaintNeverTouchesFramingAndAlwaysUsesTheFleetsCluster() throws IOException {
        // Threat pings and access dots both need "remove exactly the markers I added last time, then add
        // fresh ones into the shared cluster" — written once here so the two cannot drift apart on it, and
        // proven once here that the dance never reaches into `coords`.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function repaintIntoCluster(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).as("the shared repaint never touches the framing coordinates").doesNotContain("coords");
        assertThat(body).contains("_cluster.addLayer(m)");
        assertThat(body).contains("_cluster.removeLayer(m)");
    }

    @Test
    void aThreatPushRepaintsInPlaceRatherThanRebuildingTheMap() throws IOException {
        // A push lands every five minutes whatever is on screen. Re-rendering would tear the Leaflet map
        // down and rebuild it, throwing away the operator's pan and zoom — possibly mid-drag. The shell
        // already solved this shape for liveness with paintDots; threats follow it.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function repaintThreats(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("else if (kind === 'map') paintThreatLayer();");
        assertThat(body).doesNotContain("if (kind === 'map') render()");
    }

    @Test
    void theSecurityViewIsPushedNotPolled() throws IOException {
        // The same rule the rest of the shell lives by: the backend sweeps and publishes, the browser
        // listens. A timer here would be the first one in the file.
        String js = read("explorer-shell.js");
        assertThat(js).contains("new EventSource('/security/events')");
        assertThat(js).contains("events.addEventListener('block-decisions'");
        assertThat(js).doesNotContain("setInterval");
    }

    @Test
    void theBlockedListKeepsItsVerbsOnANarrowScreen() throws IOException {
        // The file listing hides its row verbs on a phone because ticking a file raises the selection bar
        // that carries them. Security has no such bar, so inheriting that rule would leave the view
        // read-only on the device the operator is most likely holding when the alert arrives.
        String css = read("explorer-shell.css");
        assertThat(css).contains(".ex-listing.is-threats .ex-lactions {");
        assertThat(css).contains("grid-area: acts;");
        // And the ping is decoration, so it stops when the operator asked for less motion.
        assertThat(css).contains("@keyframes ex-threat-ping");
        assertThat(css.substring(css.indexOf("@keyframes ex-threat-ping")))
            .as("the ping's animation is disabled under prefers-reduced-motion")
            .contains("prefers-reduced-motion");
    }

    // --- allowed accesses on the Map, the mirror image of the threat pings -----------------------------

    @Test
    void theMapNeverReDerivesWhetherAnAccessCanBeDrawn() throws IOException {
        // The domain decides `locatable`, exactly as it does for a blocked address (BlockDecision.locatable()
        // guards the equator/prime-meridian 0/0 sentinel trap) — the shell consumes the verdict, it never
        // re-takes it with a JS truthiness check that would silently drop a real place at latitude or
        // longitude zero.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function paintAccessLayer(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("S.accessSources.filter((d) => d.locatable)");
        assertThat(body).doesNotContain("d.latitude && d.longitude");
        assertThat(body).doesNotContain("d.latitude != null && d.longitude != null");
    }

    @Test
    void accessSourcesJoinTheFleetsClusterSoCoLocatedMarkersFanOut() throws IOException {
        // Unlike a threat, an access dot can land exactly on a machine marker — the Vaier server's own
        // exit IP is itself a real access source, so a dot in Frankfurt sits under the 36px server marker.
        // A separate layer could never fix that; only the fleet's own markerClusterGroup can, since its
        // spiderfyOnMaxZoom is what pulls co-located markers apart on click. This is still safe for framing:
        // fitBounds reads `coords`, not cluster membership, and an access marker still never joins `coords`
        // (below) — so joining the cluster cannot drag the map's zoom out to a single remote sign-in.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function paintAccessLayer(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).as("an access marker is never added to the framing coordinates").doesNotContain("coords");
        assertThat(body).contains("repaintIntoCluster(_accessMarkers");
    }

    @Test
    void anAccessPushRepaintsOnlyTheMap_andOnlyInPlace() throws IOException {
        // Two ways to get this wrong, and it had both. Re-rendering the Map would tear Leaflet down and
        // throw away the operator's pan and zoom, possibly mid-drag — hence the in-place layer repaint.
        // And the Security view, which displays no access sources at all, was being torn down and rebuilt
        // every minute the counts changed, for data it never shows.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function repaintAccessSources(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("paintAccessLayer()");
        assertThat(body).as("no view is re-rendered for a push it does not display")
            .doesNotContain("render()");
        assertThat(body).as("the Security view has no access-sources surface to repaint")
            .doesNotContain("'security'");
    }

    @Test
    void noStateIsKeptForAnAccessSourcesReadNothingEverAsksAbout() throws IOException {
        // accessSourcesRead was written in two places and read in none. threatsRead earns its keep — the
        // Security view distinguishes "nothing blocked" from "not asked yet" with it — but nothing draws
        // these, so the flag was only ever a promise of a surface that is not in this change.
        assertThat(read("explorer-shell.js")).doesNotContain("accessSourcesRead");
    }

    @Test
    void theAccessSourcesArePushedOnTheExistingSecurityStream_neverPolled() throws IOException {
        // Same stream as block-decisions and trusted-addresses — a new event name, not a new connection —
        // and the same rule the rest of the shell lives by: the backend sweeps and publishes, the browser
        // only listens.
        String js = read("explorer-shell.js");
        assertThat(js).contains("new EventSource('/security/events')");
        assertThat(js).contains("events.addEventListener('access-sources'");
        assertThat(js).doesNotContain("setInterval");
        // And the very first read happens once, at boot — never re-fetched on a timer or on view.
        int from = js.indexOf("function loadSecurity(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("fetch('/security/access-sources'");
    }

    @Test
    void thePopupNamesThePlaceTheDomainNamed() throws IOException {
        // AccessSource.place() decides how a city and a country read together. A join here would be a
        // second answer free to drift from it — and it already had: the domain joins one way, this joined
        // another. The shell renders the name it was given.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function paintAccessLayer(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("mapPopup(d.place");
        assertThat(body).as("the shell never re-derives a place name the domain already sent")
            .doesNotContain("d.city");
    }

    @Test
    void theUnplaceableBucketIsNeverPaintedAsADot() throws IOException {
        // Accesses over the VPN or the LAN arrive with locatable: false and no coordinates — Vaier's
        // domain says so, not a JS guess — so the same `d.locatable` filter that protects paintThreatLayer
        // must keep it out of the layer. Its count is a note near the map instead, never a pin nobody can
        // place.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function paintAccessLayer(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("S.accessSources.filter((d) => d.locatable)");
        int mapFrom = js.indexOf("function renderMap(");
        assertThat(mapFrom).isPositive();
        String mapBody = js.substring(mapFrom, js.indexOf("\n    }", mapFrom));
        assertThat(mapBody).as("the unplaceable count is summed from the non-locatable rows, near the map")
            .contains("S.accessSources.filter((d) => !d.locatable)")
            .contains("cannot be placed on a map");
    }

    @Test
    void theUndrawnCountNeverClaimsACauseVaierDidNotEstablish() throws IOException {
        // The note counts every row with no dot, which is the right count — its whole job is that the map's
        // totals add up, and a named city the database has no coordinates for is just as undrawn as a LAN
        // address. But `locatable: false` is not proof of a private address: that same named city lands
        // here, and so does a lookup that simply failed on a public one. Naming the VPN and the LAN as the
        // cause is the same confidently wrong caption as calling a drawable place "Not placeable".
        String js = read("explorer-shell.js");
        int mapFrom = js.indexOf("function renderMap(");
        assertThat(mapFrom).isPositive();
        String mapBody = js.substring(mapFrom, js.indexOf("\n    }", mapFrom));
        assertThat(mapBody).contains("cannot be placed on a map");
        assertThat(mapBody).as("the VPN and the LAN are an example, not an established cause")
            .doesNotContain("in over the VPN or the LAN, whose private addresses");
    }

    @Test
    void aFailedAccessSourcesReadIsVisibleRatherThanReadingAsQuiet() throws IOException {
        // The threatsError precedent: an outage must not be indistinguishable from "nobody accessed
        // anything". A failed fetch sets an error the Map surfaces as a note, never a silently empty list.
        String js = read("explorer-shell.js");
        int from = js.indexOf("function loadSecurity(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("S.accessSourcesError");
        assertThat(js).contains("if (S.accessSourcesError) body.appendChild(note(S.accessSourcesError, true));");
    }

    @Test
    void theAccessDotIsDistinctFromBothExistingMapVocabularies() throws IOException {
        // A green dot on this map already means something (.map-marker.up is a machine that is up), and red
        // already means something else close by (.threat-ping). The new mark must not borrow either class —
        // it needs its own, restrained rule so a glance never reads an allowed access as a machine or as an
        // ongoing alarm.
        String js = read("explorer-shell.js");
        assertThat(js).contains("class=\"access-dot\"");
        String css = read("explorer-shell.css");
        assertThat(css).contains(".access-dot {");
    }

    // --- the trusted addresses, and undoing one (#348) -------------------------------------------------

    @Test
    void theSecurityViewListsWhatTheOperatorHasTrusted() throws IOException {
        // The whole of #348: trusting was one confirm away, irreversible, and then invisible. The view now
        // has a second section, read from its own endpoint, so an operator can go back six months later and
        // see what they decided.
        String js = read("explorer-shell.js");
        assertThat(js).contains("fetch('/security/trusted-addresses'");
        assertThat(js).contains("S.trusted.forEach((a) => rows.appendChild(trustedRow(a)));");
        assertThat(js).contains("'Trusted addresses'");
    }

    /**
     * The constraint #348 turns on. The trusted networks are two kinds: the structural ones — the VPN, this
     * server's own container network, every network reached through a machine — which exist so CrowdSec can
     * never block the operator's own traffic, and the addresses trusted by hand. Only the second kind is
     * listed, because the list is where the untrust verb lives. The structural ones are named in a sentence
     * so the operator knows they are covered, and are never rendered as a row anything could act on.
     */
    @Test
    void theTrustedListRendersOnlyWhatCanBeUntrusted() throws IOException {
        String js = read("explorer-shell.js");
        int from = js.indexOf("function renderTrusted(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).as("the section is drawn from the hand-trusted list and nothing else")
            .contains("S.trusted")
            .doesNotContain("allCidrs")
            .doesNotContain("trustedNetworks");
        // ...and the structural networks are stated, not listed.
        assertThat(js).contains("cannot be untrusted");
    }

    @Test
    void untrustingSaysWhatItDoesAndWhenItTakesEffect() throws IOException {
        // The same restart asymmetry the trust dialog already owns, said on the way out: the whitelist file
        // loses the address on the next refresh, but CrowdSec reads its parser files only at startup and
        // Vaier will not restart it. And the one thing an operator is most likely to fear here is wrong —
        // untrusting blocks nobody, because Vaier never blocks anyone.
        String js = read("explorer-shell.js");
        int from = js.indexOf("async function untrustAddress(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("blocks nobody");
        assertThat(body).contains("when CrowdSec next restarts");
        assertThat(body).contains("locked out");
        assertThat(body).contains("'/security/trusted-addresses/' + encodeURIComponent(a.sourceIp), 'DELETE'");
    }

    @Test
    void trustingNoLongerCallsItselfForever() throws IOException {
        // Before #348 the confirmation could not say the decision was permanent, because the read/undo
        // surface was assumed to follow. It has now — so the honest copy is the reversible one, and adding
        // a "this is permanent" warning at this point would ship a sentence that is already false.
        String js = read("explorer-shell.js");
        int from = js.indexOf("async function trustAddress(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("until you untrust it here");
        assertThat(body).as("nothing in this dialog may claim the decision is final")
            .doesNotContain("for good")
            .doesNotContain("permanent");
    }

    @Test
    void theTrustedListIsPushedNotPolled() throws IOException {
        // Only a person ever changes this list, so nothing on a clock publishes it — the controller pushes
        // it straight after the action, and the browser listens. A re-read on a timer would be the first
        // poll in the file.
        String js = read("explorer-shell.js");
        assertThat(js).contains("events.addEventListener('trusted-addresses'");
        assertThat(js).doesNotContain("setInterval");
    }

    @Test
    void theTrustedListKeepsItsVerbOnANarrowScreen() throws IOException {
        // Same reason the blocked list does: there is no selection bar in this view to carry the verb, so
        // the phone-width rule that hides row actions would leave the section read-only on the device the
        // operator is most likely holding.
        String css = read("explorer-shell.css");
        assertThat(css).contains(".ex-listing.is-trusted .ex-lactions {");
        assertThat(css.substring(css.indexOf("@media"))).contains("grid-template-areas: \"src\" \"acts\";");
    }

    // --- the effective user, and the pin that refuses (#346, #345) -------------------------------------

    @Test
    void theBrowserNeverDecidesWhoIsPrivileged_itIsTold() throws IOException {
        // The whole point of domain.EffectiveUser: "is this user privileged?" is one judgement, made once,
        // with its limits documented. A second copy in the browser — comparing a username to "root" — would
        // be a second answer free to drift from the first, and it is exactly the shape this replaced.
        String js = read("explorer-shell.js");
        assertThat(js).contains("m.effectiveUserPrivileged");
        assertThat(js)
            .as("privilege arrives decided; the shell must not re-derive it from a username")
            .doesNotContain("effectiveUsername === 'root'")
            .doesNotContain("username === 'root'");
    }

    @Test
    void aRootMachineSaysSoInItsInspector() throws IOException {
        // Acceptance criterion 1 of #346: the machine states which user Vaier acts as.
        //
        // Criterion 2 — the same fact as a badge on every card, so the fleet could be read for "where am I
        // root?" without opening a machine — was REMOVED at the operator's request. The reasoning behind it
        // was sound and still lost to reality: on a fleet where Vaier is privileged nearly everywhere, a
        // badge on nearly every card marks nothing out and becomes wallpaper. The fact is a click away in
        // the inspector, where it is read at the moment it matters. Do not re-add the badge from #346 alone.
        String js = read("explorer-shell.js");
        assertThat(js).contains("'Vaier acts as ' + m.effectiveUsername");
        // The copy names the user the backend sent; it never asserts the word "root" on its own account,
        // because EffectiveUser reserves the right to widen what counts as privileged.
        assertThat(js).doesNotContain("Vaier acts as root");
    }

    @Test
    void clearPinnedKeyIsOfferedOnlyWhereTheKeyWasActuallyRefused() throws IOException {
        // Acceptance criterion 4 of #345. A pin that can be cleared from a machine's page on an ordinary day
        // is a pin that gets cleared out of habit — which is the failure the pin exists to prevent. So the
        // verb hangs off the error state a real mismatch produces, and nothing else raises it.
        String js = read("explorer-shell.js");
        assertThat(js).contains("if (held.errorCode !== 'HOST_KEY_MISMATCH') return;");
        assertThat(js).contains("'Clear pinned key'");
        // ...and the failure has to reach the browser as a code, not as a sentence to be pattern-matched.
        assertThat(read("explorer-listing.js")).contains("errorCode: err && err.code");
    }

    @Test
    void clearingAPinIsAnInformedAssertion_notAConfirm() throws IOException {
        // A changed host key means either "you rebuilt this machine" or "something is impersonating it".
        // The dialog states both, shows the two fingerprints so the assertion is informed, and stays
        // disabled until the operator types the machine's name — the thing only they can assert.
        String js = read("explorer-shell.js");
        assertThat(js).contains("or something is impersonating it");
        // As coordinates, not prose: these are compared character by character, which is what mono is for.
        assertThat(js).contains("[['Pinned', coord(prints.pinned)], ['Now offered', coord(prints.presented)]]");
        assertThat(js).contains("const armed = () => input.value === machineName;");
        // And the machine is never left unpinned — the dialog says what happens next.
        assertThat(js).contains("pins the ");
        assertThat(js).contains("so it is never left unpinned");
    }

    @Test
    void theTerminalOffersTheSameRemedy_armedInTwoSteps() throws IOException {
        // The pop-out has no dialog primitives and should not grow a modal for one verb — but this verb
        // must not be a reflex either. Arming in place is the assertion, made where the refusal is read.
        String tw = read("terminal-window.js");
        assertThat(tw).contains("} else if (ev.code === CLOSE_HOST_KEY_MISMATCH) {");
        assertThat(tw).contains("armedLabel: 'Confirm — I changed this machine'");
        assertThat(tw).contains("/host-key',\n                { method: 'DELETE' });");
        // Cleared, then reconnect — the operator is told the way back, not left at a dead end.
        assertThat(tw).contains("Reconnect to pin the key it presents now.");
    }

    @Test
    void theRefusalSentenceStaysOneSentence() throws IOException {
        // It already exists in three places (TerminalWebSocketHandler, terminal-window.js and the domain
        // exception). Adding a fourth wording would leave the operator reading two different accounts of
        // the same refusal depending on which door they came through.
        assertThat(read("terminal-window.js"))
            .contains("The host key changed and was refused. "
                + "If you rebuilt this host, clear its pinned key and reconnect.");
    }

    // --- #309: a keypair Vaier mints for itself ---------------------------------------------------------
    //
    // A managed keypair inverts the credential dialog. There is nothing for the operator to paste and
    // nothing for them to read back except the public half — so the dialog must stop offering a private-key
    // field, and start offering the one line they actually need to install.

    @Test
    void theCredentialDialog_offersToGenerateAKeypair() throws IOException {
        String js = read("explorer-shell.js");

        assertThat(js).contains("/ssh-credential/generate");
        assertThat(js).contains("Generate keypair");
    }

    @Test
    void generatingOverAnExistingCredential_saysWhatIsLostBeforeItHappens() throws IOException {
        // Generating destroys the login Vaier currently holds, and the replacement does nothing until the
        // operator installs it. That gap is the whole risk, so it is stated before the button acts — never
        // discovered afterwards by a machine that has gone dark.
        String js = read("explorer-shell.js");

        assertThat(js).contains("confirmModal('Generate a new keypair for '");
        assertThat(js).contains("stops working immediately");
        assertThat(js).contains("authorized_keys");
    }

    @Test
    void aManagedKeypair_showsThePublicKeyInsteadOfAnEditablePrivateKeyField() throws IOException {
        // The operator can never usefully edit a key they do not have. Leaving the textarea on screen would
        // say they can; the public key and a copy button say what they can actually do with it.
        String js = read("explorer-shell.js");

        assertThat(js).contains("/ssh-credential/public-key");
        assertThat(js).contains("v.managed");
        // The private-key textarea and its passphrase are both withdrawn for a managed keypair.
        assertThat(js).contains("const isManagedKey = ");
    }

    @Test
    void thePublicKeyCanBeCopied_becauseItHasToBePastedSomewhereElse() throws IOException {
        // It is going into a file on another machine. Selecting 68 characters of base64 by hand is exactly
        // the step where an operator loses a character and then debugs an auth failure for an hour.
        String js = read("explorer-shell.js");

        assertThat(js).contains("navigator.clipboard.writeText(pubKey.textContent)");
        assertThat(js).contains("toast('Copied.')");
    }

    // --- the Update action (#352) ----------------------------------------------------------------------
    //
    // The update-available mark spent its whole life as advice with no verb attached, and the Inspector said
    // so in as many words. Giving it a verb means those words are now false, and it means the one control
    // the Explorer offers over a container has to be offered on exactly the terms the domain set.

    @Test
    void theUpdateAction_isOfferedOnlyOnTheDomainsOwnVerdict() throws IOException {
        // Whether a container may be updated is a decision, and it was taken on the machine the container
        // was scraped from — the same container name means Vaier's own stack on this host and the operator's
        // container on any other. A browser re-deriving it from the compose labels would be a second, quieter
        // copy of that decision, and the two would disagree the first time either moved.
        String js = read("explorer-shell.js");

        assertThat(js).contains("updateEligibility === 'UPDATABLE'");
        assertThat(js).doesNotContain("composeCoordinates &&");
    }

    @Test
    void aContainerVaierWillNotUpdate_saysWhyRatherThanShowingNothing() throws IOException {
        // A withheld button with no reason reads as a bug. Both refusals have a plain cause the operator can
        // act on — or decide not to — so each is spoken.
        String js = read("explorer-shell.js");

        assertThat(js).contains("NOT_COMPOSE_MANAGED:");
        assertThat(js).contains("VAIER_OWN_STACK:");
    }

    @Test
    void theInspector_noLongerClaimsVaierCannotActOnAContainer() throws IOException {
        // It said "it has no endpoint to control one" and "Vaier will not show you a control it cannot
        // honour". The first is now untrue and the second is now a promise being kept, not a reason for an
        // empty pane. A stale sentence next to a working button is worse than no sentence at all.
        String js = read("explorer-shell.js");

        assertThat(js).doesNotContain("has no endpoint to control one");
    }

    @Test
    void theOutcome_isTheSentenceTheBackendSent() throws IOException {
        // "The old container is still running on the image it had" is the reassurance that makes a failed
        // recreate readable, and the domain writes it so the Explorer and the log say the same thing. The
        // shell shows what it was handed; it does not translate an enum back into English of its own.
        String js = read("explorer-shell.js");

        assertThat(js).contains("container-update-settled");
        assertThat(js).contains(".message");
    }

    @Test
    void anUpdateInFlight_isReportedWithoutPolling() throws IOException {
        // A pull is minutes. The request returns 202 immediately and the outcome arrives on the fleet stream
        // the shell already holds open — so there is no second connection, and above all no timer asking
        // "are we there yet", which is the rule this frontend does not break.
        String js = read("explorer-shell.js");

        int from = js.indexOf("async function updateContainer(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("/docker-services/update");
        assertThat(body).doesNotContain("setInterval");
        assertThat(body).doesNotContain("setTimeout");
    }

    @Test
    void aMachineVaierCannotRunDockerOn_namesTheRemedy_notJustTheFault() throws IOException {
        // Colina 27 offered Update on five containers and every one was doomed: Vaier's SSH user was not in
        // that host's docker group. The scrape reads Docker over the tunnel and needs no group at all, so the
        // machine looked perfectly healthy while offering a control it could never honour. Withholding the
        // button is only half the fix — a withheld button with no remedy is a dead end.
        String js = read("explorer-shell.js");

        assertThat(js).contains("NO_DOCKER_ACCESS:");
        assertThat(js).contains("docker group");
    }

    @Test
    void vaiersOwnContainers_sayTheyMoveWithVaier_ratherThanReportingAVerdictNobodyCanAct() throws IOException {
        // #353. Vaier's own stack is no longer swept at all, so its containers carry no verdict — and
        // "Vaier cannot tell" would be technically true and useless. What is actually true is that these
        // images move with a Vaier release, which is the same fact the withheld Update already states.
        String js = read("explorer-shell.js");

        assertThat(js).contains("VAIER_OWN_STACK");
        assertThat(js).contains("Moves with Vaier");
    }

    @Test
    void theUpdateAction_standsDownInThePast() throws IOException {
        // An archive is how a machine stood then. Acting on it now would be acting on the present through a
        // window into the past — the same reason the update mark and the liveness dot hide back there.
        String js = read("explorer-shell.js");

        int from = js.indexOf("function updateAction(");
        assertThat(from).isPositive();
        assertThat(js.substring(from, js.indexOf("\n    }", from))).contains("S.at");
    }

    // --- upload: the browser's files into a machine's directory ----------------------------------------

    @Test
    void anUploadReportsRealProgress_fromTheBrowsersOwnSendEvents_neverAPoll() throws IOException {
        // The one place in this shell where progress is genuinely the BROWSER's to report: it is the thing
        // doing the sending. XHR's upload.onprogress is an event, exactly like the SSE the rest of the shell
        // listens to — so this obeys the no-polling rule rather than being an exception to it. A timer that
        // asked the server "how far along am I?" would be a poll, and there is no endpoint that could answer.
        String js = read("explorer-shell.js");

        int from = js.indexOf("function startUpload(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("upload.onprogress");
        assertThat(body).doesNotContain("setInterval");
        assertThat(js).doesNotContain("setInterval");
    }

    @Test
    void anUploadOntoATakenName_asksBeforeReplacing_andOnlyThenSendsOverwrite() throws IOException {
        // The backend refuses a taken name with 409 rather than overwriting, so the operator is asked. Without
        // the confirm the retry would be an automatic overwrite — the silent data loss the refusal exists to
        // prevent — and without the retry the conflict would be a dead end.
        String js = read("explorer-shell.js");

        int from = js.indexOf("function startUpload(");
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("409");
        assertThat(body).contains("confirmModal");
        assertThat(body).contains("overwrite");
    }

    @Test
    void uploadsAreFilesOnly_neverAWholeFolder() throws IOException {
        // Folder upload was deliberately deferred: it means recreating a tree on the far side, which is a
        // different operation with different failure modes. The input must not quietly offer it.
        String js = read("explorer-shell.js");

        assertThat(js).doesNotContain("webkitdirectory");
    }

    @Test
    void aLandedUpload_reReadsTheDirectory_soTheNewFileIsThere() throws IOException {
        // A folder the operator is standing in is cached. Without the re-read, a file that really did land
        // would not appear until something else happened to invalidate the slot.
        String js = read("explorer-shell.js");

        int from = js.indexOf("function startUpload(");
        assertThat(js.substring(from, js.indexOf("\n    }", from))).contains("refreshDir");
    }

    @Test
    void uploadingIsPresentOnly_offeredNowhereInThePast() throws IOException {
        // The same invariant every write in this shell keeps: an archive is read-only, so there is nowhere in
        // the past to put a file. The affordance is not drawn there rather than being drawn and refused.
        String js = read("explorer-shell.js");

        int from = js.indexOf("function renderUploadAction(");
        assertThat(from).isPositive();
        assertThat(js.substring(from, js.indexOf("\n    }", from))).contains("S.at");
    }

    @Test
    void aDirectoryHasOneBar_notThree_withEveryVerbThatAppliesInIt() throws IOException {
        // The pane head, the selection bar and the paste bar were the same row shape doing the same job three
        // times — label left, actions right — and stacking them made the top of a pane a pile of bars. They
        // are one row now: the head's action group carries every verb that applies right now.
        String js = read("explorer-shell.js");
        String css = read("explorer-shell.css");

        // The two body bars are gone outright, DOM and stylesheet alike — not merely hidden.
        assertThat(js).doesNotContain("renderSelectionBar");
        assertThat(js).doesNotContain("renderPasteBar");
        assertThat(js).doesNotContain("ex-selbar");
        assertThat(js).doesNotContain("ex-pastebar");
        assertThat(css).doesNotContain("ex-selbar");
        assertThat(css).doesNotContain("ex-pastebar");
    }

    @Test
    void theFoldersOwnVerbsArePinnedLast_soRefreshAndUploadNeverMoveUnderThePointer() throws IOException {
        // Order is load-bearing once the verbs share a row. The action group hugs the right, so appending the
        // contextual verbs FIRST grows the group leftward and leaves Refresh and Upload — the two that are
        // always there, and the two used idly — anchored at the right edge whatever else comes and goes.
        String js = read("explorer-shell.js");

        assertThat(js).contains(
            "actions.append(selectionVerbs(), pasteVerb(machineId, path), folderVerbs(machineId, path))");
    }

    @Test
    void aLiveSelectionTakesOverTheSubtitle_whileTheMachineNameKeepsTheTitle() throws IOException {
        // The head's subtitle is the "how much is here" slot, and a live selection is the more urgent version
        // of that same fact. The TITLE stays the machine name: it is the pane's identity and where you are
        // standing, and swapping it for a count would move the one label that should never move.
        String js = read("explorer-shell.js");

        int from = js.indexOf("function directorySubtitle(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("S.sel");
        assertThat(body).contains("selected");
        assertThat(body).contains("items");
    }

    @Test
    void theTimeRailKeepsItsOwnRow_becauseItIsAnAxisAndNotAVerb() throws IOException {
        // Deliberately NOT folded into the merged bar: a rail is scrubbed, so it needs width, and it only
        // exists on a machine that has archives.
        String js = read("explorer-shell.js");

        assertThat(js).contains("body.appendChild(renderRail(machineId))");
    }

    @Test
    void everyVerbStaysReachableOnAPhone_becauseOneBarCarriesMoreOfThem() throws IOException {
        // The old bars wrapped on small screens because a button group cannot shrink past its content, and a
        // selection you can make but not act on is the bug that caused. One merged bar carries MORE verbs, so
        // the constraint is tighter, not looser: the head and its action group both wrap.
        String css = read("explorer-shell.css");

        assertThat(css).contains(".ex-pane-head, .ex-pane-actions { flex-wrap: wrap; }");
    }

    @Test
    void theUploadControl_livesInThePaneHeadBesideRefresh_andCostsTheListingNoRoom() throws IOException {
        // It shipped as a third bar stacked above the listing and the operator said so at once: the selection
        // bar and the paste bar EARN their row by appearing only when there is something selected or held,
        // and an upload control that is always there had earned nothing. A folder's verbs already have a home
        // in the pane head — that is where a permanent one belongs, at zero vertical cost.
        String js = read("explorer-shell.js");
        String css = read("explorer-shell.css");

        assertThat(js).contains("renderUploadAction(");
        int from = js.indexOf("function folderVerbs(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("renderUploadAction(");
        assertThat(body).contains("Refresh");
        // The bar and its stylesheet are gone outright, not merely hidden.
        assertThat(js).doesNotContain("renderUploadBar");
        assertThat(js).doesNotContain("ex-upbar");
        assertThat(css).doesNotContain("ex-upbar");
    }

    @Test
    void theDropAffordance_appearsOnlyWhileADragIsInFlight() throws IOException {
        // The original reasoning stands — dropping files onto a folder is invisible until something says you
        // can — but it is only worth saying at the moment it is true. The dropzone is drawn hidden and lit by
        // the drag itself, so at rest it costs nothing and never shifts the listing under the pointer.
        String js = read("explorer-shell.js");

        int from = js.indexOf("function armDropTarget(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("dragenter");
        assertThat(body).contains("dragleave");
        assertThat(body).contains("is-on");
        assertThat(js).contains("function renderDropzone(");
    }

    @Test
    void theWholePaneBodySwallowsTheDrop_soAFileLetGoOverTheListingNeverNavigatesTheTabAway() throws IOException {
        // A browser's default action for a dropped file is to OPEN it, which navigates the tab off the
        // Explorer and loses everything on screen. The listing is the obvious place to aim, so the body — not
        // just the lit dropzone — has to take the drop and preventDefault it.
        String js = read("explorer-shell.js");

        assertThat(js).contains("armDropTarget(body,");
        int from = js.indexOf("function armDropTarget(");
        assertThat(js.substring(from, js.indexOf("\n    }", from))).contains("e.preventDefault()");
    }

    @Test
    void theUploadEndpoint_isTheOneTheApiGrewForIt() throws IOException {
        String js = read("explorer-shell.js");

        assertThat(js).contains("/files/upload");
    }

    // --- a phone's pane says only what is true of a phone ------------------------------------------------

    @Test
    void whetherVaierCanReachInsideAMachine_isOneNamedRule_notTwoInlineCopies() throws IOException {
        // It decides two things — whether the SSH section renders, and whether "Inside this machine" does.
        // Written out twice they drift, which is exactly what happened: the phone's pane skipped the SSH
        // section and then told the operator to turn on SSH access below it.
        String js = read("explorer-shell.js");

        assertThat(js).contains("function reachesInside(m)");
        assertThat(js.split("'MOBILE_CLIENT'", -1).length - 1)
            .as("the client-type literal belongs in the named rule and nowhere else")
            .isEqualTo(1);
    }

    @Test
    void aPhonesPane_neverPointsAtAnSshControlItDoesNotRender() throws IOException {
        // Vaier cannot reach inside a phone — no SSH, so no files, shell or disk. That is structural, not a
        // setting, so the pane says nothing rather than spending a section header and a paragraph on an
        // absence that will never change, on the narrowest screen in the product.
        String js = read("explorer-shell.js");

        // The named rule is asked once and every SSH-pointing line hangs off the answer.
        assertThat(js).contains("const reachable = reachesInside(m);");
        assertThat(js).contains("const offerAccess = reachable && !m.sshAccess;");

        int instruction = js.indexOf("Turn it on to give this machine an SSH credential");
        assertThat(instruction)
            .as("the instruction still exists for machines that DO have the toggle")
            .isGreaterThan(0);
        assertThat(js.lastIndexOf("if (offerAccess) {", instruction))
            .as("...and is only reachable from inside that guard")
            .isGreaterThan(0);
        // The empty state stopped pointing at the control as well: it says what is missing, not where to go
        // and fix it, so it reads correctly on a machine that has no toggle to be sent to.
        assertThat(js).doesNotContain("Turn on SSH access below");
    }

    /**
     * The Claude sign-in is drawn only where a sign-in could actually happen. Its home is now the pop-out
     * shell window — signing in runs the CLI in one OS user's home, so it belongs to the window that IS
     * that machine's terminal as that user, not to the pane where you read about a machine. The rule that
     * gates it is untouched: SSH access alone is not enough, because without a stored credential Vaier
     * cannot open a shell at all, the backend answers {@code SKIPPED}, and a control offered there would
     * explain an impossibility instead of offering an action. The domain's own words — "A section
     * explaining why a sign-in is impossible is worth less than no section."
     *
     * <p>Retargeted at {@code claude-sign-in.js}, which owns the decision now, plus the two ends of the
     * move: the shell window mounts it, and the machine pane no longer draws it anywhere.
     */
    @Test
    void theClaudeSection_isDrawnOnlyWhereASignInCouldHappen() throws IOException {
        String js = read("claude-sign-in.js");

        assertThat(read("terminal-window.js")).as("the shell window is where a sign-in is drawn")
            .contains("window.VaierClaude.mount(");
        assertThat(read("explorer-shell.js")).as("and the machine pane draws none of it")
            .doesNotContain("renderClaudeSignIn").doesNotContain("claude-sign-in'");
        // Nothing at all where a sign-in is impossible — not a greyed control, not an explanation. The bar
        // hides its control on the same verdict the module refuses to draw on.
        assertThat(read("terminal-window.js")).as("no control where a sign-in cannot happen")
            .contains("btnClaude.hidden = !view.draw");
        // The gate is the server's answer, not a rule re-typed here. `Machine.runsAShellVaierCanReach`
        // is two clauses (SSH access AND a stored credential), and a browser copy of it is a second
        // chance to get it wrong — which is exactly what the domain's own Javadoc warns about.
        assertThat(js).as("the server decides whether a sign-in is possible here")
            .contains("st.signInPossibleHere");
        assertThat(js).as("no browser copy of the two-clause shell-reach rule")
            .doesNotContain("if (m.hasCredential) renderClaudeSignIn");
        // And whether to offer the verb is the domain's rule too: an already-signed-in machine may sign in
        // again, which a state-string derivation in here got wrong.
        assertThat(js).as("the server decides whether a sign-in can begin")
            .contains("st.signInCanBegin");
        assertThat(js).doesNotContain("st.state !== 'SIGNED_OUT' && st.state !== 'UNKNOWN'");
    }

    /**
     * The one state the backend takes real trouble over must survive into the browser: a machine Vaier
     * could not read is not a machine that is signed out. If UNKNOWN ever rendered as "signed out", an
     * operator would redo a sign-in that nothing had undone.
     */
    @Test
    void theClaudeSection_neverDrawsAnUnreadableMachineAsSignedOut() throws IOException {
        // Retargeted at claude-sign-in.js, which now holds both the state map and the sentence. The move
        // added a second way to get this wrong and the guard covers it: a read that FAILS still draws a
        // control, because "Vaier couldn't tell" is not the verdict that withholds one — and what it says
        // is that the read failed, never that the machine is signed out.
        String js = read("claude-sign-in.js");

        assertThat(js).contains("UNKNOWN:       { label: 'Couldn’t tell'");
        assertThat(js).as("UNKNOWN keeps its own sentence, distinct from signed out")
            .contains("That is not the same as ");
        assertThat(js).as("a failed read is said as a failed read")
            .contains("That is this read ");
        assertThat(js).as("and a failed read is never dressed as a standing the server took")
            .contains("if (c.phase === 'error') return { draw: true, state: 'UNKNOWN'");
    }

    /**
     * Vocabulary, enforced because it is exactly the pair an operator could confuse: a machine has an SSH
     * <b>login</b> and a Claude <b>sign-in</b>, and the thing copied out of the flow is an
     * <b>authorization URL</b>. "Login link" collides with the first while naming the third.
     */
    @Test
    void theClaudeSection_saysAuthorizationUrlAndNeverLoginLink() throws IOException {
        // Retargeted at claude-sign-in.js, which owns the copy now — and kept over the two pages that load
        // it, so the phrase cannot creep back in on either side of the move.
        assertThat(read("claude-sign-in.js")).contains("Copied the authorization URL.");
        for (String asset : List.of("claude-sign-in.js", "explorer-shell.js", "terminal-window.js")) {
            assertThat(read(asset)).as("\"login link\" in %s", asset)
                .doesNotContain("login link").doesNotContain("Login link");
        }
    }

    // --- 20. the Credentials view actually draws ---------------------------------------------------------
    //
    // The bug these exist for: `renderPane` dispatched the `credentials` kind to a `renderCredentials` that
    // was never written. Everything around it had landed — the entry, the state slot, the whole CSS
    // block for the coverage strip, the REST surface, this file's own allowlist entry — so every test passed
    // and the entry opened onto an empty pane, throwing a ReferenceError nobody saw. A view is not shipped
    // when the things around it are; it is shipped when something draws it.

    /**
     * Asserted for every kind the pane can open, not only the one that was missing: a half-landed view fails
     * exactly this way every time, and the dispatch table is the one place all of them are named.
     */
    @Test
    void everyPaneTheShellCanOpen_hasAFunctionThatDrawsIt() throws IOException {
        String js = read("explorer-shell.js");
        Matcher m = Pattern.compile("kind === '[a-z]+'\\) return (render[A-Za-z]+)\\(pane\\)").matcher(js);
        int found = 0;
        while (m.find()) {
            found++;
            assertThat(js).as("renderPane dispatches to %s, which is never defined", m.group(1))
                .contains("function " + m.group(1) + "(");
        }
        assertThat(found).as("the dispatch table was not found at all").isGreaterThan(10);
    }

    @Test
    void theCredentialsView_readsTheCredentialsItDraws() throws IOException {
        String js = read("explorer-shell.js");

        assertThat(js).contains("function renderCredentials(");
        assertThat(js).as("the view reads the endpoint that shipped with it")
            .contains("fetch('/fleet-credentials'");
        // The card's three parts, pinned against the CSS that was written for them.
        assertThat(js).contains("ex-cred-strip").contains("ex-cred-tally").contains("ex-cred-exc");
        // Every verb the entry exists for.
        assertThat(js).contains("/distribute").contains("/withdraw");
    }

    /**
     * A machine with no shell can never hold the file, so counting one would put all-green permanently out
     * of reach — and an indicator that can never come clean is one an operator learns to skip past.
     */
    @Test
    void theCoverageStrip_countsOnlyTheMachinesThatCouldHoldTheFile() throws IOException {
        String js = read("explorer-shell.js");

        assertThat(js).as("SKIPPED is excluded from the strip and the tally")
            .contains("m.state !== 'SKIPPED'");
        assertThat(js).as("and named underneath rather than dropped silently")
            .contains("no shell Vaier can reach");
    }

    /**
     * An empty {@code machines} list is the distributor having no observation yet — a fresh boot — not a
     * credential that is nowhere. "0 of 0" would send an operator to fix a fleet that is fine.
     */
    @Test
    void aCredentialNothingHasCheckedYet_saysSo_ratherThanZeroOfZero() throws IOException {
        String js = read("explorer-shell.js");

        assertThat(js).contains("Not checked yet");
        assertThat(js).doesNotContain("' of 0 in place'");
    }

    /**
     * The two removals do very different things — one reaches every machine, the other only forgets Vaier's
     * copy — and an operator who confuses them either leaves a live secret on the fleet or wipes one they
     * meant to keep. So both confirm, and each says which it is.
     */
    @Test
    void theTwoRemovals_eachSayWhetherItReachesTheFleet() throws IOException {
        String js = read("explorer-shell.js");

        assertThat(js).as("withdraw is the one that revokes").contains("Withdraw");
        assertThat(js).as("delete says plainly that it reaches no machine")
            .contains("reaches no machine");
        assertThat(js).as("and is confirmed by typing the name, not by one more OK")
            .contains("function confirmByTypingName(");
    }

    /**
     * Vaier will not show a secret it holds, so there is nothing to edit — a save replaces it whole. The
     * label has to say that, because "Edit" promises a field with the old value in it.
     */
    @Test
    void theSecretIsReplacedWhole_neverEdited() throws IOException {
        String js = read("explorer-shell.js");

        assertThat(js).contains("Replace secret");
        assertThat(js).as("storing is not distributing, and the operator is told so")
            .contains("Nothing has reached a machine yet");
    }

    // --- 21. a re-render is not a navigation --------------------------------------------------------------
    //
    // The bug these exist for: the pane is repainted under the operator constantly and without being asked —
    // the Claude sign-in read lands an SSH round-trip after the machine page opened, a disk standing moves on
    // the five-minute sweep, a container update settles, a stream reconnects. Every one of those went through
    // `renderPane`, which blanked the pane and forced `scrollTop = 0`. Standing halfway down a long machine
    // page, that is not a repaint an operator can see past: the page scrolls itself away from where they are,
    // mid-read, for a change somewhere else entirely.

    /**
     * The offset belongs to the view, not to the pane, and a repaint of the view you are already standing in
     * has to put it back. This is the whole fix: nothing else about the render changes.
     */
    @Test
    void repaintingTheViewYouAreStandingIn_leavesYouWhereYouWereStanding() throws IOException {
        String js = read("explorer-shell.js");

        assertThat(js).as("the pane no longer forces itself to the top on every single render")
            .doesNotContain("pane.scrollTop = 0;");
        assertThat(js).as("the view the offset belongs to is named in one place")
            .contains("function paneViewKey(");
        assertThat(js).contains("pane.scrollTop = resume;");
    }

    /**
     * The opposite bug, and the reason the offset is keyed by view rather than simply kept: restoring it
     * unconditionally would open a machine halfway down, at wherever the operator happened to be standing on
     * the machine before it.
     */
    @Test
    void movingToAnotherView_startsAtItsTop() throws IOException {
        String js = read("explorer-shell.js");

        assertThat(js).contains("view === _paneView ? pane.scrollTop : 0");
        assertThat(js).as("and the past is a different view of the same path, not the same one")
            .contains("key(S.path) + '@'");
    }

    // --- 22. a mark says WHAT it is; only trouble recolours it ---------------------------------------------
    //
    // The Claude mark set the rule when it took Claude's clay: colour identifies the thing, and state is
    // carried some other way. These extend it to the rest of the marks, and retire the one hue that was never
    // Vaier's — `--green: #4ec9b0` is VS Code Dark+'s type colour, borrowed by the palette this project
    // explicitly moved off, and left behind when everything around it was replaced.
    //
    // What must NOT change is the alarm. A disk over its threshold and a backup that failed are the two things
    // on a card worth interrupting someone for, so amber and red still override the identity colour outright.

    /**
     * The identity colours live as tokens beside {@code --claude}, so a mark's hue is named once and the
     * concept it belongs to is legible at the definition rather than only at the use.
     */
    @Test
    void eachMarkedConcept_hasAColourOfItsOwn() throws IOException {
        String css = read("styles.css");

        assertThat(css).as("Docker's own blue, borrowed the same way Claude's clay is")
            .contains("--docker:");
        assertThat(css).contains("--backup:").contains("--disk:");
    }

    /**
     * The point of the change: a healthy disk and a good backup stop wearing the shared green and wear their
     * own hue. Asserted on the maps rather than the CSS, because the map is where a state is turned into a
     * colour and where a regression would actually land.
     */
    @Test
    void aHealthyMark_wearsItsConceptsColour_notTheSharedGreen() throws IOException {
        String js = read("explorer-shell.js");

        assertThat(js).as("a clear disk is disk-coloured").contains("CLEAR: 'is-disk'");
        assertThat(js).as("a good backup is backup-coloured").contains("SUCCESS: 'is-backup'");

        // And the shared green is gone from the marks entirely rather than left behind as a rule nothing
        // reaches — dead tints are how a retired vocabulary quietly comes back.
        // The brace matters: `.ex-mark.is-update` — the container nudge, which is still amber and still
        // wanted — contains this rule's whole selector as a prefix.
        assertThat(read("explorer-shell.css")).doesNotContain(".ex-mark.is-up {");
    }

    /**
     * The alarm is the thing this change must not cost. A disk over its threshold and a failed or incomplete
     * backup still take the trouble colours, overriding the identity hue.
     */
    @Test
    void troubleStillOverridesTheIdentityColour() throws IOException {
        String js = read("explorer-shell.js");

        assertThat(js).contains("CLOSING: 'is-degraded'").contains("BREACHING: 'is-down'");
        assertThat(js).as("INCOMPLETE reads red, not amber — the archive looks fine until you need it")
            .contains("INCOMPLETE: 'is-down'");
    }

    /**
     * A mark carries identity; a dot is pure status and stays a traffic light. The two diverge on success
     * alone, so the mark map is DERIVED from the dot map rather than written out beside it — a new backup
     * outcome added to one can then never be forgotten in the other.
     */
    @Test
    void theMarkMapIsDerivedFromTheDotMap_soANewOutcomeCannotBeForgotten() throws IOException {
        String js = read("explorer-shell.js");

        assertThat(js).contains("RUN_MARK = { ...RUN_DOT,");
        assertThat(js).as("the dot itself is untouched — a liveness dot is status, not identity")
            .contains("SUCCESS: 'is-up'");
    }

    /**
     * Docker is a borrowed identity exactly as Claude is, so its mark wears its own blue on the fleet card —
     * the one surface a machine's capabilities are drawn on now.
     */
    @Test
    void theDockerMark_wearsItsOwnBlue() throws IOException {
        String css = read("explorer-shell.css");

        assertThat(css).contains(".ex-mark.is-docker { color: var(--docker); }");
    }

    /**
     * The rest of the capability strip stays deliberately quiet. It says what a machine IS, not how it is
     * doing, and colouring it would put it in competition with the marks that do carry a verdict.
     */
    @Test
    void theRestOfTheCapabilityStrip_staysQuiet() throws IOException {
        String css = read("explorer-shell.css");

        assertThat(css).doesNotContain(".ex-cap.is-relay {");
        assertThat(css).doesNotContain(".ex-cap.is-backupserver {");
        assertThat(css).doesNotContain(".ex-mark.is-relay {");
        assertThat(css).doesNotContain(".ex-mark.is-backupserver {");
    }

    // --- 23. the identity colours leave the fleet cards ----------------------------------------------------
    //
    // §22 gave each marked concept a hue of its own. It stopped at the fleet cards, so the same disk was
    // violet on its card and plain grey two clicks away in the pane, and the same machine's Claude standing
    // was clay on its card and GREEN on its pane. These carry the four colours out to the entry glyphs, the
    // Claude standing and the backed-up shield — and pin the boundary that makes the scheme readable at all:
    // amber and red are still spent on nothing but trouble, and a status dot is never given an identity hue.

    /**
     * An entry's glyph says WHAT it is about. The map exists so the rule is written once and the two
     * surfaces that draw an entry — the "Inside this machine" grid and the ⌘K palette — cannot drift into
     * two vocabularies.
     */
    @Test
    void anEntrysGlyph_wearsTheColourOfWhatItIsAbout() throws IOException {
        String js = read("explorer-shell.js");

        assertThat(js).as("a filesystem is violet").contains("disk: 'is-disk'");
        assertThat(js).as("an archive and the repository holding it are both backup-coloured")
            .contains("backup: 'is-backup'").contains("repo: 'is-backup'");
        assertThat(js).as("Docker is a borrowed identity, so it keeps its own blue")
            .contains("containers: 'is-docker'").contains("container: 'is-docker'");

        String css = read("explorer-shell.css");
        assertThat(css).contains(".ex-ico.is-disk { color: var(--disk); }");
        assertThat(css).contains(".ex-ico.is-backup { color: var(--backup); }");
        assertThat(css).contains(".ex-ico.is-docker { color: var(--docker); }");
    }

    /**
     * The trap this whole map exists to avoid: {@code ICON_FOR} sends a backup repository and both container
     * kinds to the same {@code box} glyph, so a tint written against the GLYPH would paint every Docker
     * container backup-aqua and every repository Docker-blue. The tint is therefore keyed off the kind, and
     * this asserts the two collide on the glyph while staying apart on the colour.
     */
    @Test
    void theEntryTint_isKeyedOffTheKindAndNeverTheGlyph() throws IOException {
        String js = read("explorer-shell.js");

        assertThat(js).as("the collision is real — three kinds, one glyph")
            .contains("containers: 'box', container: 'box'").contains("repo: 'box'");
        assertThat(js).as("and the tint still tells them apart")
            .contains("repo: 'is-backup'").contains("container: 'is-docker'");
        assertThat(js).as("because it is looked up by kind").contains("TINT_FOR[kind]");
    }

    /**
     * One rule, applied at both call sites that draw an entry, rather than two copies of a conditional — so a
     * third surface gets the colours by construction and none can be forgotten.
     */
    @Test
    void bothEntrySurfaces_drawTheirGlyphThroughTheOneTintedSeam() throws IOException {
        String js = read("explorer-shell.js");

        assertThat(js).as("the Inside-this-machine card").contains("card(entryIco(kid.kind, kid.name),");
        assertThat(js).as("the palette result")
            .contains("item.innerHTML = entryIco(entry.kind, entry.path[entry.path.length - 1]);");
        assertThat(js).as("and the seam is the one place an entry glyph is built — no surface goes round it")
            .containsOnlyOnce("svg(iconFor(");
    }

    /**
     * The Containers pane lists entries too, so its rows go through the same seam and its
     * glyphs come out Docker-blue. A published service is not a borrowed identity and keeps the inherited
     * colour — it rides along only because it is the other listing of entries.
     */
    @Test
    void theContainerRows_wearDockerBlue() throws IOException {
        String js = read("explorer-shell.js");

        assertThat(js).contains("entryIco('container'), c.containerName");
        assertThat(js).contains("listRow(entryIco('service')");
    }

    /**
     * The inconsistency this pass exists to end: the Claude card tinted its state word through the generic
     * {@code is-in} tone, which renders {@code var(--green)} — so one machine's Claude standing read green
     * where it was said and clay on its machine card, contradicting the design comment sitting on the
     * card's own mark. Signed in is solid clay, signed out the same hue faded, exactly as the mark does it.
     *
     * <p>Renamed and retargeted: the standing is no longer said on the machine pane at all. It is said in
     * the pop-out shell window, where the sign-in now lives, and its tones are the {@code tw-*} rules in
     * that window's stylesheet. The tone NAMES are unchanged on purpose — they come off the one shared
     * state map, so the card and the shell window still cannot disagree about one machine.
     */
    /**
     * A shipped-broken shell, commemorated. {@code actionButton} is a hoisted function declaration and every
     * one of its callers runs while the module body is still initialising — so when it grew a dependency on
     * an icon table declared <em>below</em> those callers, the first call threw
     * {@code ReferenceError: Cannot access 'glyph' before initialization}, the whole IIFE died, and the
     * window rendered no terminal at all. Nothing caught it: the file parses, so {@code node --check} is
     * silent, and a temporal dead zone is a runtime fault.
     *
     * <p>The rule is narrow on purpose — it pins the one ordering that broke rather than trying to be a
     * JavaScript analyser.
     */
    @Test
    void theShellWindowsIconTable_isDeclaredBeforeAnythingBuildsAButtonFromIt() throws IOException {
        String js = read("terminal-window.js");

        int icons = js.indexOf("const ICON = {");
        int glyph = js.indexOf("const glyph = ");
        assertThat(icons).as("the bar still has an icon table").isPositive();
        assertThat(glyph).as("and a builder for it").isPositive();

        int firstUse = js.indexOf("actionButton('");
        assertThat(firstUse).as("something still builds a button").isPositive();
        assertThat(icons).as("the icon table is initialised before the first button is built").isLessThan(firstUse);
        assertThat(glyph).as("and so is the builder that reads it").isLessThan(firstUse);
    }

    /**
     * The shell window's bar draws its verbs as glyphs on a phone, where five labelled buttons do not fit and
     * dropping one of them would be the wrong half to lose. Its Claude glyph is the Explorer's, copied — the
     * two files have no icon set in common and building one for five drawings would be more machinery than
     * the feature earns — so the copy is guarded instead. A machine that wears one shape on its fleet card
     * and a different one in its own shell is telling the operator they are two different things.
     */
    @Test
    void theShellWindowsClaudeGlyph_isTheSameDrawingAsTheFleetCards() throws IOException {
        String shell = read("explorer-shell.js");
        String window = read("terminal-window.js");

        Matcher m = Pattern.compile("claude:\\s*'([^']+)'").matcher(shell);
        assertThat(m.find()).as("the Explorer still names a claude glyph").isTrue();
        String drawing = m.group(1);

        assertThat(window).as("and the shell window's bar draws exactly it").contains(drawing);
        // Both at the same weight, or the same path reads as two different icons.
        assertThat(shell).contains("stroke-width=\"1.5\"");
        assertThat(window).contains("stroke-width=\"1.5\"");
    }

    @Test
    void theClaudeStandingInTheShellWindow_wearsTheSameClayAsItsFleetCard() throws IOException {
        String js = read("claude-sign-in.js");
        assertThat(js).contains("tone: 'is-claude-in'").contains("tone: 'is-claude-out'");

        String css = read("terminal-window.css");
        assertThat(css).contains(".tw-claude-state.is-claude-in { color: var(--claude); }");
        assertThat(css).contains(".tw-claude-state.is-claude-out { color: var(--claude); opacity: .45; }");
        assertThat(css).as("the left edge is the same word again, so it follows the word")
            .contains(".tw-claude-card:has(.tw-claude-state.is-claude-in) { border-left-color: var(--claude); }");
        // The bar's own control says the same standing in the same clay, hollow when nobody holds a
        // sign-in — never amber or red, which belong to trouble.
        assertThat(css).contains(".tw-claude-btn.is-claude-in {").contains(".tw-claude-btn.is-claude-out {");
        assertThat(css.substring(css.indexOf(".tw-claude-btn.is-claude-in {"),
                                 css.indexOf(".tw-claude-btn.is-muted")))
            .as("a sign-in is presence, not an outage")
            .doesNotContain("var(--red)").doesNotContain("var(--orange)").doesNotContain("var(--yellow)");
        // And the green tone it used to reach for is gone rather than left lying about — on either sheet.
        assertThat(css).doesNotContain(".tw-claude-state.is-in {");
        assertThat(read("explorer-shell.css")).doesNotContain(".ex-standing-state.is-in {")
            .doesNotContain(".ex-standing-state.is-claude-in");
    }

    /**
     * A sign-in is presence, not a verdict — but a standing Vaier could not READ still is one. The clay tone
     * is Claude-scoped so it recolours nothing else, and the trouble and unknown tones on the same card are
     * untouched.
     */
    @Test
    void theClaudeTintIsClaudeScoped_andLeavesTroubleAndUnknownAlone() throws IOException {
        // Retargeted at terminal-window.css, which carries these tones now the sign-in is drawn there. The
        // invariant is the same one: the clay is scoped to the Claude standing, and the trouble and unknown
        // tones sitting on the very same card are untouched by it.
        String css = read("terminal-window.css");

        assertThat(css).contains(".tw-claude-state.is-bad { color: var(--red); }");
        assertThat(css).contains(".tw-claude-state.is-muted { color: var(--text-dim); }");
        assertThat(css).contains(".tw-claude-card:has(.tw-claude-state.is-bad) { border-left-color: var(--red); }");
    }

    /**
     * The shield in the file browser means "this is backed up" — backup identity, not brand — so it stops
     * wearing the generic accent cyan. The half-shield reuses the same hue faded rather than switching to a
     * second colour, mirroring how the signed-out Claude mark stays clay.
     */
    @Test
    void theBackedUpShield_wearsBackupsOwnColour_notTheGenericBrand() throws IOException {
        String css = read("explorer-shell.css");

        assertThat(css).contains(".ex-shield { display: inline-flex; align-items: center; margin-left: 5px; "
            + "color: var(--backup); flex: none; }");
        assertThat(css).contains(".ex-shield.is-partial { color: var(--backup); opacity: .45; }");
    }

    /**
     * The boundary the whole scheme rests on. A dot reports STATE and stays a traffic light; amber is still
     * spent on nothing but an advisory. Handing either an identity hue would leave the fleet with colours
     * that no longer say anything.
     *
     * <p>The up dot was asserted here too until the fleet stopped drawing one — a healthy machine is not news.
     * That is a narrowing of this guard, not a hole in it: the states that still paint are still the traffic
     * light, and section 24 holds the line that removing green did not quietly turn "unknown" into "fine".
     */
    @Test
    void theStatusIndicators_keepTheirTrafficLight() throws IOException {
        String css = read("explorer-shell.css");

        assertThat(css).contains(".ex-dot.is-down { background: var(--red); }");
        assertThat(css).contains(".ex-dot.is-degraded { background: var(--yellow); }");
        assertThat(css).as("the update mark is advisory, and advisory is amber")
            .contains(".ex-mark.is-degraded, .ex-mark.is-update { color: var(--yellow);");
        assertThat(css).as("the nudge glyph is uniformly accent by design")
            .contains(".ex-nudge-ico").contains("color: var(--accent); margin-top: 1px; }");
    }

    // --- 24. up draws nothing; the card carries the alarm --------------------------------------------------
    //
    // A green dot on every healthy machine is decoration on the case nobody has to act on, and it made the two
    // machines in trouble compete for the eye with the eleven that were fine. Vaier's own rule elsewhere is to
    // speak only on trouble. These pin that rule onto the fleet's most-looked-at surface.

    /**
     * A dot that paints nothing takes no space either. Keeping the 6px as a reservation was the first cut, on
     * the theory that names should line up down the fleet — but a blank slot on every healthy machine is the
     * same noise a green dot was, only harder to point at. Layout now shifts when a machine goes amber or red,
     * which is exactly the moment a row has earned the right to draw attention to itself.
     */
    @Test
    void aMachineThatIsUp_drawsNoDotAndReservesNoSpace() throws IOException {
        String css = read("explorer-shell.css");

        assertThat(css).as("up has no paint left").doesNotContain(".ex-dot.is-up {");
        assertThat(css).as("and nothing is laid out for a dot that will not paint")
            .contains(".ex-dot { display: none;");
        assertThat(css).as("only the two that paint take their place back")
            .contains(".ex-dot.is-degraded, .ex-dot.is-down { display: block; }");
    }

    /**
     * Only trouble paints. Amber and red are the whole vocabulary now — a machine that is fine, one that is
     * asleep, and one nothing has probed yet all draw nothing.
     *
     * <p>This test asserted the opposite for exactly one deploy, and the reversal is deliberate rather than a
     * guard someone gave up on: grey was kept so that "nobody has asked yet" could not be read as "fine", and
     * the operator judged the dots on a routinely-sleeping phone and laptop to be worth more than that
     * distinction. What it costs is real and recorded here — on the fleet listing, an unprobed machine is now
     * indistinguishable from a healthy one, and the header's "N online" count is the only place the gap still
     * shows. UNKNOWN is still mapped and still named in the domain; it simply has no colour any more.
     */
    @Test
    void onlyTroublePaints_soAnUnprobedMachineLooksLikeAHealthyOne() throws IOException {
        String css = read("explorer-shell.css");
        String js = read("explorer-shell.js");

        assertThat(css).doesNotContain(".ex-dot.is-idle");
        assertThat(css).as("amber and red are the whole vocabulary")
            .contains(".ex-dot.is-degraded").contains(".ex-dot.is-down");
        assertThat(js).as("the state is still MAPPED — it lost its colour, not its meaning")
            .contains("'UNKNOWN':  'is-idle'");
    }

    /** What the dot stopped saying, said where it cannot be missed. */
    @Test
    void aMachineThatIsDown_reddensItsWholeCard() throws IOException {
        String css = read("explorer-shell.css");

        assertThat(css).contains(".ex-card.is-down");
        assertThat(css).as("and keeps a hover of its own, which the plain .ex-card:hover would have stolen")
            .contains(".ex-card.is-down:hover");
    }

    /**
     * The dot and the card behind it are two renderings of one answer, so they are painted in one place. Two
     * call sites asking {@code livenessOf} separately is how a card ends up red around a dot that has gone
     * grey — and the live repaint on the stream would only ever fix one of them.
     */
    @Test
    void theDotAndTheCardAroundIt_arePaintedFromOneAnswer() throws IOException {
        String js = read("explorer-shell.js");

        assertThat(js).containsOnlyOnce("function paintLiveness(");
        assertThat(js).as("the card class is toggled there and nowhere else")
            .containsOnlyOnce("classList.toggle('is-down'");
    }

    /**
     * Once the card is red the dot inside it is the same word twice, so it stands down there — and only
     * there. The rule stays specific to the card, because a run's own dots have no card behind them and red
     * is the only way they have of saying a backup failed.
     */
    @Test
    void aReddenedCard_dropsTheDotThatWouldRepeatIt() throws IOException {
        String css = read("explorer-shell.css");

        assertThat(css).contains(".ex-card.is-down .ex-dot.is-down");
        assertThat(css).as("but the dot itself still paints, for the run dots that have no card behind them")
            .contains(".ex-dot.is-down { background: var(--red); }");
    }
    // --- a stream: the one intent question, and everything it takes off the page -----------------------
    //
    // Publishing a non-HTTP port is one extra answer, not a second flow: the operator says whether the port
    // serves a website or raw TCP, and the form puts away what the answer makes meaningless. The refusals are
    // the backend's — a stream cannot take a login and the domain says so — so the shell's job is only to stop
    // offering controls whose every use would be refused.

    @Test
    void thePublishDialog_asksTheOneThingVaierCannotWorkOutForItself() throws IOException {
        String js = read("explorer-shell.js");

        int from = js.indexOf("function publishForm(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).as("the intent question").contains("What this port serves");
        assertThat(body).as("and its two answers")
            .contains("A website — HTTP or HTTPS")
            .contains("Raw TCP");
        assertThat(body).as("HTTP is the default — the option listed first, with nothing selecting the other")
            .doesNotContain("kind.value = 'stream'");
    }

    @Test
    void choosingAStream_putsAwayTheLoginAndSaysWhatGuardsItInstead() throws IOException {
        String js = read("explorer-shell.js");

        int from = js.indexOf("function publishForm(");
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).as("the auth toggle and the HTTP-only fold both go away")
            .contains("authRow.hidden = isStream()")
            .contains("advanced.adv.hidden = isStream()");
        assertThat(body).as("and the operator is told what does guard it")
            .contains("only gate on a stream")
            .contains("CrowdSec");
    }

    @Test
    void aStreamPublish_sendsNoneOfTheHttpAnswers() throws IOException {
        // Sending a path or a redirect from controls the operator never saw would be a value nobody chose —
        // and the backend refuses both on a stream anyway.
        String js = read("explorer-shell.js");

        int from = js.indexOf("function publishForm(");
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).contains("stream: true, requiresAuth: false");
        assertThat(js).as("and the flag reaches the publish endpoint").contains("stream: body.stream");
    }

    @Test
    void aPublishedStream_showsWhatToDialRatherThanANameToOpen() throws IOException {
        String js = read("explorer-shell.js");

        int from = js.indexOf("function renderServices(");
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).contains("s.stream ? s.connectAddress : s.dnsAddress");
        assertThat(body).as("and it says which kind of route it is").contains("kindMark('stream')");
    }

    @Test
    void aStreamsPane_offersNoControlItsOwnBackendWouldRefuse() throws IOException {
        String js = read("explorer-shell.js");

        int from = js.indexOf("function renderService(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).as("no sign-in picker on a stream").contains("if (s.stream) {");
        assertThat(body).as("no launchpad tile settings either — a stream has no link")
            .contains("if (!s.stream) {");
        assertThat(body).as("the coordinates say where to dial").contains("['Connect at', coord(s.connectAddress)]");
    }

    @Test
    void hidingAControl_actuallyHidesIt() throws IOException {
        // `el.hidden = true` leans on the browser's own `[hidden] { display: none }`, which ANY display
        // declaration outranks — and `.ex-check-row` sets `display: flex`. So the stream answer put the
        // login toggle away in the JS while the operator went on seeing it, checked, over a request that
        // sends requiresAuth:false. The stylesheet has to say that hidden wins, once, for everything.
        String css = read("explorer-shell.css");

        assertThat(css).as("hidden beats every display rule in this sheet")
            .contains("[hidden] { display: none !important; }");

        // and it is declared before the rules it has to beat, so nothing depends on source order
        assertThat(css.indexOf("[hidden] { display: none !important; }"))
            .as("declared with the reset, not after .ex-check-row")
            .isLessThan(css.indexOf(".ex-check-row {"));
    }

    @Test
    void theKindMark_isAWordAndNotAnotherColour() throws IOException {
        // The shell reserves shape and colour for trouble. A stream is not trouble, so its mark is dim text.
        String css = read("explorer-shell.css");

        assertThat(css).contains(".ex-lkind");
        assertThat(css).as("no ground, no border, no alarm colour")
            .doesNotContain(".ex-lkind { margin-left: 7px; font-size: 12px; color: var(--red)");
    }

    // --- enrolment: a phone joins with a key born on the device, approved from wherever the operator is
    // signed in (#359 slices 1 and 1b) ---------------------------------------------------------------------
    //
    // The Vaier app mints the WireGuard keypair on the phone, asks Vaier to join, and shows a four-digit join
    // code while it waits on its own stream. The operator matches the code on the fleet page — in any browser
    // they are already signed in on — and one button admits the device. The config goes to the phone over
    // the stream it opened; the browser never sees it, and nothing is copied by hand.

    @Test
    void theJoinCode_travelsOnTheQueryString_becauseAHashWouldNotSurviveTheLogin() throws IOException {
        // "Approve here" on the operator's own phone opens this page with the code. oauth2-proxy bounces
        // the browser through Google and back; a query string makes that round trip, a fragment is never
        // sent to a server and would be gone by the time the page loaded.
        String js = read("explorer-shell.js");

        int from = js.indexOf("function pendingApproval(");
        assertThat(from).as("the shell reads a join code off the address").isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).as("the code is read off the query").contains("new URLSearchParams(location.search)");
        assertThat(body).as("never parsed out of the fragment — the login round trip drops it")
            .doesNotContain("URLSearchParams(location.hash");
        // replaceState, not location.replace: changing the query is a different URL, so location.replace
        // would reload the document — and the reloaded page would arrive with no code and nothing to do.
        assertThat(body).as("asked once, then stripped in place").contains("history.replaceState(");
        assertThat(body).doesNotContain("history.pushState(");
    }

    @Test
    void aJoinCode_opensTheAddMachineDialogStraightAtItsOwnScreen() throws IOException {
        // Not a dialog of its own. The operator is adding a machine, which is a flow the Explorer already
        // has — the phone just answered the questions the flow would otherwise ask.
        String js = read("explorer-shell.js");

        assertThat(js).contains("addMachineFork('enrol'");
        // The dialog's opening line is where the screen is actually chosen. Registering the screen in the
        // router is not enough: opened at 'enrol' but landing on the fork, the operator is asked "server or
        // personal device?" about a phone that already answered — which is exactly what happened first time.
        assertThat(js).as("the dialog opens ON the enrol screen, not on the fork")
            .contains("else if (initialScreen === 'enrol' && enrolment) screen('enrol');");
        assertThat(js).contains("else if (id === 'enrol') paintEnrol();");
        assertThat(js).contains("else if (id === 'enrolHandoff') paintEnrolHandoff();");
        // A code the server no longer holds is nothing to open: the phone gave up, or ten minutes passed.
        int init = js.indexOf("async function init(");
        String body = js.substring(init, js.indexOf("\n    }", init));
        assertThat(body).contains("liveEnrolmentRequests().find((r) => r.code === approval)");
        assertThat(body).as("and says so rather than opening an empty dialog").contains("No phone is waiting with code");
    }

    @Test
    void theFleet_showsWhoIsWaitingToJoin_pushedNeverPolled() throws IOException {
        // A phone asking to join is waiting on a person right now, so it comes before the machines already
        // in — and it arrives over a stream, the moment it asks, from whichever browser is open.
        String js = read("explorer-shell.js");

        assertThat(js).contains("fetch('/vpn/enrolments')");
        assertThat(js).contains("new EventSource('/vpn/enrolments/events')");
        int from = js.indexOf("function renderFleet(");
        String fleet = js.substring(from, js.indexOf("\n    }", from));
        assertThat(fleet).contains("section('Waiting to join')");
        assertThat(fleet.indexOf("Waiting to join")).as("above the machine grid").isLessThan(fleet.indexOf("ex-grid"));
        // The code is the chip: it is what the operator reads off the phone and matches here.
        int row = js.indexOf("function enrolmentRequestRow(");
        assertThat(row).isPositive();
        String rowBody = js.substring(row, js.indexOf("\n    }", row));
        assertThat(rowBody).contains("chip.textContent = r.code");
        assertThat(rowBody).contains("addMachineFork('enrol', null, r)");
    }

    @Test
    void approvingAPhone_namesOnlyItsCode_andCanRefuseIt() throws IOException {
        // The name and the key are already on the request the phone opened; the browser adds nothing
        // to them. A refusal goes to the same request, and tells the phone over its own stream.
        String js = read("explorer-shell.js");

        assertThat(js).contains("fetch('/vpn/enrolments/' + encodeURIComponent(enrolment.code) + '/approve'");
        assertThat(js).contains("fetch('/vpn/enrolments/' + encodeURIComponent(enrolment.code), { method: 'DELETE' })");
        assertThat(js).as("the slice-1 admin enrol call is retired").doesNotContain("fetch('/vpn/peers/enrol'");
        int ask = js.indexOf("function paintEnrol(");
        String askBody = js.substring(ask, js.indexOf("\n        }", ask));
        assertThat(askBody).as("the code is the one thing set large").contains("ex-joincode");
        assertThat(askBody).contains("'Refuse'").contains("'Add'");
    }

    @Test
    void theEnrolmentScreens_neverShowTheConfigOrAQr() throws IOException {
        // The point of an enrolment is that nothing is copied by hand. Painting the config text — or a QR
        // of it — would put a tunnel's credentials on a screen for no reason and invite a photograph.
        String js = read("explorer-shell.js");

        int from = js.indexOf("function paintEnrolHandoff(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n        }", from));
        assertThat(body).doesNotContain("ex-config").doesNotContain("ex-qr")
            .doesNotContain("downloadText(").doesNotContain("qrCodePngBase64");

        int ask = js.indexOf("function paintEnrol(");
        String askBody = js.substring(ask, js.indexOf("\n        }", ask));
        assertThat(askBody).doesNotContain("ex-config").doesNotContain("ex-qr");
    }

    @Test
    void theConfig_goesToThePhoneOverItsOwnStream_neverBackThroughTheBrowser() throws IOException {
        // Slice 1 handed the config back over a vaier:// deep link from the phone's own browser. That tied
        // the approval to the phone being the operator's. Now the phone waits on a stream keyed by a ticket
        // only it holds, and the approving browser — any browser — carries nothing back.
        String js = read("explorer-shell.js");

        assertThat(js).doesNotContain("vaier://");
        assertThat(js).doesNotContain("function enrolDeepLink(");
        String handoff = js.substring(js.indexOf("function paintEnrolHandoff("));
        handoff = handoff.substring(0, handoff.indexOf("\n        }"));
        assertThat(handoff).doesNotContain("location.href").doesNotContain("configFile");
    }

    @Test
    void anEnrolledDevice_waitsForItsFirstHandshakeLikeEveryOtherPeer() throws IOException {
        // One product, one way of saying "it is not on yet". Same live dot, same sentence shape.
        String js = read("explorer-shell.js");
        String handoff = js.substring(js.indexOf("function paintEnrolHandoff("));
        handoff = handoff.substring(0, handoff.indexOf("\n        }"));

        assertThat(handoff).contains("ex-waiting");
        assertThat(handoff).contains("ex-scanmeta-dot is-live");
        assertThat(handoff).contains("first handshake");
    }

    @Test
    void aPhoneIsToldToInstallTheVaierAppBeforeItIsHandedAQrCode() throws IOException {
        // #359: a phone that runs the Vaier app mints its own key and enrols over the operator's session —
        // strictly better than photographing a config off a screen. So the app comes first and the QR stays
        // underneath as the fallback for the WireGuard app.
        //
        // The instruction has to be actionable on the device that will run it. A link would open on the
        // operator's desktop, which is the wrong machine, so the sentence names the address to open on the
        // phone and the button to tap once the launchpad is up.
        String js = read("explorer-shell.js");

        int from = js.indexOf("function peerHandoffMobile(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n        }", from));

        assertThat(body).as("the address to open, on the phone itself").contains("location.host");
        assertThat(body).as("and the button to tap there").contains("tap Install");
        assertThat(body).as("no link — it would open on the wrong machine").doesNotContain("launchpad.html");
        assertThat(body).as("and what the operator does next").contains("enrol");
        assertThat(body).as("above the QR, not below it")
            .satisfies(b -> assertThat(b.indexOf("tap Install")).isLessThan(b.indexOf("Scan it into WireGuard")));
        assertThat(body).as("the QR is still there for the WireGuard app").contains("qr: true");
    }

    @Test
    void aMachineThatMintedItsOwnKey_isOfferedNeitherReissueNorRegenerate() throws IOException {
        // Both are meaningless for an enrolled device: a reissue re-renders a config nobody can hand to
        // the phone, and a regeneration deletes the peer and mints a server-side keypair — quietly turning
        // a device that enrolled through the app back into a QR-code peer. The server refuses the reissue;
        // the pane must not offer either in the first place.
        String js = read("explorer-shell.js");

        int from = js.indexOf("function renderMachine(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));

        assertThat(body).as("the fact comes from the peer record, not guessed at from the artefact list")
            .contains("deviceHeldKey");
        assertThat(body).as("a device-held key is never reissuable")
            .containsPattern("canReissue = [^;]*!deviceHeld");
        assertThat(body).as("and the fold says why, and what to do instead")
            .contains("enrol it again from the app");
    }

    // --- Ask: the fleet, answered in sentences (#360 slice 1) --------------------------------------------

    @Test
    void ask_isOfferedOnlyWhileAnAnthropicApiKeyIsStored() throws IOException {
        // Without a key there is nothing to ask, and an entry that opens onto "go to Settings first" is a
        // door painted on a wall. The menu and the palette both ask the same one question.
        String js = read("explorer-shell.js");

        int from = js.indexOf("function offered(g)");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("g.name !== 'ask' || S.askAvailable");
        assertThat(js).contains("if (!offered(g)) return;");
        assertThat(js).contains("GLOBALS.filter(offered)");
        assertThat(js).contains("fetch('/ask/availability'");
        // The menu is drawn on the first frame, before that read has answered — so it is drawn once more
        // after it has. The live bug: a stored key and no Ask in the menu until the key was saved again.
        int init = js.indexOf("async function init(");
        String initBody = js.substring(init, js.indexOf("\n    }", init));
        int known = initBody.indexOf("loadAskAvailability()]);");
        assertThat(known).isPositive();
        assertThat(initBody.indexOf("renderVMenu();", known)).as("the menu is redrawn once Ask's availability is known").isPositive();
    }

    @Test
    void ask_streamsItsAnswerOffOnePost_andNeverPolls() throws IOException {
        // One POST carries the question and the visit's history; the answer is read off that response as it
        // streams. No EventSource for Ask, no timer, and the follow-up knows what "and" means.
        String js = read("explorer-shell.js");

        int from = js.indexOf("async function askVaier(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("fetch('/ask', {").contains("method: 'POST'");
        // Slice 3: the conversation is Vaier's to remember; the browser sends the question and nothing else.
        assertThat(body).doesNotContain("history");
        assertThat(body).contains("readAnswerStream(res.body");
        assertThat(js).doesNotContain("EventSource('/ask");
        int reader = js.indexOf("function readAnswerStream(");
        assertThat(reader).isPositive();
        String readerBody = js.substring(reader, js.indexOf("\n    }", reader));
        // A chunk that begins with a space must keep it: the server writes nothing after `data:`, and the
        // first live answer read "Theseareshowing" because one leading space per chunk was stripped.
        assertThat(readerBody).contains("data.push(line.slice(5));").doesNotContain("replace(/^ /");
    }

    @Test
    void theAnthropicApiKey_isWrittenOnce_andNeverReadBackToTheBrowser() throws IOException {
        // The settings pane only ever learns whether a key exists. The field starts empty, is a password
        // field, and the shell never names the raw key — only the fact of it.
        String js = read("explorer-shell.js");

        assertThat(js).contains("'/settings/anthropic-api-key'");
        assertThat(js).contains("c.hasAnthropicApiKey");
        assertThat(js).doesNotContain("c.anthropicApiKey");
        int from = js.indexOf("const ask = sectionForm('Ask');");
        assertThat(from).isPositive();
        String body = js.substring(from, from + 1200);
        assertThat(body).contains("'password')");
        assertThat(body).contains("input(''");
    }

    @Test
    void aHalfTypedQuestion_survivesTheRepaintsTheShellMakesOnItsOwn() throws IOException {
        // The pane is rebuilt on every render, and the shell renders whenever a stream event lands — a disk
        // reading, a container flip. The live bug: the operator typed into Ask and the words vanished mid-
        // sentence, because a fresh, empty textarea replaced the one they were writing in. So the draft is
        // state, put back into the box on every paint, along with the caret — and the box takes focus only
        // when it already had it or nothing else does, so a repaint never pulls the cursor out of a dialog.
        String js = read("explorer-shell.js");

        assertThat(js).contains("ask: { turns: [], summary: null, loaded: false, busy: false, error: null, draft: '' }");
        int from = js.indexOf("function renderAsk(");
        assertThat(from).isPositive();
        String body = js.substring(from, js.indexOf("\n    }", from));
        assertThat(body).contains("box.value = S.ask.draft;");
        assertThat(body).contains("box.oninput = () => { S.ask.draft = box.value; };");
        assertThat(body).as("the caret goes back where it was").contains("box.setSelectionRange(");
        assertThat(body).as("focus is not taken from wherever the operator was").doesNotContain("if (!S.ask.busy) box.focus();");
        assertThat(body).contains("S.ask.draft = '';");
    }

    @Test
    void aProposedAction_isACardInTheThread_thatRunsOnlyOnTheClick() throws IOException {
        // #360 slice 2. The answer stream can carry a `confirm` event: one proposed action, as a card. The
        // card is what the operator reads and clicks; the button says what will happen, and the click is
        // the only thing that runs it — the model never does. A declined card says so and runs nothing.
        String js = read("explorer-shell.js");

        int ask = js.indexOf("async function askVaier(");
        String askBody = js.substring(ask, js.indexOf("\n    }", ask));
        assertThat(askBody).contains("name === 'confirm'");
        // The card goes before the answer being written, so the answer stays the last Vaier turn and the
        // streaming painter keeps writing into the right element.
        assertThat(askBody).contains("S.ask.turns.splice(S.ask.turns.length - 1, 0,");

        int card = js.indexOf("function askCard(");
        assertThat(card).isPositive();
        String cardBody = js.substring(card, js.indexOf("\n    }", card));
        assertThat(cardBody).as("the button says what will happen").contains("yes.textContent = t.sentence");
        assertThat(cardBody).contains("'Not now'");
        assertThat(cardBody).as("a card is never the answer the stream paints into").doesNotContain("ex-ask-text");

        int confirm = js.indexOf("async function confirmAction(");
        assertThat(confirm).isPositive();
        String confirmBody = js.substring(confirm, js.indexOf("\n    }", confirm));
        assertThat(confirmBody).contains("fetch(`/ask/actions/").contains("method: 'POST'");

        // What became of a card is Vaier's to remember (slice 3), so the browser narrates nothing.
        assertThat(askBody).doesNotContain("cardAsText");
        String css = read("explorer-shell.css");
        assertThat(css).contains(".ex-ask-card");
    }

    @Test
    void theConversation_isVaiersToRemember_andThePaneOnlyShowsIt() throws IOException {
        // #360 slice 3. Opening Ask reads the kept conversation; asking sends the question alone; a card
        // declined is told to the server, so it can never run and is remembered as declined; and "Start
        // over" is the one way to forget — a DELETE, never a page reload.
        String js = read("explorer-shell.js");

        assertThat(js).contains("fetch('/ask/conversation', { cache: 'no-store' })");
        int load = js.indexOf("async function loadConversation(");
        assertThat(load).isPositive();
        int card = js.indexOf("function askCard(");
        String cardBody = js.substring(card, js.indexOf("\n    }", card));
        assertThat(cardBody).contains("declineAction(t)");
        int decline = js.indexOf("async function declineAction(");
        assertThat(decline).isPositive();
        String declineBody = js.substring(decline, js.indexOf("\n    }", decline));
        assertThat(declineBody).contains("fetch(`/ask/actions/").contains("method: 'DELETE'");
        int forget = js.indexOf("async function startOver(");
        assertThat(forget).isPositive();
        String forgetBody = js.substring(forget, js.indexOf("\n    }", forget));
        assertThat(forgetBody).contains("fetch('/ask/conversation', { method: 'DELETE' })");
        assertThat(js).contains("'Start over'");
    }

    @Test
    void filesHandedOverByAsk_areADownloadCard_whoseButtonIsTheLink() throws IOException {
        // #360: the answer stream can carry a `bundle` event — files on one machine, offered as one zip. The
        // card names the zip and what it holds, and its button is the download. Nothing is fetched by the
        // shell: the browser follows the link and streams the zip to disk, as every Explorer download does.
        String js = read("explorer-shell.js");

        int ask = js.indexOf("async function askVaier(");
        String askBody = js.substring(ask, js.indexOf("\n    }", ask));
        assertThat(askBody).contains("name === 'bundle'");
        int card = js.indexOf("function askBundleCard(");
        assertThat(card).isPositive();
        String cardBody = js.substring(card, js.indexOf("\n    }", card));
        assertThat(cardBody).contains("window.location.href = t.url");
        assertThat(cardBody).contains("'Download ' + t.name");
        assertThat(cardBody).doesNotContain("ex-ask-text");
    }
}
