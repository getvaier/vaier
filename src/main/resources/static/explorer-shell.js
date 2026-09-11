// The Explorer shell — the fleet as one address space (#323, slice A).
//
// Vaier's domain is already a namespace: a file has a coordinate (machine, path, point in time), and so does a
// container, a published service, an archive. Vaier sits at the VPN hub and is the only machine with SSH to
// every other, so it is the only place one address space spanning the fleet can exist. This is that space:
// the address bar says where you are, ⌘K moves you sideways, and the pane is a renderer chosen by what the
// entry at that address *is*. Nothing else is navigation.
//
// Slice A builds the shell and moves the terminal dock into it. Machines, files, shells and backups are real
// entries now; only two Vaier-wide globals (Users, Concepts) are still framed whole (see renderGlobalBridge),
// until they too are ported.
(function () {
    'use strict';

    // --- the icon set ---------------------------------------------------------------------------------

    const ICON = {
        fleet:   '<path d="M8 1.5l5.5 3v7L8 14.5l-5.5-3v-7z"/><path d="M8 1.5v13M2.5 4.5L8 8l5.5-3.5"/>',
        machine: '<rect x="2" y="3" width="12" height="8" rx="1"/><path d="M5.5 13.5h5M8 11v2.5"/>',
        dir:     '<path d="M2 4.2c0-.7.5-1.2 1.2-1.2h2.3l1.3 1.6h6c.7 0 1.2.5 1.2 1.2v5.9c0 .7-.5 1.3-1.2 1.3H3.2c-.7 0-1.2-.6-1.2-1.3z"/>',
        file:    '<path d="M4 2h5l3 3v9H4z"/><path d="M9 2v3h3"/>',
        shell:   '<rect x="2" y="3" width="12" height="10" rx="1"/><path d="M4.6 6.2l2 1.8-2 1.8M8.4 10h3"/>',
        chev:    '<path d="M6 4l4 4-4 4"/>',
        chat:     '<path d="M2.5 3.5h11v7H7.5l-3 2.5v-2.5h-2z"/><path d="M6.6 6.4c0-.8.6-1.4 1.4-1.4s1.4.6 1.4 1.3c0 1-1.4 1.1-1.4 2.1M8 9.9v.1"/>',
        infra:   '<rect x="1" y="2" width="14" height="10" rx="1"/><path d="M5 15h6M8 12v3"/>',
        archive: '<path d="M2 5h12v8H2z"/><path d="M1.5 3h13v2h-13zM6.5 8h3"/>',
        users:   '<circle cx="6" cy="6" r="2.4"/><path d="M1.8 13.5c.3-2.4 2.2-3.8 4.2-3.8s3.9 1.4 4.2 3.8"/><path d="M11 4.2a2.2 2.2 0 0 1 0 4.3M12 9.9c1.4.5 2.3 1.8 2.5 3.6"/>',
        gear:    '<circle cx="8" cy="8" r="2.2"/><path d="M8 1.6v1.8M8 12.6v1.8M14.4 8h-1.8M3.4 8H1.6M12.5 3.5l-1.3 1.3M4.8 11.2l-1.3 1.3M12.5 12.5l-1.3-1.3M4.8 4.8L3.5 3.5"/>',
        book:    '<path d="M2.5 2.5h7a2 2 0 0 1 2 2v9"/><path d="M2.5 2.5v9a2 2 0 0 0 2 2h7"/><path d="M4.5 5.5H9M4.5 8H8"/>',
        box:     '<rect x="2.2" y="4.4" width="11.6" height="8.6" rx="1"/><path d="M2.2 7.3h11.6M5.6 4.4v2.9"/>',
        route:   '<circle cx="8" cy="8" r="6.2"/><path d="M1.9 8h12.2"/><path d="M8 1.8c1.7 1.9 2.6 3.9 2.6 6.2S9.7 12.3 8 14.2C6.3 12.3 5.4 10.3 5.4 8S6.3 3.7 8 1.8z"/>',
        disk:    '<rect x="2" y="3.4" width="12" height="9.2" rx="1"/><circle cx="8" cy="8" r="2.3"/><circle cx="8" cy="8" r=".45" fill="currentColor" stroke="none"/>',
        // Claude sign-in: an eight-ray burst, cardinals long and diagonals short. Deliberately not
        // Anthropic's mark — this is Vaier saying where a machine stands, not their logo.
        claude:  '<path d="M8 1.7v12.6M1.7 8h12.6"/><path d="M5.2 5.2l5.6 5.6M10.8 5.2l-5.6 5.6"/>',
        copy:    '<rect x="5.5" y="5.5" width="8" height="8.2" rx="1.2"/><path d="M3.4 10.5H3a1 1 0 0 1-1-1V3.2a1 1 0 0 1 1-1h6.3a1 1 0 0 1 1 1v.4"/>',
        download:'<path d="M8 2v7.5"/><path d="M4.7 6.5L8 9.8l3.3-3.3"/><path d="M2.5 13.5h11"/>',
        // Download's own glyph, turned over: the arrow leaves the baseline instead of arriving at it. The two
        // are the same operation in opposite directions and they should read that way at a glance.
        upload:  '<path d="M8 13.5V6"/><path d="M4.7 9.3L8 6l3.3 3.3"/><path d="M2.5 2.5h11"/>',
        clip:    '<rect x="3" y="2.6" width="10" height="11.4" rx="1.2"/><path d="M5.8 2.6V2a.9.9 0 0 1 .9-.9h2.6a.9.9 0 0 1 .9.9v.6z"/><path d="M5.6 7.2h4.8M5.6 9.7h4.8M5.6 12.2h2.8"/>',
        check:   '<path d="M2.8 8.4l3.1 3.4L13.2 4.6"/>',
        warn:    '<path d="M8 2.4l6.1 11.1H1.9z"/><path d="M8 6.4v3.3"/><circle cx="8" cy="11.4" r=".5" fill="currentColor" stroke="none"/>',
        cross:   '<path d="M4 4l8 8M12 4l-8 8"/>',
        refresh: '<path d="M13.4 8a5.4 5.4 0 1 1-1.6-3.8"/><path d="M13.6 2.4v3.1h-3.1"/>',
        clock:   '<circle cx="8" cy="8" r="5.75"/><path d="M8 4.6V8.2l2.5 1.5"/>',
        // A newer image exists. Deliberately not the bare `download` arrow and not `refresh`: both of those
        // are verbs elsewhere in this shell (a Download button, a Reissue button), and a mark that borrows a
        // verb's glyph reads as a control — which is the one thing this must never do, since Vaier cannot
        // pull an image. Enclosing the arrow makes it a badge rather than a button.
        arrowup: '<circle cx="8" cy="8" r="6"/><path d="M8 11.2V5.2"/><path d="M5.6 7.6L8 5.2l2.4 2.4"/>',
        trash:   '<path d="M3 4.5h10M6.5 4.5V3a.8.8 0 0 1 .8-.8h1.4a.8.8 0 0 1 .8.8v1.5"/><path d="M4.2 4.5l.6 8a1 1 0 0 0 1 .9h4.4a1 1 0 0 0 1-.9l.6-8"/><path d="M6.7 7v4M9.3 7v4"/>',
        shield:  '<path d="M8 1.7l5.1 1.9v3.9c0 3.2-2.1 5.4-5.1 6.5-3-1.1-5.1-3.3-5.1-6.5V3.6z"/><path d="M5.7 8l1.6 1.7L10.4 6"/>',
        // A fleet credential: bow, shaft and two teeth. Deliberately not `shield` (that is Security, which is
        // about who Vaier keeps out) and not `clip` — a key is the one shape that says "a secret that opens
        // something", which is all Vaier ever knows about one.
        key:     '<circle cx="5.4" cy="10.6" r="2.9"/><path d="M7.5 8.5l5.7-5.7"/><path d="M11.1 4.9l1.5 1.5M12.3 3.7l1.5 1.5"/>',
        shieldhalf: '<path d="M8 1.7l5.1 1.9v3.9c0 3.2-2.1 5.4-5.1 6.5-3-1.1-5.1-3.3-5.1-6.5V3.6z"/><path d="M3 8.3h10"/>',
        map:     '<path d="M8 1.7c-2.5 0-4.4 1.9-4.4 4.3 0 3.1 4.4 8.3 4.4 8.3s4.4-5.2 4.4-8.3c0-2.4-1.9-4.3-4.4-4.3z"/><circle cx="8" cy="6" r="1.6"/>',
        // A GPS crosshair — "find me" in every map app, so it is the one glyph that reads as "report where I
        // am" without borrowing the fleet's own map-pin icon (which already means something else: a place).
        locate:  '<circle cx="8" cy="8" r="3.1"/><circle cx="8" cy="8" r=".6" fill="currentColor" stroke="none"/><path d="M8 1.3v2.2M8 12.5v2.2M1.3 8h2.2M12.5 8h2.2"/>',
        // Capability glyphs — a machine's powers, worn as marks on its fleet card. Line-art in the same
        // weight as everything else here, cropped small: stacked containers over a whale's waterline for Docker,
        // a hub fanning out to two dots for a relay.
        docker:  '<rect x="2.9" y="6.8" width="2.4" height="2.4"/><rect x="5.8" y="6.8" width="2.4" height="2.4"/><rect x="8.7" y="6.8" width="2.4" height="2.4"/><rect x="5.8" y="4.1" width="2.4" height="2.4"/><path d="M1.8 10.4c1 1.7 2.9 2.4 5.4 2.4 3.3 0 5.4-1.5 6.2-2.9"/>',
        relay:   '<circle cx="3.8" cy="8" r="1.5"/><circle cx="12.2" cy="4" r="1.5"/><circle cx="12.2" cy="12" r="1.5"/><path d="M5.2 7.3l5.6-2.7M5.2 8.7l5.6 2.7"/>',
        // The fleet's backup server: a safe — a door, a dial and a handle. Deliberately not the `nas` device
        // shape (the NAS already wears that because it is a NAS, and any machine can be designated), not
        // `archive` (that is the backup entry every backed-up machine grows), and not `shield` (that says
        // "this is backed up", which is the opposite of being the place backups are kept). A safe says the
        // one thing true only here: the fleet's copies live inside this machine.
        backupserver: '<rect x="2.2" y="3" width="11.6" height="10" rx="1.2"/><circle cx="7.2" cy="8" r="2.5"/>'
            + '<circle cx="7.2" cy="8" r=".55" fill="currentColor" stroke="none"/><path d="M11.4 6.6v2.8"/>',
        // Device forms, matched to the machine icons the Infrastructure page used, so a machine
        // wears the same shape everywhere it is drawn — just smaller. Keyed by device category, lowercased.
        server:  '<rect x="2.5" y="2.5" width="11" height="4" rx=".8"/><rect x="2.5" y="9" width="11" height="4" rx=".8"/><circle cx="4.6" cy="4.5" r=".55" fill="currentColor" stroke="none"/><circle cx="4.6" cy="11" r=".55" fill="currentColor" stroke="none"/><line x1="9.5" y1="4.5" x2="11.8" y2="4.5"/><line x1="9.5" y1="11" x2="11.8" y2="11"/>',
        nas:     '<rect x="4" y="1.5" width="8" height="13" rx="1"/><line x1="6.2" y1="3.5" x2="6.2" y2="10.5"/><line x1="8" y1="3.5" x2="8" y2="10.5"/><line x1="9.8" y1="3.5" x2="9.8" y2="10.5"/><circle cx="8" cy="12.5" r=".55" fill="currentColor" stroke="none"/>',
        printer: '<polyline points="4.5 5.5 4.5 2.5 11.5 2.5 11.5 5.5"/><rect x="2.5" y="5.5" width="11" height="5" rx=".8"/><rect x="4.5" y="9.5" width="7" height="4"/><circle cx="11.5" cy="7.5" r=".5" fill="currentColor" stroke="none"/>',
        router:  '<rect x="2.5" y="8.5" width="11" height="5" rx=".8"/><circle cx="5" cy="11" r=".5" fill="currentColor" stroke="none"/><line x1="11" y1="11" x2="12" y2="11"/><line x1="5.5" y1="8.5" x2="4" y2="3.5"/><line x1="10.5" y1="8.5" x2="12" y2="3.5"/>',
        gateway: '<rect x="3" y="3" width="10" height="10" rx="1"/><polyline points="6 7.5 6 5 7.8 6.5"/><polyline points="10 8.5 10 11 8.2 9.5"/>',
        laptop:  '<rect x="2" y="3" width="12" height="8" rx="1"/><line x1="1" y1="13.5" x2="15" y2="13.5"/>',
        desktop: '<rect x="2" y="2" width="12" height="8.5" rx="1"/><line x1="6" y1="13.5" x2="10" y2="13.5"/><line x1="8" y1="10.5" x2="8" y2="13.5"/>',
        phone:   '<rect x="4.5" y="1.5" width="7" height="13" rx="1.5"/><line x1="6.5" y1="12.5" x2="9.5" y2="12.5"/>',
        iot:     '<rect x="4.5" y="4.5" width="7" height="7" rx=".8"/><line x1="6.5" y1="4.5" x2="6.5" y2="2.5"/><line x1="9.5" y1="4.5" x2="9.5" y2="2.5"/><line x1="6.5" y1="13.5" x2="6.5" y2="11.5"/><line x1="9.5" y1="13.5" x2="9.5" y2="11.5"/><line x1="4.5" y1="6.5" x2="2.5" y2="6.5"/><line x1="4.5" y1="9.5" x2="2.5" y2="9.5"/><line x1="13.5" y1="6.5" x2="11.5" y2="6.5"/><line x1="13.5" y1="9.5" x2="11.5" y2="9.5"/>',
        camera:  '<rect x="2" y="4" width="12" height="9" rx="1.5"/><circle cx="8" cy="8.5" r="2.5"/><line x1="11" y1="6" x2="12" y2="6"/>',
        media:   '<rect x="2" y="2.5" width="12" height="8.5" rx="1"/><line x1="5" y1="13.5" x2="11" y2="13.5"/>',
        generic: '<rect x="2.5" y="3" width="11" height="8" rx="1"/><line x1="5.5" y1="13.5" x2="10.5" y2="13.5"/><line x1="8" y1="11" x2="8" y2="13.5"/>',
    };

    // Trusted constant markup — never interpolate anything but a key of ICON into this.
    function svg(name, cls) {
        // 1.5, not 1.3: on a 16px glyph a 1.3px stroke reads as a scratch rather than a drawing, and every
        // icon in the shell is drawn at 16px or under. Same shapes, enough weight to survive their size.
        return '<svg class="' + cls + '" viewBox="0 0 16 16" fill="none" stroke="currentColor" '
            + 'stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">' + ICON[name] + '</svg>';
    }

    // Vaier-wide entries that are NOT of the fleet — they belong to Vaier, not to any machine — so they sit at
    // the top level of the address space, outside `fleet`. Settings is native now; Users and Concepts still
    // bridge their pages (framed whole, via renderGlobalBridge) until they are ported. They are reached from
    // the topbar's Vaier menu, never from inside the fleet. Fleet-level bridges are all gone: Infrastructure and Backups are native entries now (#323).
    // Each carries the group it belongs to, because five flat siblings said nothing about what any of them
    // was for — an operator asking "who can reach my things?" needs Users and Security, which sat apart with
    // three unrelated entries between them, while Credentials (a secret placed on every machine) read as a
    // Vaier setting rather than as the fleet-wide act it is. Three jobs, named:
    //   the fleet   — what Vaier does TO the machines
    //   who gets in — who may reach Vaier and what it publishes
    //   Vaier       — the thing itself, and the words it uses
    const GLOBALS = [
        { name: 'credentials', label: 'Credentials', icon: 'key', native: true, group: 'Your fleet' },
        { name: 'users',    label: 'Users',    icon: 'users',  page: 'users.html', group: 'Who gets in' },
        { name: 'security', label: 'Security', icon: 'shield', native: true,      group: 'Who gets in' },
        { name: 'chat',     label: 'Chat',     icon: 'chat',   native: true,      group: 'Vaier',
          desc: 'Ask Marvin about your fleet' },
        { name: 'settings', label: 'Settings', icon: 'gear',   native: true,      group: 'Vaier' },
        { name: 'concepts', label: 'Concepts', icon: 'book',   page: 'concepts.html', group: 'Vaier' },
    ];

    const MACHINE_TYPE = {
        MOBILE_CLIENT:  'Mobile client',
        WINDOWS_CLIENT: 'Windows client',
        UBUNTU_SERVER:  'Ubuntu server',
        WINDOWS_SERVER: 'Windows server',
        LAN_SERVER:     'LAN server',
    };

    // The device shapes an operator can pin a machine to — the same set the Infrastructure page offered, and the
    // same keys the entry icons are drawn from (see ICON). The empty value is "let Vaier choose from what it
    // sees" (clears the override); everything else pins the icon and the map marker.
    const DEVICE_CATEGORIES = [
        ['', 'Auto-detect'], ['PHONE', 'Phone'], ['LAPTOP', 'Laptop'], ['DESKTOP', 'Desktop'],
        ['SERVER', 'Server'], ['NAS', 'NAS'], ['PRINTER', 'Printer'], ['ROUTER', 'Router'],
        ['GATEWAY', 'Gateway'], ['IOT', 'IoT device'], ['CAMERA', 'Camera'], ['MEDIA', 'TV / Media'],
        ['GENERIC', 'Generic'],
    ];

    // The Vaier server is a machine in the fleet like any other (#311) — but it is *this* machine, the one
    // serving the page. Mirrors LanAnchor.VAIER_SERVER_NAME.
    const VAIER_SERVER = 'Vaier server';
    // Which machine Vaier is running on, as an identity. /machines marks it, because comparing a display
    // name to the literal "Vaier server" is a name doing an identity's job — it stops recognising the
    // machine the moment someone renames it, and mistakes any other machine that takes the name for it.
    const vaierServerId = () => (S.machines.find((m) => m.vaierServer) || {}).id || null;
    const isVaierServerMachine = (machineId) => machineId != null && machineId === vaierServerId();

    // --- state ----------------------------------------------------------------------------------------

    const S = {
        askAvailable: false,     // whether an Anthropic API key is stored — Chat exists only then
        chat: { turns: [], summary: null, loaded: false, busy: false, error: null, draft: '', memory: [], errands: [], spend: null },   // the kept conversation: { role, text }, the question being typed, and what Vaier remembers
        enrolmentRequests: [],   // phones waiting on a join code: { code, name, publicKey, expiresAt }
        path: ['fleet'],                 // the selected entry, as its path
        machines: [],                    // GET /machines
        peers: new Map(),                // machine id -> its live WireGuard peer (tunnel address, liveness)
        peersById: new Map(),            // WireGuard peer id -> the same peer (the SSE keys stats that way)
        lan: new Map(),                  // machine identity -> its LAN server (the domain's MachineStatus)
        serverLocation: null,            // GET /vpn/peers/server-location — the Vaier server's geo (Frankfurt), for the map
        lanScan: null,                   // GET /lan-scan — the discovered-machines snapshot { status, machines, lastScanCompleted }
        lanScanLans: null,               // GET /lan-scan/lans — the LANs an operator can pick to scan [{ anchor, name, cidr }]
        dirs: new Map(),                 // dirKey -> one directory's read: its state, its children, its reader
        services: [],                    // GET /published-services/discover — the whole fleet's routes
        publishable: [],                 // GET /published-services/publishable — container ports that could be published (and which are ignored)
        access: {},                      // GET /access/services — dnsAddress -> the groups allowed through
        containers: new Map(),           // machine identity -> its containers, as Vaier last scraped them
        containersRead: false,           // whether the fleet-wide Docker scrape has landed at least once
        disks: new Map(),                // machine identity -> its filesystems: state, the list, the failure's words
        diskStandings: new Map(),        // machine identity -> how its disks stand, as the backend's 5-minute sweep
                                         //   last read them. A machine that is absent has NOT been read — never
                                         //   a machine whose disks are fine (GET /machines/disk-standings)
        claudeStandings: new Map(),      // machine identity -> where it stands on Claude sign-in, as that same
                                         //   sweep last read it. A machine that is absent has NOT been asked —
                                         //   never one that is signed out (GET /machines/claude-standings)
        roots: new Map(),                // (machine identity, at) -> where a file tree begins (its SFTP root, #326)
        at: null,                        // the archive being browsed, or null for the present — the live filesystem
        archives: new Map(),             // machine identity -> { state, list, error }: its archives, the rail's stops
        nudges: new Map(),               // machine identity -> { state, list }: its nudges (GET /machines/{machineId}/nudges), read once per machine
        clipboard: [],                   // held file coordinates {machine, path, at, name, directory, size} — the Clipboard
        transfers: new Map(),            // id -> a live/settled Transfer, streamed in over the transfers SSE topic
        uploads: new Map(),              // id -> a file on its way up from this browser {name, machine, path, sent,
                                         //   total, state, error}. Unlike a Transfer, no server pushes this: the
                                         //   browser is the one sending, so its own send events are the progress
        sel: [],                         // the fleet-wide selection: {machine, path, at, name, directory, size, backedUp}
                                         //   coordinates ticked anywhere, kept as you navigate so you can gather
                                         //   from many folders and machines before acting on them all at once
        backupServer: null,              // GET /backup-servers — the fleet's one backup server, or null when none is designated
        backupRepos: [],                 // GET /backup-repositories — the repositories that live on it
        repoArchives: new Map(),         // repo name -> { state, list, error }: the archives in a repository, read when looked at
        backupJobs: [],                  // GET /backup-jobs — the jobs, each backing one machine up to a repository
        jobRuns: new Map(),              // machine identity -> { state, run }: its last run, read on view and on the run-settled push
        preparing: new Set(),            // machine identities Vaier is readying to back up (first back-up), cleared on prepare-client-settled
        rootAfterGrant: new Set(),       // machine identities whose "back up as root" is waiting on the sudoers grant to land;
                                         //   the prepare-client-settled push asks once more, then drops them (never a loop)
        readying: new Map(),             // machine identity -> the one `sudo bash …` line Vaier staged where it could not gain root itself
        provisionWatch: null,            // { serverName, bodyEl } while a provision dialog awaits its provision-settled push
        settings: { state: 'idle', config: null, version: '' },   // the native Settings entry, read on view
        threats: [],                     // GET /security/decisions — who CrowdSec is keeping out right now
        threatsRead: false,              // whether that read has landed once (an empty list is an answer, [] is not)
        threatsError: '',                // why that read failed, when it did — never shown as "nothing blocked"
        accessSources: [],               // GET /security/access-sources — where allowed accesses to a gated
                                          //   service came from, one element per city plus an unplaceable bucket
        accessSourcesError: '',          // why that read failed, when it did — never shown as "nobody accessed anything"
        trusted: [],                     // GET /security/trusted-addresses — what the operator trusted by hand, and
                                         //   deliberately only that: the structural networks are never in this list,
                                         //   because this is the list the untrust verb hangs off (#348)
        trustedRead: false,              // whether that read has landed once
        trustedError: '',                // why it failed, when it did — never shown as "nothing trusted"
        // GET /fleet-credentials — each with where it stands on every machine. Read when the entry is opened
        // and re-read after every action: the standings are the distributor's own in-memory observation, so
        // they start empty on a fresh boot and an empty `machines` is "not checked yet", never "nowhere".
        credentials: { state: 'idle', list: [], error: '' },
        palSel: 0,
        myDeviceMachineId: null,         // GET /vpn/peers/my-device — the ONE machine THIS browser's cookie
                                          //   claims, server-decided; never "some browser claims this machine"
    };

    // A file large enough to be worth a second thought before it crosses the fleet over WireGuard. The
    // Transfer streams flat-memory whatever the size, so this is a courtesy, not a limit. (A settings field
    // to tune it is a later slice; the default is deliberate, not a placeholder.)
    const TRANSFER_WARN_BYTES = 1024 * 1024 * 1024;   // 1 GiB

    // Directory reads go through VaierListing — one reader per directory, created in readDir. A single shared
    // reader would make concurrent expands cancel one another.
    const $ = (id) => document.getElementById(id);
    const key = (path) => '/' + path.join('/');
    const el = (tag, cls) => { const n = document.createElement(tag); if (cls) n.className = cls; return n; };

    const app = document.querySelector('.ex-app');

    // --- what an entry IS, read off its own path -------------------------------------------------------
    //
    // The path is the whole model. A tree that also kept a parallel object graph would be two truths about
    // where you are, and they would drift the first time a machine was renamed under one of them.

    function kindOf(path) {
        if (path.length === 1) {
            if (path[0] === 'fleet') return 'fleet';
            const g = GLOBALS.find((x) => x.name === path[0]);
            // A native global's kind IS its name — it renders itself. This used to answer 'settings' for
            // every native entry, which was true only while Settings was the sole one; a second native
            // global (Security) would have rendered the Settings pane under its own name.
            if (g) return g.native ? g.name : 'gbridge';
            return 'fleet';
        }
        if (path.length === 2) {
            if (path[1] === 'map') return 'map';
            return 'machine';
        }
        if (path[2] === 'backup') return path.length === 3 ? 'backup' : 'repo';
        if (path[2] === 'disk') return 'disk';
        if (path[2] === 'containers') return path.length === 3 ? 'containers' : 'container';
        if (path[2] === 'services') return path.length === 3 ? 'services' : 'service';
        if (path[2] === 'files') return path.length === 3 ? 'files' : 'dir';
        return 'fleet';
    }

    // A tree path names its machine by IDENTITY. Every crossing back to a name goes through nameOf, and it
    // is a crossing to something a person reads — never to something Vaier finds a machine by.
    const machineOf = (path) => S.machines.find((m) => m.id === path[1]) || null;

    const machineById = (machineId) => S.machines.find((m) => m.id === machineId) || null;

    /**
     * What to call a machine. Falls back to the identity itself, which is ugly on purpose: a machine that has
     * left the fleet mid-render should look wrong rather than borrow a name that is not its own.
     */
    function nameOf(machineId) {
        const m = machineById(machineId);
        return m ? m.name : machineId;
    }

    // Machines are ordered the way the Infrastructure page orders them, so the two never disagree: the Vaier
    // server first, then the servers, then the clients, each group alphabetical. Server-ness is the machine's
    // type (the domain's isServerType — Ubuntu/Windows/LAN server), the same split the fleet grid draws.
    const SERVER_TYPES = new Set(['UBUNTU_SERVER', 'WINDOWS_SERVER', 'LAN_SERVER']);
    const machineRank = (m) => (m.vaierServer ? 0 : (SERVER_TYPES.has(m.type) ? 1 : 2));
    const sortedMachines = () => S.machines.slice()
        .sort((a, b) => machineRank(a) - machineRank(b) || a.name.localeCompare(b.name));

    // A tree path under `files` and a path on the machine are the same path, written twice — but a machine's
    // tree does not begin at "/". It begins at its SFTP root (#326): the NAS jails its SFTP subsystem into
    // /volume1, so its `files` entry IS /volume1, and everything below hangs off that. Until the machine has
    // told us where that is, the path is null — which means "wherever this machine's tree begins", the one
    // question only the machine can answer.
    //
    // The past has no such question. An archive captured absolute machine paths, and Vaier mounts it rooted at
    // "/" (the backend answers a past listing with root "/", always), so browsing a machine's history always
    // begins at "/". That is why the past base is known without asking: it lets a whole path chain resolve in
    // one pass when you scrub, rather than each depth waiting on the one above it to report a root.
    const rootKey = (machine, at) => machine + DIR_SEP + (at || '');
    function remotePath(path) {
        const root = S.roots.get(rootKey(path[1], S.at));
        const base = root !== undefined ? root : (S.at ? '/' : null);
        if (base == null) return null;
        const segs = path.slice(3);
        if (!segs.length) return base;
        return (base === '/' ? '' : base) + '/' + segs.join('/');
    }

    // --- which machine a published service lives on ----------------------------------------------------
    //
    // A published service is one thing with three homes: a container on a machine, a Traefik route, and the
    // name it answers on. The shell files it under the machine, and it decides which machine by the rule the Infrastructure page
    // already uses — a LAN service by its LAN server (falling back to the relay peer when no registered LAN
    // server matches its address), the hub's own routes on the Vaier server, everything else by its host. A
    // second rule here would put the same service under two different machines in two different pages.
    // The feed says which machine a route's backend runs on, by identity — the domain decides it
    // (ReverseProxyRoute.hostMachineId) so the Explorer and every other surface can never disagree about
    // whose service a service is. It used to be worked out here from display names, which put a service
    // under the wrong machine's card the moment two machines agreed on a name.
    const machineOfService = (s) => s.machineId || null;

    // A route is addressed by its DNS name, and a path-prefixed route shares that name with its siblings —
    // so the entry is named by both, exactly as the unpublish call is.
    const serviceName = (s) => (s.shortName || s.name) + (s.pathPrefix || '');

    // All three feeds carry the machine's identity now, so these are plain matches — no crossing to a
    // display name, and nothing that two machines sharing a name could confuse.
    const servicesOn = (machineId) => S.services.filter((s) => machineOfService(s) === machineId);
    const containersOn = (machineId) => S.containers.get(machineId) || [];
    const candidateMachine = (c) => c.machineId || null;
    const candidatesOn = (machineId) => S.publishable.filter((c) => candidateMachine(c) === machineId);
    // The repositories that live on a backup server, filtered by the server they name. One server, so this is
    // every repository Vaier knows — but the filter keeps it honest if a stale repo names a server that is gone.
    const reposOn = (server) => (server ? S.backupRepos.filter((r) => r.serverName === server.name) : []);

    // What a store on the backup server is, to a person: the backups of a machine. "Repository" is a borg
    // noun and the operator never chose one — Vaier creates exactly one per machine, behind the Back up verb,
    // and names it after the machine's IDENTITY, so two machines called "NAS" cannot compete for one
    // directory. That name says nothing to a person, so the backend supplies the label (BackupStoreLabel):
    // the machine's name, and where it is when another machine shares that name.
    //
    // Deliberately not re-derived here. Two surfaces working out which store is which is how they come to
    // disagree, and being wrong about that means restoring the wrong machine's data.
    function repoLabel(repoName) {
        const repo = S.backupRepos.find((r) => r.name === repoName);
        return repo && repo.label ? repo.label : repoName;
    }
    // The same store named inside a sentence — a title, a confirm, a toast. When a machine claims it that is
    // "Roon server’s backups"; when none does, the label falls back to the borg name, and "where a3f2…’s
    // backups are kept" says less to a person than saying nothing does. So an unclaimed store is "these
    // backups": the operator is standing on it, and the pane above already says nobody backs up here.
    function repoPhrase(repoName) {
        const label = repoLabel(repoName);
        return label === repoName ? 'these backups' : label + '’s backups';
    }
    // The backup jobs that back a machine up. A job carries the identity of the machine it protects, so this
    // is how a machine learns it is backed up — and grows a `backup` entry even when it is not the server.
    const jobsOn = (machineId) => S.backupJobs.filter((j) => j.machineId === machineId);

    // Whether an entry is inside its machine's backup is NOT decided here — the containment rule (a source path
    // covers itself and its descendants) lives in the domain, and the backend stamps each listed entry with
    // `backedUp`. The shell only reads that flag; it never re-derives the rule in JS.

    function childrenOf(path) {
        const kind = kindOf(path);
        if (kind === 'fleet') {
            return [{ name: 'map', kind: 'map', label: 'Map' }]
                // The segment is the identity and the label is the name: the shell addresses a machine by
                // what cannot change and shows what a person recognises.
                .concat(sortedMachines().map((m) => ({ name: m.id, kind: 'machine', label: m.name })));
        }
        if (kind === 'machine') {
            const m = machineOf(path);
            if (!m) return [];
            // Honest and conditional: a machine grows only the entries Vaier can actually reach on it. Files
            // and disk ride on a held SSH credential, so they appear only once one is stored — not merely
            // because the SSH-access toggle is on, which would grow an entry that opens onto a red "no login"
            // wall until the operator refreshes. A machine that runs no Docker must not grow an empty
            // `containers` entry that opens onto nothing. /machines carries both facts — childrenOf asks them
            // rather than assuming every machine is the same machine.
            const kids = [];
            if (m.hasCredential) kids.push({ name: 'files', kind: 'files' });   // the shell is opened from the machine’s SSH-access section, not an entry
            if (m.runsDocker) kids.push({ name: 'containers', kind: 'containers' });
            // A machine grows a `services` entry when it publishes something, has a container port that could be
            // published, or is a server (so a non-container service on it — a printer's page, a LAN app — can
            // still be published by hand). Clients, which host nothing, stay quiet.
            if (servicesOn(m.id).length || candidatesOn(m.id).length
                || SERVER_TYPES.has(m.type) || m.vaierServer) {
                kids.push({ name: 'services', kind: 'services' });
            }
            if (m.hasCredential) kids.push({ name: 'disk', kind: 'disk' });
            // A machine grows a `backup` entry when it plays any part in fleet backup: it is the one backup
            // server (the backend refuses a second), or a job backs it up. The entry reads both ways; here we
            // only decide whether it exists.
            if ((S.backupServer && S.backupServer.machineId === m.id) || jobsOn(m.id).length) {
                kids.push({ name: 'backup', kind: 'backup' });
            }
            return kids;
        }
        if (kind === 'backup') {
            // Only the server's `backup` entry has children — its repositories. A client machine's `backup`
            // entry shows its job inline and has none, so it must not inherit the server's repos as its own.
            const isServer = S.backupServer && S.backupServer.machineId === path[1];
            // The path segment stays the store's own id — it is the address — while the row reads as the
            // machine whose backups are in it.
            return isServer
                ? reposOn(S.backupServer).map((r) => ({ name: r.name, kind: 'repo', label: repoLabel(r.name) }))
                : [];
        }
        if (kind === 'containers') {
            return containersOn(path[1]).map((c) => ({ name: c.containerName, kind: 'container' }));
        }
        if (kind === 'services') {
            return servicesOn(path[1]).map((s) => ({ name: serviceName(s), kind: 'service' }));
        }
        if (kind === 'files' || kind === 'dir') {
            // Whatever the cache already holds, and nothing more. This function is called on every repaint,
            // so it must never be able to start a read — otherwise a repaint on its own would walk the
            // fleet over SFTP, and the fleet is on the far side of a VPN.
            const entry = S.dirs.get(dirKey(path[1], remotePath(path), S.at));
            if (!entry || entry.state !== 'ready') return [];
            // Only directories become entries: a directory is a place you can stand, a file is contents the
            // Inspector lists. An entry per file would make ⌘K a list of the fleet's files, not of its places.
            return entry.entries.filter((e) => e.directory).map((e) => ({ name: e.name, kind: 'dir' }));
        }
        return [];   // a shell, a bridge — leaves
    }

    // --- reading a directory ----------------------------------------------------------------------------
    //
    // One directory per expand, read once, cached by machine *and* path — two machines both have a /home, and
    // they are not the same directory.

    // A machine name carries spaces ("Apalveien 5") and a path carries slashes, so the two are joined
    // on a separator that can appear in neither. Written as an escape — never as a raw control byte.
    const DIR_SEP = '\u0000';
    // A path is always absolute, so the empty string is free to mean "the root, wherever it turns out to be"
    // — the slot a read is parked in before the machine has said where its tree begins. The archive id joins
    // the key too: /home on a machine now and /home in last night's archive are the same path a day apart, and
    // they are not the same directory. The present is the empty archive.
    const dirKey = (machine, path, at) =>
        machine + DIR_SEP + (path == null ? '' : path) + DIR_SEP + (at || '');

    const dirStateOf = (path) => {
        const entry = S.dirs.get(dirKey(path[1], remotePath(path), S.at));
        return entry ? entry.state : 'unread';
    };


    async function readDir(machine, rpath, at) {
        const k = dirKey(machine, rpath, at);
        let entry = S.dirs.get(k);
        if (entry && entry.state === 'loading') return;   // already on its way
        if (!entry) {
            // Each directory owns its own VaierListing browser, and therefore its own monotonic ticket. That
            // is the whole race guard, and the reason it is per-directory rather than shared: a *re-read* of
            // this directory must supersede the read before it, but three directories expanded at once are
            // three independent reads that must all land. One shared ticket would be worse than none — the
            // earlier expands would be declared stale and spin forever, which is precisely the hang we are
            // guarding against.
            entry = { machine: machine, state: 'unread', entries: [], error: null,
                      reader: VaierListing.createBrowser() };
            S.dirs.set(k, entry);
        }
        entry.state = 'loading';
        entry.error = null;

        const result = await entry.reader.list(machine, rpath, at);

        // The slot was dropped under us while we were reading — the machine left the fleet, or the fleet
        // reshaped. A late answer must not resurrect a directory on a machine that is gone.
        if (S.dirs.get(k) !== entry) return;
        if (result.stale) return;   // a newer read of this same directory is already on its way

        if (result.error) {
            entry.state = 'error';
            entry.error = result.error;
            entry.errorCode = result.errorCode;
            entry.errorDetail = result.errorDetail;
        } else {
            entry.state = 'ready';
            entry.entries = result.entries;
            entry.path = result.path;

            // Where this tree begins, straight from the machine. Remembered per machine *and* time — a jail
            // ("/volume1") is a fact about the live filesystem, never about an archive rooted at "/".
            if (result.root != null) S.roots.set(rootKey(machine, at), result.root);

            // We may have asked "where does your tree begin?" (rpath null) and been answered "/volume1". The
            // slot belongs to the directory the server actually read, so re-key it — otherwise the very next
            // repaint would compute the real path, miss the cache, and read the same directory all over again.
            if (result.path !== rpath) {
                S.dirs.delete(k);
                S.dirs.set(dirKey(machine, result.path, at), entry);
            }
        }
        render();
    }

    // --- containers, services and disks -----------------------------------------------------------------
    //
    // Three reads, three different shapes of truth, and none of them polled:
    //
    //   containers — a fleet-wide Docker scrape. Read once at start (so ⌘K can find a container without the
    //                operator having gone looking for it first) and re-read only when the backend says a
    //                container changed state, on the stream it already publishes that on.
    //   services   — the published routes. Read at start because a machine cannot be honest without them: the
    //                `services` entry exists only on a machine that actually has some.
    //   disk       — one machine, read when its disk entry is looked at. A fleet-wide df on page load would
    //                wake every sleeping machine to answer a question nobody asked.

    // Vaier scrapes Docker per kind of machine, so this is three endpoints and one map. All three already
    // exist — this is the Infrastructure page's own data, re-filed under the machines it belongs to.
    //
    // The filing used to be the whole difficulty: a peer's containers arrived keyed by the peer's *id* (the
    // WireGuard directory, "apalveien5") while the shell spoke the display name ("Apalveien 5"), so either
    // choice needed a crossing, and one character of disagreement showed a machine with no containers while
    // Vaier could see them perfectly well. Every scrape carries the machine's identity now, so there is no
    // crossing left to get wrong — the cache is keyed by the same thing the address stands on.
    async function loadContainers() {
        const next = new Map();
        try {
            const [peers, server, lan] = await Promise.all([
                fetch('/docker-services/peers').then((r) => (r.ok ? r.json() : [])),
                fetch('/docker-services/vaier-server').then((r) => (r.ok ? r.json() : null)),
                fetch('/docker-services/lan-servers').then((r) => (r.ok ? r.json() : [])),
            ]);
            // Filed by machine identity. A peer with no stored config carries none — it is in no machine
            // registry, so there is no machine to file its containers under, and it simply does not join.
            (peers || []).forEach((p) => {
                if (p.machineId) next.set(p.machineId, p.containers || []);
            });
            (lan || []).forEach((l) => next.set(l.machineId, l.containers || []));
            if (server) next.set(vaierServerId(), server.containers || []);
            S.containers = next;
            S.containersRead = true;
        } catch (e) {
            // A fleet whose Docker hosts are asleep is a fleet, not a failure. The machines still stand; they
            // simply have no containers to show, and the Inspector says which case it is.
            S.containersRead = true;
        }
        render();
    }

    // The routes, and the groups allowed through them. The access rules are keyed by the route's DNS name —
    // the same key the Access page writes them under.
    async function loadServices() {
        try {
            const res = await fetch('/published-services/discover', { cache: 'no-store' });
            S.services = res.ok ? await res.json() : [];
        } catch (e) {
            S.services = [];
        }
        try {
            const res = await fetch('/access/services', { cache: 'no-store' });
            S.access = res.ok ? await res.json() : {};
        } catch (e) {
            S.access = {};
        }
        try {
            const res = await fetch('/published-services/publishable', { cache: 'no-store' });
            S.publishable = res.ok ? await res.json() : [];
        } catch (e) {
            S.publishable = [];
        }
    }

    // The whole fleet's disk pressure, in ONE request — the disk mark on every machine card.
    //
    // This is the exception the note above earns rather than breaks. A fleet-wide df on page load would wake
    // every sleeping machine; this wakes nothing, because it is not a df at all. RemoteDiskWatcher has swept
    // every reachable machine every five minutes for as long as the disk alerts have existed, judged every
    // filesystem, and then thrown the readings away — this is that reading, retained in memory and served
    // back. One request for the fleet, not one per machine, and the backend pushes `disk-standing-changed`
    // when a machine's standing actually moves.
    async function loadDiskStandings() {
        const next = new Map();
        try {
            const res = await fetch('/machines/disk-standings', { cache: 'no-store' });
            if (res.ok) for (const standing of await res.json()) next.set(standing.machineId, standing);
        } catch (e) {
            // A read that failed leaves the cards bare rather than showing yesterday's verdicts as though
            // they were current. Nothing about a disk is better said late than not at all.
        }
        S.diskStandings = next;
    }

    // Where the whole fleet stands on Claude sign-in, in ONE request — the Claude mark on every machine card.
    //
    // The same exception loadDiskStandings earns, for the same reason. This is NOT the per-machine
    // /machines/{id}/claude-sign-in read: that one opens a shell and runs the CLI's own `auth status`, so a
    // card-per-machine version of it would put a fleet-wide SSH sweep behind opening the Explorer. The
    // five-minute sweep is on every credentialed machine anyway, so it asks there and this reads memory.
    async function loadClaudeStandings() {
        const next = new Map();
        try {
            const res = await fetch('/machines/claude-standings', { cache: 'no-store' });
            if (res.ok) for (const standing of await res.json()) next.set(standing.machineId, standing);
        } catch (e) {
            // A read that failed leaves the cards bare. "Vaier could not ask" is never drawn as an answer.
        }
        S.claudeStandings = next;
    }

    // One machine's filesystems, read when they are looked at (#323 slice C, every filesystem since #325).
    // Vaier has computed this on a schedule since the disk alerts shipped and only ever emailed about it;
    // this is the same reading, looked at.
    async function loadDisk(machineId) {
        const held = S.disks.get(machineId);
        if (held && held.state === 'loading') return;
        S.disks.set(machineId, { state: 'loading', filesystems: null, error: null });
        try {
            const res = await fetch('/machines/' + encodeURIComponent(machineId) + '/disk');
            const body = await res.json();
            if (res.ok) {
                S.disks.set(machineId, { state: 'ready', filesystems: body, error: null });
            } else {
                // The server's own sentence, verbatim — "Vaier could not read the disk on X. The machine may
                // be asleep..." says everything a status code cannot. A disk Vaier failed to read is never
                // painted as a disk with room on it.
                S.disks.set(machineId, { state: 'error', filesystems: null,
                    error: body.message || 'Vaier could not read the disks on ' + nameOf(machineId) + '.',
                    errorCode: body.code, errorDetail: body.detail });
            }
        } catch (e) {
            S.disks.set(machineId, { state: 'error', filesystems: null,
                error: 'Vaier could not read the disks on ' + nameOf(machineId) + '.' });
        }
        render();
    }

    // One machine's archives — the stops on its time rail (#323 slice D). Read once when its files are first
    // looked at, never polled: a new nightly backup lands on a reload, not under the operator's cursor. A
    // machine with no backup job answers with an empty list and simply grows no rail — the file browser is
    // exactly what it was. A failure is the same quiet outcome: no rail, and the present still browses.
    async function loadArchives(machineId) {
        const held = S.archives.get(machineId);
        if (held && (held.state === 'loading' || held.state === 'ready')) return;   // once per machine
        S.archives.set(machineId, { state: 'loading', list: [], error: null });
        try {
            const res = await fetch('/machines/' + encodeURIComponent(machineId) + '/archives');
            S.archives.set(machineId, { state: 'ready', list: res.ok ? await res.json() : [], error: null });
        } catch (e) {
            S.archives.set(machineId, { state: 'ready', list: [], error: null });
        }
        render();
    }

    // A machine's progressive-adoption nudges — the next capabilities Vaier suggests adopting for it
    // (publish its services, back it up, make it the fleet's backup server). Read once per machine when its
    // pane opens, then repainted; never polled — a nudge changes when the operator acts, not under the cursor.
    // The domain decides which apply (GET /machines/{name}/nudges); the shell only renders them and routes the
    // action, so "should this fire?" is never re-derived in JS. A failure is a quiet empty list: no nudges,
    // the pane is exactly what it was.
    async function loadNudges(machineId) {
        const held = S.nudges.get(machineId);
        if (held && (held.state === 'loading' || held.state === 'ready')) return;   // once per machine
        S.nudges.set(machineId, { state: 'loading', list: [] });
        try {
            const res = await fetch('/machines/' + encodeURIComponent(machineId) + '/nudges', { cache: 'no-store' });
            S.nudges.set(machineId, { state: 'ready', list: res.ok ? await res.json() : [] });
        } catch (e) {
            S.nudges.set(machineId, { state: 'ready', list: [] });
        }
        render();
    }

    // The fleet's one backup server, and the repositories that live on it. Loaded before the first paint, like
    // the services are, because a machine cannot be honest without it: it grows a `backup` entry only if
    // it is the designated backup server, and an entry that grew a moment later would have been lying for that
    // moment. Never polled — a server is designated by an operator, not by a schedule, so a reload is the right
    // time to learn a new one. The fleet has at most one, so the list is read as its single head.
    async function loadBackup() {
        try {
            const res = await fetch('/backup-servers', { cache: 'no-store' });
            const list = res.ok ? await res.json() : [];
            S.backupServer = list.length ? list[0] : null;
        } catch (e) {
            S.backupServer = null;
        }
        try {
            const res = await fetch('/backup-repositories', { cache: 'no-store' });
            S.backupRepos = res.ok ? await res.json() : [];
        } catch (e) {
            S.backupRepos = [];
        }
        try {
            const res = await fetch('/backup-jobs', { cache: 'no-store' });
            S.backupJobs = res.ok ? await res.json() : [];
        } catch (e) {
            S.backupJobs = [];
        }
    }

    // A machine that left the fleet takes its cached directories with it. Machines that are still here keep
    // theirs — that is the point of the cache: collapsing and re-expanding must not re-hit SFTP.
    function pruneDirs() {
        const alive = new Set(S.machines.map((m) => m.name));
        Array.from(S.dirs.entries()).forEach(([k, entry]) => {
            if (!alive.has(entry.machine)) S.dirs.delete(k);
        });
    }

    const ICON_FOR = { fleet: 'fleet', machine: 'machine', files: 'dir', dir: 'dir', file: 'file',
                       containers: 'box', container: 'box', services: 'route',
                       service: 'route', disk: 'disk', backup: 'archive', repo: 'box', map: 'map' };

    // A machine wears its device's shape — server, NAS, printer — the same icon its Infrastructure card uses,
    // read off its device category. A category with no icon (or a machine not yet loaded) falls back to the
    // generic machine glyph, never to a blank.
    function machineIcon(machineId) {
        const m = machineById(machineId);
        const cat = m && m.deviceCategory ? String(m.deviceCategory).toLowerCase() : '';
        return ICON[cat] ? cat : 'machine';
    }

    // Every kind that names a top-level global: a native one's kind IS its own name (see kindOf), plus the
    // one kind the bridged ones share. Derived from GLOBALS rather than spelled out, because the hand-written
    // list this replaces read `settings || gbridge` and so had already stopped covering Security.
    const GLOBAL_KINDS = new Set(GLOBALS.map((g) => g.name).concat(['gbridge']));

    // {@code segment} is the path segment the row stands on — a machine's identity, a global's name.
    const iconFor = (kind, segment) => {
        if (GLOBAL_KINDS.has(kind)) return (GLOBALS.find((g) => g.name === segment) || {}).icon || 'file';
        if (kind === 'machine') return machineIcon(segment);
        return ICON_FOR[kind] || 'file';
    };

    // A glyph's colour says WHAT the entry is about — the same four the fleet cards' marks wear. Keyed off the
    // KIND and never off the glyph: ICON_FOR sends `repo`, `containers` and `container` to the one `box`, so a
    // rule written against the glyph name would paint every Docker container backup-aqua. Everything else keeps
    // the colour it inherits — amber and red stay spent on trouble alone, and green belongs to the dots.
    const TINT_FOR = { disk: 'is-disk', backup: 'is-backup', repo: 'is-backup',
                       containers: 'is-docker', container: 'is-docker' };

    // The one place an entry's glyph is built. Every surface that draws an entry — the machine pane's grid,
    // the palette, the listing rows — goes through here, so the colours cannot drift into three vocabularies
    // and a fourth surface gets them for free.
    const entryIco = (kind, segment) =>
        svg(iconFor(kind, segment), 'ex-ico' + (TINT_FOR[kind] ? ' ' + TINT_FOR[kind] : ''));

    // --- liveness --------------------------------------------------------------------------------------
    //
    // The browser never polls: the backend already watches WireGuard *and* probes the LAN, and pushes what it
    // sees on the vpn-peers stream. We listen, and repaint the dots in place.
    //
    // A machine's liveness has three possible sources, and the fleet is not one kind of machine:
    //
    //   a peer         — WireGuard knows whether the tunnel is up, right now
    //   a LAN server   — the backend probes it on a schedule and hands us MachineStatus, already decided
    //   the Vaier server — it is serving this page; if you can read this, it is up
    //
    // Knowing only the first (which is all the shell knew when this shipped) meant the NAS, the NUCs and the
    // Roon boxes — every machine the operator actually SSHes into — sat grey forever, and grey claimed "Vaier
    // has no idea". Vaier did have an idea. It just never asked itself.

    // The domain's four-state MachineStatus, mapped to a dot. The *combination* (reachability + Docker scrape
    // + runsDocker) is decided server-side in MachineStatus.forLanServer — the browser only picks a colour, and
    // the fleet map picks the same four. Note what is deliberately NOT green: UNKNOWN means no probe has run
    // yet, and painting that up would be Vaier claiming to know something it does not. Grey is the honest
    // answer to a question nobody has asked yet.
    const STATUS_DOT = {
        'OK':       'is-up',
        'DEGRADED': 'is-degraded',   // on the network, but its Docker scrape is failing — up, and not well
        'DOWN':     'is-down',
        'UNKNOWN':  'is-idle',
    };

    function livenessOf(machineId) {
        // We are standing inside the answer.
        if (isVaierServerMachine(machineId)) return 'is-up';

        const peer = S.peers.get(machineId);
        if (peer) {
            // A personal device (phone / Mac / Linux / Windows PC) is a different question from a server.
            // A server that is fine is not news and a server that is down is trouble; a phone is neither —
            // it is either here or away, and the operator wants to see which. So it gets presence: a quiet
            // mark either way, never the red a fallen server earns. The same SERVER_TYPES split the up/down
            // alert policy uses.
            const m = machineById(machineId);
            if (m && !SERVER_TYPES.has(m.type)) return peer.connected ? 'is-present' : 'is-away';
            return peer.connected ? 'is-up' : 'is-down';
        }

        const server = S.lan.get(machineId);
        if (server) return STATUS_DOT[server.status] || 'is-idle';

        return 'is-idle';   // a machine with no liveness source at all — honest, not a bug
    }

    // The dot and the card around it are two renderings of one answer, so one place gives both. Asking
    // livenessOf twice is how a card ends up red around a dot that has gone grey — and the stream's repaint
    // would only ever have fixed one of them.
    function paintLiveness(el) {
        const state = livenessOf(el.getAttribute('data-ex-dot'));
        el.className = 'ex-dot ' + state;
        // The answer is worn by the machine itself — its name, on the card or the pane title around the
        // dot — and the dot is only the anchor this repaint finds. Red for down, amber for degraded, dimmed
        // while a personal device is away, and the ordinary colour otherwise.
        const host = el.closest('.ex-card, .ex-pane-title');
        if (host) {
            host.classList.toggle('is-down', state === 'is-down');
            host.classList.toggle('is-degraded', state === 'is-degraded');
            host.classList.toggle('is-away', state === 'is-away');
        }
    }

    function paintDots() {
        document.querySelectorAll('[data-ex-dot]').forEach(paintLiveness);
    }

    function dot(machineId) {
        const el = document.createElement('span');
        el.className = 'ex-dot ' + livenessOf(machineId);
        el.setAttribute('data-ex-dot', machineId);
        return el;
    }

    // What a machine IS, in the order an operator scans for it: reached → runs → keeps. Relay (it routes a
    // LAN behind it, so other machines are reachable only through it) reads off its peer; Docker (it hosts
    // containers) reads off the machine; backup server (it holds the fleet's archives) reads off the one
    // designated server. The fleet card draws them as marks — a glyph and its word.
    function capabilitiesOf(machineId) {
        const m = machineById(machineId);
        const peer = S.peers.get(machineId);
        const caps = [];
        if (peer && peer.isRelay) {
            caps.push({ kind: 'relay', word: 'Reaches its LAN',
                        title: 'Machines on its network are reached through it' });
        }
        if (m && m.runsDocker) caps.push({ kind: 'docker', word: 'Docker', title: 'Runs Docker' });
        if (S.backupServer && S.backupServer.machineId === machineId) {
            caps.push({ kind: 'backupserver', word: 'Backup server',
                        title: 'The fleet’s archives are kept here' });
        }
        return caps;
    }

    // How a run's outcome is said to a person, on the fleet card's hover. The pane spells the same fact out
    // at length; here it has one line, so each word has to carry the consequence rather than the status name.
    const RUN_WORD = {
        SUCCESS:    'Backed up',
        WARNING:    'Backed up, with a complaint',
        INCOMPLETE: 'Files are missing from the last backup',
        FAILED:     'The last backup failed',
        RUNNING:    'Backing up now',
        UNKNOWN:    'The last backup’s outcome is unknown',
    };

    // The same outcomes at chip length — what fits beside a glyph on a card, where RUN_WORD's sentence
    // would wrap the card open. The sentence is still there, on the chip's title.
    const RUN_CHIP = {
        SUCCESS:    'Backed up',
        WARNING:    'Backup complained',
        INCOMPLETE: 'Files missing',
        FAILED:     'Backup failed',
        RUNNING:    'Backing up',
        UNKNOWN:    'Backup unknown',
    };

    /**
     * What a machine is telling you without being opened — its capabilities, how its last backup went, and
     * whether anything on it wants a newer image. This is the fleet pane's whole job: the machine in trouble
     * is visible from the one place that lists every machine, without opening any of them.
     *
     * Deliberately not a second liveness dot. On a card a bare coloured dot would sit next to the liveness
     * dot with nothing to tell them apart, and a green dot that might mean either is worse than no dot at
     * all. So the backup outcome is the archive glyph, tinted, and the words are on its title. Its colours
     * come from the same RUN_MARK map the job pane uses, so the two can never disagree about a run — and a
     * job that has never run gets the idle one: "not yet" is neither trouble nor success.
     */
    // How a machine's disks stand, tinted. The level is the server's own DiskStandingLevel — the browser is
    // never a second place deciding when a disk is in trouble, exactly as it never recomputes a filesystem's
    // aboveThreshold in the disk pane. There is deliberately no entry for "not read": a machine the sweep has
    // not reached has no standing at all, and a standing that does not exist draws nothing.
    // A clear disk wears the disk's own colour rather than the shared green; closing and breaching still
    // take the trouble colours outright, because a filling disk is the thing on this card worth interrupting
    // someone for. Named MARK, not DOT: nothing draws a disk dot, and the old name said otherwise.
    const DISK_MARK = { CLEAR: 'is-disk', CLOSING: 'is-degraded', BREACHING: 'is-down' };
    const DISK_WORD = { CLEAR: 'well under', CLOSING: 'closing on', BREACHING: 'over' };

    // A mark is a glyph and the word for what it means. The word used to live only in the `title`, which is
    // to say: only for someone with a pointer, who already suspected there was something to hover. The long
    // sentence stays on the title; the chip carries enough of it to be understood without one.
    function mark(tone, glyph, label, title) {
        const m = el('span', 'ex-mark ' + tone);
        m.innerHTML = svg(glyph, 'ex-cap-ico');
        const l = el('span', 'ex-mark-lbl');
        l.textContent = label;
        m.appendChild(l);
        if (title) m.title = title;
        return m;
    }

    // Where a machine stands on Claude, in the one vocabulary the shell window's sign-in also speaks
    // (claude-sign-in.js). A second map here is two vocabularies one rename away from disagreeing about
    // what a machine is doing — which is why the words live with the UI that acts on them, not here.
    const claudeWords = (state) => window.VaierClaude.words(state);

    function machineMarks(machineId) {
        const marks = el('span', 'ex-card-marks');

        // Capabilities lead: what a machine is comes before how it is doing. They stay quiet — the strip says
        // what a machine IS, and colouring it would put it in competition with the marks carrying a verdict.
        capabilitiesOf(machineId).forEach((c) => marks.appendChild(mark('is-' + c.kind, c.kind, c.word, c.title)));

        const job = jobsOn(machineId)[0];
        if (job) {
            marks.appendChild(mark(RUN_MARK[job.lastRunStatus] || 'is-idle', 'archive',
                RUN_CHIP[job.lastRunStatus] || 'No backup yet',
                RUN_WORD[job.lastRunStatus] || 'No backup has run yet'));
        }

        // Disk pressure, from the sweep the backend already runs — no machine is asked anything to draw this.
        //
        // Absence is not health. A machine the sweep has not reached (a cold start, up to five minutes; no
        // SSH access; no stored credential) has no standing, and gets NO mark rather than a green one — a
        // disk at 89% once sat silent for weeks here precisely because missing state read as fine. And like
        // the update mark, how full a disk is is a fact about now, so it stands down in the past.
        const standing = S.diskStandings.get(machineId);
        if (!S.at && standing) {
            // The number is shown only where it is worth an eye. A percentage on every card would be a row
            // of digits nobody reads; on the two that are filling it is the thing you were looking for.
            const d = mark(DISK_MARK[standing.level], 'disk',
                standing.level === 'CLEAR' ? 'Disk has room' : 'Disk ' + standing.usedPercent + '% full');
            d.title = standing.mountPoint + ' is ' + standing.usedPercent + '% full — '
                + DISK_WORD[standing.level] + ' its ' + standing.thresholdPercent + '% threshold'
                + (standing.breachingFilesystems > 1
                    ? ' (' + standing.breachingFilesystems + ' of its ' + standing.watchedFilesystems
                      + ' watched filesystems are over theirs)'
                    : '');
            marks.appendChild(d);
        }

        // Where this machine stands on Claude, from that same sweep — again, nothing is asked to draw it.
        //
        // Whether a reading is a verdict about a sign-in at all is the server's answer, not a state list
        // kept here — the same reason the disk mark takes DiskStandingLevel rather than recomputing it. So
        // a machine the sweep has not reached, one with no Claude on it, and one that did not answer all
        // draw nothing. Absence matters more here than it does for disks: the backend goes to real trouble
        // never to report a sign-in the CLI did not say, and a mark invented from silence would undo it.
        // The tone comes from claudeWords, the one map the shell window's sign-in reads too. And like the
        // disk mark, this is a fact about now, so it stands down in the past.
        const claude = S.claudeStandings.get(machineId);
        if (!S.at && claude && claude.saysWhereTheSignInStands) {
            const c = mark(claudeWords(claude.state).card || 'is-claude-out', 'claude',
                claudeWords(claude.state).chip || 'Claude');
            // A sign-in lives in one user's home, so the title names the user it is about — a standing that
            // named only the machine once reported a healthy account while the user doing that machine's
            // work was expired.
            c.title = 'Claude — ' + claudeWords(claude.state).label
                + (claude.accountEmail ? ' as ' + claude.accountEmail : '')
                + (claude.effectiveUsername ? ' (Vaier acts as ' + claude.effectiveUsername + ' here)' : '');
            marks.appendChild(c);
        }

        const u = updateCountMark(machineId);
        if (u) marks.appendChild(u);
        return marks;
    }

    // How many of a machine's containers want a newer image, as one pill — on the fleet card and again on the
    // machine's own containers card, which is the door to those containers and used to say nothing. One
    // helper so the two can never count differently. The registry verdict is about now, and an archive is
    // about then — the same reason updateMark and the liveness dots stand down in the past.
    function updateCountMark(machineId) {
        const stale = S.at ? 0
            : containersOn(machineId).filter((c) => c.updateAvailable === 'UPDATE_AVAILABLE').length;
        if (!stale) return null;
        const u = mark('is-update', 'arrowup', stale === 1 ? 'One update' : stale + ' updates');
        u.title = stale === 1
            ? 'One container here has a newer image available'
            : stale + ' containers here have a newer image available';
        return u;
    }

    // --- the address bar --------------------------------------------------------------------------------

    function renderCrumbs() {
        const bar = $('exCrumbs');
        bar.textContent = '';
        S.path.forEach((seg, i) => {
            if (i) {
                const sep = document.createElement('span');
                sep.className = 'ex-crumb-sep';
                sep.textContent = '/';
                bar.appendChild(sep);
            }
            // Segments are addresses; crumbs are for reading. A top-level global reads by its label
            // ("Settings"), not its lowercase address — and the machine segment is an identity, so it reads
            // by the machine's name. The bar says where you are, and nobody is anywhere called
            // "7a6d0e35-25d9-420c-b7bc-1815ce7e0dc1".
            let text = seg;
            if (i === 0 && seg !== 'fleet') {
                text = (GLOBALS.find((g) => g.name === seg) || {}).label || seg;
            } else if (i === 1 && S.path[0] === 'fleet' && seg !== 'map') {
                text = nameOf(seg);
            }
            if (i === S.path.length - 1) {
                const here = document.createElement('span');
                here.className = 'ex-crumb-here';
                here.textContent = text;
                bar.appendChild(here);
            } else {
                const crumb = document.createElement('button');
                crumb.className = 'ex-crumb';
                crumb.textContent = text;
                crumb.onclick = () => go(S.path.slice(0, i + 1));
                bar.appendChild(crumb);
            }
        });
        // A path too long for the bar scrolls inside it, and it is the tail that matters: the folder you are
        // standing in is the answer to "where am I", while the fleet root it hangs off is the part you can
        // already guess. Left at zero a phone showed "fleet / Vaier server / files / home / ubu…" and cut off
        // the only crumb that was news. The ancestors are still one swipe away.
        bar.scrollLeft = bar.scrollWidth;
    }

    // --- the Inspector ----------------------------------------------------------------------------------

    // What the head's subtitle says about a directory. Idle it is how much is here; with a live selection it
    // is the selection instead — the more urgent version of the same "how much" fact, and the only thing on
    // the pane that changes while you are choosing. The TITLE stays the machine name throughout: it is the
    // pane's identity and where you are standing, and it is the one label that must never move.
    //
    // A selection is fleet-wide, so it names how many machines are in play when it is more than one: "6
    // selected" must never be read as six files in this one folder, because the verbs beside it fan out to
    // every machine involved.
    function directorySubtitle(loaded) {
        if (S.sel.length) {
            const machines = new Set(S.sel.map((s) => s.machine));
            return S.sel.length + ' selected' + (machines.size > 1 ? ' · ' + machines.size + ' machines' : '');
        }
        const count = loaded && loaded.state === 'ready' ? loaded.entries.length : null;
        return count == null ? null : count + (count === 1 ? ' item' : ' items');
    }

    // The verbs that are always there: Refresh, and Upload in the present. Pinned LAST in the action group so
    // they stay anchored at the right edge whatever comes and goes to their left.
    function folderVerbs(machineId, path) {
        const group = el('div', 'ex-verbgroup');
        const refresh = el('button', 'ex-iconbtn');
        refresh.innerHTML = svg('refresh', 'ex-ico');
        refresh.title = 'Refresh';
        refresh.setAttribute('aria-label', 'Refresh this folder');
        refresh.onclick = () => refreshDir(machineId, path);
        group.appendChild(refresh);
        group.appendChild(renderUploadAction(machineId, path));   // present-only; nothing in the past
        return group;
    }

    function paneHead(title, titleIsId, sub) {
        const head = document.createElement('div');
        head.className = 'ex-pane-head';

        const h = document.createElement('div');
        h.className = 'ex-pane-title';
        const t = document.createElement('span');
        if (titleIsId) t.className = 'is-id';
        t.textContent = title;
        h.appendChild(t);
        head.appendChild(h);

        if (sub) {
            const s = document.createElement('div');
            s.className = 'ex-pane-sub';
            s.textContent = sub;
            head.appendChild(s);
        }
        return head;
    }

    function section(text) {
        const el = document.createElement('div');
        el.className = 'ex-sect';
        el.textContent = text;
        return el;
    }

    function note(text, isError) {
        const el = document.createElement('div');
        el.className = 'ex-note' + (isError ? ' is-error' : '');
        el.textContent = text;
        return el;
    }

    // Explanation, not state. A bordered note reads as a thing that happened or a thing to act on, so a pane
    // that wears one on every paragraph has nothing left to mark the paragraph that matters — which is how the
    // machine pane grew five identical boxes. note() is for a state (empty, error, warning); hint() is for
    // prose that only explains how a control works, and it stays out of the way.
    function hint(text) {
        const el = document.createElement('div');
        el.className = 'ex-hint';
        el.textContent = text;
        return el;
    }

    // disabledTitle: when set, the card is greyed and inert rather than removed — the entry still names what
    // is there, it just cannot be opened right now (e.g. Vaier's last check found no SSH server). Pass a
    // falsy value for a normal, live card.
    function card(ico, name, noteText, onClick, dotMachineId, disabledTitle) {
        const btn = document.createElement('button');
        btn.className = 'ex-card';

        const top = document.createElement('div');
        top.className = 'ex-card-top';
        // The badge wears the glyph's own identity colour and tints its ground from it, so the card says what
        // it is about before it is read. The tone is lifted off the glyph's class rather than passed in
        // separately — one fact, stated once, at the call site that already knows it.
        const tone = (ico.match(/is-(disk|backup|docker|claude)/) || ['', ''])[1];
        top.innerHTML = '<span class="ex-badge' + (tone ? ' is-' + tone : '') + '">' + ico + '</span>';
        const nm = document.createElement('span');
        nm.className = 'ex-card-name';
        nm.textContent = name;
        top.appendChild(nm);
        const liveDot = dotMachineId ? dot(dotMachineId) : null;
        if (liveDot) top.appendChild(liveDot);
        btn.appendChild(top);

        const n = document.createElement('div');
        n.className = 'ex-card-note';
        n.textContent = noteText;
        btn.appendChild(n);

        if (disabledTitle) {
            btn.disabled = true;
            btn.title = disabledTitle;
        } else {
            btn.onclick = onClick;
        }
        // Only now is the dot inside the card, so the painter can reach both from the one answer.
        if (liveDot) paintLiveness(liveDot);
        return btn;
    }

    // A detail value that is an ADDRESS rather than a sentence — a path, a host, an image, an id. Wrapping it
    // says so at the call site, which is the only place that knows. Everything else is prose and reads as
    // prose: "Desktop", "3 days ago", "Yes — port 2375" were all set in mono, and a page of ordinary English
    // in a terminal face is most of what made the machine pane read like a config file rather than an answer.
    const coord = (value) => ({ coord: value });

    function kv(rows) {
        const dl = document.createElement('dl');
        dl.className = 'ex-kv';
        rows.forEach(([term, value]) => {
            const isCoord = value != null && typeof value === 'object' && 'coord' in value;
            const text = isCoord ? value.coord : value;
            const dt = document.createElement('dt');
            dt.textContent = term;
            const dd = document.createElement('dd');
            if (isCoord) dd.className = 'is-coord';
            dd.textContent = text == null || text === '' ? '—' : String(text);
            dl.append(dt, dd);
        });
        return dl;
    }

    // Which view the pane is showing. The past is a different view of the same path — the amber light is a
    // different reading of the same directory, not the same page — so the time is part of the identity.
    function paneViewKey() {
        return key(S.path) + '@' + (S.at || '');
    }

    let _paneView = '';

    function renderPane() {
        const pane = $('exPane');
        // A repaint is not a navigation, and the pane is repainted constantly without being asked: the Claude
        // sign-in read lands an SSH round-trip after a machine page opens, the five-minute sweep moves a disk
        // standing, a container update settles, a stream reconnects. Blanking the pane drops it to the top,
        // which read as the page scrolling itself away from the operator mid-sentence. So where they were
        // standing in THIS view is put back; only a real move to another view starts at its top.
        const view = paneViewKey();
        const resume = view === _paneView ? pane.scrollTop : 0;
        _paneView = view;

        pane.className = 'ex-pane';
        pane.textContent = '';

        const kind = kindOf(S.path);
        // Standing anywhere else ends the visit to the Map, so opening it again frames the fleet afresh.
        if (kind !== 'map') _mapFramed = false;
        if (kind !== 'chat') _chatOpen = false;
        // The same reflex, for the same reason: leaving Credentials ends the visit, so opening it again
        // re-reads where every credential stands. Those standings age on the server — the reconcile runs
        // every five minutes — and a stale strip is the one thing this entry must never show.
        if (kind !== 'credentials' && S.credentials.state === 'ready') S.credentials.state = 'idle';
        drawPane(pane, kind);
        // After the drawing, because the offset can only be restored against the height the drawing gives it.
        pane.scrollTop = resume;
    }

    function drawPane(pane, kind) {
        if (kind === 'fleet') return renderFleet(pane);
        if (kind === 'map') return renderMap(pane);
        if (kind === 'settings') return renderSettings(pane);
        if (kind === 'chat') return renderChat(pane);
        if (kind === 'security') return renderSecurity(pane);
        if (kind === 'credentials') return renderCredentials(pane);
        if (kind === 'gbridge') return renderGlobalBridge(pane);
        if (kind === 'machine') return renderMachine(pane);
        if (kind === 'containers') return renderContainers(pane);
        if (kind === 'container') return renderContainer(pane);
        if (kind === 'services') return renderServices(pane);
        if (kind === 'service') return renderService(pane);
        if (kind === 'backup') return renderBackup(pane);
        if (kind === 'repo') return renderRepo(pane);
        if (kind === 'disk') return renderDisk(pane);
        return renderDirectory(pane);
    }

    function renderFleet(pane) {
        const online = S.machines.filter((m) => ['is-up', 'is-present'].includes(livenessOf(m.id))).length;
        pane.appendChild(paneHead('Fleet', false,
            S.machines.length + (S.machines.length === 1 ? ' machine · ' : ' machines · ') + online
            + ' online'));

        const body = document.createElement('div');
        body.className = 'ex-pane-body';

        // A phone that asked to join is waiting on someone right now, so it comes before the machines that
        // are already in. Gone the moment it is answered or gives up; the section paints nothing otherwise.
        const waiting = liveEnrolmentRequests();
        if (waiting.length) {
            body.appendChild(section('Waiting to join'));
            const list = el('div', 'ex-disc');
            waiting.forEach((r) => list.appendChild(enrolmentRequestRow(r)));
            body.appendChild(list);
        }

        // No "Machines" heading: the pane is already titled Fleet and its subtitle has just counted them, so
        // a label over the grid says a third time what two lines above it already said.
        if (!S.machines.length) {
            body.appendChild(note('No machines yet. Add one with the Add machine button below and it will appear '
                + 'here.', false));
        } else {
            const grid = document.createElement('div');
            grid.className = 'ex-grid';
            sortedMachines().forEach((m) => {
                // The card says what the machine is FOR, not where it answers. A tunnel address is Vaier's own
                // plumbing: standing on the fleet an operator can do nothing with 10.13.13.6, while the
                // description is the one line that tells them which machine this is. The address has not been
                // hidden, only put where an address belongs — renderMachine still lists it under the machine's
                // details, so this is a move, not a removal. A machine nobody has described says just its type.
                const purpose = machineDescription(m);
                const c = card(svg(machineIcon(m.id), 'ex-ico'), m.name,
                    MACHINE_TYPE[m.type] + (purpose ? ' · ' + purpose : ''),
                    () => go(['fleet', m.id]), m.id);
                // Everything the machine says without being opened, on one strip in one vocabulary: what it is
                // (Docker, Backup server) and how it is doing (its last backup, its disks, Claude, containers
                // wanting a newer image), each a glyph and its word. Splitting these across two places put two
                // rows of small things on a card that only ever had one thing to say. This is what makes the
                // fleet pane worth looking at without opening anything on it.
                const marks = machineMarks(m.id);
                if (marks.childNodes.length) c.appendChild(marks);
                // A long description is clamped to two lines so one card cannot stand taller than its
                // neighbours in the grid; the whole of it is a hover away rather than lost.
                if (purpose) c.querySelector('.ex-card-note').title = purpose;
                grid.appendChild(c);
            });
            body.appendChild(grid);
        }

        // The fleet seen as one picture rather than a list — a child of the fleet exactly as each machine is,
        // and reached from here. Its own section: it is a different way of looking at the
        // same machines, not another machine, and dropping it into the grid above would have said it was one.
        body.appendChild(section('The fleet, seen whole'));
        const views = el('div', 'ex-grid');
        views.appendChild(card(svg('map', 'ex-ico'), 'Map', 'Where the machines physically are',
            () => go(['fleet', 'map'])));
        body.appendChild(views);

        // Adding a machine is a fleet-level act — it belongs on the fleet, not floating in the topbar over
        // every path you happen to be standing in. It sits under the same heading a machine's own suggestions
        // do, in the same place on the page: where you go is above, what you can do is here.
        body.appendChild(section('What to do next'));
        const addBar = el('div', 'ex-lactions is-static');
        addBar.appendChild(selVerb('server', 'Add machine', 'ex-btn is-accent', () => addMachine()));
        body.appendChild(addBar);

        // Discovery lives in the Add-a-machine flow and nowhere else. It used to have a second home here, but
        // scanning is a step on the way to adding something, not a standing report about the fleet — a fleet
        // page that lists things which are *not* in the fleet, each with its own Add and Ignore, is a second
        // entry point competing with the one the button above opens. One road in.

        pane.appendChild(body);
    }

    // One waiting phone: what it calls itself, the code it is showing, and the two answers. The code is the
    // chip because it is the thing the operator reads off the phone and matches here.
    function enrolmentRequestRow(r) {
        const row = el('div', 'ex-disc-row');
        const ic = el('span', 'ex-disc-icon');
        ic.innerHTML = svg('phone', 'ex-disc-svg');
        const info = el('div', 'ex-disc-info');
        const line = el('div', 'ex-disc-line');
        const nm = el('span', 'ex-disc-name'); nm.textContent = r.name;
        const chip = el('span', 'ex-disc-kind'); chip.textContent = r.code;
        line.append(nm, chip);
        const meta = el('span', 'ex-disc-meta');
        const mins = Math.max(1, Math.round((r.expiresAt - Date.now()) / 60000));
        meta.textContent = 'wants to join from the Vaier app · waits ' + mins + (mins === 1 ? ' more minute' : ' more minutes');
        info.append(line, meta);
        const acts = el('div', 'ex-lactions is-static');
        const add = el('button', 'ex-btn is-accent'); add.textContent = 'Add';
        add.onclick = () => addMachineFork('enrol', null, r);
        acts.appendChild(add);
        row.append(ic, info, acts);
        return row;
    }

    // The fleet on a map — where the machines physically are. Leaflet, loaded from explorer.html; if it did not
    // load, the entry says so rather than breaking. The map is built once and kept for as long as the tab lives:
    // renderPane empties the pane on every render, so the map's element is detached and re-attached rather than
    // rebuilt, and requestAnimationFrame — never a timer — settles its size again. Clustered so co-located
    // machines gather and spiderfy on click.
    //
    // A client peer's own position comes entirely from `placement`: a REPORTED fix is a firm pin, an
    // ISP_ESTIMATE is a soft region (never a pin — a carrier's registry point is not the device), and no
    // placement draws nothing at all. It shows twice regardless: once wherever `placement` puts it (or not),
    // and once firm at the Vaier server, where its tunnelled traffic actually surfaces.
    // LAN servers sit at their relay; the Vaier server itself is the big marker. Leaflet + markercluster load
    // from explorer.html.
    let _map = null;
    let _cluster = null;
    let _mapHolder = null;    // the map's own element, moved between renders rather than rebuilt
    let _mapFramed = false;   // whether this visit to the Map has had its one framing
    let _fleetMarkers = new Map();   // pin key -> { marker, look, at }, so an update can move what moved
    let _fleetRegions = new Map();   // the same, for the ISP-estimate circles
    let _threatMarkers = [];
    let _accessMarkers = [];
    let _trailLayers = [];           // the drawn position trail, torn down whole and redrawn
    let _trailKey = null;            // which pin's trail is on the map, or null when none is
    let _trailLook = null;           // the points as drawn, so an unchanged trail is left alone
    let _trailByKey = new Map();     // pin key -> its points, refreshed every paint so a redraw is current
    const MAP_STATUS = { OK: 'up', DEGRADED: 'degraded', DOWN: 'down', UNKNOWN: 'unknown' };
    const iconByCategory = (cat) => { const k = cat ? String(cat).toLowerCase() : ''; return ICON[k] ? k : 'generic'; };

    // Split out from mapMarker so a marker that is already on the map can be re-dressed (setIcon) when its
    // status changes, instead of being replaced by a new one.
    function markerIcon(iconKind, statusKey, opts) {
        const cls = ['map-marker', statusKey];
        if (opts && opts.big) cls.push('large');
        // A REPORTED placement the domain has called stale — a real fix, just an old one. Faded, not dashed:
        // dashed already means "not a real fix" (the ISP circle), and stale coordinates are still a real one.
        if (opts && opts.stale) cls.push('stale');
        const sz = opts && opts.big ? 36 : 28;
        return L.divIcon({ html: '<div class="' + cls.join(' ') + '">' + svg(iconKind, 'ex-ico') + '</div>',
            className: '', iconSize: [sz, sz], iconAnchor: [sz / 2, sz / 2] });
    }

    function mapMarker(latlng, iconKind, statusKey, opts) {
        return L.marker(latlng, { icon: markerIcon(iconKind, statusKey, opts), riseOnHover: true });
    }
    // A popup, built from DOM elements (no interpolation) — a bold title then muted/mono lines.
    function mapPopup(title, lines) {
        const box = el('div');
        const t = el('strong'); t.textContent = title; box.appendChild(t);
        lines.forEach((ln) => {
            if (!ln || !ln.text) return;
            box.appendChild(el('br'));
            const s = el('span');
            if (ln.mono) s.style.fontFamily = 'var(--mono)';
            if (ln.muted) { s.style.color = '#888'; s.style.fontSize = '0.85em'; }
            s.textContent = ln.text; box.appendChild(s);
        });
        return box;
    }

    // Threat pings and access dots both need "remove exactly the markers this layer added last paint, then
    // add fresh ones into the fleet's shared cluster" — written once so the two cannot drift apart on it.
    // Joining the cluster, not a separate layer, is what makes co-located markers fan out on click (Frankfurt
    // is both the access source AND the Vaier server's own 36px marker; a lone-layer dot hid underneath it).
    // `coords` — what fitBounds reads for framing — is untouched here, so cluster membership can never drag
    // the map's zoom toward a single remote scanner or sign-in.
    //
    // The fleet's own markers deliberately do NOT come through here (see syncPins): a machine has a stable
    // identity to diff on and a popup worth keeping open across an update. A blocked address or a sign-in
    // is an event, not a thing that persists and moves, so there is nothing to key it by and wholesale
    // replacement is the honest way to redraw it.
    function repaintIntoCluster(prevMarkers, items, buildMarker) {
        if (!_map || !_cluster) return prevMarkers;
        prevMarkers.forEach((m) => _cluster.removeLayer(m));
        return items.map((d) => { const m = buildMarker(d); _cluster.addLayer(m); return m; });
    }

    // Refreshed in place (not re-rendered) so a push mid-pan doesn't yank the map from under the operator.
    function paintThreatLayer() {
        _threatMarkers = repaintIntoCluster(_threatMarkers, S.threats.filter((d) => d.locatable), (d) => {
            const m = L.marker([d.latitude, d.longitude], {
                icon: L.divIcon({ html: '<div class="threat-ping"></div>', className: '',
                    iconSize: [22, 22], iconAnchor: [11, 11] }),
                riseOnHover: true,
            });
            m.bindPopup(mapPopup(d.sourceIp, [{ text: 'blocked at the edge', muted: true },
                { text: d.scenario }, { text: d.origin },
                { text: 'expires in ' + d.duration, muted: true }]));
            return m;
        });
    }

    // The mirror image of paintThreatLayer: same shared repaint, same reason to join the cluster.
    function paintAccessLayer() {
        _accessMarkers = repaintIntoCluster(_accessMarkers, S.accessSources.filter((d) => d.locatable), (d) => {
            const m = L.marker([d.latitude, d.longitude], {
                icon: L.divIcon({ html: '<div class="access-dot"></div>', className: '',
                    iconSize: [12, 12], iconAnchor: [6, 6] }),
                riseOnHover: true,
            });
            const people = d.people || [];
            const shown = people.slice(0, 5);
            const rest = people.length - shown.length;
            m.bindPopup(mapPopup(d.place, [
                { text: d.count + (d.count === 1 ? ' allowed access' : ' allowed accesses'), muted: true },
                { text: 'last seen ' + timeAgo(d.lastSeen), muted: true },
                { text: shown.join(', ') + (rest > 0 ? ', +' + rest + ' more' : '') },
            ]));
            return m;
        });
    }

    // The position trail — where one machine has actually been, drawn from its own reported positions.
    //
    // Shown for ONE machine at a time: the one whose popup is open. The fleet map already carries a chip per
    // machine, a second chip per client at the hub, soft ISP circles, green access dots and red threat pings;
    // every trail always on would be a permanent scribble across all of it, and two travelling devices would
    // cross into one unreadable tangle. Opening a machine's popup is already how the operator asks about that
    // machine, so it is the selection — no new control, no legend, nothing to switch off.
    const TRAIL_COLOUR = '#d9a05b';   // --accent: the console drawing, not a status. No other map layer wears it.
    const TRAIL_BANDS = 12;

    // The line is drawn in bands rather than one segment per point: a 500-point trail is one polyline per band
    // instead of 499, and twelve steps is a ramp the eye reads as continuous. Consecutive bands share an
    // endpoint, so the path has no gaps.
    function trailBands(points) {
        const segments = points.length - 1;
        const bands = Math.min(TRAIL_BANDS, segments);
        const out = [];
        for (let b = 0; b < bands; b++) {
            const from = Math.floor(b * segments / bands);
            const to = Math.floor((b + 1) * segments / bands);
            out.push({ path: points.slice(from, to + 1), age: bands === 1 ? 1 : b / (bands - 1) });
        }
        return out;
    }

    // Older recedes: the trail thins and fades back into the past, so which end is "now" is legible without an
    // arrowhead or a legend. The newest end runs into the machine's own marker, which is the head of the line.
    //
    // Look-guarded like the fleet's pins are: the peers feed repaints on every SSE nudge, and a trail that has
    // not gained a point must not be torn down and rebuilt underneath an operator reading the map.
    function paintTrail(points) {
        const look = JSON.stringify(points);
        if (look === _trailLook && _trailLayers.length) return;
        clearTrail();
        _trailLook = look;
        if (!_map || points.length < 2) return;
        trailBands(points).forEach((band) => {
            _trailLayers.push(L.polyline(band.path, {
                color: TRAIL_COLOUR, weight: 1.5 + 2.5 * band.age, opacity: 0.22 + 0.68 * band.age,
                lineCap: 'round', lineJoin: 'round', interactive: false,
            }).addTo(_map));
        });
        // A dot per retained point, because where the reports fell is information the line alone hides:
        // clustered dots are a device that lingered, strung-out ones a device that was moving.
        points.forEach((at, i) => {
            const age = i / (points.length - 1);
            _trailLayers.push(L.circleMarker(at, {
                radius: 1.6 + 1.6 * age, color: TRAIL_COLOUR, weight: 0,
                fillColor: TRAIL_COLOUR, fillOpacity: 0.3 + 0.6 * age, interactive: false,
            }).addTo(_map));
        });
    }

    function clearTrail() {
        _trailLayers.forEach((layer) => _map.removeLayer(layer));
        _trailLayers = [];
        _trailLook = null;
    }

    function showTrail(key) {
        _trailKey = key;
        paintTrail(_trailByKey.get(key) || []);
    }

    // Guarded by key: a popup closing because another just opened must not erase the one that replaced it.
    function hideTrail(key) {
        if (_trailKey !== key) return;
        _trailKey = null;
        clearTrail();
    }

    // Leaflet holds the operator's pan and zoom inside the map object, so building it once is the whole fix:
    // a rebuilt map is a reset view. The element is handed to each freshly rendered body instead, and once
    // built the map lives as long as the tab — deliberately. Anything that would have to be chosen afresh
    // (a different basemap, a different projection) has to be applied to this instance, not waited for on
    // the next render, because there is no next construction.
    //
    // trackResize is off: Leaflet's own window-resize handler would fire while the element is detached (the
    // pane is emptied on every render), measure a 0x0 container and pan by half its old size. renderMap
    // calls invalidateSize itself on every paint, which is when the size can actually be measured.
    function ensureMap(body) {
        if (!_mapHolder) _mapHolder = el('div', 'ex-map');
        body.appendChild(_mapHolder);
        if (_map) return;
        _map = L.map(_mapHolder, { worldCopyJump: true, trackResize: false }).setView([20, 0], 1);
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
            { maxZoom: 18, attribution: '© OpenStreetMap' }).addTo(_map);
        _cluster = L.markerClusterGroup({ showCoverageOnHover: false, spiderfyOnMaxZoom: true, maxClusterRadius: 30 });
        _map.addLayer(_cluster);
        // The one case trackResize was right about — the window resizing while the Map is on screen — handled
        // where the element can be checked for being attached first. An event, never a timer.
        window.addEventListener('resize', () => { if (_mapHolder.isConnected) _map.invalidateSize(); });
    }

    // What the fleet layer draws, as data: one entry per pin, keyed by the machine it belongs to and the role
    // it plays there (a client draws twice — where the device is, and at the hub its traffic surfaces through
    // — so the machine's identity alone is not a key). Describing the pins before drawing them is what lets
    // the next paint tell "this is the same pin, moved" from "this is a different pin".
    function fleetPins() {
        const loc = S.serverLocation;
        const hasLoc = loc && loc.latitude != null && loc.longitude != null;
        const pins = [];
        const regions = [];

        Array.from(S.peers.values()).forEach((p) => {
            const statusKey = p.connected ? 'up' : 'down';
            const iconKind = iconByCategory(p.deviceCategory);
            if (p.isClient) {
                // `placement` is the one true answer to "where is this client" — never re-derived here, only
                // rendered. No re-check against p.latitude/p.longitude: those still carry the raw ISP guess,
                // which is exactly what put a 35-hours-disconnected phone at Telenor HQ.
                const pl = p.placement;
                if (pl && pl.source === 'REPORTED') {
                    const lines = pl.stale
                        ? [{ text: 'Last reported — ' + timeAgo(pl.asOf), muted: true }]
                        : [{ text: 'Reported by the device', muted: true }, { text: timeAgo(pl.asOf) }];
                    if (pl.accuracyMetres != null) {
                        lines.push({ text: '±' + Math.round(pl.accuracyMetres) + ' m', muted: true });
                    }
                    // Only ever the machine's own reported positions — an ISP estimate never joins a trail,
                    // so a peer placed by its carrier gets none. A lone point is where it already is.
                    const trail = (p.positionTrail || []).map((t) => [t.latitude, t.longitude]);
                    if (trail.length > 1) {
                        lines.push({ text: 'Position trail — ' + trail.length + ' points, oldest '
                            + timeAgo(p.positionTrail[0].at), muted: true });
                    }
                    pins.push({ key: 'device:' + p.machineId, at: [pl.latitude, pl.longitude],
                        icon: iconKind, status: statusKey, opts: { stale: !!pl.stale }, title: p.name, lines,
                        trail });
                } else if (pl && pl.source === 'ISP_ESTIMATE') {
                    // A soft region, not a pin: a carrier's registry point is not evidence of where the device
                    // sits. The radius is a fixed visual convention, not a claimed accuracy — Vaier does not
                    // know one, and drawing a number here would repeat the bug in a new costume.
                    regions.push({ key: 'isp:' + p.machineId, at: [pl.latitude, pl.longitude], title: p.name,
                        lines: [{ text: 'ISP estimate', muted: true },
                            { text: p.endpointIp, mono: true }, { text: pl.place || '' },
                            { text: 'Where the carrier registered this address — not where the device is.',
                                muted: true }] });
                }
                // A client's traffic surfaces at the Vaier server no matter where the device itself is — true
                // whether or not Vaier can honestly place the device, so this never waits on `placement`.
                if (hasLoc) {
                    pins.push({ key: 'hub:' + p.machineId, at: [loc.latitude, loc.longitude],
                        icon: iconKind, status: statusKey, title: p.name,
                        lines: [{ text: 'internet via Vaier', muted: true },
                            { text: [loc.city, loc.country].filter(Boolean).join(', ') }] });
                }
            } else {
                // Deliberately not `placement`: a fixed-line peer's ISP estimate is good evidence about a
                // thing that does not move, so its last known dot stays rather than vanishing off the map.
                if (p.latitude == null || p.longitude == null) return;
                const place = [p.city, p.country].filter(Boolean).join(', ');
                pins.push({ key: 'device:' + p.machineId, at: [p.latitude, p.longitude],
                    icon: iconKind, status: statusKey, title: p.name,
                    lines: [{ text: p.endpointIp, mono: true }, { text: place }] });
            }
        });

        Array.from(S.lan.values()).forEach((s) => {
            if (!s.relayPeerName) return;
            let lat, lon;
            // Anchored at the Vaier server's own LAN, which is the case where there IS no relay peer — so
            // the absence of a relay identity is the test, not the relay's name. Tested by name, a peer an
            // operator had called "Vaier server" (allowed since names stopped needing to be unique) would
            // have put every machine behind it on the hub's dot instead of its own.
            if (!s.relayMachineId) {
                if (!hasLoc) return; lat = loc.latitude; lon = loc.longitude;
            } else {
                // The feed says which machine relays this LAN, by identity. It used to say only the
                // relay's name, which had to be resolved through the machine registry — and would have
                // drawn this server at the wrong relay's coordinates once two machines shared a name.
                const relay = S.peers.get(s.relayMachineId);
                if (!relay || relay.latitude == null || relay.longitude == null) return;
                lat = relay.latitude; lon = relay.longitude;
            }
            pins.push({ key: 'device:' + s.machineId, at: [lat, lon], icon: iconByCategory(s.deviceCategory),
                status: MAP_STATUS[s.status] || 'unknown', title: s.name,
                lines: [{ text: 'Behind ' + s.relayPeerName, muted: true },
                    { text: s.lanAddress, mono: true },
                    { text: s.runsDocker ? 'Docker on :' + s.dockerPort : '', muted: true }] });
        });

        if (hasLoc) {
            pins.push({ key: 'server', at: [loc.latitude, loc.longitude], icon: 'server', status: 'up',
                opts: { big: true }, title: 'Vaier server',
                lines: [{ text: loc.publicHost, mono: true },
                    { text: [loc.city, loc.country].filter(Boolean).join(', ') }] });
        }
        return { pins, regions };
    }

    // Everything about a pin except where it is — its icon, its state, what its popup says. Compared to decide
    // whether an existing pin needs re-dressing at all, so a pin that has not changed is left strictly alone
    // and the popup an operator is reading survives the update that arrives while they read it.
    const pinLook = (p) => JSON.stringify([p.icon, p.status, p.opts || null, p.title, p.lines]);
    const sameAt = (a, b) => a[0] === b[0] && a[1] === b[1];

    // The fleet's markers, diffed by key: what is new is added, what moved is moved, what changed is
    // re-dressed, what is gone is removed, and what is unchanged is not touched.
    //
    // setLatLng is all a move needs even inside the cluster: MarkerClusterGroup binds a `move` handler to
    // every marker it holds (_childMarkerMoved), which re-clusters it at the new position and re-opens its
    // popup if one was open. refreshClusters is not the tool here — it only refreshes cluster ICONS.
    function syncPins(pins) {
        const next = new Map();
        // Rebuilt from this paint's data so an open trail redraws from what is current, never from a snapshot
        // taken when the popup opened. Deliberately outside pinLook: a trail growing by a point must not
        // re-dress the marker.
        _trailByKey = new Map();
        pins.forEach((p) => { if (p.trail && p.trail.length > 1) _trailByKey.set(p.key, p.trail); });
        pins.forEach((p) => {
            if (next.has(p.key)) return;   // one pin per key — a second would orphan the first in the cluster
            const held = _fleetMarkers.get(p.key);
            const look = pinLook(p);
            if (!held) {
                const marker = mapMarker(p.at, p.icon, p.status, p.opts);
                marker.bindPopup(mapPopup(p.title, p.lines));
                // The popup IS the selection — opening one asks about that machine, and its trail is part of
                // the answer. A machine with no trail simply has nothing to draw.
                marker.on('popupopen', () => showTrail(p.key));
                marker.on('popupclose', () => hideTrail(p.key));
                _cluster.addLayer(marker);
                next.set(p.key, { marker, look, at: p.at });
                return;
            }
            if (!sameAt(held.at, p.at)) held.marker.setLatLng(p.at);
            if (held.look !== look) {
                held.marker.setIcon(markerIcon(p.icon, p.status, p.opts));
                held.marker.setPopupContent(mapPopup(p.title, p.lines));
            }
            next.set(p.key, { marker: held.marker, look, at: p.at });
        });
        _fleetMarkers.forEach((held, k) => { if (!next.has(k)) _cluster.removeLayer(held.marker); });
        _fleetMarkers = next;
        // A trail on screen follows its machine: it grows as new points arrive and goes when the machine does
        // (deleted, or its position forgotten). Pushed by the vpn-peers SSE topic the fleet already repaints
        // on — nothing here is on a timer.
        if (_trailKey) {
            if (_trailByKey.has(_trailKey)) paintTrail(_trailByKey.get(_trailKey));
            else hideTrail(_trailKey);
        }
    }

    // The same diff for the ISP-estimate circles. They are not cluster members — they sit straight on the
    // map — so a move here is a plain setLatLng with nobody to tell.
    function syncRegions(regions) {
        const next = new Map();
        regions.forEach((r) => {
            const held = _fleetRegions.get(r.key);
            const look = pinLook(r);
            if (!held) {
                const circle = L.circle(r.at, { radius: 30000,
                    color: '#7a6a53', weight: 1, opacity: .6, fillColor: '#7a6a53', fillOpacity: .12 });
                circle.bindPopup(mapPopup(r.title, r.lines));
                circle.addTo(_map);
                next.set(r.key, { circle, look, at: r.at });
                return;
            }
            if (!sameAt(held.at, r.at)) held.circle.setLatLng(r.at);
            if (held.look !== look) held.circle.setPopupContent(mapPopup(r.title, r.lines));
            next.set(r.key, { circle: held.circle, look, at: r.at });
        });
        _fleetRegions.forEach((held, k) => { if (!next.has(k)) _map.removeLayer(held.circle); });
        _fleetRegions = next;
    }

    // Returns every coordinate drawn — what the first paint frames on, and nothing after it reads.
    function paintFleetLayer() {
        const { pins, regions } = fleetPins();
        syncPins(pins);
        syncRegions(regions);
        return pins.map((p) => p.at).concat(regions.map((r) => r.at));
    }

    function renderMap(pane) {
        const seen = S.threats.filter((d) => d.locatable).length;
        pane.appendChild(paneHead('Map', false,
            seen ? 'Where the fleet is, and where ' + seen + (seen === 1 ? ' blocked address is' : ' blocked addresses are')
                 : 'Where the fleet is'));
        const body = el('div', 'ex-pane-body ex-map-body');
        pane.appendChild(body);
        if (typeof L === 'undefined') { body.appendChild(note('The map could not load its library.', true)); return; }
        ensureMap(body);

        const coords = paintFleetLayer();
        paintThreatLayer();
        paintAccessLayer();

        // An unreadable threat list must not look like a quiet one. With no pings and no note, a map drawn
        // during a CrowdSec outage reads as "nobody is probing us" — the same falsely reassuring silence the
        // Security view says out loud, and worse here because the Map never claims a count to contradict.
        if (S.threatsError) body.appendChild(note(S.threatsError, true));
        if (S.accessSourcesError) body.appendChild(note(S.accessSourcesError, true));

        // Every access with no dot, counted rather than lost: allowed traffic that cannot be drawn is not a
        // gap in the data and not zero usage. `!locatable` is the right filter precisely because it is the
        // map's own question — anything the map will not draw belongs in this count, whether that is the
        // unplaceable bucket, a city the database has no coordinates for, or null island.
        //
        // Which is also why the note does not name the VPN and the LAN as the cause: they are the common
        // one, not an established one, and a lookup that simply failed on a public address lands here too.
        const unplaceable = S.accessSources.filter((d) => !d.locatable)
            .reduce((sum, d) => sum + (d.count || 0), 0);
        if (unplaceable) {
            body.appendChild(note(unplaceable + (unplaceable === 1 ? ' allowed access came' : ' allowed accesses came')
                + ' from an address that cannot be placed on a map — your own VPN and LAN among them.', false));
        }

        // Framed on the first paint of a visit and never again: from the operator's first pan or zoom the view
        // is theirs, and an update must not take it back. Leaving the Map ends the visit (renderPane).
        if (coords.length && !_mapFramed) {
            _map.fitBounds(L.latLngBounds(coords).pad(0.3), { maxZoom: 5 });
            _mapFramed = true;
        }
        if (!coords.length) body.appendChild(note('No machine has a known location yet.', false));
        // The element has just been re-attached to a new body, so Leaflet has to measure it again.
        requestAnimationFrame(() => { try { _map.invalidateSize(); } catch (e) { /* gone */ } });
    }

    // A peer answers at its tunnel address, a LAN server on its LAN — the same rule the SSH connection
    // itself is resolved by, so what the Inspector shows is where Vaier would actually go.
    function tunnelAddress(m) {
        const peer = S.peers.get(m.id);
        if (peer && peer.tunnelIp) return peer.tunnelIp;
        return m.lanAddress || '';
    }

    // What a machine is FOR, in the operator's own words. It rides on the peer record — or, for a LAN server,
    // on its own — and not on /machines, so this is the exact lookup the edit form writes it back through.
    // One way to resolve a description; a second would be a second way to get it wrong.
    //
    // Blank and whitespace-only are the same thing here: a description nobody wrote. Callers say just the
    // machine's type rather than trailing a separator with nothing after it.
    function machineDescription(m) {
        const rec = S.peers.get(m.id) || S.lan.get(m.id) || {};
        return (rec.description || '').trim();
    }

    // Whether Vaier has any way inside this machine. A phone or a laptop has none — nothing listens for SSH,
    // so there are no files, no shell and no disk, and never will be. One rule, because it decides three
    // things that have to agree: whether the shell is offered among the doors, whether Vaier's reach is
    // stated at all, and whether the machine gets the device-claim band instead.
    function reachesInside(m) {
        return m.type !== 'MOBILE_CLIENT' && m.type !== 'WINDOWS_CLIENT';
    }

    function renderMachine(pane) {
        const m = machineOf(S.path);
        if (!m) return pane.appendChild(note('That machine is no longer in the fleet.', true));

        const peer = S.peers.get(m.id);
        // The same subtitle the card the operator just clicked was wearing — what the machine is, and what
        // the operator said it is FOR. Losing the second half on the way in made the pane say less about the
        // machine than the grid it came from.
        const purpose = machineDescription(m);
        // Vaier's own machine answers differently in three places below, so the question is asked once here.
        const isVaierServer = !!m.vaierServer;
        const head = paneHead(m.name, true, MACHINE_TYPE[m.type] + (purpose ? ' · ' + purpose : ''));
        head.querySelector('.ex-pane-title').appendChild(dot(m.id));
        // The machine's open verbs live where every other pane keeps the verbs that apply right now: the
        // head's one action group, hugging the right. Editing details is common, and a LAN server's setup
        // command is the whole of onboarding it — neither had earned a section heading of its own halfway
        // down the body. The Vaier server is this machine: it is never edited or removed here.
        if (!isVaierServer) {
            const acts = el('div', 'ex-pane-actions');
            acts.appendChild(selVerb('gear', 'Edit details', 'ex-btn', () => editMachine(m)));
            // Whether a setup script can be run on the machine is the server's reading of it — an
            // appliance has no shell for one, however it is anchored — so the verb is not offered
            // where a click could only end in a toast.
            if (m.acceptsSetupScript) {
                acts.appendChild(selVerb('shell', 'Setup command', 'ex-btn', () => lanSetupScript(m.id)));
            }
            head.appendChild(acts);
        }
        pane.appendChild(head);

        const body = document.createElement('div');
        body.className = 'ex-pane-body';

        // Vaier's last-known belief about whether this machine has an SSH server at all — pushed live by the
        // same 5-minute sweep that already reaches every SSH-accessible, credentialed machine
        // (RemoteDiskWatcher), never a fresh probe from here. Transient and self-healing: the next sweep that
        // reaches the machine lifts every greying below without a reload.
        const noSshServer = m.sshServerPresence === 'ABSENT';
        const reachable = reachesInside(m);

        // Whether Vaier may open a session at all. It appears in one of two places depending on the answer:
        // while it is off it is the next step and stands in the open, because granting it is what gives this
        // page anything to show; once on it is a fact about the machine and sits quietly among the others.
        // A control shouts while acting on it changes the page, and stops shouting once it has.
        function sshAccessRow() {
            const access = el('label', 'ex-check-row');
            const box = el('input'); box.type = 'checkbox'; box.checked = !!m.sshAccess;
            // Never disable an already-on toggle: an operator who turned access on must still be able to turn
            // it back off, see the stored credential, or retry — never stranded behind a control with no way
            // back. The gate only ever stops turning access ON for a machine Vaier already knows has nothing
            // listening.
            if (!m.sshAccess && noSshServer) {
                box.disabled = true;
                box.title = 'No SSH server detected on last check';
            }
            box.onchange = () => toggleSshAccess(m.id, box.checked, box);
            const atxt = el('span'); atxt.textContent = 'Let Vaier open an SSH session to this machine';
            access.append(box, atxt);
            return access;
        }

        // --- go: the ways into this machine, first and under no heading -------------------------------
        //
        // A machine is opened in order to get somewhere — its files, a shell, what it runs, how full it is,
        // what is kept of it. Those doors used to sit fourth, below a details table and a fold, so the page
        // answered "what is this machine" before "where can I go", which is the wrong way round for the one
        // page an operator lands on most. What the machine IS is reference, and reference reads second.
        const inside = childrenOf(S.path);
        const grid = el('div', 'ex-grid');
        // The backup entry reads both ways, exactly as childrenOf grows it: this machine is the one backup
        // server, or a job backs it up. Colina once introduced itself as where the fleet backs up.
        const isBackupServer = !!S.backupServer && S.backupServer.machineId === m.id;
        const NOTE = {
            files:      'Browse over SFTP',
            containers: containersOn(m.id).length + ' seen by Vaier',
            services:   servicesOn(m.id).length + ' published from here',
            disk:       'Its filesystems, and how full they are',
            backup:     isBackupServer ? 'The fleet backs up here'
                : 'Backs up to ' + (S.backupServer ? S.backupServer.name : 'the backup server'),
        };
        // Files and disk both ride on SSH — a credential alone grew the entries, but a machine whose
        // last check found no SSH server would just relocate the same dead end one click deeper. Grey them
        // out rather than remove them: the entry still names what is there, and the next sweep (or an SSH
        // server put back) lifts the greying on its own.
        const SSH_ENTRY_KINDS = new Set(['files', 'disk']);
        inside.forEach((kid) => {
            const disabledTitle = (noSshServer && SSH_ENTRY_KINDS.has(kid.kind))
                ? 'No SSH server detected on last check' : null;
            const door = card(entryIco(kid.kind, kid.name), kid.name,
                NOTE[kid.name], () => go(['fleet', m.id, kid.name]), null, disabledTitle);
            // The containers door wears the fleet card's own "One update" pill: the operator who came here
            // for that mark should not have to open the door to find it again.
            const stale = kid.kind === 'containers' ? updateCountMark(m.id) : null;
            if (stale) {
                const marks = el('span', 'ex-card-marks');
                marks.appendChild(stale);
                door.appendChild(marks);
            }
            grid.appendChild(door);
        });
        // A shell is a way into this machine exactly as its files and its containers are. It was filed under
        // a heading called "SSH access" because SSH is how it travels — which is the machine's plumbing, not
        // the operator's reason for wanting it, and grouping by plumbing is how that heading came to hold a
        // toggle, a credential, a privilege warning and the whole Claude sign-in. It is a door, so it is with
        // the doors, greyed for the same two reasons the others are. It opens a window rather than a pane,
        // and its own line is where that is said.
        if (reachable && m.sshAccess) {
            let shellStop = null;
            if (!m.hasCredential) shellStop = 'Give this machine an SSH credential first';
            else if (noSshServer) shellStop = 'No SSH server answered on the last check';
            const shellCard = card(svg('shell', 'ex-ico'), 'shell',
                shellStop || 'Runs on ' + m.name + ' itself — reopening reattaches where you left off',
                () => openShellWindow(m.id), null, shellStop);
            // A session that outlives its window needs to say how it is ended, or the operator has started
            // something they cannot stop. The card's note has room for what it IS; the rest goes here.
            if (!shellStop) {
                shellCard.title = 'Runs on ' + m.name + ' itself and outlives the window — reopening '
                    + 'reattaches where you left off; Exit shell, inside it, is what stops it.';
            }
            grid.appendChild(shellCard);
        }
        if (grid.childNodes.length) body.appendChild(grid);
        else if (reachable) {
            body.appendChild(note('Nothing to open here yet — no files, shell or disk without SSH access, no '
                + 'Docker Vaier knows of, nothing published.', false));
        }

        // --- do: what is worth doing here right now ---------------------------------------------------
        //
        // What Vaier suggests doing next with this machine — progressive-adoption nudges (§6.15.1): publish its
        // services, back it up, or (in the bootstrapping moment before any exists) make it the fleet's backup
        // server. Each is an evidence-backed single action. The *domain* decides which apply
        // (GET /machines/{name}/nudges) — so the shell no longer hand-rolls "offer a backup server when none
        // exists yet" here; it renders whatever the domain returns and only routes the action. Read once per
        // machine, repainted, never polled. Granting SSH joins them while it is ungranted: it is the step that
        // unlocks every other one.
        const nudges = S.nudges.get(m.id);
        if (!nudges) loadNudges(m.id);
        const hasNudges = nudges && nudges.state === 'ready' && nudges.list.length;
        const offerAccess = reachable && !m.sshAccess;
        if (hasNudges || offerAccess) {
            body.appendChild(section('What to do next'));
            if (offerAccess) {
                body.appendChild(sshAccessRow());
                body.appendChild(hint('Without it Vaier has no shell, no files and no disk reading here. '
                    + 'Turn it on to give this machine an SSH credential.'));
            }
            if (hasNudges) nudges.list.forEach((n) => body.appendChild(nudgeCard(m, n)));
        }

        // A phone or a laptop has no SSH story — Vaier cannot reach inside it. What it CAN do is ask the
        // device itself where it is, and the device's own browser is the only witness that can say. The claim
        // is the one-time act of that browser vouching "I am the one running on this machine"; sharing and
        // forgetting only make sense once it has. This is that machine's whole Do band.
        if (!reachable) {
            body.appendChild(section('This device'));
            // "I claim this machine" is per-browser, not per-machine — GET /vpn/peers/my-device says which
            // ONE machine THIS browser's cookie is bound to, read once at boot and held in S. Comparing a
            // per-peer flag would have shown Share on every browser looking at Geir's phone, not just his
            // phone's own.
            if (S.myDeviceMachineId === m.id) {
                body.appendChild(devicePlacementControls(m));
            } else {
                const claimRow = el('div', 'ex-lactions is-static');
                claimRow.appendChild(selVerb('locate', 'This browser is ' + m.name, 'ex-btn', () => claimDevice(m)));
                body.appendChild(claimRow);
                body.appendChild(hint('Claim ' + m.name + ' from the browser running on it, and it can report '
                    + 'where it actually is — with the tunnel down too.'));
            }
        }

        // --- know: what this machine is ---------------------------------------------------------------
        body.appendChild(section('About this machine'));
        // A LAN server has no tunnel, so it never gets mesh rows — blanks would claim one that is merely down.
        const isLan = m.type === 'LAN_SERVER';
        const rows = [];
        if (isVaierServer) {
            rows.push(['Role', 'The fleet’s hub — WireGuard server and reverse proxy']);
        } else if (isLan) {
            const lan = S.lan.get(m.id);
            rows.push(['Last seen', lan ? agoFromEpochSeconds(lan.lastSeen) : '']);
        } else {
            rows.push(['Last handshake', peer ? agoFromEpochSeconds(peer.latestHandshake) : '']);
        }
        rows.push(['Device category', categoryLabel(m.deviceCategory)]);
        // Only an access that came over the tunnel identifies a device rather than a person, so this is the
        // last published service Vaier SAW this machine reach — which is why the row says so, and why no
        // record draws no row: never having seen one is not the same as the machine having reached none.
        const reached = peer && peer.lastServiceReached;
        if (reached) {
            const age = timeAgo(reached.at);
            rows.push(['Last service', (reached.displayName || reached.host)
                + (age ? ' · ' + age : '') + ', over the tunnel']);
        }
        body.appendChild(kv(rows));

        // Vaier's reach into this machine, stated as the fact it now is: that it may go in, and who it is
        // when it does. The shell that uses this is a door and sits at the top of the page with the others.
        if (reachable && m.sshAccess) {
            body.appendChild(sshAccessRow());
            const cred = el('div', 'ex-lactions is-static');
            cred.appendChild(selVerb('gear', 'SSH credential', 'ex-btn', () => credentialDialog(m.id)));
            body.appendChild(cred);
            // Who Vaier actually is on this machine. The credential's username IS the effective user, so
            // saying it costs nothing — and it is the whole difference between a delete that removes a file
            // you did not want and one that removes a file the machine needs to boot. Nobody chose root on
            // the DietPi boxes; it arrived with the image. Naming it is the first step to deciding whether
            // it stays.
            if (m.effectiveUsername) {
                const said = 'Vaier acts as ' + m.effectiveUsername + ' on ' + m.name;
                // Privilege is the one fact here that earns a bordered box. A warning only reads as a warning
                // while the paragraphs beside it are not wearing the same edge.
                if (m.effectiveUserPrivileged) {
                    const warn = note(said + ' — a privileged user, so everything it does here, reading, '
                        + 'writing and deleting alike, runs unrestricted.', false);
                    warn.classList.add('is-warn');
                    body.appendChild(warn);
                } else {
                    // is-who: not an aside. This is the machine's blast radius and the line the Claude card
                    // hangs off, so it reads a shade above the hints around it.
                    const who = hint(said + ' — it reaches exactly what that user can reach, and nothing else.');
                    who.classList.add('is-who');
                    body.appendChild(who);
                }
            }
        }

        const wires = disclosure('Connection details');
        const wireRows = [];
        if (!isVaierServer && !isLan) {
            wireRows.push(['Tunnel address', coord(tunnelAddress(m))]);
            wireRows.push(['Endpoint', coord(m.endpointIp ? m.endpointIp + ':' + (m.endpointPort || '') : '')]);
            wireRows.push(['Transfer', m.transferRx || m.transferTx
                ? (m.transferTx || '0') + ' up / ' + (m.transferRx || '0') + ' down' : '']);
        }
        if (isLan) wireRows.push(['LAN address', coord(m.lanAddress || m.lanCidr)]);
        else if (m.lanCidr || m.lanAddress) wireRows.push(['LAN', coord(m.lanCidr || m.lanAddress)]);
        wireRows.push(['Docker', m.runsDocker ? (m.dockerPort ? 'Yes — port ' + m.dockerPort : 'Yes') : 'No']);
        wires.appendChild(kv(wireRows));
        body.appendChild(wires);

        // --- danger: rare, and able to undo work ------------------------------------------------------
        //
        // Named by what it does rather than "Advanced", which said only that the operator was unlikely to
        // want it — never that opening it put a working machine at risk.
        if (!isVaierServer) {
            // A device that enrolled from the app holds the only copy of its private key. Reissue would
            // re-render a config nobody can hand to it, and Regenerate would mint a server-side keypair —
            // turning it back into a QR-code peer. Neither is offered; the server refuses the first anyway.
            const deviceHeld = !!(S.peers.get(m.id) || {}).deviceHeldKey;
            const canReissue = S.peers.has(m.id) && !deviceHeld;
            const adv = dangerFold(canReissue ? 'Reissue, regenerate or remove this machine'
                                              : 'Remove this machine');
            if (canReissue) {
                const cfg = el('div', 'ex-lactions is-static');
                cfg.appendChild(selVerb('refresh', 'Reissue config', 'ex-btn', () => reissuePeer(m)));
                cfg.appendChild(selVerb('refresh', 'Regenerate config', 'ex-btn', () => regenerateMachine(m)));
                adv.appendChild(cfg);
                adv.appendChild(hint('Reissue re-hands the same identity’s config. Regenerate replaces the '
                    + 'keypair — a new identity on the VPN — and the old config stops working at once.'));
            } else if (deviceHeld) {
                adv.appendChild(hint('This machine made its own key when it enrolled, so there is no config '
                    + 'here to reissue or regenerate — the private half only ever existed on the device. '
                    + 'To replace the key, remove the machine and enrol it again from the app.'));
            }
            const rm = el('div', 'ex-lactions is-static');
            rm.appendChild(selVerb('trash', 'Remove machine', 'ex-btn is-danger', () => removeMachine(m)));
            adv.appendChild(rm);
            body.appendChild(adv);
        }
        pane.appendChild(body);
    }

    // --- device claim: a browser vouching that it runs on a given machine, so it can report that machine's --
    // position without needing the VPN tunnel up (the tunnel is exactly what tends to be down on a phone).

    // localStorage, not a server setting: this is a per-browser habit ("keep refreshing while I'm the one
    // holding you"), scoped by machine since a browser could plausibly be re-claimed as a different one later.
    const autoShareKey = (machineId) => 'vaier-auto-share-position-' + machineId;
    const autoShareEnabled = (machineId) => localStorage.getItem(autoShareKey(machineId)) === '1';
    const setAutoShare = (machineId, on) => {
        if (on) localStorage.setItem(autoShareKey(machineId), '1');
        else localStorage.removeItem(autoShareKey(machineId));
    };

    // Once claimed: share now, keep it fresh on its own, or forget. Grouped together because the way out
    // (Forget) has to sit exactly where the way in (Share) does — storing a person's whereabouts is not
    // something to make easy going in and hard coming back out of.
    function devicePlacementControls(m) {
        const wrap = el('div');
        const row = el('div', 'ex-lactions is-static');
        row.appendChild(selVerb('locate', 'Share position', 'ex-btn is-accent', () => sharePosition(m)));
        row.appendChild(selVerb('cross', 'Forget', 'ex-btn', () => forgetPosition(m)));
        wrap.appendChild(row);

        const auto = el('label', 'ex-check-row');
        const box = el('input'); box.type = 'checkbox'; box.checked = autoShareEnabled(m.id);
        box.onchange = () => setAutoShare(m.id, box.checked);
        const atxt = el('span');
        atxt.textContent = 'Keep it up to date whenever this browser opens Vaier';
        auto.append(box, atxt);
        wrap.appendChild(auto);

        wrap.appendChild(hint('This browser is ' + m.name + '. Forget erases its position, its trail and this '
            + 'claim together.'));
        return wrap;
    }

    // The assertion itself. Vaier remembers it as an HttpOnly cookie this browser cannot read back — which is
    // why claimedness is always read off GET /vpn/peers/my-device (loadMyDevice), never off document.cookie.
    // Claiming a different machine than the one already held is allowed on purpose — last claim wins, and
    // the operator is never asked to un-claim first — so this always just re-fetches and re-renders.
    async function claimDevice(m) {
        const ok = await confirmModal('This browser is ' + m.name + '?',
            'Vaier will trust this browser to report where ' + m.name + ' actually is, tunnel up or not. '
            + 'Claim it only from the browser that actually runs on ' + m.name + '.', 'Claim');
        if (!ok) return;
        try {
            const res = await fetch('/vpn/peers/' + encodeURIComponent(m.id) + '/device-claim', { method: 'POST' });
            if (!res.ok) { toast('Vaier could not claim this device.'); return; }
            toast('This browser is now ' + m.name + '.');
            await loadMyDevice(); render();
        } catch (e) { toast('Vaier could not claim this device.'); }
    }

    // Each failure mode gets its own honest sentence — no false hope, since "try again" means something
    // different for a declined permission than for a fix that never arrived.
    const GEO_ERROR = {
        1: 'Location sharing was declined — allow it for this site in the browser’s settings to share it.',
        2: 'This device could not work out its own location right now.',
        3: 'Location took too long to arrive — try again, ideally with a clearer view of the sky.',
    };

    function sharePosition(m) {
        if (!navigator.geolocation) { toast('This browser cannot report a location.'); return; }
        navigator.geolocation.getCurrentPosition(
            (pos) => reportPosition(pos.coords, m),
            (err) => toast(GEO_ERROR[err.code] || 'Vaier could not read this device’s location.'),
            { enableHighAccuracy: true, timeout: 20000 });
    }

    // The POST itself, shared by the manual Share-position button and the silent boot-time refresh below — one
    // place decides the body and what happens after, so the two paths can never drift apart on it. `m` is
    // null for the silent path: nothing was directly asked for, so nothing is toasted either way.
    async function reportPosition(coords, m) {
        try {
            const res = await fetch('/vpn/peers/my-position', { method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ latitude: coords.latitude, longitude: coords.longitude,
                    accuracyMetres: coords.accuracy }) });
            // The claim can lapse (revoked, or superseded by a claim made in another browser) — 403 here means
            // exactly that. Resync what THIS browser actually holds rather than leave a stale claimed UI on
            // screen; the manual path also says so, the silent one just quietly stops offering Share.
            if (res.status === 403) {
                await loadMyDevice();
                if (m) toast('This browser has not claimed ' + m.name + ' — claim it again, then share.');
                render();
                return;
            }
            if (!res.ok) { if (m) toast('Vaier could not record that position.'); return; }
            if (m) toast(m.name + '’s position shared.');
            await loadFleet(); render();
        } catch (e) { if (m) toast('Vaier could not record that position.'); }
    }

    // Erases the reported position AND revokes this browser's claim in one action, per the contract — a claim
    // with no way back to "unclaimed" would be a one-way door, and this is the door. Re-fetches my-device
    // alongside the fleet so the claim UI flips back to "This browser is …" without a reload.
    async function forgetPosition(m) {
        const ok = await confirmModal('Forget ' + m.name + '’s position?',
            'This erases ' + m.name + '’s reported position and this browser’s claim on it together. Sharing '
            + 'again starts over with the claim.', 'Forget');
        if (!ok) return;
        try {
            const res = await fetch('/vpn/peers/my-position', { method: 'DELETE' });
            if (!res.ok) {
                // A failed forget can still mean the claim itself lapsed server-side — resync rather than
                // leave Share/Forget on screen for a device this browser no longer holds.
                await loadMyDevice();
                toast('Vaier could not forget that position.');
                render();
                return;
            }
            setAutoShare(m.id, false);
            toast(m.name + '’s position forgotten.');
            await Promise.all([loadFleet(), loadMyDevice()]); render();
        } catch (e) { toast('Vaier could not forget that position.'); }
    }

    // A claimed device's position kept current without a fresh permission prompt (the browser remembers the
    // grant per origin) — but only when the operator explicitly opted in, and always silent: this runs
    // unprompted at boot, so nothing here should ever surface as a toast. S.myDeviceMachineId is the one
    // machine THIS browser holds, so there is never more than one to refresh.
    function maybeAutoSharePosition() {
        if (!navigator.geolocation || !S.myDeviceMachineId || !autoShareEnabled(S.myDeviceMachineId)) return;
        navigator.geolocation.getCurrentPosition(
            (pos) => reportPosition(pos.coords, null),
            () => { /* opportunistic — a denial or timeout here is not worth interrupting anyone about */ },
            { enableHighAccuracy: true, timeout: 20000 });
    }

    // Where each nudge's action goes — the one bit that is a UI concern, not a domain one: the domain says a
    // nudge applies and carries its title + evidence; the shell decides which pane opening lets the operator
    // act on it. PUBLISH → the machine's services (candidates are there to route); BACK_UP → its file browser,
    // where folders are ticked and protected; DESIGNATE → the make-the-backup-server form. Keyed by the
    // domain's nudge kind; an unknown kind falls back to a no-op label so a new kind can never throw here.
    const NUDGE_ACTION = {
        PUBLISH:                 (m) => ({ icon: 'route',   label: 'Publish',          run: () => go(['fleet', m.id, 'services']) }),
        BACK_UP:                 (m) => ({ icon: 'archive', label: 'Choose folders',   run: () => go(['fleet', m.id, 'files']) }),
        DESIGNATE_BACKUP_SERVER: (m) => ({ icon: 'nas',     label: 'Set it up',        run: () => designateBackupServer(m) }),
        // The only nudge whose answer changes what Vaier's login on that machine is allowed to do, so it is
        // the only one that carries a `learn` slug: the operator can read what saying yes grants, on the
        // Concepts page, before answering. `run` is the single grant-and-flag action, never a wizard step.
        BACK_UP_AS_ROOT:         (m) => ({ icon: 'shield',  label: 'Back up everything', run: () => backUpAsRootNow(m.id), learn: 'back-up-as-root' }),
        // The only nudge carrying a value: Vaier read the network off the machine, and the operator is
        // answering whether the fleet should reach it. The CIDR travels on the nudge, so the shell never
        // has to recover it from the sentence it was rendered into.
        ROUTE_LAN:               (m, n) => ({ icon: 'relay',   label: 'Route this network', run: () => routeDetectedLan(m, n.value) }),
    };

    // One nudge, rendered as a quiet invitation: an accent glyph for its kind, the domain's title and the
    // evidence behind it ("the why"), and a single outline action button routed by NUDGE_ACTION.
    function nudgeCard(m, n) {
        const a = (NUDGE_ACTION[n.kind] || (() => ({ icon: 'gear', label: 'Open', run: () => {} })))(m, n);
        const row = el('div', 'ex-nudge');
        row.innerHTML = svg(a.icon, 'ex-nudge-ico');
        const text = el('div', 'ex-nudge-text');
        const title = el('div', 'ex-nudge-title'); title.textContent = n.title;
        const why = el('div', 'ex-nudge-why'); why.textContent = n.evidence;
        text.append(title, why);
        // A nudge that changes what Vaier may do on a machine says where to read what that means. The shell
        // has no deep-link routing and does not need any: the Concepts page already anchors every term by
        // slug, so this is a plain link to it.
        if (a.learn) {
            const more = el('a', 'ex-nudge-more');
            more.href = 'concepts.html#' + a.learn;
            more.target = '_blank';
            more.rel = 'noopener';
            more.textContent = 'What that means \u203a';
            text.appendChild(more);
        }
        row.appendChild(text);
        const btn = el('button', 'ex-btn'); btn.textContent = a.label; btn.onclick = a.run;
        row.appendChild(btn);
        return row;
    }

    // Says yes to a detected network. It goes through the one endpoint that has always routed a LAN — the
    // same one the Advanced CIDR field writes to — so accepting the nudge produces exactly the routing that
    // typing the CIDR by hand produces, with nothing reimplemented alongside it.
    async function routeDetectedLan(m, cidr) {
        const peer = S.peers.get(m.id);
        if (!peer || !cidr) { toast('Vaier cannot route that network.'); return; }
        const ok = await patchJson('/vpn/peers/' + encodeURIComponent(peer.id) + '/lan-cidr',
            { lanCidr: cidr }, 'Could not route that network — another machine may already carry it.');
        if (!ok) return;
        S.nudges.delete(m.id);   // the machine now routes it; the suggestion has been answered
        await loadFleet();
        toast('The fleet can now reach ' + cidr + ' through ' + m.name + '.');
    }

    // --- editing a machine, and its SSH access ---------------------------------------------------------

    function editMachine(m) {
        editMachineForm(m).then((vals) => { if (vals) saveMachineEdits(m, vals); });
    }

    // Saves only what changed, each field to its own endpoint (the same ones the Infrastructure page used), and
    // renames last because a rename changes the key every other edit keyed off. Peers are addressed by id, LAN
    // servers by name. Then the fleet reloads and we follow the machine to its — possibly new — name.
    async function saveMachineEdits(m, v) {
        const isLan = m.type === 'LAN_SERVER';
        const peer = S.peers.get(m.id);
        const pid = peer ? peer.id : null;
        if (!isLan && !pid) { toast('Vaier cannot edit that machine.'); return; }
        const enc = encodeURIComponent;
        // A LAN server is addressed by its identity; a peer by its WireGuard id, which a rename never moves.
        const base = isLan ? '/lan-servers/' + enc(m.id) : '/vpn/peers/' + enc(pid);
        const rec = S.peers.get(m.id) || S.lan.get(m.id) || {};
        let ok = true, renamedTo = m.name;

        if (v.description !== (rec.description || '')) {
            ok = await patchJson(base + '/description', { description: v.description },
                'Could not save the description.') && ok;
        }
        // LAN address and CIDR are a relay-capable peer's to edit — the endpoints exist only for peers, and
        // only a machine that could relay a network routes a subnet. `canRelayALan` is the domain's answer,
        // carried on the machine; the shell no longer re-derives it from a type set of its own.
        if (m.canRelayALan) {
            if (v.lanAddress !== (m.lanAddress || '')) {
                ok = await patchJson(base + '/lan-address', { lanAddress: v.lanAddress },
                    'Could not save the LAN address.') && ok;
            }
            if (v.lanCidr !== (m.lanCidr || '')) {
                ok = await patchJson(base + '/lan-cidr', { lanCidr: v.lanCidr },
                    'Could not save the LAN behind it.') && ok;
            }
        }
        if (v.deviceCategory !== (m.deviceCategory || '')) {
            ok = await patchJson(base + '/device-category', { deviceCategory: v.deviceCategory },
                'Could not save the device category.') && ok;
        }
        if (v.name && v.name !== m.name) {
            const url = isLan ? '/lan-servers/' + enc(m.id) : '/vpn/peers/' + enc(pid);
            const renamed = await patchJson(url, { newName: v.name },
                'Could not rename the machine — is the name taken?');
            ok = renamed && ok;
            if (renamed) renamedTo = v.name;
        }
        await loadFleet();
        await loadServices();   // a rename shifts the host key services hang under
        if (ok) toast(m.name + ' updated.');
        // Renaming no longer moves the machine's coordinate — its identity is what the address stands on, so
        // the pane simply re-labels itself where it is.
        go(['fleet', m.id]);
    }

    // The edit form: a machine's human details, resolved as a body or null. A server peer also gets its LAN
    // address and CIDR; every machine gets a device category. Keys and tunnel address are never asked — they
    // are Vaier's.
    function editMachineForm(m) {
        return new Promise((resolve) => {
            const rec = S.peers.get(m.id) || S.lan.get(m.id) || {};
            const isServerPeer = m.canRelayALan;
            const scrim = el('div', 'ex-scrim is-on');
            const dialog = el('div', 'ex-dialog');
            const h = el('div', 'ex-dialog-title'); h.textContent = 'Edit ' + m.name;
            const sub = el('div', 'ex-dialog-body');
            sub.textContent = 'What Vaier knows about this machine. Its keys and tunnel address are Vaier’s to '
                + 'keep — everything here is yours to name.';
            const form = el('div', 'ex-form');
            const field = (label, hint, control) => {
                const f = el('div', 'ex-field'); const l = el('label'); l.textContent = label; f.append(l, control);
                if (hint) { const hn = el('div', 'ex-hint'); hn.textContent = hint; f.appendChild(hn); }
                return f;
            };
            const text = (val, ph) => { const i = el('input', 'ex-input'); i.type = 'text';
                i.value = val == null ? '' : String(val); if (ph) i.placeholder = ph;
                i.autocomplete = 'off'; i.spellcheck = false; return i; };
            const name = text(m.name);
            const desc = text(rec.description || '');
            const lanAddr = text(m.lanAddress || '', 'e.g. 192.168.1.10');
            const lanCidr = text(m.lanCidr || '', 'e.g. 192.168.1.0/24');
            const cat = catSelect(m.deviceCategory);
            form.append(field('Name', null, name), field('Description', 'Optional.', desc));
            if (isServerPeer) {
                form.append(field('LAN address', 'Where this server answers on its own network.', lanAddr));
            }
            form.append(field('Device category', 'Its shape in the fleet and on the map. Auto-detect lets Vaier '
                + 'choose from what it sees.', cat));
            if (isServerPeer) {
                // Vaier reads the network off the machine and offers it on the machine's own pane, so this
                // field is no longer how the question gets answered — it is the escape hatch for the case
                // nothing can detect: a machine fronting a second subnet. Folded, and it says why.
                const adv = disclosure('The network behind this machine');
                adv.appendChild(field('Network behind it', 'Vaier reads this off the machine and offers it on '
                    + 'the machine’s page. Set it by hand only for a network it cannot see — a second subnet '
                    + 'this machine also fronts.', lanCidr));
                // A machine that already routes a network shows it rather than hiding state behind a fold.
                if (m.lanCidr) adv.open = true;
                form.appendChild(adv);
            }

            const actions = el('div', 'ex-dialog-actions');
            const cancel = el('button', 'ex-btn'); cancel.textContent = 'Cancel';
            const ok = el('button', 'ex-btn is-accent'); ok.textContent = 'Save';
            actions.append(cancel, ok);
            dialog.append(h, sub, form, actions);
            scrim.appendChild(dialog); document.body.appendChild(scrim);

            const close = (r) => { scrim.remove(); document.removeEventListener('keydown', onKey); resolve(r); };
            const onKey = (e) => { if (e.key === 'Escape') close(null); };
            const sync = () => { ok.disabled = name.value.trim() === ''; };
            name.oninput = sync;
            scrim.onclick = (e) => { if (e.target === scrim) close(null); };
            cancel.onclick = () => close(null);
            ok.onclick = () => {
                if (!name.value.trim()) return;
                close({ name: name.value.trim(), description: desc.value.trim(),
                    lanAddress: lanAddr.value.trim(), lanCidr: lanCidr.value.trim(), deviceCategory: cat.value });
            };
            document.addEventListener('keydown', onKey);
            sync(); name.focus();
        });
    }

    // Whether Vaier may open an SSH session to a machine at all — the switch above the credential. Distinct from
    // the credential itself: this is the capability, that is the login. Optimistic, with the checkbox reverted if
    // the server refuses; on success the fleet reloads so the files/shell/disk entries appear or vanish with it.
    async function toggleSshAccess(machineId, enabled, checkbox) {
        checkbox.disabled = true;
        const ok = await patchJson('/machines/' + encodeURIComponent(machineId) + '/ssh-access', { enabled },
            'Could not update SSH access.');
        if (!ok) { checkbox.checked = !enabled; checkbox.disabled = false; return; }
        await loadFleet();
        toast(enabled ? 'Vaier can now open SSH sessions to ' + nameOf(machineId) + '.'
                      : 'SSH sessions to ' + nameOf(machineId) + ' turned off.');
        render();
    }

    // Regenerate a peer's config — delete the peer and recreate it, which mints a fresh keypair (#202). Unlike
    // reissue, this is a new identity on the VPN: the old config is dead the instant the peer is deleted, so it
    // asks first and shows the new config once, exactly as a new machine does. Recreated from what /machines and
    // the peer record already hold, so the machine keeps its name, type, LAN and description.
    async function regenerateMachine(m) {
        const peer = S.peers.get(m.id);
        if (!peer) return;
        const ok = await confirmModal('Regenerate ' + m.name + '’s config?',
            'Vaier deletes ' + m.name + '’s WireGuard peer and recreates it with a fresh keypair — a brand-new '
            + 'identity on the VPN. The current config stops working the moment the peer is deleted, and ' + m.name
            + ' can only reconnect once the new one is installed. Reissue keeps the same keys; regenerate '
            + 'replaces them. Only regenerate when the keypair itself must change.', 'Regenerate');
        if (!ok) return;
        const rec = S.peers.get(m.id) || {};
        const recreate = { name: m.name, peerType: m.type, lanCidr: m.lanCidr || null,
            lanAddress: m.lanAddress || null, description: rec.description || null };
        try {
            const del = await fetch('/vpn/peers/' + encodeURIComponent(peer.id), { method: 'DELETE' });
            if (!del.ok && del.status !== 204) { toast('Vaier could not regenerate ' + m.name + '.'); return; }
            const res = await fetch('/vpn/peers', { method: 'POST',
                headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(recreate) });
            if (!res.ok) {
                const e = await res.json().catch(() => ({}));
                toast(e.message || 'Vaier deleted the old peer but could not recreate it.');
                await loadFleet(); render(); return;
            }
            const created = await res.json();
            await loadFleet();
            toast(m.name + '’s config regenerated — save the new config now.');
            createResult(created);   // the same one-shot config view a new machine gets
        } catch (e) {
            toast('Vaier could not regenerate ' + m.name + '.');
        }
    }

    // Mint a single-use, ~15-min setup token for a LAN host, so the curl one-liner authorizes itself on a
    // bare box with no Vaier session — exactly like a peer's. Returns the token, or null if minting failed.
    // Mint a single-use setup token for a LAN host, or learn there is nothing to set up. Returns
    // { needed: true, token } when the host has a setup script, { needed: false } when it has none
    // (204 — runs no Docker, anchors no LAN), or null on a real failure.
    async function mintLanSetupToken(machineId) {
        try {
            const res = await fetch('/lan-servers/' + encodeURIComponent(machineId) + '/setup-token',
                { method: 'POST' });
            if (res.status === 204) return { needed: false };
            if (!res.ok) return null;
            const j = await res.json();
            return j.token ? { needed: true, token: j.token } : null;
        } catch (e) { return null; }
    }

    // A LAN host's setup script. Vaier mints a single-use token and hands over the curl one-liner to run on the
    // host: the box pulls the script over HTTPS from Vaier's token-gated /setup route. It needs sudo because it
    // installs Docker. The in-console setup.sh download stays a by-hand fallback. (A VPN peer's setup script is
    // shown once with its config; reissue or regenerate to see it again.)
    async function lanSetupScript(machineId) {
        const machineName = nameOf(machineId);
        const mint = await mintLanSetupToken(machineId);
        if (!mint) { toast('Vaier could not prepare the setup command for ' + machineName + '.'); return; }
        if (!mint.needed) { toast('Nothing to set up on ' + machineName + ' — Vaier manages it as-is.'); return; }
        const origin = window.location.origin;
        const runUrl = origin + '/lan-servers/' + encodeURIComponent(machineId)
            + '/setup?t=' + encodeURIComponent(mint.token);
        setupScriptDialog({
            title: 'Setup command for ' + machineName,
            body: 'Run this on ' + machineName + ' itself (it needs sudo) to open its Docker engine API to Vaier '
                + 'and install the routes that let it reach the rest of the fleet. It’s idempotent — safe to '
                + 'run once at onboarding or again later, e.g. after rebuilding the host. The link works once.',
            curl: "curl -fsSL '" + runUrl + "' | sudo bash",
            downloadUrl: origin + '/lan-servers/' + encodeURIComponent(machineId) + '/setup.sh',
        });
    }

    function setupScriptDialog(opts) {
        const scrim = el('div', 'ex-scrim is-on');
        const dialog = el('div', 'ex-dialog is-wide');
        const h = el('div', 'ex-dialog-title'); h.textContent = opts.title;
        const sub = el('div', 'ex-dialog-body'); sub.textContent = opts.body;
        dialog.append(h, sub);
        const pre = el('pre', 'ex-config'); pre.textContent = opts.curl; dialog.appendChild(pre);
        const row = el('div', 'ex-set-actions');
        const copy = el('button', 'ex-btn is-accent'); copy.textContent = 'Copy command';
        copy.onclick = () => navigator.clipboard.writeText(opts.curl)
            .then(() => toast('Command copied.')).catch(() => toast('Could not copy the command.'));
        const dl = el('button', 'ex-btn'); dl.textContent = 'Download setup.sh';
        dl.onclick = () => { window.location.href = opts.downloadUrl; };
        row.append(copy, dl); dialog.appendChild(row);
        const actions = el('div', 'ex-dialog-actions');
        const done = el('button', 'ex-btn'); done.textContent = 'Done'; actions.appendChild(done);
        dialog.appendChild(actions);
        scrim.appendChild(dialog); document.body.appendChild(scrim);
        const close = () => { scrim.remove(); document.removeEventListener('keydown', onKey); };
        const onKey = (e) => { if (e.key === 'Escape') close(); };
        done.onclick = close; scrim.onclick = (e) => { if (e.target === scrim) close(); };
        document.addEventListener('keydown', onKey);
    }

    // --- adding and removing a machine -----------------------------------------------------------------
    //
    // A machine is a WireGuard peer. Adding one asks a name and a type (and, for a server, the LAN behind it);
    // Vaier assigns the keys and the tunnel address, and hands back the config to install — shown once, because
    // the download endpoints are one-shot. Removing deletes the peer (or a LAN-server registration) behind the
    // typed-name gate. LAN servers are usually found by the scan, so the add form offers the peer kinds.

    // Adding a machine is one flow with one fork Vaier can't infer: a new peer (Vaier hands it a config) or a
    // server already on one of your networks (Vaier scans, finds it, and adopts it). The peer branch hands off
    // to the existing peer form unchanged; the LAN-server branch discovers and adopts. Everything else — the
    // address, the kind, the Docker port, the site it sits behind — Vaier already knows, so it is never typed.
    function addMachine() { addMachineFork('fork', null); }

    // A join code the Vaier app handed to this browser — "Approve here" on the operator's own phone. It rides the
    // QUERY string, never a fragment: oauth2-proxy bounces the browser through Google and back, and a fragment
    // is never sent to a server, so it would be gone on arrival.
    function pendingApproval() {
        const q = new URLSearchParams(location.search);
        const code = (q.get('approve') || '').trim();
        if (!code) return null;
        // Asked once — rewritten in place with replaceState because changing the query with location.replace
        // would reload the document and arrive with nothing to do.
        q.delete('approve');
        const rest = q.toString();
        history.replaceState(null, '', location.pathname + (rest ? '?' + rest : '') + location.hash);
        return code;
    }

    // One modal, internal screens — the fork Vaier can't infer, then (LAN-server branch) discover + adopt, with
    // a quiet by-address fallback for empty scans. Optionally opens straight on a screen
    // (the fleet's "Discovered on the LAN" list jumps in at 'adopt' for a chosen candidate). Cached-first and
    // push-driven: candidates show instantly from the last scan, and a finished scan repaints the list over the
    // lan-scan-updated stream the shell already holds — never a timer, never a poll.
    function addMachineFork(initialScreen, initialCandidate, initialEnrolment) {
        const scrim = el('div', 'ex-scrim is-on');
        const dialog = el('div', 'ex-dialog is-wide');
        const titleEl = el('div', 'ex-dialog-title');
        const content = el('div');                 // repainted per screen
        dialog.append(titleEl, content);
        scrim.appendChild(dialog);
        document.body.appendChild(scrim);

        let cand = initialCandidate || null;        // the candidate being adopted
        let pickedLan = null;                       // the LAN chosen on pickLan: { anchor, name, cidr }
        let peerIntent = null;                      // the peer branch's intent: 'SERVER' | 'PERSONAL_DEVICE'
        let peerWindows = false;                    // whether the peer runs Windows — the OS second step's answer
        let peerCreated = null;                     // the create response, held for the handoff screen
        let adopted = null;                         // the adopt result, held for the LAN handoff screen: { name, credNote }
        const enrolment = initialEnrolment || null; // the phone's enrolment request: { code, name, publicKey }
        let enrolled = null;                        // the enrol response, held for the handoff screen

        const close = () => {
            _lanScanModalRefresh = null;
            scrim.remove();
            document.removeEventListener('keydown', onKey);
        };
        const onKey = (e) => { if (e.key === 'Escape') close(); };
        scrim.onclick = (e) => { if (e.target === scrim) close(); };
        document.addEventListener('keydown', onKey);

        const field = (label, hint, control) => {
            const f = el('div', 'ex-field');
            const l = el('label'); l.textContent = label; f.append(l, control);
            if (hint) { const hn = el('div', 'ex-hint'); hn.textContent = hint; f.appendChild(hn); }
            return f;
        };
        const choiceCard = (iconName, heading, desc) => {
            const b = el('button', 'ex-choice');
            const g = el('div', 'ex-choice-glyph'); g.innerHTML = svg(iconName, 'ex-choice-svg');
            const h = el('h4'); h.textContent = heading;
            const p = el('p'); p.textContent = desc;
            b.append(g, h, p);
            return b;
        };
        const actionsRow = () => el('div', 'ex-dialog-actions');
        // One line of the "Vaier will generate" block: what it is, and what Vaier fills in.
        const drow = (k, v, accent) => {
            const r = el('div', 'ex-drow');
            const kk = el('span', 'ex-drow-k'); kk.textContent = k;
            const vv = el('span', 'ex-drow-v' + (accent ? ' is-accent' : '')); vv.textContent = v;
            r.append(kk, vv); return r;
        };
        // The "Vaier will generate" panel, shared by the peer form and an enrolment.
        const generatedBlock = (rows) => {
            const det = el('div', 'ex-detected');
            const head = el('div', 'ex-detected-head');
            head.appendChild(el('span', 'ex-detected-check')).textContent = '✓';
            const ht = el('span'); ht.textContent = 'Vaier will generate'; head.appendChild(ht);
            det.appendChild(head);
            rows.forEach((r) => det.appendChild(r));
            return det;
        };

        // The screen router. The refresh hook is armed only on the two screens that read live scan state —
        // the LAN picker (counts) and the discover list — so a scan settling elsewhere never repaints under
        // the operator.
        function screen(id) {
            _lanScanModalRefresh = (id === 'discover') ? paintDiscover
                : (id === 'pickLan') ? paintPickLan : null;
            if (id === 'fork') paintFork();
            else if (id === 'pickLan') paintPickLan();
            else if (id === 'discover') paintDiscover();
            else if (id === 'adopt') paintAdopt();
            else if (id === 'lanHandoff') paintLanHandoff();
            else if (id === 'byaddress') paintByAddress();
            else if (id === 'peerWhat') paintPeerWhat();
            else if (id === 'peerOs') paintPeerOs();
            else if (id === 'peerName') paintPeerName();
            else if (id === 'peerHandoff') paintPeerHandoff();
            else if (id === 'enrol') paintEnrol();
            else if (id === 'enrolHandoff') paintEnrolHandoff();
        }

        // The LAN a candidate sits on, for jumping into discover from an adopt opened directly off the fleet
        // page. Prefer the real scannable-LAN entry (it carries the CIDR); synthesise one from the anchor
        // otherwise so Back still works before the LAN list has loaded.
        function lanForAnchor(anchor) {
            return (S.lanScanLans || []).find((l) => l.anchor === anchor)
                || { anchor: anchor, name: relayName(anchor) || anchor, cidr: null };
        }

        // ---- fork: the one question Vaier can't infer ---------------------------------------------------
        function paintFork() {
            titleEl.textContent = 'Add a machine';
            content.innerHTML = '';
            const sub = el('div', 'ex-dialog-body');
            sub.textContent = 'Vaier fills in everything it can. Answer the one thing it can’t infer: a new peer '
                + 'that connects through the VPN, or a server already on one of your networks?';
            const grid = el('div', 'ex-choice-grid');
            const peer = choiceCard('relay', 'A peer',
                'A new box or device that connects through Vaier’s VPN. Gets a tunnel address, keys and a config '
                + 'to install.');
            peer.onclick = () => screen('peerWhat');
            const lan = choiceCard('server', 'A LAN server',
                'A machine already running on one of your networks. Vaier scans, finds it, and adopts it.');
            lan.onclick = () => { screen('pickLan'); };
            grid.append(peer, lan);
            content.append(sub, section('What are you adding?'), grid);
            // Read the snapshot now so the cached candidates are ready the moment they pick the LAN-server
            // branch — never a wait on the next screen.
            if (S.lanScan === null) loadLanScan();
        }

        // ---- pickLan: choose which LAN to scan first (small page, targeted scan) ------------------------
        // The operator always picks a LAN before Vaier sweeps it, so no screen ever renders every LAN's finds
        // at once and no scan fans out over the whole fleet. Each row is one "via <name>" network with its
        // CIDR and, from the cached snapshot, how many candidates it holds and when it was last scanned.
        function paintPickLan() {
            titleEl.textContent = 'Add a LAN server';
            content.innerHTML = '';
            const sub = el('div', 'ex-dialog-body');
            sub.textContent = 'Pick the network to look on. Vaier scans that one LAN — quicker than sweeping them '
                + 'all — and shows what it finds.';
            content.appendChild(sub);

            if (S.lanScanLans === null) {
                loadLanScanLans();
                content.appendChild(note('Reading your networks…', false));
                content.appendChild(pickFoot());
                return;
            }
            const lans = S.lanScanLans || [];
            const snap = S.lanScan;
            if (lans.length) {
                const list = el('div', 'ex-lanpick');
                lans.forEach((lan) => {
                    const count = (snap && snap.machines)
                        ? snap.machines.filter((d) => d.relayAnchor === lan.anchor && !d.ignored).length : 0;
                    list.appendChild(lanPickRow(lan, count, snap));
                });
                content.appendChild(list);
            } else {
                // A zero state, so it has to name the thing to go and do — and the by-address path is not it:
                // an address Vaier cannot route to is refused, and with no networks here there is none.
                content.appendChild(note('No networks to scan yet. Add a peer on the network you want to '
                    + 'reach and tell Vaier the network behind it — Vaier can only look where a machine of '
                    + 'yours already is.', false));
            }
            content.appendChild(pickFoot());
        }

        // One LAN row on the picker: "via <name>", the CIDR, the cached find-count and last-scanned time.
        function lanPickRow(lan, count, snap) {
            const row = el('button', 'ex-lanpick-row');
            const ic = el('span', 'ex-disc-icon'); ic.innerHTML = svg('route', 'ex-disc-svg');
            const info = el('div', 'ex-disc-info');
            const line = el('div', 'ex-disc-line');
            const nm = el('span', 'ex-disc-name'); nm.textContent = 'via ' + lan.name;
            line.appendChild(nm);
            const meta = el('span', 'ex-disc-meta');
            const bits = [];
            if (lan.cidr) bits.push(lan.cidr);
            bits.push(count === 1 ? '1 found' : count + ' found');
            bits.push(snap && snap.lastScanCompleted ? 'scanned ' + timeAgo(snap.lastScanCompleted) : 'not scanned yet');
            meta.textContent = bits.join(' · ');
            info.append(line, meta);
            const go = el('span', 'ex-lanpick-go'); go.innerHTML = svg('chev', 'ex-disc-svg');
            row.append(ic, info, go);
            row.onclick = () => { pickedLan = lan; screen('discover'); };
            return row;
        }

        // The LAN picker's foot: a way back to the fork, and the quiet by-address fallback.
        function pickFoot() {
            const foot = actionsRow();
            foot.style.justifyContent = 'space-between';
            const left = el('div', 'ex-lactions is-static');
            const back = el('button', 'ex-btn'); back.textContent = 'Back';
            back.onclick = () => screen('fork');
            left.appendChild(back);
            const byAddr = el('button', 'ex-btn is-quiet'); byAddr.textContent = 'Add by address instead';
            byAddr.onclick = () => screen('byaddress');
            foot.append(left, byAddr);
            return foot;
        }

        // ---- discover: one LAN's cached candidates, live over SSE -------------------------------------
        // Scoped to the LAN picked on the previous screen: only its finds render (a small list that paints
        // instantly from cache) and Rescan sweeps only that LAN. Repaints in place over the lan-scan-updated
        // stream — never a timer, never a poll.
        function paintDiscover() {
            if (!pickedLan) { screen('pickLan'); return; }
            titleEl.textContent = 'Add a LAN server — via ' + pickedLan.name;
            content.innerHTML = '';
            const snap = S.lanScan;
            if (snap === null) {
                loadLanScan();
                content.appendChild(note('Looking for machines on ' + pickedLan.name + '…', false));
                content.appendChild(discoverFoot());
                return;
            }

            const scanning = snap.status === 'SCANNING';
            const found = (snap.machines || [])
                .filter((d) => d.relayAnchor === pickedLan.anchor && !d.ignored);

            const meta = el('div', 'ex-scanmeta');
            const ago = el('span', 'ex-scanmeta-ago');
            ago.appendChild(el('span', 'ex-scanmeta-dot' + (scanning ? ' is-live' : '')));
            const agoTxt = el('span');
            agoTxt.textContent = scanning ? 'Scanning ' + pickedLan.name + '…'
                : (snap.lastScanCompleted ? 'Last scan ' + timeAgo(snap.lastScanCompleted) : 'Not scanned yet');
            ago.appendChild(agoTxt);
            const rescan = selVerb('refresh', scanning ? 'Scanning…' : 'Rescan this LAN', 'ex-btn',
                () => { scanLan(pickedLan.anchor); paintDiscover(); });
            if (scanning) rescan.disabled = true;
            meta.append(ago, rescan);
            content.appendChild(meta);

            if (pickedLan.cidr) {
                const where = el('div', 'ex-hint'); where.textContent = 'via ' + pickedLan.name + ' · ' + pickedLan.cidr;
                content.appendChild(where);
            }

            if (found.length) {
                const list = el('div', 'ex-disc');
                found.sort((a, b) => discoveredRank(a) - discoveredRank(b)
                    || ipOrder(a.ipAddress).localeCompare(ipOrder(b.ipAddress)));
                found.forEach((d) => list.appendChild(discoveredRow(d, [
                    selVerb('server', 'Add', 'ex-btn is-accent', () => { cand = d; screen('adopt'); }),
                    selVerb('cross', 'Ignore', 'ex-btn', () => ignoreDiscovered(d)),
                ])));
                content.appendChild(list);
            } else {
                content.appendChild(note(scanning
                    ? 'Looking across ' + pickedLan.name + ' — this can take a minute.'
                    : 'No unregistered machines found on this LAN. Rescan to look again.', false));
            }
            // Ignoring has to be undoable from the same screen that does it. This list hides ignored finds, so
            // without a way back a dismissed host would be invisible forever with no UI left to restore it —
            // which is exactly what happened when discovery had a second home on the fleet page and this was
            // the only screen left after it went.
            const ignoredHere = (snap.machines || [])
                .filter((d) => d.relayAnchor === pickedLan.anchor && d.ignored);
            if (ignoredHere.length) {
                const toggle = el('div', 'ex-lactions is-static');
                toggle.appendChild(selVerb('chev',
                    (_showIgnoredLan ? 'Hide ignored' : 'Show ignored') + ' (' + ignoredHere.length + ')',
                    'ex-btn' + (_showIgnoredLan ? ' is-open' : ''),
                    () => { _showIgnoredLan = !_showIgnoredLan; paintDiscover(); }));
                content.appendChild(toggle);
                if (_showIgnoredLan) {
                    const list = el('div', 'ex-disc is-ignored');
                    ignoredHere.forEach((d) => list.appendChild(discoveredRow(d, [
                        selVerb('check', 'Unignore', 'ex-btn', () => unignoreDiscovered(d)),
                    ])));
                    content.appendChild(list);
                }
            }
            content.appendChild(discoverFoot());
        }

        // The discover screen's foot: back to the LAN picker, and the quiet by-address fallback.
        function discoverFoot() {
            const foot = actionsRow();
            foot.style.justifyContent = 'space-between';
            const left = el('div', 'ex-lactions is-static');
            const back = el('button', 'ex-btn'); back.textContent = 'Back';
            back.onclick = () => screen('pickLan');
            left.appendChild(back);
            const byAddr = el('button', 'ex-btn is-quiet'); byAddr.textContent = 'Add by address instead';
            byAddr.onclick = () => screen('byaddress');
            foot.append(left, byAddr);
            return foot;
        }

        // ---- adopt: one editable name, everything else Vaier already probed -----------------------------
        function paintAdopt() {
            const d = cand;
            // Opened straight from the fleet's "Discovered on the LAN" list (no LAN picked), so seed the LAN
            // this candidate sits on — Back then returns to that LAN's discover list, not a dead end.
            if (!pickedLan && d) pickedLan = lanForAnchor(d.relayAnchor);
            const suggested = registerName(d);
            titleEl.textContent = 'Add — ' + suggested;
            content.innerHTML = '';

            const name = el('input', 'ex-input'); name.type = 'text'; name.value = suggested;
            name.autocomplete = 'off'; name.spellcheck = false;
            content.appendChild(field('Name', 'The only thing Vaier can’t infer — what to call it.', name));

            // The read-only readout of what the scan already knows. None of it is typed.
            const det = el('div', 'ex-detected');
            const head = el('div', 'ex-detected-head');
            head.appendChild(el('span', 'ex-detected-check')).textContent = '✓';
            const ht = el('span'); ht.textContent = 'Detected by Vaier — no need to enter'; head.appendChild(ht);
            det.appendChild(head);
            const drow = (k, v, accent) => {
                const r = el('div', 'ex-drow');
                const kk = el('span', 'ex-drow-k'); kk.textContent = k;
                const vv = el('span', 'ex-drow-v' + (accent ? ' is-accent' : '')); vv.textContent = v;
                r.append(kk, vv); return r;
            };
            det.appendChild(drow('Kind', discoveredLabel(d)));
            det.appendChild(drow('LAN address', d.ipAddress));
            const via = relayName(d.relayAnchor);
            if (via) det.appendChild(drow('Reached via', via));
            const dockerPort = (d.openPorts || []).find((p) => p === 2375 || p === 2376);
            if (dockerPort) det.appendChild(drow('Docker API', ':' + dockerPort + ' open', true));
            const relay = relayMachine(d.relayAnchor);
            if (relay && relay.lanCidr) det.appendChild(drow('Cross-site route', relay.lanCidr));
            content.appendChild(det);

            // Optional SSH access — the same login the web terminal, disk watch and backups ride on. Offered
            // only when the host actually answered on port 22 (Vaier probed it): a machine that doesn't speak
            // SSH is adopted without a credential, so the fields never appear for it. Tested live against the
            // host before it is stored, so a wrong password is caught here, not later in the dark.
            let draft = () => null;
            let credentialFilled = () => false;
            if (d.sshAvailable) {
                const disc = disclosure('Add SSH access — for the web terminal, disk watch & backups');
                const username = el('input', 'ex-input'); username.type = 'text'; username.autocomplete = 'off';
                username.spellcheck = false; username.placeholder = 'e.g. admin';
                const method = el('select', 'ex-input');
                [['PASSWORD', 'Password'], ['PRIVATE_KEY', 'Private key']].forEach(([v, t]) => {
                    const o = el('option'); o.value = v; o.textContent = t; method.appendChild(o);
                });
                const password = el('input', 'ex-input'); password.type = 'password'; password.autocomplete = 'new-password';
                const keyArea = el('textarea', 'ex-input ex-cred-key'); keyArea.rows = 4;
                keyArea.placeholder = '-----BEGIN OPENSSH PRIVATE KEY-----'; keyArea.spellcheck = false;
                const passphrase = el('input', 'ex-input'); passphrase.type = 'password'; passphrase.autocomplete = 'new-password';
                const pwF = field('Password', null, password);
                const keyF = field('Private key (PEM)', null, keyArea);
                const passF = field('Key passphrase', 'Optional.', passphrase);
                const syncMethod = () => {
                    const isKey = method.value === 'PRIVATE_KEY';
                    pwF.style.display = isKey ? 'none' : '';
                    keyF.style.display = isKey ? '' : 'none';
                    passF.style.display = isKey ? '' : 'none';
                };
                method.onchange = syncMethod;
                const testRow = el('div', 'ex-testrow');
                const testBtn = el('button', 'ex-btn'); testBtn.textContent = 'Test connection';
                const testOk = el('span', 'ex-testok'); testOk.textContent = '✓ Reached & authenticated';
                testRow.append(testBtn, testOk);
                disc.append(field('User', null, username), field('Auth', null, method), pwF, keyF, passF, testRow);
                syncMethod();
                content.appendChild(disc);

                draft = () => ({ username: username.value.trim(), authMethod: method.value,
                    secret: (method.value === 'PRIVATE_KEY' ? keyArea.value : password.value),
                    passphrase: passphrase.value || null });
                credentialFilled = () => disc.open && username.value.trim() && draft().secret.trim();

                testBtn.onclick = async () => {
                    if (!username.value.trim() || !draft().secret.trim()) { toast('Enter a username and the secret to test.'); return; }
                    testOk.classList.remove('is-on');
                    testBtn.disabled = true; const was = testBtn.textContent; testBtn.textContent = 'Testing…';
                    try {
                        const r = await fetch('/lan-scan/' + encodeURIComponent(d.ipAddress) + '/ssh-credential/test', {
                            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(draft()) });
                        if (!r.ok) { toast('Vaier could not test that login.'); return; }
                        const v = await r.json();
                        if (v.authenticated) { testOk.classList.add('is-on'); }
                        else if (v.reachable) { toast('Reached ' + d.ipAddress + ', but that login was refused.'); }
                        else { toast('Vaier could not reach ' + d.ipAddress + ' over SSH.'); }
                    } catch (e) { toast('Vaier could not test that login.'); }
                    finally { testBtn.disabled = false; testBtn.textContent = was; }
                };
            }

            const actions = actionsRow();
            const back = el('button', 'ex-btn'); back.textContent = 'Back';
            back.onclick = () => screen('discover');
            const add = el('button', 'ex-btn is-accent'); add.textContent = 'Add machine';
            add.onclick = () => doAdopt(name.value.trim(), credentialFilled() ? draft() : null, add);
            const sync = () => { add.disabled = name.value.trim() === ''; };
            name.oninput = sync; sync();
            actions.append(back, add);
            content.appendChild(actions);
            name.focus(); name.select();
        }

        // Adopt probes and (optionally) SSH-verifies server-side, so it is slow — the button goes busy for the
        // whole round trip rather than sitting inert, and is only re-enabled if we stay on this screen.
        async function doAdopt(nameOverride, credential, addBtn) {
            const bodyObj = { nameOverride: nameOverride || null };
            if (credential) bodyObj.credential = credential;
            const was = addBtn ? addBtn.textContent : null;
            if (addBtn) { addBtn.disabled = true; addBtn.textContent = 'Adding…'; }
            const restore = () => { if (addBtn) { addBtn.disabled = false; addBtn.textContent = was; } };
            try {
                const res = await fetch('/lan-scan/' + encodeURIComponent(cand.ipAddress) + '/adopt', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(bodyObj) });
                if (!res.ok) { const e = await res.json().catch(() => ({})); toast(e.message || 'Vaier could not add that machine.'); restore(); return; }
                const resp = await res.json();
                await loadFleet(); await loadLanScan();
                const credNote = resp.credentialProvided && !resp.credentialStored
                    ? ' Its SSH login couldn’t be saved — set one from the machine.' : '';
                adopted = { machineId: resp.machineId, name: resp.name, credNote: credNote };
                screen('lanHandoff');
            } catch (e) { toast('Vaier could not add that machine.'); restore(); }
        }

        // ---- LAN handoff — the machine is registered; hand over the command that lets Vaier manage it -----
        // Parity with the peer handoff: instead of jumping straight to the machine, show the copyable
        // curl one-liner the operator runs on the host (with sudo — it installs Docker), gated by a fresh
        // single-use token. The by-hand setup.sh download stays as a fallback. Done closes and navigates.
        async function paintLanHandoff() {
            const a = adopted;
            if (!a) { screen('fork'); return; }
            titleEl.textContent = a.name + ' — let Vaier manage it';
            content.innerHTML = '';

            const mint = await mintLanSetupToken(a.machineId);
            const sub = el('div', 'ex-dialog-body');
            if (mint && mint.needed) {
                sub.textContent = a.name + ' is registered.' + (a.credNote || '') + ' To let Vaier manage its '
                    + 'containers, run this on ' + a.name + ' — it needs sudo:';
                content.appendChild(sub);

                const curl = "curl -fsSL '" + window.location.origin + '/lan-servers/'
                    + encodeURIComponent(a.machineId) + '/setup?t=' + encodeURIComponent(mint.token)
                    + "' | sudo bash";
                content.appendChild(copyableCommand(curl));

                const fb = el('details', 'ex-fallback');
                const summ = el('summary');
                summ.textContent = 'Prefer to run it by hand? Download the setup script.';
                fb.appendChild(summ);
                const dl = el('button', 'ex-btn'); dl.textContent = 'Download setup.sh';
                dl.onclick = () => { window.location.href = window.location.origin + '/lan-servers/'
                    + encodeURIComponent(a.machineId) + '/setup.sh'; };
                fb.appendChild(dl);
                content.appendChild(fb);
            } else if (mint && !mint.needed) {
                sub.textContent = a.name + ' is registered.' + (a.credNote || '')
                    + ' Vaier can manage it as-is — nothing to install on the host.';
                content.appendChild(sub);
            } else {
                sub.textContent = a.name + ' is registered.' + (a.credNote || '');
                content.appendChild(sub);
                const warn = el('div', 'ex-hint');
                warn.textContent = 'Vaier could not prepare the setup command. Open ' + a.name
                    + ' and use its Setup script button.';
                content.appendChild(warn);
            }

            const actions = actionsRow();
            const done = el('button', 'ex-btn is-accent'); done.textContent = 'Done';
            done.onclick = () => {
                close();
                go(['fleet', a.machineId]);
            };
            actions.appendChild(done);
            content.appendChild(actions);
        }

        // ---- by address: the fallback (an empty scan, or a LAN Vaier can't reach) -----------------------
        // The scan can come back empty, or find nothing on a LAN Vaier can't reach, so registering a LAN
        // server by hand never goes away — it just steps out of the way of discovery. But once the operator
        // names an address, Vaier can still probe that one host (POST /lan-servers/probe) and offer the same
        // detected readout + SSH credential test the adopt flow does. Detection never blocks Add: an
        // unreachable host just leaves the plain fields to fill in by hand.
        function paintByAddress() {
            titleEl.textContent = 'Add a LAN server';
            content.innerHTML = '';
            const sub = el('div', 'ex-dialog-body');
            sub.textContent = 'A LAN server is reached through the machine whose network it sits on, so Vaier only '
                + 'needs where it answers. Give its LAN address and Vaier will try to detect the rest.';
            content.appendChild(sub);

            const name = el('input', 'ex-input'); name.type = 'text'; name.placeholder = 'e.g. Roon server';
            name.autocomplete = 'off'; name.spellcheck = false;
            const lanAddr = el('input', 'ex-input'); lanAddr.type = 'text'; lanAddr.placeholder = 'e.g. 192.168.1.50';
            lanAddr.autocomplete = 'off'; lanAddr.spellcheck = false;
            const dockerBox = el('input'); dockerBox.type = 'checkbox';
            const dockerRow = el('label', 'ex-check-row');
            const dtxt = el('span'); dtxt.textContent = 'It runs Docker Vaier can read'; dockerRow.append(dockerBox, dtxt);
            const dockerPort = el('input', 'ex-input'); dockerPort.type = 'number'; dockerPort.min = '1';
            dockerPort.max = '65535'; dockerPort.value = '2375';
            const dockerPortField = field('Docker API port', 'The port its Docker engine API listens on.', dockerPort);
            const cat = catSelect('');
            const desc = el('input', 'ex-input'); desc.type = 'text'; desc.autocomplete = 'off';
            const syncDocker = () => { dockerPortField.style.display = dockerBox.checked ? '' : 'none'; };
            dockerBox.onchange = syncDocker; syncDocker();

            // A quiet line under the address: "Detecting…", "detected — reached via <relay>", or the fall-back note.
            const detectMsg = el('div', 'ex-hint'); detectMsg.style.display = 'none';

            const form = el('div', 'ex-form');
            form.append(field('Name', null, name),
                field('LAN address', 'Where this machine answers on its network.', lanAddr),
                detectMsg, dockerRow, dockerPortField,
                field('Device category', 'Its shape in the fleet and on the map. Optional.', cat),
                field('Description', 'Optional.', desc));
            content.appendChild(form);

            // The SSH block — the same login the web terminal, disk watch and backups ride on. Built once and
            // hidden; revealed only when the probe says the host answered on port 22, exactly as adopt does.
            const disc = disclosure('Add SSH access — for the web terminal, disk watch & backups');
            disc.style.display = 'none';
            const username = el('input', 'ex-input'); username.type = 'text'; username.autocomplete = 'off';
            username.spellcheck = false; username.placeholder = 'e.g. admin';
            const method = el('select', 'ex-input');
            [['PASSWORD', 'Password'], ['PRIVATE_KEY', 'Private key']].forEach(([v, t]) => {
                const o = el('option'); o.value = v; o.textContent = t; method.appendChild(o);
            });
            const password = el('input', 'ex-input'); password.type = 'password'; password.autocomplete = 'new-password';
            const keyArea = el('textarea', 'ex-input ex-cred-key'); keyArea.rows = 4;
            keyArea.placeholder = '-----BEGIN OPENSSH PRIVATE KEY-----'; keyArea.spellcheck = false;
            const passphrase = el('input', 'ex-input'); passphrase.type = 'password'; passphrase.autocomplete = 'new-password';
            const pwF = field('Password', null, password);
            const keyF = field('Private key (PEM)', null, keyArea);
            const passF = field('Key passphrase', 'Optional.', passphrase);
            const syncMethod = () => {
                const isKey = method.value === 'PRIVATE_KEY';
                pwF.style.display = isKey ? 'none' : '';
                keyF.style.display = isKey ? '' : 'none';
                passF.style.display = isKey ? '' : 'none';
            };
            method.onchange = syncMethod;
            const testRow = el('div', 'ex-testrow');
            const testBtn = el('button', 'ex-btn'); testBtn.textContent = 'Test connection';
            const testOk = el('span', 'ex-testok'); testOk.textContent = '✓ Reached & authenticated';
            testRow.append(testBtn, testOk);
            disc.append(field('User', null, username), field('Auth', null, method), pwF, keyF, passF, testRow);
            syncMethod();
            content.appendChild(disc);

            const draft = () => ({ username: username.value.trim(), authMethod: method.value,
                secret: (method.value === 'PRIVATE_KEY' ? keyArea.value : password.value),
                passphrase: passphrase.value || null });
            const credentialFilled = () => disc.style.display !== 'none' && disc.open
                && username.value.trim() && draft().secret.trim();

            testBtn.onclick = async () => {
                const addr = lanAddr.value.trim();
                if (!addr) { toast('Enter the LAN address first.'); return; }
                if (!username.value.trim() || !draft().secret.trim()) { toast('Enter a username and the secret to test.'); return; }
                testOk.classList.remove('is-on');
                testBtn.disabled = true; const was = testBtn.textContent; testBtn.textContent = 'Testing…';
                try {
                    const r = await fetch('/lan-scan/' + encodeURIComponent(addr) + '/ssh-credential/test', {
                        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(draft()) });
                    if (!r.ok) { toast('Vaier could not test that login.'); return; }
                    const v = await r.json();
                    if (v.authenticated) { testOk.classList.add('is-on'); }
                    else if (v.reachable) { toast('Reached ' + addr + ', but that login was refused.'); }
                    else { toast('Vaier could not reach ' + addr + ' over SSH.'); }
                } catch (e) { toast('Vaier could not test that login.'); }
                finally { testBtn.disabled = false; testBtn.textContent = was; }
            };

            // Probe the typed address once the operator leaves the field. Reachable → prefill Docker + category
            // and reveal SSH access; not reachable → keep the manual fields with a quiet fall-back note.
            let lastProbed = null;
            const detect = async () => {
                const addr = lanAddr.value.trim();
                if (!addr || addr === lastProbed) return;
                lastProbed = addr;
                detectMsg.style.display = ''; detectMsg.textContent = 'Detecting…';
                disc.style.display = 'none';
                try {
                    const r = await fetch('/lan-servers/probe', {
                        method: 'POST', headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ address: addr }) });
                    if (lanAddr.value.trim() !== addr) return;   // the operator moved on; ignore a stale result
                    const p = r.ok ? await r.json() : { reachable: false };
                    if (p.reachable) {
                        if (p.runsDocker) { dockerBox.checked = true; syncDocker();
                            if (p.dockerPort) dockerPort.value = String(p.dockerPort); }
                        if (p.guessedCategory && p.guessedCategory !== 'GENERIC') cat.value = p.guessedCategory;
                        if (p.sshAvailable) disc.style.display = '';
                        detectMsg.textContent = p.routedVia ? 'Detected — reached via ' + p.routedVia : 'Detected.';
                    } else {
                        detectMsg.textContent = 'Couldn’t reach it to detect — fill these in and add anyway.';
                    }
                } catch (e) {
                    if (lanAddr.value.trim() === addr) detectMsg.textContent = 'Couldn’t reach it to detect — fill these in and add anyway.';
                }
            };
            lanAddr.onblur = detect;

            const actions = actionsRow();
            const back = el('button', 'ex-btn'); back.textContent = 'Back';
            back.onclick = () => screen('pickLan');
            const add = el('button', 'ex-btn is-accent'); add.textContent = 'Add machine';
            const sync = () => { add.disabled = !(name.value.trim() && lanAddr.value.trim()); };
            name.oninput = sync; lanAddr.oninput = () => { lastProbed = null; sync(); }; sync();
            add.onclick = async () => {
                const runsDocker = dockerBox.checked;
                const port = parseInt(dockerPort.value, 10);
                const credential = credentialFilled() ? draft() : null;
                // Register + SSH-verify is slow, so keep the modal up with a busy button rather than closing
                // into a blank pane; close only once the machine is in and we're navigating to it.
                const was = add.textContent;
                add.disabled = true; back.disabled = true; add.textContent = 'Adding…';
                const ok = await createLanServer({ name: name.value.trim(), lanAddress: lanAddr.value.trim(),
                    runsDocker: runsDocker, dockerPort: runsDocker && port > 0 ? port : null,
                    deviceCategory: cat.value, description: desc.value.trim(), credential: credential });
                if (ok) { close(); }
                else { add.disabled = false; back.disabled = false; add.textContent = was; }
            };
            actions.append(back, add);
            content.appendChild(actions);
            name.focus();
        }

        // ---- peer branch: intent first, an explicit OS second, then name, then handoff ------------------
        // A brand-new peer is on no network yet, so Vaier can't probe it. It asks only intent — what the
        // machine is for, and its OS — and generates everything else. The intent -> MachineType mapping is
        // the domain's (MachineIntent, resolved server-side from intent + windows); the browser never
        // decides the routing type. The LAN a server routes is set later from the machine, so the operator
        // types only the name here.

        // A lone Back control at the foot of a peer screen.
        function peerFoot(onBack) {
            const foot = actionsRow();
            foot.style.justifyContent = 'flex-start';
            const back = el('button', 'ex-btn'); back.textContent = 'Back';
            back.onclick = onBack;
            foot.appendChild(back);
            return foot;
        }

        // ---- peer · what is this? the intent fork ------------------------------------------------------
        function paintPeerWhat() {
            titleEl.textContent = 'Add a peer';
            content.innerHTML = '';
            const sub = el('div', 'ex-dialog-body');
            sub.textContent = 'A peer connects through Vaier’s VPN — Vaier assigns its tunnel address and keys. '
                + 'Answer what it is for, and it generates the rest.';
            const grid = el('div', 'ex-choice-grid');
            // The two cards carry the whole of the difference between the intents, so they say what each one
            // gets — how it is reachable, and where its traffic goes — rather than naming the two tunnel
            // shapes that decide it.
            const server = choiceCard('server', 'A server',
                'Runs around the clock and can host services. Stays reachable from the fleet, and can open '
                + 'up the network it sits on.');
            server.onclick = () => { peerIntent = 'SERVER'; screen('peerOs'); };
            const device = choiceCard('laptop', 'A personal device',
                'A phone, laptop or desktop that just needs to reach the fleet. Its traffic goes through '
                + 'Vaier while it’s connected.');
            device.onclick = () => { peerIntent = 'PERSONAL_DEVICE'; screen('peerOs'); };
            grid.append(server, device);
            content.append(sub, section('What is this?'), grid, peerFoot(() => screen('fork')));
        }

        // ---- peer · OS second step — always shown, never inferred --------------------------------------
        function paintPeerOs() {
            if (!peerIntent) { screen('peerWhat'); return; }
            const isServer = peerIntent === 'SERVER';
            titleEl.textContent = 'Add a peer';
            content.innerHTML = '';
            const sub = el('div', 'ex-dialog-body');
            sub.textContent = isServer
                ? 'The operating system sets how the peer comes up — a no-root container command on Ubuntu, '
                  + 'or the WireGuard app on Windows.'
                : 'The kind of device sets how Vaier hands off — a QR to scan, or a config for the '
                  + 'WireGuard app.';
            const grid = el('div', 'ex-choice-grid');
            let a, b;
            if (isServer) {
                a = choiceCard('server', 'Ubuntu',
                    'Comes up with a single no-root command — Vaier runs the tunnel in a container.');
                a.onclick = () => { peerWindows = false; screen('peerName'); };
                b = choiceCard('desktop', 'Windows',
                    'Comes up with WireGuard for Windows and the config Vaier generates.');
                b.onclick = () => { peerWindows = true; screen('peerName'); };
            } else {
                a = choiceCard('phone', 'Phone / Mac / Linux',
                    'Scan a QR in the WireGuard app, or import the config.');
                a.onclick = () => { peerWindows = false; screen('peerName'); };
                b = choiceCard('desktop', 'Windows PC',
                    'Import the config into the WireGuard app for Windows.');
                b.onclick = () => { peerWindows = true; screen('peerName'); };
            }
            grid.append(a, b);
            content.append(sub, section(isServer ? 'Which OS?' : 'Which device?'), grid,
                peerFoot(() => screen('peerWhat')));
        }

        // ---- peer · name — the one thing Vaier can't generate ------------------------------------------
        function paintPeerName() {
            if (!peerIntent) { screen('peerWhat'); return; }
            titleEl.textContent = 'Add a peer';
            content.innerHTML = '';

            const name = el('input', 'ex-input'); name.type = 'text';
            name.placeholder = peerIntent === 'SERVER' ? 'e.g. Roon server' : 'e.g. Geir’s phone';
            name.autocomplete = 'off'; name.spellcheck = false;
            content.appendChild(field('Name', 'The only thing Vaier can’t generate — what to call it.', name));

            content.appendChild(generatedBlock([
                drow('Tunnel IP', 'the next free address', true),
                drow('Keys + preshared key', 'on save')]));

            const actions = actionsRow();
            const back = el('button', 'ex-btn'); back.textContent = 'Back';
            back.onclick = () => screen('peerOs');
            const add = el('button', 'ex-btn is-accent'); add.textContent = 'Generate config';
            add.onclick = () => submitPeer(name.value.trim(), add);
            const sync = () => { add.disabled = name.value.trim() === ''; };
            name.oninput = sync; sync();
            name.onkeydown = (e) => { if (e.key === 'Enter' && name.value.trim()) submitPeer(name.value.trim(), add); };
            actions.append(back, add);
            content.appendChild(actions);
            name.focus();
        }

        // The create call, in-modal: POST with intent + windows so the intent -> MachineType decision stays
        // the domain's (resolved server-side), then render the handoff into this same modal. lanCidr is left
        // empty — a server's routed LAN is set later from its machine page, so the operator types only a name.
        async function submitPeer(name, addBtn) {
            if (!name) return;
            addBtn.disabled = true; const was = addBtn.textContent; addBtn.textContent = 'Generating…';
            try {
                const res = await fetch('/vpn/peers', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ name: name, intent: peerIntent, windows: peerWindows,
                        lanCidr: '', lanAddress: '', description: '' }),
                });
                if (!res.ok) {
                    const e = await res.json().catch(() => ({}));
                    toast(e.message || 'Vaier could not add that machine.');
                    addBtn.disabled = false; addBtn.textContent = was; return;
                }
                peerCreated = await res.json();
                await loadFleet();
                toast(peerCreated.name + ' added.');
                screen('peerHandoff');
            } catch (e) {
                toast('Vaier could not add that machine.');
                addBtn.disabled = false; addBtn.textContent = was;
            }
        }

        // ---- enrol · a phone that arrived with its own key ---------------------------------------------
        // The device answered every question the peer flow would have asked, and the one answer Vaier
        // normally generates — the key — it generated itself. So there is nothing to fill in: one button.
        function paintEnrol() {
            if (!enrolment) { screen('fork'); return; }
            titleEl.textContent = 'Add a machine';
            content.innerHTML = '';

            const sub = el('div', 'ex-dialog-body');
            sub.textContent = 'A phone called ' + enrolment.name + ' wants to join the fleet. It is showing this '
                + 'code — add it only if the two match. Its key was made on the phone and never leaves it; '
                + 'Vaier only ever sees the public half.';
            content.appendChild(sub);

            // The one thing to compare with the phone's screen, so it is the one thing set large.
            const code = el('div', 'ex-joincode'); code.textContent = enrolment.code;
            content.appendChild(code);

            content.appendChild(generatedBlock([
                drow('Tunnel IP', 'the next free address', true),
                drow('Preshared key', 'on save')]));

            const actions = actionsRow();
            const refuse = el('button', 'ex-btn'); refuse.textContent = 'Refuse';
            refuse.onclick = () => refuseEnrolment(refuse);
            const add = el('button', 'ex-btn is-accent'); add.textContent = 'Add';
            add.onclick = () => submitEnrolment(add);
            actions.append(refuse, add);
            content.appendChild(actions);
            add.focus();
        }

        // A refusal tells the phone, over the same stream its approval would have arrived on, and leaves
        // nothing behind: no peer was ever made and no key was ever stored.
        async function refuseEnrolment(btn) {
            btn.disabled = true;
            try {
                await fetch('/vpn/enrolments/' + encodeURIComponent(enrolment.code), { method: 'DELETE' });
                toast(enrolment.name + ' was refused.');
                close();
            } catch (e) {
                toast('Vaier could not reach that request.');
                btn.disabled = false;
            }
        }

        // The approval names only the code. The name and the public key are already on the request the
        // phone opened, and the config goes to the phone over its own stream — this browser never sees it.
        async function submitEnrolment(addBtn) {
            addBtn.disabled = true; const was = addBtn.textContent; addBtn.textContent = 'Adding…';
            try {
                const res = await fetch('/vpn/enrolments/' + encodeURIComponent(enrolment.code) + '/approve',
                    { method: 'POST' });
                if (res.status === 404) {
                    // The phone gave up, or ten minutes passed. Nothing to add any more.
                    toast('That phone is no longer waiting. Ask it to join again.');
                    await loadEnrolmentRequests(); render(); close(); return;
                }
                if (!res.ok) {
                    const e = await res.json().catch(() => ({}));
                    toast(e.message || 'Vaier could not add that device.');
                    addBtn.disabled = false; addBtn.textContent = was; return;
                }
                enrolled = await res.json();
                await Promise.all([loadFleet(), loadEnrolmentRequests()]);
                toast(enrolled.name + ' added.');
                screen('enrolHandoff');
            } catch (e) {
                toast('Vaier could not add that device.');
                addBtn.disabled = false; addBtn.textContent = was;
            }
        }

        // ---- enrol · handoff — to the phone, never to the operator -----------------------------------------
        // Nothing is shown and nothing is saved: the config went to the phone over the stream it has been
        // waiting on since it asked. This browser was only ever the place the operator said yes.
        function paintEnrolHandoff() {
            const p = enrolled;
            if (!p) { screen('enrol'); return; }
            titleEl.textContent = p.name + ' — over to the phone';
            content.innerHTML = '';

            const sub = el('div', 'ex-dialog-body');
            sub.textContent = 'Vaier sent ' + p.name + ' everything except the private key, which the phone '
                + 'already has. There is nothing here to save.';
            content.appendChild(sub);

            const wait = el('div', 'ex-waiting');
            wait.appendChild(el('span', 'ex-scanmeta-dot is-live'));
            const wt = el('span');
            wt.textContent = 'Waiting for ' + p.name + '’s first handshake — it turns green here on its own.';
            wait.appendChild(wt);
            content.appendChild(wait);

            const actions = actionsRow();
            const done = el('button', 'ex-btn'); done.textContent = 'Done';
            done.onclick = () => { close(); go(['fleet', p.machineId]); };
            actions.appendChild(done);
            content.appendChild(actions);
        }

        // ---- peer · handoff — the config, shown once, and the way to get it onto the box ----------------
        // Four variants off the same one-shot create response (#202): the Ubuntu server gets the no-sudo
        // recipe (its setup script is shown here to transfer, never fetched with a token); the others get
        // the config, the QR (mobile), and short WireGuard-app instructions. No new endpoint is involved.
        function paintPeerHandoff() {
            const p = peerCreated;
            if (!p) { screen('peerWhat'); return; }
            titleEl.textContent = p.name + ' — get it on the air';
            content.innerHTML = '';

            const isServer = peerIntent === 'SERVER';
            const sub = el('div', 'ex-dialog-body');
            sub.textContent = 'Save this now: for security the config is shown once. Install it on ' + p.name
                + ' to bring it onto the VPN.';
            content.appendChild(sub);

            if (isServer && !peerWindows) content.appendChild(peerHandoffUbuntu(p));
            else if (isServer) content.appendChild(peerHandoffWindows(p, 'server'));
            else if (!peerWindows) content.appendChild(peerHandoffMobile(p));
            else content.appendChild(peerHandoffWindows(p, 'client'));

            const wait = el('div', 'ex-waiting');
            wait.appendChild(el('span', 'ex-scanmeta-dot is-live'));
            const wt = el('span');
            wt.textContent = 'Waiting for ' + p.name + '’s first handshake — it turns green here on its own.';
            wait.appendChild(wt);
            content.appendChild(wait);

            const actions = actionsRow();
            const done = el('button', 'ex-btn is-accent'); done.textContent = 'Done';
            done.onclick = () => {
                close();
                go(['fleet', p.machineId]);
            };
            actions.appendChild(done);
            content.appendChild(actions);
        }

        // The config text + its downloads, shared by every handoff variant. Everything comes from the inline
        // create response, so the one-shot GET budget is never touched.
        function peerConfigBlock(p, opts) {
            const wrap = el('div');
            if (opts.showConfig && p.configFile) {
                const pre = el('pre', 'ex-config'); pre.textContent = p.configFile;
                wrap.appendChild(pre);
            }
            const row = el('div', 'ex-set-actions');
            if (p.configFile) {
                const dl = el('button', 'ex-btn is-accent'); dl.textContent = 'Download .conf';
                dl.onclick = () => downloadText(p.name + '.conf', p.configFile);
                row.appendChild(dl);
            }
            if (opts.compose && p.dockerCompose) {
                const dc = el('button', 'ex-btn'); dc.textContent = 'Download docker-compose.yml';
                dc.onclick = () => downloadText('docker-compose.yml', p.dockerCompose);
                row.appendChild(dc);
            }
            if (opts.setup && p.setupScript) {
                const sc = el('button', 'ex-btn'); sc.textContent = 'Download vaier-up.sh';
                sc.onclick = () => downloadText('vaier-up.sh', p.setupScript);
                row.appendChild(sc);
            }
            wrap.appendChild(row);
            if (opts.qr && p.qrCodePngBase64) {
                const img = el('img', 'ex-qr');
                img.src = 'data:image/png;base64,' + p.qrCodePngBase64;
                img.alt = 'WireGuard config QR code';
                wrap.appendChild(img);
            }
            return wrap;
        }

        // One numbered recipe step: appended text and inline elements (never innerHTML, since the name is
        // operator-typed), with an optional command line under it.
        function recipeStep(parts, cmdText) {
            const li = el('li');
            const rt = el('div', 'ex-recipe-t');
            parts.forEach((part) => rt.appendChild(typeof part === 'string'
                ? document.createTextNode(part) : part));
            if (cmdText) { const c = el('code', 'ex-cmd'); c.textContent = cmdText; rt.appendChild(c); }
            li.appendChild(rt);
            return li;
        }
        const strong = (t) => { const e = el('b'); e.textContent = t; return e; };

        // Ubuntu server: the no-sudo recipe. When a single-use setup token is present (Slice 4b) the
        // PRIMARY recipe is the curl one-liner — log in as yourself, paste one line, it turns green on its
        // own; the box pulls its own config over HTTPS from Vaier's token-gated /setup route. Copying the
        // script by hand stays as a fallback. Without a token (older responses) we keep the save-the-file flow.
        function peerHandoffUbuntu(p) {
            const wrap = el('div');
            const sect = section('Get ' + p.name + ' on the air');
            const badge = el('span', 'ex-nosudo'); badge.textContent = '✓ no sudo';
            sect.appendChild(badge);
            wrap.appendChild(sect);

            if (p.setupToken) {
                const curl = "curl -fsSL '" + window.location.origin + '/vpn/peers/'
                    + encodeURIComponent(p.id) + '/setup?t=' + encodeURIComponent(p.setupToken) + "' | bash";

                const ol = el('ol', 'ex-recipe');
                ol.appendChild(recipeStep([strong('Log in'), ' to the box as yourself — no sudo.'],
                    'ssh you@' + p.id));
                ol.appendChild(recipeStep([strong('Paste this line'), ' and run it. It pulls the config and '
                    + 'starts WireGuard in a container. The link works once.']));
                wrap.appendChild(ol);
                wrap.appendChild(copyableCommand(curl));
                const last = el('ol', 'ex-recipe');
                last.appendChild(recipeStep([strong('That’s it.'), ' ' + p.name
                    + ' turns green here on its first handshake.']));
                wrap.appendChild(last);

                const fb = el('details', 'ex-fallback');
                const sum = el('summary'); sum.textContent = 'Prefer to copy the script by hand? Download it.';
                fb.appendChild(sum);
                if (p.setupScript) {
                    const pre = el('pre', 'ex-config'); pre.textContent = p.setupScript;
                    fb.appendChild(pre);
                }
                fb.appendChild(peerConfigBlock(p, { showConfig: false, compose: true, setup: true, qr: false }));
                wrap.appendChild(fb);
                return wrap;
            }

            const ol = el('ol', 'ex-recipe');
            ol.appendChild(recipeStep([strong('Log in'), ' to the box as yourself.'], 'ssh you@' + p.id));
            ol.appendChild(recipeStep([strong('Save the setup script'), ' below onto the box as ',
                strong('vaier-up.sh'), '.']));
            ol.appendChild(recipeStep([strong('Run it.'), ' Writes the config and starts WireGuard in a '
                + 'container — Docker only, no root.'], 'sh vaier-up.sh'));
            ol.appendChild(recipeStep([strong('That’s it.'), ' ' + p.name
                + ' turns green here on its first handshake.']));
            wrap.appendChild(ol);

            if (p.setupScript) {
                const pre = el('pre', 'ex-config'); pre.textContent = p.setupScript;
                wrap.appendChild(pre);
            }
            wrap.appendChild(peerConfigBlock(p, { showConfig: false, compose: true, setup: true, qr: false }));
            return wrap;
        }

        // A command in a copy-friendly block: the text shown verbatim plus the existing copy affordance.
        function copyableCommand(cmd) {
            const wrap = el('div');
            const pre = el('pre', 'ex-config'); pre.textContent = cmd; wrap.appendChild(pre);
            const row = el('div', 'ex-set-actions');
            const copy = el('button', 'ex-btn is-accent'); copy.textContent = 'Copy command';
            copy.onclick = () => navigator.clipboard.writeText(cmd)
                .then(() => toast('Command copied.')).catch(() => toast('Could not copy the command.'));
            row.appendChild(copy); wrap.appendChild(row);
            return wrap;
        }

        // Windows server or Windows PC: the config file plus short WireGuard-for-Windows steps. The server
        // also gets its docker-compose.
        function peerHandoffWindows(p, role) {
            const wrap = el('div');
            wrap.appendChild(section('Set up ' + p.name + ' on Windows'));
            const list = el('ol', 'ex-instr');
            ['Install WireGuard for Windows on the ' + (role === 'server' ? 'box' : 'PC') + '.',
             'Download the config below, then in WireGuard choose Add Tunnel → Import from file.',
             'Activate the tunnel. ' + p.name + ' turns green here on its first handshake.']
                .forEach((t) => { const li = el('li'); li.textContent = t; list.appendChild(li); });
            wrap.appendChild(list);
            wrap.appendChild(peerConfigBlock(p,
                { showConfig: true, compose: role === 'server', setup: false, qr: false }));
            return wrap;
        }

        // Phone / Mac / Linux: the QR to scan, the config to import.
        function peerHandoffMobile(p) {
            const wrap = el('div');
            // #359: on Android there is a better route than a photographed QR — the Vaier app mints the
            // key on the phone and enrols it over the operator's own session, so nothing is copied by
            // hand. Said first; the QR below stays for the WireGuard app.
            //
            // No link: this pane is open on the operator's desktop and the app has to be installed on the
            // phone, so what helps is the address to type there — which is the address this page is on.
            const app = el('div', 'ex-hint');
            app.textContent = 'On Android, open ' + location.host + ' on the phone itself and tap Install '
                + 'on the card at the top, then enrol ' + p.name + ' from the app — the phone makes its own '
                + 'key and nothing is copied by hand.';
            wrap.appendChild(app);
            wrap.appendChild(section('Scan it into WireGuard'));
            const list = el('ol', 'ex-instr');
            ['Open the WireGuard app on the device.',
             'Tap Add → Scan from QR code and point it at the code below.',
             'Toggle the tunnel on. It turns green here on its first handshake.']
                .forEach((t) => { const li = el('li'); li.textContent = t; list.appendChild(li); });
            wrap.appendChild(list);
            wrap.appendChild(peerConfigBlock(p, { showConfig: false, compose: false, setup: false, qr: true }));
            const alt = el('div', 'ex-hint');
            alt.textContent = 'On a Mac or Linux box, import the .conf into WireGuard instead of scanning.';
            wrap.appendChild(alt);
            return wrap;
        }

        if (initialScreen === 'adopt' && cand) screen('adopt');
        else if (initialScreen === 'enrol' && enrolment) screen('enrol');
        else screen('fork');
    }

    // Register a LAN server by hand — a machine on a relay's LAN that is not a WireGuard peer of its own (a NAS,
    // a printer, an appliance). It is reached through its relay, so Vaier only needs where it answers and, if it
    // speaks Docker, on which port. Usually these are found by the scan; this is the by-hand path. A 400 means
    // the address is not inside any relay's LAN — the one failure worth its own sentence.
    async function createLanServer(body) {
        try {
            const res = await fetch('/lan-servers', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name: body.name, lanAddress: body.lanAddress, runsDocker: body.runsDocker,
                    dockerPort: body.dockerPort, description: body.description,
                    deviceCategory: body.deviceCategory || null,
                    credential: body.credential || null }),
            });
            if (res.status === 400) {
                toast(body.lanAddress + ' isn’t on a network Vaier can reach. Add the machine that sits on '
                    + 'that network first.');
                return false;
            }
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                toast(err.message || 'Vaier could not add that machine.');
                return false;
            }
            // With a credential the body reports whether it stuck; the machine is registered either way.
            const resp = await res.json().catch(() => null);
            const credNote = resp && resp.credentialProvided && !resp.credentialStored
                ? ' Its SSH login couldn’t be saved — set one from the machine.' : '';
            await loadFleet();
            toast(body.name + ' added.' + credNote);
            const added = resp.machineId;
            go(['fleet', added]);
            return true;
        } catch (e) {
            toast('Vaier could not add that machine.');
            return false;
        }
    }

    async function removeMachine(m) {
        const isPeer = S.peers.has(m.id);
        const ok = await confirmTyped('Remove ' + m.name + '?',
            'This deletes ' + m.name + ' from the fleet — its ' + (isPeer ? 'WireGuard peer' : 'registration')
            + ' is removed and it can no longer reach the VPN. This cannot be undone. Type the machine name to '
            + 'confirm.', m.name, 'Remove');
        if (!ok) return;
        const url = isPeer ? '/vpn/peers/' + encodeURIComponent(S.peers.get(m.id).id)
                           : '/lan-servers/' + encodeURIComponent(m.id);
        try {
            const res = await fetch(url, { method: 'DELETE' });
            if (!res.ok && res.status !== 204) { toast('Vaier could not remove ' + m.name + '.'); return; }
        } catch (e) { toast('Vaier could not remove ' + m.name + '.'); return; }
        await loadFleet();
        toast(m.name + ' removed.');
        go(['fleet']);
    }

    // Reissue a peer's WireGuard config — fresh keys and a new config, shown once like a new machine's. The old
    // config stops working once the new one is installed, so it asks first.
    async function reissuePeer(m) {
        const peer = S.peers.get(m.id);
        if (!peer) return;
        const ok = await confirmModal('Reissue ' + m.name + '’s config?',
            'Vaier generates a fresh WireGuard config for ' + m.name + '. The current one stops working the '
            + 'moment the new one is installed — reissue only when you are ready to replace it.', 'Reissue');
        if (!ok) return;
        try {
            const res = await fetch('/vpn/peers/' + encodeURIComponent(peer.id) + '/reissue', { method: 'POST' });
            if (!res.ok) {
                const e = await res.json().catch(() => ({}));
                toast(e.message || 'Vaier could not reissue the config.');
                return;
            }
            toast(m.name + '’s config reissued.');
            createResult(await res.json());   // same one-shot config view a new machine gets
        } catch (e) {
            toast('Vaier could not reissue the config.');
        }
    }

    // A tiny client-side download — turns text Vaier already sent (a config, a compose file) into a file the
    // browser saves, so nothing has to be re-fetched through the peer's one-shot download gate.
    function downloadText(filename, text) {
        const blob = new Blob([text], { type: 'text/plain' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url; a.download = filename; a.click();
        URL.revokeObjectURL(url);
    }

    // The config a new peer needs, shown once. The config text (with a download), the QR for a phone, and the
    // docker-compose for a server — all from the inline create response, so the one-shot download gate is never
    // touched. This is the only chance to save it, and the dialog says so.
    function createResult(p) {
        const scrim = el('div', 'ex-scrim is-on');
        const dialog = el('div', 'ex-dialog is-wide');
        const h = el('div', 'ex-dialog-title'); h.textContent = p.name + ' — its config';
        const sub = el('div', 'ex-dialog-body');
        sub.textContent = 'Save this now: for security the config is shown once. Install it on ' + p.name
            + ' to bring it onto the VPN.';
        dialog.append(h, sub);

        if (p.configFile) {
            const pre = el('pre', 'ex-config'); pre.textContent = p.configFile;
            dialog.appendChild(pre);
            const row = el('div', 'ex-set-actions');
            const dl = el('button', 'ex-btn is-accent'); dl.textContent = 'Download .conf';
            dl.onclick = () => downloadText(p.name + '.conf', p.configFile);
            row.appendChild(dl);
            if (p.dockerCompose) {
                const dc = el('button', 'ex-btn'); dc.textContent = 'Download docker-compose.yml';
                dc.onclick = () => downloadText('docker-compose.yml', p.dockerCompose);
                row.appendChild(dc);
            }
            // A server peer also ships a setup script — the one-shot provisioning step that installs WireGuard,
            // opens Docker and adds the fleet routes. Shown here or nowhere: like the config, it is not stored.
            if (p.setupScript) {
                const sc = el('button', 'ex-btn'); sc.textContent = 'Download setup-' + p.name + '.sh';
                sc.onclick = () => downloadText('setup-' + p.name + '.sh', p.setupScript);
                row.appendChild(sc);
            }
            dialog.appendChild(row);
        }
        if (p.qrCodePngBase64) {
            const img = el('img', 'ex-qr');
            img.src = 'data:image/png;base64,' + p.qrCodePngBase64;
            img.alt = 'WireGuard config QR code';
            dialog.appendChild(img);
        }

        const actions = el('div', 'ex-dialog-actions');
        const done = el('button', 'ex-btn'); done.textContent = 'Done';
        actions.appendChild(done);
        dialog.appendChild(actions);
        scrim.appendChild(dialog); document.body.appendChild(scrim);
        const close = () => { scrim.remove(); document.removeEventListener('keydown', onKey); };
        const onKey = (e) => { if (e.key === 'Escape') close(); };
        done.onclick = close;
        document.addEventListener('keydown', onKey);
    }

    // The SSH credential Vaier holds for a machine, ported from the Infrastructure page's modal. Loads the
    // stored username/method on open (the secret is never returned), saves a new one, or forgets it. This is
    // what turns a machine's files/shell/disk/backups on — without it they are dark.
    function credentialDialog(machineId) {
        const machineName = nameOf(machineId);
        const scrim = el('div', 'ex-scrim is-on');
        const dialog = el('div', 'ex-dialog');
        const h = el('div', 'ex-dialog-title'); h.textContent = 'SSH credential — ' + machineName;
        const status = el('div', 'ex-dialog-body'); status.textContent = 'Checking…';
        const form = el('div', 'ex-form');
        const field = (label, hint, control) => {
            const f = el('div', 'ex-field'); const l = el('label'); l.textContent = label; f.append(l, control);
            if (hint) { const hn = el('div', 'ex-hint'); hn.textContent = hint; f.appendChild(hn); }
            return f;
        };
        const username = el('input', 'ex-input'); username.type = 'text'; username.autocomplete = 'off'; username.spellcheck = false;
        const method = el('select', 'ex-input');
        [['PASSWORD', 'Password'], ['PRIVATE_KEY', 'Private key']].forEach(([v, t]) => {
            const o = el('option'); o.value = v; o.textContent = t; method.appendChild(o);
        });
        const password = el('input', 'ex-input'); password.type = 'password'; password.autocomplete = 'new-password';
        const keyArea = el('textarea', 'ex-input ex-cred-key'); keyArea.rows = 4;
        keyArea.placeholder = '-----BEGIN OPENSSH PRIVATE KEY-----'; keyArea.spellcheck = false;
        const passphrase = el('input', 'ex-input'); passphrase.type = 'password'; passphrase.autocomplete = 'new-password';
        const passF = field('Key passphrase', 'Optional.', passphrase);
        const pwF = field('Password', null, password);
        const keyF = field('Private key (PEM)', null, keyArea);

        // The public half of whatever key Vaier holds — derived from the private key, never stored beside it.
        // It is the one piece of a key credential that is safe to show, and the only piece that is any use to
        // the operator: it has to end up in a file on the machine itself.
        const pubWrap = el('div', 'ex-pubkey-wrap');
        const pubKey = el('code', 'ex-pubkey');
        const pubCopy = el('button', 'ex-btn ex-pubkey-copy'); pubCopy.type = 'button'; pubCopy.textContent = 'Copy';
        pubWrap.append(pubKey, pubCopy);
        const pubF = field('Public key', 'Add this line to ~/.ssh/authorized_keys on ' + machineName
            + '. Vaier keeps the private half and never shows it.', pubWrap);
        pubF.style.display = 'none';
        pubCopy.onclick = () => {
            navigator.clipboard.writeText(pubKey.textContent).then(() => toast('Copied.'))
                .catch(() => toast('Could not copy. Select the key and copy it by hand.'));
        };

        form.append(field('Username', null, username), field('Auth method', null, method), pwF, keyF, passF, pubF);

        // What Vaier currently holds, as far as this dialog knows. `managed` is the whole reason the dialog
        // has two shapes: a keypair Vaier generated has no private half the operator could edit, so offering
        // them the textarea would be a lie about what they can do.
        let managed = false;
        let hasCredential = false;

        const syncMethod = () => {
            const isKey = method.value === 'PRIVATE_KEY';
            const isManagedKey = isKey && managed;
            pwF.style.display = isKey ? 'none' : '';
            keyF.style.display = isKey && !isManagedKey ? '' : 'none';
            passF.style.display = isKey && !isManagedKey ? '' : 'none';
            pubF.style.display = isKey && pubKey.textContent ? '' : 'none';
            // Nothing to save for a managed keypair: to hand Vaier a key of your own, pick Password or
            // delete this one first. A Save button with no editable secret behind it would do nothing.
            ok.style.display = isManagedKey ? 'none' : '';
        };
        method.onchange = syncMethod;

        const actions = el('div', 'ex-dialog-actions');
        const del = el('button', 'ex-btn is-danger'); del.textContent = 'Delete';
        del.style.display = 'none';
        // Both are "act on the credential" verbs, so they group left, away from the dialog's Cancel/Save.
        const gen = el('button', 'ex-btn'); gen.textContent = 'Generate keypair'; gen.style.marginRight = 'auto';
        const cancel = el('button', 'ex-btn'); cancel.textContent = 'Cancel';
        const ok = el('button', 'ex-btn is-accent'); ok.textContent = 'Save';
        actions.append(del, gen, cancel, ok);
        syncMethod();
        dialog.append(h, status, form, actions);
        scrim.appendChild(dialog); document.body.appendChild(scrim);

        const close = () => { scrim.remove(); document.removeEventListener('keydown', onKey); };
        const onKey = (e) => { if (e.key === 'Escape') close(); };
        cancel.onclick = close;
        scrim.onclick = (e) => { if (e.target === scrim) close(); };
        document.addEventListener('keydown', onKey);
        username.focus();

        fetch('/machines/' + encodeURIComponent(machineId) + '/ssh-credential').then(async (r) => {
            if (r.status === 404) { status.textContent = 'No credential stored yet.'; return; }
            if (!r.ok) { status.textContent = 'Could not read the credential status.'; return; }
            const v = await r.json();
            username.value = v.username || '';
            method.value = v.authMethod || 'PASSWORD';
            managed = !!v.managed;
            hasCredential = !!v.hasSecret;
            syncMethod();
            del.style.display = v.hasSecret ? '' : 'none';
            status.textContent = managed
                ? 'Vaier generated this keypair for ' + (v.username || '') + ' and holds the private half.'
                : 'Stored for ' + (v.username || '') + ' · '
                    + (v.authMethod || '').toLowerCase().replace('_', ' ') + '. Enter the secret again to replace it.';
            if (v.authMethod === 'PRIVATE_KEY' && v.hasSecret) loadPublicKey();
        }).catch(() => { status.textContent = 'Could not reach Vaier.'; });

        // A pasted key has a public half too, and an operator checking "is this the key I installed?" needs
        // it as much as a generated one does — so this is asked for any key credential, not only a managed one.
        async function loadPublicKey() {
            try {
                const r = await fetch('/machines/' + encodeURIComponent(machineId) + '/ssh-credential/public-key');
                if (!r.ok) return;
                showPublicKey((await r.json()).publicKey);
            } catch (e) { /* the key is unreadable or Vaier is unreachable; the panel simply stays away */ }
        }

        function showPublicKey(key) {
            pubKey.textContent = key || '';
            syncMethod();
        }

        gen.onclick = async () => {
            const user = username.value.trim();
            if (!user) { toast('Enter a username.'); return; }
            if (hasCredential) {
                const sure = await confirmModal('Generate a new keypair for ' + machineName + '?',
                    'Vaier replaces the login it holds for ' + machineName + ' with a keypair it generates '
                    + 'itself. The current login stops working immediately, and the new one does nothing until '
                    + 'you add its public key to authorized_keys on ' + machineName + '. Its files, shell, disk '
                    + 'and backups go dark in between.', 'Generate');
                if (!sure) return;
            }
            gen.disabled = true;
            try {
                const r = await fetch('/machines/' + encodeURIComponent(machineId) + '/ssh-credential/generate', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username: user }),
                });
                if (!r.ok) {
                    const e = await r.json().catch(() => ({}));
                    toast(e.message || 'Could not generate a keypair.'); gen.disabled = false; return;
                }
                managed = true; hasCredential = true;
                method.value = 'PRIVATE_KEY';
                del.style.display = '';
                showPublicKey((await r.json()).publicKey);
                status.textContent = 'Keypair generated for ' + user + '. It does not work until its public key '
                    + 'is in authorized_keys on ' + machineName + '.';
                gen.disabled = false;
                // Deliberately left open: the operator has nowhere else to read the public key from, and
                // closing the dialog on them would send them straight back into it.
                toast('Keypair generated.');
                await loadFleet(); render();
            } catch (e) { toast('Could not generate a keypair.'); gen.disabled = false; }
        };

        ok.onclick = async () => {
            const secret = method.value === 'PRIVATE_KEY' ? keyArea.value : password.value;
            if (!username.value.trim()) { toast('Enter a username.'); return; }
            if (!secret.trim()) { toast('Enter the ' + (method.value === 'PRIVATE_KEY' ? 'private key' : 'password') + '.'); return; }
            ok.disabled = true;
            try {
                const r = await fetch('/machines/' + encodeURIComponent(machineId) + '/ssh-credential', {
                    method: 'PUT', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username: username.value.trim(), authMethod: method.value,
                        secret: secret, passphrase: passphrase.value || null }),
                });
                if (!r.ok) { const e = await r.json().catch(() => ({})); toast(e.message || 'Could not save the credential.'); ok.disabled = false; return; }
                toast('SSH credential saved for ' + machineName + '.');
                close(); await loadFleet(); render();
            } catch (e) { toast('Could not save the credential.'); ok.disabled = false; }
        };

        del.onclick = async () => {
            const sure = await confirmModal('Delete the SSH credential for ' + machineName + '?',
                'Vaier forgets the login it holds for ' + machineName + '. Its files, shell, disk and backups go dark '
                + 'until you set one again.', 'Delete');
            if (!sure) return;
            try {
                const r = await fetch('/machines/' + encodeURIComponent(machineId) + '/ssh-credential', { method: 'DELETE' });
                if (!r.ok && r.status !== 204) { toast('Could not delete the credential.'); return; }
                toast('SSH credential deleted.');
                close(); await loadFleet(); render();
            } catch (e) { toast('Could not delete the credential.'); }
        };
    }

    // --- containers: what Vaier can see, and nothing it cannot do ---------------------------------------
    //
    // Read-only, and that is not an omission to paper over. DockerServiceRestController exposes GETs and
    // nothing else — Vaier has no endpoint to start, stop or restart a container, and none to fetch its
    // logs. So this Inspector shows what Vaier genuinely knows and offers no control it cannot honour.
    // A button that looks like a verb and does nothing is a lie about what works, and the fleet is exactly
    // the place where an operator must be able to trust what the screen says.

    function renderContainers(pane) {
        const machineId = S.path[1];
        const machineName = nameOf(machineId);
        const found = containersOn(machineId);
        pane.appendChild(paneHead('Containers', false,
            found.length + (found.length === 1 ? ' container' : ' containers')));

        const body = document.createElement('div');
        body.className = 'ex-pane-body';

        if (!S.containersRead) {
            body.appendChild(note('Reading the fleet’s containers…', false));
            pane.appendChild(body);
            return;
        }
        if (!found.length) {
            body.appendChild(note('Vaier scraped this machine’s Docker and found no containers. Either '
                + 'none are running, or the machine is not answering on its Docker port.', false));
            pane.appendChild(body);
            return;
        }

        // --- go: the containers themselves, which is what the operator opened this for ----------------
        const rows = document.createElement('div');
        rows.className = 'ex-listing is-wide';
        rows.appendChild(listHead(['Name', 'Image', 'State']));
        found.forEach((c) => {
            rows.appendChild(listRow(
                entryIco('container'), c.containerName,
                () => go(['fleet', machineId, 'containers', c.containerName]),
                [c.image || '—', c.state || 'unknown'],
                c.state === 'running' ? 'OK' : 'DOWN', updateMark(c)));
        });
        body.appendChild(rows);

        // --- do: the one thing to do here, under the list it re-reads ---------------------------------
        //
        // It is HERE, and only here, because this is where the operator lands — they pull a whole compose
        // stack on one machine and then look at that machine's containers. Not on each container's Inspector
        // (they did not pull one image) and not in three places, since the check is a single fleet-wide act
        // however many buttons front it. The check covers everything Vaier can see, because the backend's
        // sweep does; a per-machine control would be a lie about what happens.
        // Hidden in the archive for the same reason the mark is: there is no "now" back there to re-check.
        if (!S.at) {
            body.appendChild(section('What to do next'));
            const act = el('div', 'ex-lactions is-static');
            const btn = selVerb('refresh', _updateChecking ? 'Checking…' : 'Check the registries now',
                'ex-btn', () => checkForUpdates());
            btn.title = 'Ask each registry whether it now serves a newer image for the tag these containers '
                + 'run. Vaier only reads — it never pulls an image or touches a container.';
            if (_updateChecking) btn.disabled = true;
            act.appendChild(btn);
            body.appendChild(act);
            const said = updateCheckNote();
            if (said) body.appendChild(said);
        }
        pane.appendChild(body);
    }

    function renderContainer(pane) {
        const machineId = S.path[1];
        const machineName = nameOf(machineId);
        const name = S.path[3];
        const c = containersOn(machineId).find((x) => x.containerName === name);
        if (!c) {
            return pane.appendChild(note('Vaier no longer sees a container by that name on ' + machineName + '.',
                true));
        }

        pane.appendChild(paneHead(name, true, machineName));
        const body = document.createElement('div');
        body.className = 'ex-pane-body';

        const ports = (c.ports || []).map((p) => (p.publicPort ? p.publicPort + '→' : '')
            + p.privatePort + '/' + (p.type || 'tcp')).join('  ');

        // --- do: the one verb Vaier has over a container --------------------------------------------
        //
        // It is here rather than in the list because it acts on ONE container, which is exactly why the
        // registry check on the list is not here: that one act is fleet-wide however many buttons front it.
        // Withheld against anything but a real update, so most of the time this pane is details alone — and
        // then it wears no headings at all, because there is only one band to name.
        const act = updateAction(machineId, c);
        if (act) {
            body.appendChild(section('What to do next'));
            body.appendChild(act);
            body.appendChild(section('About this container'));
        }

        // --- know: what the container is ------------------------------------------------------------
        //
        // The Inspector is where the verdict is spoken in words, all three of them. No mark on the list does
        // NOT mean the image is current — it means either "current" or "Vaier cannot tell", and those are very
        // different facts to an operator. A list row has no room for the difference; this row does, and #57 was
        // filed precisely because "cannot tell" had been quietly rendered as "fine".
        body.appendChild(kv([
            ['Image', coord(c.image)],
            ['Version', coord(c.version)],
            ['Update', updateSays(c)],
            ['State', c.state],
            ['Ports', coord(ports)],
            ['Networks', coord((c.networks || []).join(', '))],
            ['Container id', coord((c.containerId || '').slice(0, 12))],
        ]));

        body.appendChild(note('This is very nearly everything Vaier knows about the container: it reads '
            + 'Docker, and updating it to the image its registry now serves is the only thing it can do to '
            + 'one. Nothing here can start, stop or reconfigure it, and Vaier will not show you a control it '
            + 'cannot honour. Use the machine’s shell for that.', false));
        pane.appendChild(body);
    }

    // --- services: a route, a machine and the name it answers on are one thing --------------------------

    function renderServices(pane) {
        const machineId = S.path[1];
        const machineName = nameOf(machineId);
        const found = servicesOn(machineId);
        const open = candidatesOn(machineId).filter((c) => !c.ignored);
        const hidden = candidatesOn(machineId).filter((c) => c.ignored);
        pane.appendChild(paneHead('Services', false,
            found.length + (found.length === 1 ? ' published service' : ' published services')));

        const body = el('div', 'ex-pane-body');

        // --- go: what is published — the live routes, each openable, each unpublishable ---------------
        //
        // No heading: this listing is the pane, and the head already counts it. A "Published" label above it
        // would only say the subtitle again, and the next heading is what marks where the listing stops.
        if (!found.length) {
            body.appendChild(note('Nothing is published from this machine yet.', false));
        } else {
            const rows = el('div', 'ex-listing is-wide');
            rows.appendChild(listHead(['Published at', 'Backend', 'State']));
            // A stream is not reached at a URL, so the row shows what a client actually dials — the name
            // and the one TLS port — and says which kind of route it is in a word.
            found.forEach((s) => rows.appendChild(listRow(entryIco('service'),
                (s.stream ? s.connectAddress : s.dnsAddress) || serviceName(s),
                () => go(['fleet', machineId, 'services', serviceName(s)]),
                [(s.hostAddress || '') + (s.hostPort ? ':' + s.hostPort : ''), s.state || 'UNKNOWN'],
                s.state, s.stream ? kindMark('stream') : null)));
            body.appendChild(rows);
        }

        // Container ports Vaier could put on the internet — publish one, or ignore it so it stops being offered.
        if (open.length) {
            body.appendChild(section('Ready to publish'));
            open.forEach((c) => body.appendChild(candidateRow(machineName, c, [
                selVerb('route', 'Publish', 'ex-btn is-accent', () => publishCandidate(machineId, c)),
                selVerb('cross', 'Ignore', 'ex-btn', () => ignoreCandidate(machineId, c)),
            ])));
        }
        // The dismissed ports, folded away behind a toggle — the same reflex as the LAN scan's ignored finds.
        // You ignored them because you did not want to look at them; a wall of them under everything you do
        // want to see undoes that. The count stays visible so they are never silently forgotten.
        if (hidden.length) {
            body.appendChild(section('Ignored'));
            const toggle = el('div', 'ex-lactions is-static');
            toggle.appendChild(selVerb('chev',
                (_showIgnoredServices ? 'Hide ignored' : 'Show ignored') + ' (' + hidden.length + ')',
                'ex-btn' + (_showIgnoredServices ? ' is-open' : ''),
                () => { _showIgnoredServices = !_showIgnoredServices; render(); }));
            body.appendChild(toggle);
            if (_showIgnoredServices) {
                hidden.forEach((c) => body.appendChild(candidateRow(machineName, c, [
                    selVerb('check', 'Unignore', 'ex-btn', () => unignoreCandidate(machineId, c)),
                ])));
            }
        }
        // --- do: the one thing here that is not already a list ----------------------------------------
        //
        // A service that isn't a container Vaier discovered — a LAN app, a device's own web page — is published
        // by hand: name a port on this machine and Vaier makes the route just the same. "By hand" moved off
        // the heading and onto the verb, because the heading is now the band's and every pane's Do band wears
        // the same words.
        body.appendChild(section('What to do next'));
        const manual = el('div', 'ex-lactions is-static');
        manual.appendChild(selVerb('route', 'Publish a service by hand', 'ex-btn', () => lanPublish(machineId)));
        body.appendChild(manual);
        pane.appendChild(body);
    }

    // What kind of route a row is, when it is not the ordinary one. A word: shapes and colour are trouble's.
    function kindMark(word) {
        const m = el('span', 'ex-lkind');
        m.textContent = word;
        return m;
    }

    // A publishable container port: its container name and where it listens, then the actions for it.
    function candidateRow(machine, c, buttons) {
        const row = el('div', 'ex-brepo');
        const info = el('div', 'ex-brepo-info');
        const nm = el('span', 'ex-brepo-name'); nm.textContent = c.containerName;
        const meta = el('span', 'ex-brepo-path'); meta.textContent = ':' + c.port + ' · ' + c.address;
        info.append(nm, meta);
        const acts = el('div', 'ex-lactions is-static');
        buttons.forEach((btn) => acts.appendChild(btn));
        row.append(info, acts);
        return row;
    }

    async function reloadServices(machineId) {
        await loadServices();
        S.nudges.delete(machineId);   // publishing or ignoring answers the publish nudge; it is read once per machine
        go(['fleet', machineId, 'services']);   // stay on the entry; it repaints from the fresh data
    }

    // Publish a container port: the only choices that are the operator's are the subdomain (defaulted from the
    // container name by the domain) and whether it sits behind login. The Traefik route Vaier does, and the
    // name already resolves. So the form is two fields.
    function publishCandidate(machineId, c) {
        publishForm(c).then((body) => {
            if (!body) return;
            saveJson('/published-services/publish', 'POST', {
                address: c.address, port: c.port, subdomain: body.subdomain, requiresAuth: body.requiresAuth,
                rootRedirectPath: body.rootRedirectPath, directUrlDisabled: body.directUrlDisabled,
                pathPrefix: body.pathPrefix, stream: body.stream,
            }, 'Publishing ' + body.subdomain + '…', () => reloadServices(machineId),
               'Could not publish that.');
        });
    }

    // The advanced-publish fold, shared by both publish forms: a path prefix, a root redirect, and whether to
    // link straight to the LAN URL. Rare at publish time — most services want none of it — so it stays folded.
    // Returns the three controls so the caller can read them back. `redirect` is prefilled (a candidate can
    // suggest one), and the fold opens itself when it is, so a suggested redirect is never hidden.
    function publishAdvanced(form, prefillRedirect) {
        const adv = disclosure('Sub-path, root redirect and the direct LAN link');
        const pathPrefix = plainInput('', 'e.g. /grafana');
        const redirect = plainInput(prefillRedirect || '', 'e.g. /dashboard');
        const directRow = el('label', 'ex-check-row');
        const direct = el('input'); direct.type = 'checkbox'; direct.checked = true;
        const dtxt = el('span'); dtxt.textContent = 'Link straight to the LAN URL when the visitor shares its network';
        directRow.append(direct, dtxt);
        adv.append(formField('Path prefix', 'Serve it under a sub-path of another name instead of its own '
            + 'subdomain.', pathPrefix), formField('Root redirect', 'Send the bare address on to a sub-path.',
            redirect), directRow);
        if (prefillRedirect) adv.open = true;
        form.appendChild(adv);
        return { adv, pathPrefix, redirect, direct };
    }

    function ignoreCandidate(machineId, c) {
        saveJson('/published-services/publishable/ignore', 'POST', { key: c.ignoreKey },
            'Ignored ' + c.containerName + '.', () => reloadServices(machineId), 'Could not ignore that.');
    }

    function unignoreCandidate(machineId, c) {
        saveJson('/published-services/publishable/unignore', 'POST', { key: c.ignoreKey },
            c.containerName + ' can be published again.', () => reloadServices(machineId), 'Could not unignore that.');
    }

    // A small POST/PUT helper for the fire-and-refresh actions: send, toast a first message, and on success run
    // an after() (usually a reload) — on failure, toast the server's own reason.
    async function saveJson(url, method, body, workingMsg, after, failMsg) {
        try {
            const res = await fetch(url, { method: method, headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body) });
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                toast(err.message || failMsg);
                return;
            }
            toast(workingMsg);
            if (after) await after();
        } catch (e) {
            toast(failMsg);
        }
    }

    // The publish form — subdomain (defaulted) and an auth toggle, over the shell's overlay. Resolves the body
    // on confirm and null otherwise; the button stays disabled until the subdomain is non-empty.
    function publishForm(c) {
        return new Promise((resolve) => {
            const scrim = el('div', 'ex-scrim is-on');
            const dialog = el('div', 'ex-dialog');
            const h = el('div', 'ex-dialog-title'); h.textContent = 'Publish ' + c.containerName;
            const sub = el('div', 'ex-dialog-body');
            sub.textContent = 'Put ' + c.address + ':' + c.port + ' on the internet. Vaier makes the route; '
                + 'the name already resolves under your wildcard record. You choose the name and whether '
                + 'it needs a login.';
            const form = el('div', 'ex-form');
            const subF = el('div', 'ex-field');
            const subL = el('label'); subL.textContent = 'Subdomain';
            const subIn = el('input', 'ex-input'); subIn.type = 'text'; subIn.value = c.suggestedSubdomain || '';
            subIn.autocomplete = 'off'; subIn.spellcheck = false;
            subF.append(subL, subIn);

            // The one thing about a port Vaier cannot work out for itself, and the answer that decides what
            // the rest of the form even means. A website is routed by name and can be put behind a login;
            // raw TCP is forwarded byte for byte, so none of that applies and none of it is shown.
            const kind = el('select', 'ex-input');
            [['http', 'A website — HTTP or HTTPS'],
             ['stream', 'Raw TCP — MQTT, a database, anything that is not a website']]
                .forEach(([v, t]) => { const o = el('option'); o.value = v; o.textContent = t; kind.appendChild(o); });
            const kindF = formField('What this port serves',
                'A website gets a URL. Raw TCP gets the same name on the HTTPS port, with the bytes passed '
                + 'straight through.', kind);

            const authRow = el('label', 'ex-check-row');
            const auth = el('input'); auth.type = 'checkbox'; auth.checked = true;
            const atxt = el('span'); atxt.textContent = 'Require a login to reach it';
            authRow.append(auth, atxt);

            const streamNote = el('div', 'ex-hint');
            streamNote.textContent = 'The service’s own password is the only gate on a stream, and CrowdSec '
                + 'does not watch it.';
            streamNote.hidden = true;

            form.append(subF, kindF, authRow, streamNote);
            const advanced = publishAdvanced(form, c.rootRedirectPath);
            const isStream = () => kind.value === 'stream';
            kind.onchange = () => {
                authRow.hidden = isStream();
                advanced.adv.hidden = isStream();
                streamNote.hidden = !isStream();
            };

            const actions = el('div', 'ex-dialog-actions');
            const cancel = el('button', 'ex-btn'); cancel.textContent = 'Cancel';
            const ok = el('button', 'ex-btn is-accent'); ok.textContent = 'Publish';
            actions.append(cancel, ok);
            dialog.append(h, sub, form, actions);
            scrim.appendChild(dialog); document.body.appendChild(scrim);

            const close = (r) => { scrim.remove(); document.removeEventListener('keydown', onKey); resolve(r); };
            const onKey = (e) => { if (e.key === 'Escape') close(null); };
            const sync = () => { ok.disabled = subIn.value.trim() === ''; };
            subIn.oninput = sync;
            scrim.onclick = (e) => { if (e.target === scrim) close(null); };
            cancel.onclick = () => close(null);
            // A stream carries none of the HTTP answers, so it sends none of them rather than sending
            // values from controls the operator was never shown.
            ok.onclick = () => { if (!subIn.value.trim()) return;
                close(isStream()
                    ? { subdomain: subIn.value.trim(), stream: true, requiresAuth: false,
                        pathPrefix: '', rootRedirectPath: '', directUrlDisabled: false }
                    : { subdomain: subIn.value.trim(), stream: false, requiresAuth: auth.checked,
                        pathPrefix: advanced.pathPrefix.value.trim(),
                        rootRedirectPath: advanced.redirect.value.trim(),
                        directUrlDisabled: !advanced.direct.checked }); };
            document.addEventListener('keydown', onKey);
            sync(); subIn.focus();
        });
    }

    // Publish a service by hand — a port on the machine that Vaier did not find as a container. The machine is
    // the context; the operator names the subdomain, the port, whether it speaks http or https, and whether it
    // needs a login. Vaier makes the route; the name already resolves under the operator's wildcard record.
    function lanPublish(machineId) {
        // The identity goes to the endpoint; the name only into the dialog's title, where a person reads it.
        lanPublishForm(nameOf(machineId)).then((body) => {
            if (!body) return;
            saveJson('/published-services/lan', 'POST', {
                subdomain: body.subdomain, machineId: machineId, port: body.port, protocol: body.protocol,
                requireAuth: body.requireAuth, directUrlDisabled: body.directUrlDisabled,
                rootRedirectPath: body.rootRedirectPath, pathPrefix: body.pathPrefix,
            }, 'Publishing ' + body.subdomain + '…', () => reloadServices(machineId), 'Could not publish that.');
        });
    }

    function lanPublishForm(machine) {
        return new Promise((resolve) => {
            const scrim = el('div', 'ex-scrim is-on');
            const dialog = el('div', 'ex-dialog');
            const h = el('div', 'ex-dialog-title'); h.textContent = 'Publish a service on ' + machine;
            const sub = el('div', 'ex-dialog-body');
            sub.textContent = 'A service Vaier did not discover as a container — a LAN app, a device’s own page. '
                + 'Name where it listens on ' + machine + ' and Vaier puts it on the internet.';
            const form = el('div', 'ex-form');
            const field = (label, hint, control) => {
                const f = el('div', 'ex-field'); const l = el('label'); l.textContent = label;
                f.append(l, control);
                if (hint) { const hn = el('div', 'ex-hint'); hn.textContent = hint; f.appendChild(hn); }
                return f;
            };
            const text = (ph) => { const i = el('input', 'ex-input'); i.type = 'text'; if (ph) i.placeholder = ph;
                i.autocomplete = 'off'; i.spellcheck = false; return i; };
            const subIn = text('e.g. printer');
            const port = el('input', 'ex-input'); port.type = 'number'; port.min = '1'; port.max = '65535'; port.placeholder = 'e.g. 8080';
            const protocol = el('select', 'ex-input');
            [['http', 'http'], ['https', 'https']].forEach(([v, t]) => { const o = el('option'); o.value = v; o.textContent = t; protocol.appendChild(o); });
            const authRow = el('label', 'ex-check-row');
            const auth = el('input'); auth.type = 'checkbox'; auth.checked = true;
            const atxt = el('span'); atxt.textContent = 'Require a login to reach it';
            authRow.append(auth, atxt);
            form.append(field('Subdomain', null, subIn), field('Port on ' + machine, null, port),
                field('Speaks', 'How the backend serves — http or https.', protocol), authRow);
            const advanced = publishAdvanced(form, '');

            const actions = el('div', 'ex-dialog-actions');
            const cancel = el('button', 'ex-btn'); cancel.textContent = 'Cancel';
            const ok = el('button', 'ex-btn is-accent'); ok.textContent = 'Publish';
            actions.append(cancel, ok);
            dialog.append(h, sub, form, actions);
            scrim.appendChild(dialog); document.body.appendChild(scrim);

            const close = (r) => { scrim.remove(); document.removeEventListener('keydown', onKey); resolve(r); };
            const onKey = (e) => { if (e.key === 'Escape') close(null); };
            const armed = () => subIn.value.trim() !== '' && parseInt(port.value, 10) > 0;
            const sync = () => { ok.disabled = !armed(); };
            subIn.oninput = sync; port.oninput = sync;
            scrim.onclick = (e) => { if (e.target === scrim) close(null); };
            cancel.onclick = () => close(null);
            ok.onclick = () => { if (armed()) close({ subdomain: subIn.value.trim(), port: parseInt(port.value, 10),
                protocol: protocol.value, requireAuth: auth.checked, pathPrefix: advanced.pathPrefix.value.trim(),
                rootRedirectPath: advanced.redirect.value.trim(), directUrlDisabled: !advanced.direct.checked }); };
            document.addEventListener('keydown', onKey);
            sync(); subIn.focus();
        });
    }

    // --- shared field builders (forms and inline pane editors both use these) --------------------------

    function formField(label, hint, control) {
        const f = el('div', 'ex-field');
        const l = el('label'); l.textContent = label; f.append(l, control);
        if (hint) { const hn = el('div', 'ex-hint'); hn.textContent = hint; f.appendChild(hn); }
        return f;
    }
    function plainInput(value, ph) {
        const i = el('input', 'ex-input'); i.type = 'text';
        i.value = value == null ? '' : String(value); if (ph) i.placeholder = ph;
        i.autocomplete = 'off'; i.spellcheck = false;
        return i;
    }
    // An input that saves on blur only when its value actually changed, Enter blurs to save, and its baseline
    // advances on a save that stuck — the same edit-in-place idiom the Infrastructure page used. onSave returns
    // a truthy/Promise result; a save reported as false leaves the baseline so the next blur retries.
    function blurInput(value, ph, onSave) {
        const i = plainInput(value, ph);
        i.dataset.original = i.value;
        i.onkeydown = (e) => { if (e.key === 'Enter') i.blur(); };
        i.onblur = () => {
            const val = i.value.trim();
            if (val === (i.dataset.original || '')) return;
            Promise.resolve(onSave(val)).then((ok) => { if (ok !== false) i.dataset.original = val; });
        };
        return i;
    }
    function checkRow(labelText, checked, onChange) {
        const row = el('label', 'ex-check-row');
        const box = el('input'); box.type = 'checkbox'; box.checked = !!checked;
        box.onchange = () => onChange(box.checked);
        const t = el('span'); t.textContent = labelText;
        row.append(box, t);
        return row;
    }
    // A stable id from a string — for pairing a datalist with its input without Math.random (banned here).
    function hashStr(str) { let h = 0; for (let i = 0; i < str.length; i++) h = (h * 31 + str.charCodeAt(i)) | 0; return h; }
    // The group names already in use across the fleet's routes — a suggestion set, gathered from what Vaier
    // already holds so it needs no extra fetch.
    function accessGroupSuggestions() {
        const set = new Set();
        Object.values(S.access).forEach((gs) => (gs || []).forEach((g) => set.add(g)));
        return Array.from(set).sort();
    }

    // --- editing a published service -------------------------------------------------------------------
    //
    // A published route is not just coordinates to read — who may reach it, what it is called on the launchpad,
    // and a handful of advanced knobs are the operator's. All ride the one PATCH the Infrastructure page used
    // (path-prefixed routes carry their prefix in the query); the allowed groups are the exception — they are an
    // access rule, PUT whole under the route's DNS name. Each save reloads the routes and repaints in place.

    const patchUrl = (s) => {
        const base = '/published-services/' + encodeURIComponent(s.dnsAddress);
        return s.pathPrefix ? base + '?pathPrefix=' + encodeURIComponent(s.pathPrefix) : base;
    };
    function patchService(s, patch, failMsg) {
        return patchJson(patchUrl(s), patch, failMsg || 'Could not save the change.',
            async () => { await loadServices(); render(); });
    }
    async function saveAccessGroups(host, groups) {
        try {
            const r = await fetch('/access/services/' + encodeURIComponent(host) + '/groups', {
                method: 'PUT', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ groups }) });
            if (!r.ok) { const e = await r.json().catch(() => ({})); toast(e.message || 'Could not update the allowed groups.'); return; }
            await loadServices(); render();
        } catch (e) { toast('Could not update the allowed groups.'); }
    }

    function allowedGroupsEditor(s) {
        const host = s.dnsAddress;
        const groups = (S.access[host] || []).slice();
        const wrap = el('div', 'ex-field');
        const l = el('label'); l.textContent = 'Allowed groups'; wrap.appendChild(l);

        const chips = el('div', 'ex-chips');
        if (!groups.length) {
            const empty = el('span', 'ex-chip is-empty'); empty.textContent = 'Any signed-in, approved user';
            chips.appendChild(empty);
        } else {
            groups.forEach((g) => {
                const chip = el('span', 'ex-chip');
                const t = el('span'); t.textContent = g; chip.appendChild(t);
                const x = el('button', 'ex-chip-x'); x.innerHTML = svg('cross', 'ex-ico');
                x.title = 'Remove ' + g; x.setAttribute('aria-label', 'Remove ' + g);
                x.onclick = () => saveAccessGroups(host, groups.filter((y) => y !== g));
                chip.append(x); chips.appendChild(chip);
            });
        }
        wrap.appendChild(chips);

        const row = el('div', 'ex-chip-add');
        const inp = plainInput('', 'Add a group…');
        const listId = 'ex-groups-' + Math.abs(hashStr(host));
        const dl = el('datalist'); dl.id = listId;
        accessGroupSuggestions().filter((g) => !groups.includes(g)).forEach((g) => {
            const o = el('option'); o.value = g; dl.appendChild(o);
        });
        inp.setAttribute('list', listId);
        const add = () => {
            const v = inp.value.trim(); inp.value = '';
            if (!v || groups.some((g) => g.toLowerCase() === v.toLowerCase())) return;
            saveAccessGroups(host, groups.concat(v));
        };
        inp.onkeydown = (e) => { if (e.key === 'Enter') { e.preventDefault(); add(); } };
        const btn = el('button', 'ex-btn'); btn.textContent = 'Add'; btn.onclick = add;
        row.append(inp, dl, btn);
        wrap.appendChild(row);
        const hint = el('div', 'ex-hint');
        hint.textContent = 'Empty means any signed-in, approved user. Otherwise a visitor needs at least one of '
            + 'these groups.';
        wrap.appendChild(hint);
        return wrap;
    }

    function renderService(pane) {
        const machineId = S.path[1];
        const machineName = nameOf(machineId);
        const s = servicesOn(machineId).find((x) => serviceName(x) === S.path[3]);
        if (!s) return pane.appendChild(note('That service is no longer published from ' + machineName + '.',
            true));

        pane.appendChild(paneHead(s.dnsAddress || serviceName(s), true, machineName));

        const body = el('div', 'ex-pane-body');

        // --- do: the route's settings, which are the whole reason to open a service ------------------
        //
        // Two named groups rather than one "What to do next", because they answer two different questions —
        // who may reach this service, and how it appears — and the first of those is the most consequential
        // control on the pane. A field label says which field it is; a band heading says what you are
        // deciding, and collapsing the two left a security setting in an undifferentiated list beside a
        // cosmetic one. The rule that governs a band is its ORDER; a band may still name its groups where
        // they are genuinely about different things.
        body.appendChild(section('Access'));
        const authMode = s.authMode || (s.authenticated ? 'social' : 'none');
        if (s.stream) {
            // No picker, because there is nothing to pick: the backend refuses a login on a stream, and a
            // control that can only be refused is worse than none. Say why instead.
            body.appendChild(hint('Nothing inside a stream is a web request, so Vaier cannot put a login in '
                + 'front of it and CrowdSec cannot watch it. The service’s own password is the only gate.'));
        } else {
            const authSel = el('select', 'ex-input');
            [['none', 'Public — no sign-in'], ['social', 'Social login (Google)']].forEach(([v, t]) => {
                const o = el('option'); o.value = v; o.textContent = t; if (v === authMode) o.selected = true;
                authSel.appendChild(o);
            });
            authSel.onchange = () => patchService(s, { authMode: authSel.value },
                'Could not update the sign-in requirement.');
            body.appendChild(formField('Sign-in', 'Which login a visitor must pass to reach this service.', authSel));
            if (authMode === 'social') body.appendChild(allowedGroupsEditor(s));
        }

        // The launchpad is a wall of links, and a stream has no link — so it has no tile and no name for one.
        if (!s.stream) {
            body.appendChild(section('Launchpad'));
            body.appendChild(formField('Display name', 'The name on its launchpad tile — defaults to the subdomain.',
                blurInput(s.launchpadAlias || '', '(default)',
                    (val) => patchService(s, { launchpadAlias: val }, 'Could not save the display name.'))));
            body.appendChild(checkRow('Show a tile for this service on the launchpad', !s.hiddenFromLaunchpad,
                (checked) => patchService(s, { hiddenFromLaunchpad: !checked },
                    'Could not update the launchpad visibility.')));
        }

        // --- know: what the service is, and the reference behind it -----------------------------------
        //
        // The coordinates — the read-only truth about the route, the name it answers on and its backend.
        body.appendChild(section('About this service'));
        body.appendChild(kv(s.stream ? [
            ['Connect at', coord(s.connectAddress)],
            ['Route', s.state],
            ['Backend', coord((s.hostAddress || '') + (s.hostPort ? ':' + s.hostPort : ''))],
            ['Kind', 'Stream (TCP)'],
            ['Container image', coord(s.image)],
            ['Version', coord(s.version)],
        ] : [
            ['Address', coord(s.dnsAddress)],
            ['Route', s.state],
            ['Backend', coord((s.hostAddress || '') + (s.hostPort ? ':' + s.hostPort : ''))],
            ['Path prefix', coord(s.pathPrefix)],
            ['Container image', coord(s.image)],
            ['Version', coord(s.version)],
        ]));

        // Rarely wanted, so folded — but named by what is inside it. "Advanced" said only that the operator
        // was unlikely to want it, which is not enough to decide whether to open it. Every knob in it is an
        // HTTP one — a redirect, a URL to probe, a URL to swap — so a stream is not offered the fold at all.
        if (!s.stream) {
        const adv = disclosure('Root redirect, version probe and the direct LAN link');
        adv.appendChild(formField('Root redirect', 'Send the bare address straight on to a sub-path.',
            blurInput(s.rootRedirectPath || '', 'e.g. /dashboard',
                (val) => patchService(s, { rootRedirectPath: val }, 'Could not save the redirect.'))));

        const ve = plainInput(s.versionEndpoint || '', '/sys/metrics');
        const vp = plainInput(s.versionProperty || '', 'property');
        ve.dataset.original = s.versionEndpoint || ''; vp.dataset.original = s.versionProperty || '';
        const saveVersion = () => {
            const endpoint = ve.value.trim(), property = vp.value.trim();
            if (endpoint === (ve.dataset.original || '') && property === (vp.dataset.original || '')) return;
            patchService(s, { versionEndpoint: endpoint, versionProperty: property },
                'Could not save the version endpoint.').then((ok) => {
                    if (ok !== false) { ve.dataset.original = endpoint; vp.dataset.original = property; }
                });
        };
        ve.onblur = saveVersion; vp.onblur = saveVersion;
        ve.onkeydown = vp.onkeydown = (e) => { if (e.key === 'Enter') e.target.blur(); };
        const verPair = el('div', 'ex-field-pair'); verPair.append(ve, vp);
        adv.appendChild(formField('Version endpoint',
            'Where Vaier reads this service’s running version, and the JSON property to read from it.', verPair));

        adv.appendChild(checkRow('Link straight to the LAN URL when the visitor shares its network',
            !s.directUrlDisabled,
            (checked) => patchService(s, { directUrlDisabled: !checked },
                'Could not update the direct LAN URL setting.')));
        body.appendChild(adv);
        }

        // The point of the single namespace, said plainly. These three are not three things that happen to
        // share a name — they are one service, and when one of them is wrong the service is down.
        body.appendChild(note('A published service is one thing with three homes: a container on '
            + machineName + ', a route through Traefik, and the name it answers on — '
            + (s.dnsAddress || 'its name') + '. '
            + (s.stream ? 'Traefik answers that name on 443, proves it with a Let’s Encrypt certificate, '
                + 'and hands the bytes to the backend port unchanged. ' : '')
            + 'Unpublishing removes the route; the name goes on resolving '
            + 'under your wildcard record, which is yours and Vaier never touches. The container keeps '
            + 'running — the machine that hosts it does not notice.', false));

        // --- danger: the one verb that takes the service off the internet -----------------------------
        //
        // Still the last thing on the page, directly under the paragraph that says what it does, and never in
        // the pane header — a destructive verb parked in the chrome is one mis-tap from the title while you
        // are reading the settings. Now behind the fold that is coloured for consequence, so reaching it is
        // deliberate twice over.
        const gone = dangerFold('Unpublish this service');
        const danger = el('div', 'ex-lactions is-static');
        const del = el('button', 'ex-btn is-danger'); del.textContent = 'Unpublish';
        del.onclick = () => unpublish(s, machineId);
        danger.appendChild(del);
        gone.appendChild(danger);
        body.appendChild(gone);
        pane.appendChild(body);
    }

    // --- disk -------------------------------------------------------------------------------------------
    //
    // Every real filesystem on the machine, not just the root one (#325). Vaier used to read `df -P /` — on
    // the NAS that is the 2.3 GB DSM system partition, 88% by design and never moving, while /volume1 (11.6
    // TB, every borg backup) was invisible and could have filled to 100% in silence.
    //
    // The verdict is the server's, always: RemoteDiskUsage.breaches resolves mute, the filesystem's own
    // threshold and the global fallback — once, for the alert email and for this pane alike. The browser
    // paints what it is told and never re-decides it, which is why changing a threshold re-reads rather than
    // recomputing locally.

    function renderDisk(pane) {
        const machineId = S.path[1];
        const machineName = nameOf(machineId);
        pane.appendChild(paneHead('Disk', false, 'Filesystems'));

        const body = document.createElement('div');
        body.className = 'ex-pane-body';
        pane.appendChild(body);

        if (!S.disks.has(machineId)) loadDisk(machineId);      // it re-renders when it lands

        const held = S.disks.get(machineId);
        if (!held || held.state === 'loading') {
            return body.appendChild(note('Reading the disks on ' + machineName + '…', false));
        }
        if (held.state === 'error') return failureNote(body, machineId, held);

        held.filesystems.forEach(fs => body.appendChild(filesystemBlock(fs, machineId)));

        body.appendChild(note('Vaier reads these with df over SSH, on a schedule, and emails the admins when '
            + 'a watched filesystem crosses its threshold. A filesystem with no threshold of its own is '
            + 'judged against the fleet-wide one, set on Settings. Mute the ones that are full by design — '
            + 'a DSM system partition sits near-full forever — but mute them deliberately: everything Vaier '
            + 'has not been told about is watched.', false));
    }

    // One filesystem: what it is, how full, what it is judged against, and whether Vaier is watching at all.
    // Mount point and device are coordinates, so they are mono — the shell's one type rule.
    function filesystemBlock(fs, machineId) {
        const block = document.createElement('div');
        block.className = 'ex-fs' + (fs.aboveThreshold ? ' is-over' : '') + (fs.watched ? '' : ' is-muted');

        const top = document.createElement('div');
        top.className = 'ex-fs-top';

        const mount = document.createElement('span');
        mount.className = 'ex-fs-mount';
        mount.textContent = fs.mountPoint;
        top.appendChild(mount);

        const dev = document.createElement('span');
        dev.className = 'ex-fs-dev';
        dev.textContent = fs.device;
        top.appendChild(dev);

        const size = document.createElement('span');
        size.className = 'ex-fs-size';
        size.textContent = fs.size + ' · ' + fs.available + ' free';
        top.appendChild(size);
        block.appendChild(top);

        block.appendChild(meter(fs.usedPercent, fs.thresholdPercent, fs.aboveThreshold, fs.watched));
        block.appendChild(watchControl(fs, machineId));
        return block;
    }

    // The mute switch and the threshold. Both write to PUT /machines/{machine}/disk/watch — the mount point
    // travels in the body, because a mount point is full of slashes and a path variable carrying them is an
    // encoding bug waiting to happen.
    function watchControl(fs, machineId) {
        const ctl = document.createElement('div');
        ctl.className = 'ex-fs-ctl';

        const watchLabel = document.createElement('label');
        const watch = document.createElement('input');
        watch.type = 'checkbox';
        watch.checked = fs.watched;
        watch.addEventListener('change',
            () => saveWatch(machineId, fs.mountPoint, watch.checked, fs.thresholdPercent));
        const watchText = document.createElement('span');
        watchText.textContent = 'Watch';
        watchLabel.append(watch, watchText);
        ctl.appendChild(watchLabel);

        const threshLabel = document.createElement('label');
        const threshText = document.createElement('span');
        threshText.textContent = 'Alert above';
        const thresh = document.createElement('input');
        thresh.type = 'number';
        thresh.className = 'ex-thresh';
        thresh.min = '1';
        thresh.max = '100';
        thresh.value = fs.thresholdPercent;
        thresh.disabled = !fs.watched;
        thresh.addEventListener('change',
            () => saveWatch(machineId, fs.mountPoint, fs.watched, Number(thresh.value)));
        const pct = document.createElement('span');
        pct.textContent = '%';
        threshLabel.append(threshText, thresh, pct);
        ctl.appendChild(threshLabel);
        return ctl;
    }

    // Write the watch, then re-read the machine's disks. The re-read is the point: the breach verdict is the
    // domain's, so a new threshold has to come back FROM the server rather than being recomputed here. The
    // email and this pane can never disagree, because only one of them ever decides.
    async function saveWatch(machineId, mountPoint, watched, thresholdPercent) {
        try {
            const res = await fetch('/machines/' + encodeURIComponent(machineId) + '/disk/watch', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ mountPoint, watched, thresholdPercent })
            });
            if (!res.ok) {
                alert('Vaier could not save the watch on ' + mountPoint + '.');
                return;
            }
        } catch (e) {
            alert('Vaier could not save the watch on ' + mountPoint + '.');
            return;
        }
        S.disks.delete(machineId);
        loadDisk(machineId);
    }

    // The shell's one figure, once per filesystem. The bar is how full it is; the tick is what that is being
    // judged against — its own threshold or the fleet-wide one, drawn where it actually falls. Without it the
    // operator would do the comparison in their head, and that comparison is a decision Vaier has already
    // made (RemoteDiskUsage.breaches) and emails about.
    //
    // A muted filesystem loses its tick, because nothing is being judged: muting is not a quieter alert, it
    // is the absence of one, and the figure should say so.
    function meter(usedPercent, thresholdPercent, above, watched) {
        const wrap = document.createElement('div');
        wrap.className = 'ex-meter' + (above ? ' is-over' : '') + (watched ? '' : ' is-muted');

        const fill = document.createElement('span');
        fill.className = 'ex-meter-fill';
        fill.style.width = usedPercent + '%';
        wrap.appendChild(fill);

        if (watched) {
            const tick = document.createElement('span');
            tick.className = 'ex-meter-tick';
            tick.style.left = thresholdPercent + '%';
            tick.title = 'Alert threshold: ' + thresholdPercent + '%';
            wrap.appendChild(tick);
        }

        const label = document.createElement('span');
        label.className = 'ex-meter-label';
        label.textContent = usedPercent + '%';
        wrap.appendChild(label);
        return wrap;
    }

    // Unpublish is the one verb slice C ships, because it is the one verb the backend actually has:
    // DELETE /published-services/{dnsName}. It tears down a Traefik route, so it asks first.
    async function unpublish(s, machineId) {
        const label = s.dnsAddress || serviceName(s);
        if (!confirm('Unpublish ' + label + '?\n\nThis removes its Traefik route, so the name stops '
            + 'answering. The container on ' + nameOf(machineId) + ' keeps running.')) return;

        const query = s.pathPrefix ? '?pathPrefix=' + encodeURIComponent(s.pathPrefix) : '';
        try {
            const res = await fetch('/published-services/' + encodeURIComponent(s.dnsAddress) + query,
                { method: 'DELETE' });
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                alert(err.message || 'Vaier could not unpublish ' + label + '.');
                return;
            }
        } catch (e) {
            alert('Vaier could not unpublish ' + label + '.');
            return;
        }
        // Stand where the service used to be: its parent. The route is gone, so the entry is gone with it.
        await loadServices();
        go(['fleet', machineId, 'services']);
    }

    // Two shapes the Inspector lists things in, built once: a container list and a service list are the same
    // kind of surface as a directory listing, because in one namespace they are the same kind of thing.
    function listHead(labels) {
        const head = document.createElement('div');
        head.className = 'ex-lhead';
        labels.forEach((h) => {
            const cell = document.createElement('span');
            cell.textContent = h;
            head.appendChild(cell);
        });
        return head;
    }

    // `mark` is an optional badge riding just after the name — the same shape the file browser's shield takes.
    // `ico` arrives already built, so a listed container is tinted by the same seam every entry uses.
    function listRow(ico, name, onClick, meta, state, mark) {
        const row = document.createElement('div');
        row.className = 'ex-lrow';

        const btn = document.createElement('button');
        btn.className = 'ex-lname';
        btn.innerHTML = ico;
        const nm = document.createElement('span');
        nm.className = 'ex-nm';
        nm.textContent = name;
        btn.appendChild(nm);
        if (mark) btn.appendChild(mark);
        btn.onclick = onClick;
        row.appendChild(btn);

        meta.forEach((value, i) => {
            const cell = document.createElement('span');
            cell.className = 'ex-lmeta';
            // The last column is a state, and a state has a colour — the same four the fleet's dots use.
            if (i === meta.length - 1 && state !== undefined) {
                cell.appendChild(stateDot(state));
            }
            const text = document.createElement('span');
            text.textContent = value;
            cell.appendChild(text);
            row.appendChild(cell);
        });
        // The same columns again as one line under the name, for a screen with no room for columns. Without
        // it a phone would simply lose them — a container row would be a name and nothing else, no image, no
        // state — so the narrow layout would be hiding facts rather than rearranging them. The state keeps
        // its dot here too: it is the one column that is a colour before it is a word.
        const sub = document.createElement('span');
        sub.className = 'ex-lsub';
        if (state !== undefined) sub.appendChild(stateDot(state));
        const subText = document.createElement('span');
        subText.textContent = meta.filter(Boolean).join(' · ');
        sub.appendChild(subText);
        row.appendChild(sub);
        return row;
    }

    // --- the Update available mark (#57) ---------------------------------------------------------------
    //
    // Whether a newer image is being served for the tag a container runs is NOT decided here. The domain owns
    // it — UpdateAvailability.compare() weighs the two digests, once, and the backend stamps each container
    // with the verdict. The sweep that mails the admins and this mark therefore say the same thing about the
    // same container by construction; a second comparison in JS is exactly how they would drift apart. The
    // shell reads one enum and paints it, and never sees a digest at all.
    //
    // Only UPDATE_AVAILABLE draws. UNKNOWN — registry unreachable, image built locally, tag pinned to an
    // immutable digest, or simply no sweep yet — draws nothing: it is the ordinary resting state, and a mark
    // on every row for it would train the operator to ignore the column that matters. The silence is not a
    // promise that the image is current, which is why renderContainer() says the verdict in words.
    //
    // The mark itself is still not a verb — it is a reading, and a row of them is a list of readings. The verb
    // lives one click away on the container's own page, where there is room to say what it will do first, so
    // the tooltip points at it rather than carrying it. For a container Vaier will not recreate, the tooltip
    // keeps naming the operator's own action, because that is the only one there is.
    function updateMark(container) {
        // The past has no update to report: an archive is how a filesystem stood then, and the registry's
        // answer is about now. (Same reasoning as the liveness dot, which the stylesheet hides in the past.)
        if (S.at || !container || container.updateAvailable !== 'UPDATE_AVAILABLE') return null;
        const mark = el('span', 'ex-update');
        mark.innerHTML = svg('arrowup', 'ex-ico');
        const act = container.updateEligibility === 'UPDATABLE'
            ? 'open this container to update it'
            : 'pull this image on the machine yourself';
        // A moving tag serves a new image every day or so, so this row lights up most mornings and no mail
        // ever comes. One word says which kind of tag it is; the tooltip says what that costs the operator.
        // Whether the tag moves is the backend's reading, exactly as the verdict is — see movingTag.
        if (container.movingTag) {
            const kind = el('span', 'ex-moving');
            kind.textContent = 'moving';
            mark.appendChild(kind);
            mark.title = 'Update available on a moving tag — this tag has been serving a new image every '
                + 'day or so, so Vaier marks it here and never mails about it. To take it, ' + act + '.';
            return mark;
        }
        mark.title = 'Update available — ' + act;
        return mark;
    }

    // The verdict in words, for the one place with room to be honest about not knowing.
    const UPDATE_SAYS = {
        UPDATE_AVAILABLE: 'Update available',
        UP_TO_DATE: 'Up to date',
        UNKNOWN: 'Vaier cannot tell',
    };

    /**
     * What the Update row says (#353). Vaier's own stack is no longer asked about at all — its images are
     * pinned by a Vaier release and move when Vaier does — so those containers carry no verdict, and
     * rendering "Vaier cannot tell" for them would be true, useless, and would read as a gap where there is
     * a rule. Say the rule instead. Every other container still gets the domain's verdict, unaltered.
     */
    function updateSays(c) {
        if (c.updateEligibility === 'VAIER_OWN_STACK') return 'Moves with Vaier — update from Settings';
        const said = UPDATE_SAYS[c.updateAvailable] || UPDATE_SAYS.UNKNOWN;
        // The one surface with room for the reason, so it is where the silence is explained rather than
        // left to look like Vaier having missed something.
        return c.movingTag && c.updateAvailable === 'UPDATE_AVAILABLE'
            ? said + ' — a moving tag, so Vaier marks it and never mails about it'
            : said;
    }

    // --- the Update action (#352) ----------------------------------------------------------------------
    //
    // The verb the mark above never had. For its whole life the update-available mark was advice with no way
    // to act on it, and the operator ended up SSH-ing to the host to do by hand the thing Vaier had just told
    // them to do. This is that thing, done from here.
    //
    // Whether a container may be updated at all is NOT decided here. ContainerUpdateEligibility took that decision on
    // the machine the container was scraped from, which is the only place that can: the same container name
    // means Vaier's own stack on this host and the operator's own container on any other, and a name alone
    // cannot tell you which. The shell reads one enum, exactly as it does for the update verdict.
    //
    // What Vaier does with it is compose's own `pull` then `up -d`, over SSH, from the coordinates the
    // container carries about itself — never a rebuild from `docker inspect`, which comes back subtly
    // different and reports success.

    // Why the button is withheld, in the operator's terms. Both are ordinary states, not faults, so neither
    // sentence is an error — it says what is true and, where there is one, what to do instead.
    const UPDATE_WITHHELD = {
        NOT_COMPOSE_MANAGED: 'Vaier will not update this one: it was started by hand rather than by compose, '
            + 'so there is no compose file to recreate it from. Recreating it anyway would drop whatever it '
            + 'was started with. Update it on the machine itself.',
        VAIER_OWN_STACK: 'This is part of Vaier’s own stack. Its image is pinned by a Vaier release and moves '
            + 'when Vaier does — update it from Settings, not one container at a time.',
        // The one withheld reason that is somebody's to fix, so it says how. Vaier reads this machine's Docker
        // over the tunnel, which needs no group at all — which is exactly why the machine looks healthy while
        // being unable to act. It re-checks on its own rounds, so the button returns without anyone asking.
        NO_DOCKER_ACCESS: 'Vaier can read this machine’s Docker but not act on it: the user it signs in as is '
            + 'not in the machine’s docker group. Add it there — you can see which user under the machine’s '
            + 'SSH credential — and Vaier will offer this again on its own.',
    };

    // Which updates this browser is waiting on. Keyed by machine and container, because a name is only unique
    // on one machine, and the operator can start one on a peer and go and look at another.
    const _updating = new Set();
    const updateKey = (machineId, containerName) => machineId + ' ' + containerName;

    /**
     * The one control the Explorer offers over a container: a button, a reason it is withheld, or nothing at
     * all. Offered only against a real update — recreating a container that is already on the newest image is
     * a service interruption bought for nothing.
     */
    function updateAction(machineId, c) {
        // The past has no container to act on: an archive is how a machine stood then, and this acts on now.
        if (S.at || c.updateAvailable !== 'UPDATE_AVAILABLE') return null;
        if (c.updateEligibility !== 'UPDATABLE') {
            const said = UPDATE_WITHHELD[c.updateEligibility];
            return said ? note(said, false) : null;
        }
        const waiting = _updating.has(updateKey(machineId, c.containerName));
        const act = el('div', 'ex-lactions is-static');
        const btn = selVerb('arrowup', waiting ? 'Updating…' : 'Update', 'ex-btn is-accent',
            () => updateContainer(machineId, c.containerName));
        btn.title = 'Pull the image this container’s registry now serves and recreate it from its own compose '
            + 'file. The container is briefly down while it restarts.';
        btn.disabled = waiting;
        act.appendChild(btn);
        return act;
    }

    /**
     * Ask for the update and stop. A pull is minutes of somebody else's bandwidth, so the backend accepts the
     * request and answers 202; the outcome arrives on the fleet stream this shell already holds open. Nothing
     * here waits, and nothing here asks again — the settled event is what ends it.
     */
    async function updateContainer(machineId, containerName) {
        const key = updateKey(machineId, containerName);
        if (_updating.has(key)) return;
        // Said before it happens, because it happens to something that is running. The reassurance is real and
        // it is the domain's: a recreate that fails leaves the old container up, on the image it already had.
        const sure = await confirmModal('Update ' + containerName + '?',
            'Vaier will pull the newer image on ' + nameOf(machineId) + ' and recreate ' + containerName
            + ' from its own compose file. It is down for a moment while it restarts. If the recreate fails, '
            + 'the old container keeps running on the image it has now.', 'Update');
        if (!sure) return;

        _updating.add(key);
        render();
        try {
            const res = await fetch('/docker-services/update', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ machineId: machineId, containerName: containerName }),
            });
            if (!res.ok) {
                // A refusal is a status code on purpose, so it can never be a settled event that never comes.
                _updating.delete(key);
                toast(await refusalSays(res, containerName));
                render();
            }
        } catch (e) {
            _updating.delete(key);
            toast('Vaier could not ask ' + nameOf(machineId) + ' for that update.');
            render();
        }
    }

    // Why an update was refused before it started. The backend distinguishes these deliberately; collapsing
    // them into "something went wrong" would throw away the one part the operator can act on.
    async function refusalSays(res, containerName) {
        if (res.status === 424) return 'Vaier holds no SSH login for that machine, so it cannot update '
            + containerName + '. Store one under the machine first.';
        if (res.status === 404) return 'Vaier no longer sees a container named ' + containerName
            + ' on that machine.';
        try {
            const body = await res.json();
            if (body && body.message) return body.message;
        } catch (e) { /* a refusal without a body is still a refusal */ }
        return 'Vaier would not update ' + containerName + '.';
    }

    // How it ended, in the words the domain wrote. The shell shows the sentence it was handed rather than
    // turning the enum back into English of its own: "the old container is still running on the image it had"
    // is the whole point of a failed recreate, and it must not be lost between the two.
    function onUpdateSettled(payload) {
        _updating.delete(updateKey(payload.machineId, payload.containerName));
        toast(payload.message);
        // Re-read the containers whatever the outcome: on success the scrape now reads the new digest and the
        // mark clears itself, and on a failure the operator is looking at what is actually there.
        return loadContainers();
    }

    // --- checking the registries on demand (#57 slice 3) -----------------------------------------------
    //
    // The sweep behind the mark runs once a day, which leaves the operator ahead of it: they read the rollup
    // mail, pull the image, and Vaier goes on saying "update available" for hours about something already
    // fixed. That lingering mark is corrosive — a mark you know is wrong is a mark you learn to ignore — and
    // it would eventually cost the whole column its credibility. This is the button that settles it.
    //
    // It is a legitimate control on a page that deliberately offers no container verbs (see renderContainer)
    // for exactly one reason: it acts on VAIER'S OWN KNOWLEDGE, never on a container. It checks. It cannot
    // pull, cannot restart, and there is no endpoint for it to do either with. Hence "Check the registries
    // now" rather than "Check for updates": the latter is what every OS updater says immediately before
    // installing something, and that connotation is the one thing this must not carry.
    //
    // Nothing here decides anything. The browser asks the backend to re-take the verdict and renders what it
    // is told; UpdateAvailability.compare() in the domain remains the only place two digests ever meet.
    let _updateCheck = null;        // the last outcome, exactly as the backend reported it
    let _updateChecking = false;

    async function checkForUpdates() {
        if (_updateChecking || S.at) return;         // the archive has no "now" to re-check against
        _updateChecking = true;
        _updateCheck = null;
        render();
        try {
            const res = await fetch('/docker-services/image-updates/check', { method: 'POST' });
            _updateCheck = res.ok ? await res.json() : { failed: true };
        } catch (e) {
            _updateCheck = { failed: true };
        }
        _updateChecking = false;
        // A moved verdict is pushed to every open Explorer on `published-services` (watchServices re-reads
        // the containers on it). This browser re-reads anyway: the operator who clicked must never be left
        // waiting on an event to see the answer they personally asked for.
        await loadContainers();
    }

    // What Vaier says it did — and it only ever says what actually happened. "Checked, nothing new" and "did
    // not check, here is when it last did" are different facts, and the backend keeps them apart precisely so
    // this can too. None of these sentences may imply Vaier changed anything: it read.
    function updateCheckNote() {
        if (_updateChecking) return note('Checking the registries…', false);
        if (!_updateCheck) return null;
        if (_updateCheck.failed) {
            return note('Vaier could not reach the registries just now, so the marks above stand as they '
                + 'were. Nothing on any machine was touched.', true);
        }
        if (!_updateCheck.checked) {
            // The floor refused. Say so plainly rather than painting a tick over a check that never ran — and
            // say it by reporting WHEN Vaier last looked, which is the fact the backend sent for exactly this
            // purpose. Spelling the floor's own length out in prose here would copy a domain constant into
            // English: change UpdateCheckFloor and the sentence quietly becomes false — the same
            // wrong-but-confident claim this whole feature exists to stop making.
            return note('Vaier last checked ' + timeAgo(_updateCheck.lastCheckedAt) + ', so it did not ask '
                + 'again — that answer still stands.', false);
        }
        return note(_updateCheck.changed
            ? 'Checked just now against the registries.'
            : 'Checked just now against the registries — nothing new.', false);
    }

    // A published service's State is the domain's own (OK / UNKNOWN / anything else is down) — read exactly
    // as the Infrastructure page reads it, so one service cannot be green on one page and red on the other.
    function stateDot(state) {
        const el = document.createElement('span');
        const key = state === 'OK' ? 'is-up' : (state === 'UNKNOWN' ? 'is-idle' : 'is-down');
        el.className = 'ex-dot ' + key;
        return el;
    }

    // --- the backup server: the fleet's one archive destination, given a coordinate --------------------
    //
    // Identity lives here — which machine plays the role, and how Vaier reaches its `borg serve` — with the
    // repositories that live on it shown read-only. The operations that stand a server up or trust a client
    // (provision, authorize) poll for their outcome, and the shell never polls, so those stay on the Backups
    // page; this entry links there. Editing the coordinates and removing the designation are single calls, and
    // they live here, because designating the backup server is now a thing you do to a machine.

    // Where a borg server is reached on the machine's own network — its LAN address, the CIDR's host part, or,
    // failing both, its tunnel address. A guess to prefill the field, never the last word: the operator edits it.
    function machineHostGuess(m) {
        if (m.lanAddress) return m.lanAddress;
        if (m.lanCidr) return String(m.lanCidr).split('/')[0];
        return tunnelAddress(m) || '';
    }

    // The store keys a server by name, and a name is a shell/path token — so a machine name is slugged into one
    // the same way BackupServer.sanitizedName does on the server. Mirrored here, not shared: it is the client
    // side of the same rule, and the server validates again.
    function serverNameFor(machineName) {
        return String(machineName).trim()
            .replace(/[^A-Za-z0-9_-]/g, '-').replace(/-{2,}/g, '-').replace(/^-|-$/g, '');
    }

    // A machine's `backup` entry is its whole part in fleet backup — read two ways. The one machine that hosts
    // the borg shows the server and its repositories; every machine a job backs up shows that job, its last run
    // and how to run it now. A machine that is neither has no `backup` entry at all (childrenOf never grows one).
    // The rare machine that is both stacks the two sections.
    function renderBackup(pane) {
        const machineId = S.path[1];
        const machineName = nameOf(machineId);
        const s = S.backupServer;
        const isServer = s && s.machineName === machineName;
        const jobs = jobsOn(machineId);
        if (!isServer && !jobs.length) {
            return pane.appendChild(note('This machine has no part in fleet backup — it is not the backup '
                + 'server, and no job backs it up.', true));
        }
        pane.appendChild(paneHead('Backup', false,
            isServer ? 'The fleet’s backup server' : 'How this machine is backed up'));
        const body = el('div', 'ex-pane-body');
        // One machine can be both the store and a thing stored, and then this pane is two halves stacked.
        // The server half ends in what can undo work, so appending it in place would leave a danger fold
        // sitting in the middle of the page with a job's bands after it. It is handed back and landed last,
        // where danger belongs on every other pane.
        const serverRisk = isServer ? renderServerBackup(body, machineId, s) : null;
        if (jobs.length) renderJobsBackup(body, machineId, jobs);
        if (serverRisk) body.appendChild(serverRisk);
        pane.appendChild(body);
    }

    // The machine that hosts the fleet's borg: its coordinates, the repositories on it (each an entry of its
    // own), and the identity actions. The operations that poll for an outcome stay on the Backups bridge.
    function renderServerBackup(body, machineId, s) {
        // The operator pointed at this machine and said "keep the fleet's backups here". Everything that
        // followed — the borg user, the paths under it, the port Vaier reaches it on — Vaier chose, and none
        // of it is a decision to revisit. So the entry opens on what the machine is actually doing (what it
        // keeps), and its coordinates fold away for the day someone needs to check them.
        // What this machine is keeping, by machine — not a list of borg repositories with their paths. The
        // operator never made these and never named them: Vaier creates one per machine behind the Back up
        // verb. There is deliberately no way to add one by hand here, because doing that by hand is what
        // once minted a second store with a fresh passphrase over a live borg repository and orphaned it.
        body.appendChild(section('Backups kept here'));
        const repos = reposOn(s);
        if (!repos.length) {
            body.appendChild(note('Nothing is backed up here yet. Tick files or folders on a machine and '
                + 'choose Back up — Vaier will set the rest up itself.', false));
        } else {
            const list = el('div', 'ex-brepos');
            repos.forEach((r) => {
                const row = el('div', 'ex-brepo');
                const nm = el('button', 'ex-brepo-name is-link');
                nm.textContent = repoLabel(r.name);
                nm.onclick = () => go(['fleet', machineId, 'backup', r.name]);
                const held = el('span', 'ex-brepo-path');
                // The one fact worth carrying in a list of machines: whether anything still backs up to it.
                held.textContent = S.backupJobs.some((j) => j.repositoryName === r.name)
                    ? '' : 'no machine backs up here';
                row.append(nm, held);
                list.appendChild(row);
            });
            body.appendChild(list);
        }

        // know: the coordinates, which are Vaier's own choices and not decisions to revisit. They fold for
        // the day someone needs to check them, and the fold is plain because reading them costs nothing.
        const adv = disclosure('Server details');
        adv.appendChild(kv([
            ['Machine', s.machineName],
            ['Reached at', coord(s.host + ':' + s.sshPort)],
            ['Borg user', coord(s.borgUser)],
            ['Base repo path', coord('/' + s.baseRepoPath)],
            ['Server data path', coord(s.serverDataPath)],
            ['Stood up by Vaier', s.managed ? 'Yes' : 'No — adopted'],
        ]));
        body.appendChild(adv);

        // danger: the operations. They are things Vaier does for the operator already — it provisions when it
        // can, and it authorizes a host as part of backing that host up — kept only as the manual fallback for
        // the hosts where it cannot (a Synology it has no root on). They stay folded for that reason, but they
        // no longer share a fold with the coordinates: the last one in the row takes the fleet's backup server
        // away, and a fold that says "Server details" gave no warning that opening it reached a verb like that.
        const risky = dangerFold('Provision, authorize or remove this backup server');
        const ops = el('div', 'ex-lactions is-static');
        ops.appendChild(selVerb('refresh', 'Provision', 'ex-btn', () => provisionBackupServer(s)));
        ops.appendChild(selVerb('shield', 'Authorize a host', 'ex-btn', () => authorizeHostDialog(s)));
        ops.appendChild(selVerb('download', 'Setup script', 'ex-btn', () => downloadBackupSetup(s.name)));
        ops.appendChild(selVerb('gear', 'Edit coordinates', 'ex-btn', () => editBackupServer(s)));
        ops.appendChild(selVerb('trash', 'Remove designation', 'ex-btn is-danger', () => removeBackupServer(s)));
        risky.appendChild(ops);
        // Handed back rather than appended: renderBackup lands it after a job's bands when this machine is
        // both the store and stored, so there is only ever one thing at the foot of the pane.
        return risky;
    }

    // --- Backup server operations (ported from the retired Backups page, #323) --------------------------
    // Provision, authorize a host, and download the setup script — the ops that stayed on the bridge because
    // provisioning awaits an outcome, and the shell never polls. Provisioning runs detached on the host; a
    // backend sweep pushes `provision-settled` on the backups stream, which watchBackups routes back to the
    // open dialog. Everything else here is a plain request/response.

    // The setup script is a one-shot host bootstrap that holds no secret — a direct browser download.
    function downloadBackupSetup(name) {
        const a = document.createElement('a');
        a.href = '/backup-servers/' + encodeURIComponent(name) + '/setup.sh';
        a.download = name + '-setup.sh';
        document.body.appendChild(a); a.click(); a.remove();
        toast('Downloading the setup script for ' + name + '.');
    }

    // The running/succeeded/failed word, coloured like the rest of the shell's inline status notes.
    function provisionStatus(state) {
        const cls = state === 'SUCCESS' ? 'ex-set-note is-ok'
            : state === 'FAILED' ? 'ex-set-note is-err' : 'ex-set-note';
        const row = el('div', cls);
        row.textContent = state === 'SUCCESS' ? 'Succeeded' : state === 'FAILED' ? 'Failed' : 'Running…';
        return row;
    }

    function provisionBackupServer(s) {
        const scrim = el('div', 'ex-scrim is-on');
        const dialog = el('div', 'ex-dialog is-wide');
        const h = el('div', 'ex-dialog-title'); h.textContent = 'Provision · ' + s.name;
        const bodyEl = el('div', 'ex-dialog-body'); bodyEl.textContent = 'Starting…';
        const actions = el('div', 'ex-dialog-actions');
        const done = el('button', 'ex-btn'); done.textContent = 'Close';
        actions.appendChild(done);
        dialog.append(h, bodyEl, actions);
        scrim.appendChild(dialog); document.body.appendChild(scrim);
        const close = () => {
            scrim.remove(); document.removeEventListener('keydown', onKey);
            if (S.provisionWatch && S.provisionWatch.bodyEl === bodyEl) S.provisionWatch = null;
        };
        const onKey = (e) => { if (e.key === 'Escape') close(); };
        done.onclick = close;
        scrim.onclick = (e) => { if (e.target === scrim) close(); };
        document.addEventListener('keydown', onKey);

        fetch('/backup-servers/' + encodeURIComponent(s.name) + '/provision', { method: 'POST' })
            .then(async (r) => {
                const result = await r.json().catch(() => ({}));
                if (!r.ok) { bodyEl.textContent = result.message || 'Could not start provisioning.'; return; }
                if (result.scriptOnly) { renderProvisionScriptOnly(bodyEl, s, result); return; }
                if (result.started) {
                    // Detached on the host; the backend watches it and pushes provision-settled. No polling.
                    bodyEl.textContent = '';
                    bodyEl.appendChild(provisionStatus('RUNNING'));
                    bodyEl.appendChild(note('Provisioning started — pulling the borg-server image and starting it. '
                        + 'This updates itself when it finishes; you can close it and carry on.', false));
                    S.provisionWatch = { serverName: s.name, bodyEl: bodyEl };
                    return;
                }
                bodyEl.textContent = '';
                bodyEl.appendChild(provisionStatus(result.provisioned ? 'SUCCESS' : 'FAILED'));
                if (result.message) bodyEl.appendChild(note(result.message, !result.provisioned));
            })
            .catch(() => { bodyEl.textContent = 'Could not reach Vaier to start provisioning.'; });
    }

    // Vaier couldn't drive Docker over SSH: either it staged the script on the host (show the one command to
    // run) or it couldn't (offer the download). Directive guidance, not an error — the same fork the bridge drew.
    function renderProvisionScriptOnly(bodyEl, s, result) {
        bodyEl.textContent = '';
        if (result.stagedScriptPath) {
            const cmd = 'sudo bash ' + result.stagedScriptPath;
            bodyEl.appendChild(note('Vaier can’t drive Docker over SSH on ' + s.machineName + ', so it placed the '
                + 'setup script on the host. Run this on ' + s.machineName + ' to finish:', false));
            const code = el('div', 'ex-config'); code.textContent = cmd;
            bodyEl.appendChild(code);
            const copy = el('button', 'ex-btn'); copy.textContent = 'Copy command';
            copy.onclick = () => {
                if (navigator.clipboard) navigator.clipboard.writeText(cmd).then(() => toast('Copied.'));
                else toast(cmd);
            };
            bodyEl.appendChild(copy);
            return;
        }
        bodyEl.appendChild(note('Vaier can’t drive Docker over SSH on ' + s.machineName + ' and couldn’t stage the '
            + 'script there. Download setup.sh, copy it to the host, and run it with sudo.'
            + (result.message ? ' ' + result.message : ''), false));
        const dl = el('button', 'ex-btn is-accent'); dl.textContent = 'Download setup.sh';
        dl.onclick = () => downloadBackupSetup(s.name);
        bodyEl.appendChild(dl);
    }

    // Trust a machine's SSH host key on the backup server, so its backups can reach it. A no-op (already
    // trusted) reads as reassurance, not an error, and a second line reports whether the server's own key
    // could be pinned back on the client (no trust-on-first-use), the same two verdicts the bridge showed.
    function authorizeHostDialog(s) {
        const scrim = el('div', 'ex-scrim is-on');
        const dialog = el('div', 'ex-dialog');
        const h = el('div', 'ex-dialog-title'); h.textContent = 'Authorize a host · ' + s.name;
        const bodyEl = el('div', 'ex-dialog-body');
        bodyEl.appendChild(note('Trust a machine’s SSH key on this backup server so its backups can reach it.', false));
        // The option's value is the machine's identity and its text is the name: one addresses the machine,
        // the other is for the person choosing it.
        const choices = sortedMachines();
        const sel = el('select', 'ex-input');
        const ph = el('option'); ph.value = ''; ph.disabled = true; ph.selected = true;
        ph.textContent = choices.length ? 'Select a machine' : 'No machines yet';
        sel.appendChild(ph);
        choices.forEach((m) => {
            const o = el('option'); o.value = m.id; o.textContent = m.name; sel.appendChild(o);
        });
        const result = el('div', 'ex-authresult');
        bodyEl.append(sel, result);
        const actions = el('div', 'ex-dialog-actions');
        const cancel = el('button', 'ex-btn'); cancel.textContent = 'Close';
        const ok = el('button', 'ex-btn is-accent'); ok.textContent = 'Authorize';
        actions.append(cancel, ok);
        dialog.append(h, bodyEl, actions);
        scrim.appendChild(dialog); document.body.appendChild(scrim);
        const close = () => { scrim.remove(); document.removeEventListener('keydown', onKey); };
        const onKey = (e) => { if (e.key === 'Escape') close(); };
        cancel.onclick = close;
        scrim.onclick = (e) => { if (e.target === scrim) close(); };
        document.addEventListener('keydown', onKey);

        ok.onclick = async () => {
            const machineId = sel.value;
            const machineName = sel.options[sel.selectedIndex] ? sel.options[sel.selectedIndex].textContent : '';
            if (!machineId) { toast('Choose a machine to authorize.'); return; }
            ok.disabled = true;
            result.textContent = 'Trusting the host key…';
            try {
                const r = await fetch('/backup-servers/' + encodeURIComponent(s.name)
                    + '/authorize/' + encodeURIComponent(machineId), { method: 'POST' });
                const v = await r.json().catch(() => ({}));
                if (!r.ok) { result.textContent = ''; toast(v.message || ('Could not authorize ' + machineName + '.')); return; }
                renderAuthorizeResult(result, v, machineName);
            } catch (e) {
                result.textContent = ''; toast('Could not reach Vaier to authorize the host.');
            } finally { ok.disabled = false; }
        };
    }

    function renderAuthorizeResult(container, result, machineName) {
        container.textContent = '';
        if (!result.authorized) {
            container.appendChild(checkNote('is-err', '✕ ' + (result.message || ('Could not authorize ' + machineName + '.'))));
            return;
        }
        container.appendChild(checkNote('is-ok', '✓ ' + (result.alreadyTrusted
            ? machineName + ' was already trusted — no change.'
            : machineName + ' is now trusted on this server.')));
        container.appendChild(result.hostKeyPinned
            ? checkNote('is-ok', '✓ Server host key pinned on ' + machineName + '.')
            : checkNote('is-warn', '! Host key not pinned — re-run the setup script on the server, then authorize again.'));
    }

    // A coloured one-line verdict (ex-set-note's palette), used by the authorize result.
    function checkNote(kind, text) {
        const row = el('div', 'ex-set-note ' + kind);
        row.style.marginTop = '8px';
        row.textContent = text;
        return row;
    }

    // Make a machine the fleet's one backup server. The machine is already the context, so the form asks only
    // what the machine cannot tell us — the borg coordinates — with the machine's own address prefilled.
    function designateBackupServer(m) {
        backupServerForm({
            title: 'Make ' + m.name + ' the backup server',
            intro: 'The fleet backs its archives up here. Vaier reaches the server’s borg over SSH — the '
                + 'coordinates below say how.',
            existing: { host: machineHostGuess(m), sshPort: 8022, borgUser: 'borg',
                        baseRepoPath: 'home/borg/backups', serverDataPath: '', managed: false },
            confirmLabel: 'Designate',
        }).then((body) => {
            if (body) putBackupServer(serverNameFor(m.name), m.id, body, 'designated');
        });
    }

    // Edit the coordinates of the server that already is. The name and the machine are fixed — moving the role
    // to another machine is a remove-then-designate, not an edit — so only the reach-it fields are offered.
    function editBackupServer(s) {
        backupServerForm({
            title: 'Edit ' + s.name,
            intro: 'How Vaier reaches this server’s borg over SSH.',
            existing: s,
            confirmLabel: 'Save',
        }).then((body) => {
            if (body) putBackupServer(s.name, s.machineId, body, 'saved');
        });
    }

    async function putBackupServer(name, machineId, body, verbedPast) {
        try {
            const res = await fetch('/backup-servers/' + encodeURIComponent(name), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ machineId: machineId, host: body.host, sshPort: body.sshPort,
                    borgUser: body.borgUser, baseRepoPath: body.baseRepoPath,
                    serverDataPath: body.serverDataPath, managed: body.managed }),
            });
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                toast(err.message || 'Vaier could not save the backup server.');
                return;
            }
            await loadBackup();
            toast('Backup server ' + verbedPast + '.');
            go(['fleet', machineId, 'backup']);   // the role now has a coordinate — stand on it
        } catch (e) {
            toast('Vaier could not save the backup server.');
        }
    }

    // Removing the designation is destructive in the way delete is — the fleet is left with nowhere to back up
    // to until another machine is designated — so it takes the same typed-name gate. It does not touch the
    // borg server itself or its repositories; it forgets which machine plays the role.
    async function removeBackupServer(s) {
        const ok = await confirmTyped('Remove the backup server?',
            'The fleet will have nowhere to back up to until you designate another. This does not delete the '
            + 'borg server on ' + s.machineName + ' or anything it holds — it forgets that this machine is '
            + 'the backup server. Type the server name to confirm.', s.name, 'Remove designation');
        if (!ok) return;
        try {
            const res = await fetch('/backup-servers/' + encodeURIComponent(s.name), { method: 'DELETE' });
            if (!res.ok && res.status !== 404) {
                toast('Vaier could not remove the backup server.');
                return;
            }
        } catch (e) {
            toast('Vaier could not remove the backup server.');
            return;
        }
        const machineId = s.machineId;
        await loadBackup();
        toast('Backup server removed.');
        go(['fleet', machineId]);   // the `backup` entry is gone; fall back to the machine
    }

    // The coordinates form, built on demand over the shell's overlay layer. Resolves the entered body on confirm
    // and null on cancel or Escape — the same shape as confirmModal, one field richer. Host is the only required
    // field (the rest carry borg's conventional defaults); the Designate/Save button stays disabled until it has
    // a value.
    function backupServerForm({ title, intro, existing, confirmLabel }) {
        return new Promise((resolve) => {
            const scrim = el('div', 'ex-scrim is-on');
            const dialog = el('div', 'ex-dialog');
            const h = el('div', 'ex-dialog-title');
            h.textContent = title;
            const sub = el('div', 'ex-dialog-body');
            sub.textContent = intro;
            const form = el('div', 'ex-form');

            const field = (label, hint, control) => {
                const f = el('div', 'ex-field');
                const l = el('label');
                l.textContent = label;
                f.append(l, control);
                if (hint) { const hn = el('div', 'ex-hint'); hn.textContent = hint; f.appendChild(hn); }
                return f;
            };
            const input = (value, ph, type) => {
                const i = el('input', 'ex-input');
                i.type = type || 'text';
                i.value = value == null ? '' : String(value);
                if (ph) i.placeholder = ph;
                i.autocomplete = 'off';
                i.spellcheck = false;
                return i;
            };

            const host = input(existing.host, 'e.g. 192.168.3.3');
            const sshPort = input(existing.sshPort || 8022, '8022', 'number');
            const borgUser = input(existing.borgUser || 'borg', 'borg');
            const baseRepoPath = input(existing.baseRepoPath || 'home/borg/backups', 'home/borg/backups');
            const serverDataPath = input(existing.serverDataPath, 'e.g. /volume1/docker/borg');
            const managed = el('input');
            managed.type = 'checkbox';
            managed.checked = !!existing.managed;

            const managedRow = el('label', 'ex-check-row');
            managedRow.append(managed);
            const mtxt = el('span');
            mtxt.textContent = 'Vaier stood this server up (rather than adopting an existing one)';
            managedRow.append(mtxt);

            form.append(
                field('Reached at', 'The host the borg server’s SSH listens on.', host),
                field('SSH port', null, sshPort),
                field('Borg user', null, borgUser),
                field('Base repo path', 'No leading slash — each machine’s backups sit under this.', baseRepoPath),
                field('Server data path', 'The borg container’s host data directory. Optional; found at provision.', serverDataPath),
                managedRow,
            );

            const actions = el('div', 'ex-dialog-actions');
            const cancel = el('button', 'ex-btn');
            cancel.textContent = 'Cancel';
            const ok = el('button', 'ex-btn is-accent');
            ok.textContent = confirmLabel || 'Save';
            actions.append(cancel, ok);

            dialog.append(h, sub, form, actions);
            scrim.appendChild(dialog);
            document.body.appendChild(scrim);

            const close = (result) => { scrim.remove(); document.removeEventListener('keydown', onKey); resolve(result); };
            const onKey = (e) => { if (e.key === 'Escape') close(null); };
            const armed = () => host.value.trim() !== '';
            const sync = () => { ok.disabled = !armed(); };
            host.oninput = sync;
            scrim.onclick = (e) => { if (e.target === scrim) close(null); };
            cancel.onclick = () => close(null);
            ok.onclick = () => {
                if (!armed()) return;
                const port = parseInt(sshPort.value, 10);
                close({
                    host: host.value.trim(),
                    sshPort: Number.isFinite(port) ? port : 8022,
                    borgUser: borgUser.value.trim() || 'borg',
                    baseRepoPath: baseRepoPath.value.trim() || 'home/borg/backups',
                    serverDataPath: serverDataPath.value.trim(),
                    managed: managed.checked,
                });
            };
            document.addEventListener('keydown', onKey);
            sync();
            host.focus();
        });
    }

    // --- a backup repository: a named store on the server, with the archives inside it -----------------
    //
    // A repository is a child of the server it lives on, so it hangs under the `backup` entry as its own
    // coordinate. Its Inspector shows where it sits and what is in it, and lets you edit or forget it — all
    // single calls, none of which poll, so the whole of repository management lives here.
    // The archives are read when the repository is looked at (borg list runs on a job's host),
    // never polled — a nightly archive lands on a reload, not under the cursor.

    function renderRepo(pane) {
        const machineId = S.path[1];
        const machineName = nameOf(machineId);
        const name = S.path[3];
        const s = S.backupServer;
        const r = reposOn(s).find((x) => x.name === name);
        if (!r) return pane.appendChild(note('Those backups are no longer on this server.', true));

        // Titled by whose backups these are, not by the store's borg name. Where no machine claims them the
        // label falls back to that name, and the note below says why — backups nobody is watching are worth
        // saying out loud, not quietly rendering as an ordinary entry.
        const owner = repoLabel(r.name);
        const claimed = S.backupJobs.some((j) => j.repositoryName === r.name);
        pane.appendChild(paneHead(owner, true,
            claimed ? 'Backed up to ' + (s ? s.name : '') : 'Kept on ' + (s ? s.name : '')));
        const body = el('div', 'ex-pane-body');
        if (!claimed) {
            body.appendChild(note('No machine backs up here any more. These archives are still kept — Vaier '
                + 'never deletes them — but nothing is adding to them. That happens when a machine was '
                + 'renamed or stopped being backed up.', true));
        }
        // --- go: the archives, which are the only reason to open this entry ---------------------------
        //
        // They led with a fold of storage coordinates — a page that answered "where do the bytes sit" before
        // "what is in here". Under no heading: this listing is the pane, and the head already says whose
        // backups these are and where they are kept.
        // Read on view. A store nothing targets has no host to read it from, so the answer is an empty list,
        // not an error — the note says which case it is.
        const held = S.repoArchives.get(name);
        if (!held || held.state === 'loading') {
            body.appendChild(note('Reading the archives kept here…', false));
            loadRepoArchives(name);
        } else if (held.state === 'error') {
            body.appendChild(note(held.error, true));
        } else if (!held.list.length) {
            body.appendChild(note('No archives to show. Nothing has been backed up here yet, or no backup job '
                + 'points here any more — Vaier reads the list from the machine a job backs up, so without '
                + 'one there is nowhere to read it from.', false));
        } else {
            const list = el('div', 'ex-brepos');
            held.list.forEach((a) => {
                const row = el('div', 'ex-brepo');
                const nm = el('span', 'ex-brepo-name');
                nm.textContent = a.name;
                const when = el('span', 'ex-brepo-path');
                when.textContent = timeAgo(a.time);
                row.append(nm, when);
                list.appendChild(row);
            });
            body.appendChild(list);
        }

        // --- know: where the bytes sit -----------------------------------------------------------------
        //
        // Mechanism the operator did not choose and cannot act on from here, so it folds away. Vaier's
        // promise is that it is handled; the fold is for the day someone needs to check.
        const adv = disclosure('Storage details');
        adv.appendChild(kv([
            ['Path on ' + (s ? s.name : 'the server'), coord(r.repoPath)],
            ['Append-only', r.appendOnly ? 'Yes' : 'No'],
            ['Passphrase', r.hasPassphrase ? 'Stored in the vault' : 'None'],
        ]));
        body.appendChild(adv);

        // --- danger: the two verbs that change or end what is kept -------------------------------------
        //
        // Editing moves where the bytes live and can re-key the store; forgetting drops Vaier's whole record
        // of these archives. Neither belongs behind a fold that promises only details.
        const risky = dangerFold('Edit or forget these backups');
        const acts = el('div', 'ex-lactions is-static');
        acts.appendChild(selVerb('gear', 'Edit', 'ex-btn', () => editRepository(r)));
        acts.appendChild(selVerb('trash', 'Forget these backups', 'ex-btn is-danger',
            () => deleteRepository(r)));
        risky.appendChild(acts);
        body.appendChild(risky);
        pane.appendChild(body);
    }

    async function loadRepoArchives(name) {
        const held = S.repoArchives.get(name);
        if (held && (held.state === 'loading' || held.state === 'ready')) return;   // once per look
        S.repoArchives.set(name, { state: 'loading', list: [], error: null });
        try {
            const res = await fetch('/backup-repositories/' + encodeURIComponent(name) + '/archives',
                { cache: 'no-store' });
            if (res.ok) {
                S.repoArchives.set(name, { state: 'ready', list: await res.json(), error: null });
            } else {
                const b = await res.json().catch(() => ({}));
                S.repoArchives.set(name, { state: 'error', list: [],
                    error: b.message || 'Vaier could not read the archives kept here.' });
            }
        } catch (e) {
            S.repoArchives.set(name, { state: 'error', list: [],
                error: 'Vaier could not read the archives kept here.' });
        }
        render();
    }

    // Make a repository on the server. The server is the context, so the form asks only the repository's own
    // name, an optional path override (it derives under the server's base path when blank), append-only, and a
    // passphrase — pre-filled with a strong generated one, shown once, because a repository that cannot be
    // unlocked is a repository that cannot be restored. Vaier stores it encrypted in the vault regardless.
    // There is no by-hand "create a store" any more. Vaier makes exactly one per machine behind the Back up
    // verb, names it after the machine and generates its passphrase, so the operator never had a reason to
    // make one — and making one by hand is what once minted a second store with a fresh passphrase over a
    // live borg repository and orphaned it. Adopting a store Vaier did not create is still possible over the
    // API (PUT /backup-repositories/{name}); it is rare enough that it does not belong on this page.
    function editRepository(r) {
        repoForm({
            title: 'Where ' + repoPhrase(r.name) + ' are kept',
            intro: 'Where these backups sit on the server, and whether the machine may prune them. Leave the '
                + 'passphrase blank to keep the stored one.',
            existing: r,
            confirmLabel: 'Save',
        }).then((body) => {
            if (body) putRepository(r.name, r.serverName, body, 'saved');
        });
    }

    async function putRepository(name, serverName, body, verbedPast) {
        try {
            const res = await fetch('/backup-repositories/' + encodeURIComponent(name), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ serverName: serverName, repoPath: body.repoPath,
                    passphrase: body.passphrase, appendOnly: body.appendOnly }),
            });
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                toast(err.message || 'Vaier could not save the storage details.');
                return;
            }
            await loadBackup();
            S.repoArchives.delete(name);   // a re-saved repo re-reads its archives fresh
            toast('Storage details ' + verbedPast + '.');
            go(['fleet', S.path[1], 'backup', name]);
        } catch (e) {
            toast('Vaier could not save the storage details.');
        }
    }

    // Deleting a repository forgets it in Vaier — it does not erase the borg store on the server. That is the
    // safe default (the archives on disk are the whole point of a backup), so it takes a plain confirm rather
    // than the typed-name gate a file delete does.
    async function deleteRepository(r) {
        // One flow, one verb. The button says Forget, so the confirm asks Forget and the toast says forgotten
        // — the operator should never have to work out that "delete repository" was the thing they pressed.
        const ok = await confirmModal('Forget ' + repoPhrase(r.name) + '?',
            'Vaier stops tracking them — it does not erase the borg store on ' + r.serverName + ' or the '
            + 'archives in it. Any backup job pointing here will have nowhere to write until you add them '
            + 'back.',
            'Forget');
        if (!ok) return;
        const phrase = repoPhrase(r.name);   // read before the reload drops the store from the feed
        try {
            const res = await fetch('/backup-repositories/' + encodeURIComponent(r.name), { method: 'DELETE' });
            if (!res.ok && res.status !== 404) {
                toast('Vaier could not forget ' + phrase + '.');
                return;
            }
        } catch (e) {
            toast('Vaier could not forget ' + phrase + '.');
            return;
        }
        const machineId = S.path[1];
        S.repoArchives.delete(r.name);
        await loadBackup();
        toast('Vaier has forgotten ' + phrase + '.');
        go(['fleet', machineId, 'backup']);   // back up to the server
    }

    // The repository form, in the same dialog shell as the others. On create, the name is asked and a strong
    // passphrase is pre-filled (shown once); on edit, the name is fixed and the passphrase field is blank
    // (blank keeps the stored one). Name (create) is the only required field.
    function repoForm({ title, intro, existing, confirmLabel }) {
        return new Promise((resolve) => {
            const creating = !existing;
            const scrim = el('div', 'ex-scrim is-on');
            const dialog = el('div', 'ex-dialog');
            const h = el('div', 'ex-dialog-title');
            h.textContent = title;
            const sub = el('div', 'ex-dialog-body');
            sub.textContent = intro;
            const form = el('div', 'ex-form');

            const field = (label, hint, control) => {
                const f = el('div', 'ex-field');
                const l = el('label');
                l.textContent = label;
                f.append(l, control);
                if (hint) { const hn = el('div', 'ex-hint'); hn.textContent = hint; f.appendChild(hn); }
                return f;
            };
            const input = (value, ph) => {
                const i = el('input', 'ex-input');
                i.type = 'text';
                i.value = value == null ? '' : String(value);
                if (ph) i.placeholder = ph;
                i.autocomplete = 'off';
                i.spellcheck = false;
                return i;
            };

            const name = input(existing ? existing.name : '', 'e.g. colina27');
            if (!creating) { name.disabled = true; }
            const repoPath = input(existing ? existing.repoPath : '', 'Optional — derives under the base path');
            const passphrase = input(creating ? generatePassphrase(32) : '',
                creating ? '' : 'Leave blank to keep the stored passphrase');
            const appendOnly = el('input');
            appendOnly.type = 'checkbox';
            appendOnly.checked = !!(existing && existing.appendOnly);
            const appendRow = el('label', 'ex-check-row');
            const atxt = el('span');
            atxt.textContent = 'Append-only — the client key cannot prune or delete archives';
            appendRow.append(appendOnly, atxt);

            form.append(
                field('Name', creating ? 'Letters, digits, dot, dash, underscore.' : null, name),
                field('Path override', 'No leading slash. Blank derives ' + (existing ? existing.serverName
                    : (S.backupServer ? S.backupServer.name : 'the server')) + '’s base path + the name.', repoPath),
                field('Passphrase', creating ? 'Save this — it unlocks these backups. Vaier also keeps it '
                    + 'encrypted in the vault.' : 'Blank keeps the stored one; type a new one to replace it.',
                    passphrase),
                appendRow,
            );

            const actions = el('div', 'ex-dialog-actions');
            const cancel = el('button', 'ex-btn');
            cancel.textContent = 'Cancel';
            const ok = el('button', 'ex-btn is-accent');
            ok.textContent = confirmLabel || 'Save';
            actions.append(cancel, ok);

            dialog.append(h, sub, form, actions);
            scrim.appendChild(dialog);
            document.body.appendChild(scrim);

            const close = (result) => { scrim.remove(); document.removeEventListener('keydown', onKey); resolve(result); };
            const onKey = (e) => { if (e.key === 'Escape') close(null); };
            const armed = () => !creating || name.value.trim() !== '';
            const sync = () => { ok.disabled = !armed(); };
            name.oninput = sync;
            scrim.onclick = (e) => { if (e.target === scrim) close(null); };
            cancel.onclick = () => close(null);
            ok.onclick = () => {
                if (!armed()) return;
                close({
                    name: name.value.trim(),
                    repoPath: repoPath.value.trim(),
                    passphrase: passphrase.value,   // not trimmed — a passphrase may legitimately carry spaces
                    appendOnly: appendOnly.checked,
                });
            };
            document.addEventListener('keydown', onKey);
            sync();
            (creating ? name : repoPath).focus();
        });
    }

    // A crypto-strong, alphanumeric passphrase — no quotes or shell metacharacters, so it is safe to hand borg
    // over SSH. Rejection sampling keeps the distribution uniform (no modulo bias).
    function generatePassphrase(length) {
        const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
        const threshold = 256 - (256 % alphabet.length);
        const out = [];
        const byte = new Uint8Array(1);
        while (out.length < (length || 32)) {
            crypto.getRandomValues(byte);
            if (byte[0] < threshold) out.push(alphabet[byte[0] % alphabet.length]);
        }
        return out.join('');
    }

    // --- backup jobs: how a machine is backed up, shown on the machine ---------------------------------
    //
    // A job is rendered inline in the `backup` entry of the machine it protects — its target, its schedule,
    // its last run, and the buttons to run it now, edit it, enable it or forget it. Running is the one long
    // operation, and it does not poll: POST starts it, the run shows RUNNING, and the backend pushes
    // `run-settled` on the backups stream when borg finishes (watchBackups). Getting a host ready the first
    // time — installing borg, the root grant — happens on the backup server's own entry (see Server operations).

    // INCOMPLETE reads red, not amber: the archive exists but is missing files borg could not read, which is
    // the failure mode that hurts most — it looks fine until you need the data. WARNING stays amber; borg got
    // everything it was asked for and merely grumbled on the way.
    const RUN_DOT = { SUCCESS: 'is-up', WARNING: 'is-degraded', INCOMPLETE: 'is-down', FAILED: 'is-down',
                      RUNNING: 'is-idle', UNKNOWN: 'is-idle' };

    // The same outcomes as a mark rather than a dot. A mark says WHAT it is in colour and lets trouble
    // recolour it; a dot is pure status and stays a traffic light. They diverge on success alone — and this
    // is derived rather than written out, so a new outcome added above can never be forgotten here.
    const RUN_MARK = { ...RUN_DOT, SUCCESS: 'is-backup' };

    // A run's summary is only shown when it is a short, human line. Borg's own {@code --json} stats can end up
    // stored here — a multi-line braces-y blob — and that is never dumped into the status line; the status and
    // the time say enough, and a failure's diagnostics get their own note below.
    function runSummary(r) {
        const s = r.summary;
        if (!s) return '';
        return (s.length <= 60 && s.indexOf('{') === -1 && s.indexOf('\n') === -1) ? s : '';
    }

    // A machine is backed up by one job that Vaier owns — this READS it, it does not configure it. What's
    // protected is chosen in the file browser (tick and Back up); the schedule is fleet-wide; retention and
    // whether to read as root are Vaier's to decide, not knobs to turn. So the whole entry is a readout with a
    // single intent — run it now — and one way out — stop backing this machine up.
    function renderJobsBackup(body, machineId, jobs) {
        const machineName = nameOf(machineId);
        if (S.preparing.has(machineId)) {
            const prep = el('div', 'ex-runline');
            prep.textContent = 'Getting this machine ready to back up — installing borg and trusting its key…';
            body.appendChild(prep);
        }
        jobs.forEach((job) => renderOneJob(body, machineId, job, jobs.length > 1));
    }

    function renderOneJob(body, machineId, job, named) {
        const machineName = nameOf(machineId);
        if (named) {
            const h = el('div', 'ex-sub');
            h.textContent = job.name;
            body.appendChild(h);
        }

        // What's protected — each a link back into the file browser, where it is added and removed. The paths
        // are the only backup fact the operator owns; everything else is Vaier's.
        body.appendChild(section('Protected'));
        const paths = job.sourcePaths || [];
        if (!paths.length) {
            body.appendChild(note('Nothing is backed up on this machine yet. Open its files, tick what matters '
                + 'and choose Back up.', false));
        } else {
            const list = el('div', 'ex-brepos');
            paths.forEach((sp) => {
                const row = el('div', 'ex-brepo');
                const nm = el('button', 'ex-brepo-name is-link');
                nm.textContent = sp;
                nm.onclick = () => go(['fleet', machineId, 'files'].concat(sp.split('/').filter(Boolean)));
                row.appendChild(nm);
                list.appendChild(row);
            });
            body.appendChild(list);
        }

        // The holes carved out of those paths — each one the operator's own "stop backing this up" on a folder
        // that sits inside a protected one, made durable. Showing what is protected without showing what was
        // taken back out of it would overstate what the archives actually hold.
        const excluded = job.excludes || [];
        if (excluded.length) {
            body.appendChild(section('Not backed up'));
            const holes = el('div', 'ex-brepos');
            excluded.forEach((ex) => {
                const row = el('div', 'ex-brepo');
                const nm = el('button', 'ex-brepo-name is-link');
                nm.textContent = ex;
                nm.onclick = () => go(['fleet', machineId, 'files'].concat(ex.split('/').filter(Boolean)));
                row.appendChild(nm);
                holes.appendChild(row);
            });
            body.appendChild(holes);
        }

        // --- do: the verbs that apply to this backup right now ----------------------------------------
        //
        // The last run is read here because it decides WHICH verbs apply — a run that died for a missing borg
        // client is the one failure the operator can fix from where they stand — but it is reported further
        // down under Last run, where it is a fact about the backup rather than a thing to press.
        const held = S.jobRuns.get(job.machineId);
        if (held === undefined) loadJobRun(job.machineId);
        const needsReady = held && held.state === 'ready' && held.run.needsClientReadying;
        const running = held && held.state === 'ready' && held.run.status === 'RUNNING';
        const staged = S.readying.get(machineId);

        body.appendChild(section('What to do next'));
        // The domain decides that a missing borg client is what happened (BackupRun.needsClientReadying) — the
        // shell never reads the error text to work it out — and the fix is offered on the spot rather than
        // named and left to be hunted for. The command appears only where Vaier could not gain root itself.
        if (needsReady && !S.preparing.has(machineId)) {
            body.appendChild(note('This machine has no borg client yet — nothing else is wrong. Vaier can '
                + 'install it, and tonight’s backup will run.', true));
        }
        if (staged) {
            body.appendChild(note('Vaier cannot become root on ' + machineName + ', so it has left the installer '
                + 'there. Open this machine’s shell and run this once:', true));
            const cmd = el('div', 'ex-cmd');
            cmd.textContent = staged;
            body.appendChild(cmd);
        }
        const acts = el('div', 'ex-lactions is-static');
        if (needsReady) {
            const ready = selVerb('shield', S.preparing.has(machineId) ? 'Getting ready…' : 'Get this machine ready',
                'ex-btn is-accent', () => readyClient(job));
            if (S.preparing.has(machineId)) ready.disabled = true;
            acts.appendChild(ready);
        }
        const run = selVerb('refresh', running ? 'Backing up…' : 'Back up now',
            needsReady ? 'ex-btn' : 'ex-btn is-accent', () => runNow(job));
        if (running) run.disabled = true;
        acts.append(run);
        body.appendChild(acts);

        // --- know: how this backup stands, and the one setting behind it ------------------------------
        body.appendChild(section('Schedule'));
        const sched = el('div', 'ex-runline');
        sched.textContent = 'Runs every night. A failed run emails the admins.';
        body.appendChild(sched);

        // The last run, read on view and refreshed when the backend says it settled.
        body.appendChild(section('Last run'));
        const runLine = el('div', 'ex-runline');
        if (held === undefined || held.state === 'loading') {
            runLine.textContent = 'Reading the last run…';
        } else if (held.state === 'none') {
            runLine.textContent = 'Never run yet.';
        } else if (held.state === 'error') {
            runLine.textContent = 'Vaier could not read the last run.';
        } else {
            const r = held.run;
            const d = el('span');
            d.className = 'ex-dot ' + (RUN_DOT[r.status] || 'is-idle');
            const txt = el('span');
            if (r.status === 'RUNNING') {
                txt.textContent = 'Running now — started ' + timeAgo(r.startedAt);
            } else {
                const title = r.status.charAt(0) + r.status.slice(1).toLowerCase();
                const sum = runSummary(r);
                txt.textContent = title + ' · ' + timeAgo(r.finishedAt || r.startedAt)
                    + (sum ? ' · ' + sum : '');
            }
            runLine.append(d, txt);
        }
        body.appendChild(runLine);
        // An incomplete run gets the plain sentence first, because the status word alone does not say what
        // happened to the data. The domain decided the run is incomplete (BackupRunStatus.INCOMPLETE) and the
        // diagnostics below name the files; this line says what that means and what to do about it.
        if (held && held.state === 'ready' && held.run.status === 'INCOMPLETE') {
            body.appendChild(note('Some files were not backed up. Vaier could not read them, so they are '
                + 'missing from the archive — the backup ran, but the data is not all there. '
                + (job.backupAsRoot
                    ? 'Backing up other users’ files is already on, so these are files even root cannot read; '
                      + 'the diagnostics below name them.'
                    : 'Turn on “Back up files owned by other users” for this machine and run it again.'), true));
        }
        // A failed, warning OR incomplete run says why, right here: the skipped-file and error lines the
        // backend pulled out of borg's own output (a domain decision — BackupRun.diagnostics). A warning reads
        // amber and the two failing outcomes red, the same colours the run's dot uses, so "what went wrong" is
        // never a mystery.
        if (held && held.state === 'ready' && held.run.diagnostics
            && (held.run.status === 'FAILED' || held.run.status === 'WARNING'
                || held.run.status === 'INCOMPLETE')) {
            const n = note(held.run.diagnostics, held.run.status !== 'WARNING');
            if (held.run.status === 'WARNING') n.classList.add('is-warn');
            body.appendChild(n);
        }

        // The one backup setting that decides whether a backup of a shared directory is real. Colina 27 ran
        // without it over /home for months and silently skipped every file another user owned. It is stated as
        // a consequence, not as "run borg as root": the operator's question is "will my data be in there?",
        // not "which uid does borg run under".
        //
        // Folded since #334. Asked up front it is a question nobody can answer — it is really about file
        // ownership inside container volumes and about what a passwordless `sudo borg` grants — put to someone
        // with no evidence either way. The evidence-backed version of the same question is the machine's
        // BACK_UP_AS_ROOT nudge, raised only once a run has actually lost files. This stays here so it is
        // reachable and so the current state is visible; it is no longer the way the question is asked. The
        // fold is named for what it holds rather than "Advanced", which said only that you probably did not
        // want it — and this is the setting an INCOMPLETE run sends you to.
        const rootAdv = disclosure('Backing up other users’ files');
        rootAdv.appendChild(checkRow('Back up files owned by other users', job.backupAsRoot,
            (on) => toggleBackupAsRoot(job, on)));
        rootAdv.appendChild(note(job.backupAsRoot
            ? 'On — Vaier reads every file in the protected paths, whoever owns them, so the archive holds '
              + 'all of it.'
            : 'Off — any file here that belongs to someone else is skipped, and the archive is missing it. '
              + 'Turn this on if the protected paths hold other people’s home directories, a container’s '
              + 'data, or a database file.', false));
        body.appendChild(rootAdv);

        // --- danger: the verb that ends this backup ---------------------------------------------------
        //
        // It rode in the same row as Back up now, one button along from the verb an operator presses often.
        // Stopping does not delete an archive, but it is the end of this machine being protected, and that
        // is not something to reach by aiming slightly wide.
        const risky = dangerFold('Stop backing up this machine');
        const stop = el('div', 'ex-lactions is-static');
        stop.appendChild(selVerb('cross', 'Stop backing up this machine', 'ex-btn is-danger',
            () => stopMachineBackup(job)));
        risky.appendChild(stop);
        body.appendChild(risky);
    }

    // The fleet card's backup mark reads the outcome off the job list, which is loaded once at boot — so
    // anything that learns a newer outcome has to write it back there, or the card would keep showing last
    // night's result all day. This is that write-back: one line, called wherever a run's outcome becomes known.
    function noteRunStatus(machineId, status) {
        const job = S.backupJobs.find((j) => j.machineId === machineId);
        if (job) job.lastRunStatus = status;
    }

    // The last run, read on view (404 means it has never run) and again on the run-settled push. Not skipped on
    // 'ready' — the push calls this to replace a RUNNING run with its outcome — but skipped while a read is in
    // flight, and only ever the latest run, so a machine's whole history is not dragged into the browser.
    async function loadJobRun(machineId) {
        const held = S.jobRuns.get(machineId);
        if (held && held.state === 'loading') return;
        S.jobRuns.set(machineId, { state: 'loading', run: held ? held.run : null });
        try {
            const res = await fetch('/backup-jobs/' + encodeURIComponent(machineId) + '/runs',
                { cache: 'no-store' });
            if (res.status === 404) {
                S.jobRuns.set(machineId, { state: 'none', run: null });
                noteRunStatus(machineId, null);
            } else if (res.ok) {
                const run = await res.json();
                S.jobRuns.set(machineId, { state: 'ready', run: run });
                noteRunStatus(machineId, run.status);
            } else {
                S.jobRuns.set(machineId, { state: 'error', run: null });
            }
        } catch (e) {
            S.jobRuns.set(machineId, { state: 'error', run: null });
        }
        render();
    }

    // Start a run now. The POST returns the RUNNING run at once; the outcome arrives on the backups stream
    // (watchBackups) as run-settled, so this shows RUNNING and never waits or polls for the end.
    async function runNow(job) {
        try {
            const res = await fetch('/backup-jobs/' + encodeURIComponent(job.machineId) + '/runs', { method: 'POST' });
            if (!res.ok) {
                toast(res.status === 404
                    ? 'Cannot start the backup — Vaier can’t find where this machine’s backups are kept.'
                    : 'Vaier could not start the backup.');
                return;
            }
            const started = await res.json();                                     // RUNNING
            S.jobRuns.set(job.machineId, { state: 'ready', run: started });
            noteRunStatus(job.machineId, started.status);   // the card goes amber-idle while it runs, from here
            toast('Backing up ' + job.machineName + '…');
            render();
        } catch (e) {
            toast('Vaier could not start the backup.');
        }
    }

    // The two directions of "back up files owned by other users" are not mirror images, so they are not one
    // call. Turning it OFF needs nothing installed anywhere — it is a job save. Turning it ON only means
    // anything if the machine lets Vaier's login run borg as root, and that grant used to be a separate,
    // undiscoverable step: a ticked box on a machine without it produced a job whose every run died on
    // `sudo -n` before borg started. So ON goes through the one action that does both.
    async function toggleBackupAsRoot(job, on) {
        if (on) return backUpAsRootNow(job.machineId);
        return stopBackingUpAsRoot(job);
    }

    // Say yes to backing up as root, in ONE call: Vaier installs the sudoers grant where it is missing and
    // turns the job's flag on where the machine actually grants it. The response is compound — granted / job
    // / provisioning — and this reports it as it is. A 200 is NOT "done": a machine that has not granted root
    // borg comes back granted:false with the flag still off, and telling the operator otherwise would leave
    // them believing tonight's archive is whole when it has the same holes as last night's.
    //
    // When the grant had to be installed, that install is detached, so the answer arrives later on
    // prepare-client-settled. The machine is remembered in S.rootAfterGrant and asked exactly once more when
    // it lands — `retrying` is what stops that from ever becoming a loop on a host that will never grant it.
    async function backUpAsRootNow(machineId, retrying) {
        const name = nameOf(machineId);
        try {
            const res = await fetch('/backup-jobs/' + encodeURIComponent(machineId) + '/back-up-as-root',
                { method: 'POST' });
            if (!res.ok) {
                toast(res.status === 404
                    ? 'Nothing on ' + name + ' is backed up yet.'
                    : 'Vaier could not turn that on for ' + name + '.');
                return;
            }
            const out = await res.json();
            const staged = out.provisioning && out.provisioning.scriptOnly;
            if (out.granted) {
                toast('Vaier will read every file on ' + name + ', whoever owns them, from the next run.');
            } else if (staged) {
                S.readying.set(machineId, 'sudo bash ' + out.provisioning.stagedScriptPath);
                toast('Vaier cannot gain root on ' + name + ' by itself. Run the command on its Backup entry, '
                    + 'then turn this on again.');
            } else {
                S.preparing.add(machineId);
                if (!retrying) S.rootAfterGrant.add(machineId);
                toast('Getting ' + name + ' ready to read every file — Vaier will finish this off itself.');
            }
            S.nudges.delete(machineId);     // the evidence just changed; the pane re-asks on the next paint
            await loadBackup();
            render();
        } catch (e) {
            toast('Vaier could not turn that on for ' + name + '.');
        }
    }

    // Stop reading as root. This one really is just the job spec: the flag has no endpoint of its own, so the
    // job is re-saved with it off and every other field carried through unchanged (drop one and the job would
    // silently lose its protected paths). Optimistic like the SSH-access toggle — the checkbox is already
    // where the operator put it — and a save that fails puts it back rather than leaving the screen lying
    // about what Vaier will do tonight.
    async function stopBackingUpAsRoot(job) {
        const was = job.backupAsRoot;
        job.backupAsRoot = false;
        render();
        try {
            const res = await fetch('/backup-jobs/' + encodeURIComponent(job.machineId), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ repositoryName: job.repositoryName,
                    sourcePaths: job.sourcePaths, excludes: job.excludes, keepDaily: job.keepDaily,
                    keepWeekly: job.keepWeekly, keepMonthly: job.keepMonthly, compression: job.compression,
                    enabled: job.enabled, backupAsRoot: false }),
            });
            if (!res.ok) throw new Error('save failed');
        } catch (e) {
            job.backupAsRoot = was;
            toast('Vaier could not save that. Nothing changed.');
            render();
            return;
        }
        await loadBackup();
        S.nudges.delete(job.machineId);
        toast('Vaier will skip files owned by other users on ' + job.machineName + '.');
        render();
    }

    // Stop backing this machine up entirely — Vaier forgets the job and its schedule. It never touches the
    // archives already made; they stay on the server. (Removing individual paths is done in the file browser;
    // this is the one-click "stop everything".) A plain confirm: the data you'd hate to lose is not at risk.
    async function stopMachineBackup(job) {
        const ok = await confirmModal('Stop backing up ' + job.machineName + '?',
            'Vaier stops backing up ' + job.machineName + ' — nothing new is protected and the nightly run '
            + 'stops. The archives already made are untouched; they stay on the server.', 'Stop backing up');
        if (!ok) return;
        try {
            const res = await fetch('/backup-jobs/' + encodeURIComponent(job.machineId), { method: 'DELETE' });
            if (!res.ok && res.status !== 404) { toast('Vaier could not stop the backup.'); return; }
        } catch (e) { toast('Vaier could not stop the backup.'); return; }
        const machineId = job.machineId;
        S.jobRuns.delete(job.machineId);
        await loadBackup();
        toast('Stopped backing up ' + job.machineName + '.');
        go(['fleet', machineId]);   // the `backup` entry is gone now; the machine always stands
    }

    // The backups stream — the fourth and last the shell holds. It carries a run's outcome: when a launched
    // backup settles, the backend pushes run-settled { jobName, status }, and we re-read exactly that job's last
    // run. No polling: the browser waits to be told, the same discipline the transfers and services streams keep.
    function watchBackups() {
        const events = new EventSource('/backup-jobs/events');
        events.addEventListener('run-settled', (e) => {
            const d = JSON.parse(e.data);
            if (S.backupJobs.some((j) => j.machineId === d.machineId)) loadJobRun(d.machineId);
            // A settled run IS the evidence a nudge rests on — a run that lost files to permissions raises
            // "this backup is missing N files". Nudges are read once per machine, so without dropping the
            // cached answer here the card would not appear until the operator left the pane and came back:
            // the moment the evidence arrives would be the moment it is invisible. Not a poll — we were told.
            S.nudges.delete(d.machineId);
            render();
        });
        // The other half of the readying we started under a first back-up: the detached borg install has
        // finished on the host. Clear the "getting ready" state and say how it went — no polling, we were told.
        events.addEventListener('prepare-client-settled', (e) => {
            const d = JSON.parse(e.data);   // { machineName, state }
            // The event carries both halves; we watch by identity and speak by name.
            if (!S.preparing.has(d.machineId)) return;
            S.preparing.delete(d.machineId);
            // The staged command was a standing instruction; a readying that landed on its own retires it.
            if (d.state === 'SUCCESS') S.readying.delete(d.machineId);
            // The other half of "one action": an operator who said yes to backing up as root on a machine
            // that had no grant is not asked to come back and say it again. The grant has landed, so Vaier
            // finishes what they started — once. Dropped from the set either way, so a host that will never
            // grant it (no visudo) gets one more try and then stops.
            if (S.rootAfterGrant.delete(d.machineId) && d.state === 'SUCCESS') {
                backUpAsRootNow(d.machineId, true);
            }
            toast(d.state === 'SUCCESS'
                ? d.machineName + ' is ready — its backup will run tonight, or now if you like.'
                : 'Vaier could not finish getting ' + d.machineName + ' ready — try backing it up again.');
            loadBackup();
            render();
        });
        // A detached server provisioning has settled on the host. If a provision dialog is still open on that
        // server, re-read its status once for the log tail and show the outcome; either way, say how it went.
        events.addEventListener('provision-settled', async (e) => {
            const d = JSON.parse(e.data);   // { serverName, state }
            const w = S.provisionWatch;
            if (!w || w.serverName !== d.serverName) return;
            S.provisionWatch = null;
            let status = null;
            try {
                const r = await fetch('/backup-servers/' + encodeURIComponent(d.serverName)
                    + '/provision/status', { cache: 'no-store' });
                if (r.ok) status = await r.json();
            } catch (_) { /* fall back to the event's own state */ }
            const state = (status && status.state) || d.state;
            w.bodyEl.textContent = '';
            w.bodyEl.appendChild(provisionStatus(state));
            if (status && status.logTail) {
                const pre = el('div', 'ex-config'); pre.textContent = status.logTail;
                w.bodyEl.appendChild(pre);
            }
            toast(state === 'SUCCESS'
                ? 'Server “' + d.serverName + '” provisioned.'
                : 'Provisioning “' + d.serverName + '” failed — see the log.');
            loadBackup();
            render();
        });
    }

    // The terminal itself is in the dock, not in the pane: the address bar and the shell are all on
    // screen at once, which is the whole point of moving the dock in here.
    // A machine's shell opens in its own browser window — the default now, because a window is bigger, resizes
    // freely, and you can have several across a wide screen, none of which the bottom dock could give. One
    // window per machine: re-opening focuses the one already there rather than spawning a second. `popup` drops
    // the browser's tab strip and address bar — chrome a terminal has no use for.
    function openShellWindow(machineId, fresh) {
        // The socket is opened against the machine's identity, never its name — a name in that path is closed
        // as "Machine not found". Both travel in the URL: the identity to connect with, the name to show.
        const machineName = nameOf(machineId);
        const features = 'popup,width=1024,height=680';
        // Duplicate: another, separate shell on the same machine. A brand-new session id, and a window named by
        // that id (never the machine), so it opens beside the machine's window instead of focusing it — that is
        // what lets you have several shells open on one machine at once.
        if (fresh) {
            const pane = (window.VaierPanes && VaierPanes.newId) ? VaierPanes.newId() : ('p-' + Date.now());
            const w = window.open('terminal.html?machine=' + encodeURIComponent(machineName)
                + '&id=' + encodeURIComponent(machineId)
                + '&pane=' + encodeURIComponent(pane), 'vaier-shell-' + encodeURIComponent(pane), features);
            if (!w) { toast('Your browser blocked the shell window. Allow pop-ups for Vaier and try again.'); return; }
            w.focus();
            return;
        }
        // The machine's primary shell window — one per machine, so re-opening focuses the one already there.
        // Named by identity: named by the display name, a rename opened a SECOND window onto the same machine
        // while the first sat there holding the live session.
        const w = window.open('', 'vaier-shell-' + encodeURIComponent(machineId), features);
        if (!w) { toast('Your browser blocked the shell window. Allow pop-ups for Vaier and try again.'); return; }
        // A fresh window lands on about:blank — point it at the terminal, carrying the machine's *stable* primary
        // pane id so it reattaches to the same session every time (never a random orphan, and never a surprise
        // fresh shell). One that is already there is only focused, so its live session is never navigated away.
        let href = '';
        try { href = w.location.href; } catch (e) { href = ''; }
        if (!href || href === 'about:blank') {
            const pane = (window.VaierPanes && VaierPanes.primary)
                ? VaierPanes.primary(machineId, machineName) : '';
            w.location.href = 'terminal.html?machine=' + encodeURIComponent(machineName)
                + '&id=' + encodeURIComponent(machineId)
                + (pane ? '&pane=' + encodeURIComponent(pane) : '');
        }
        w.focus();
    }


    // --- Security (#329 Slice 3) -----------------------------------------------------------------------
    //
    // Who CrowdSec is keeping out of the fleet right now, and the two things an operator can do about it:
    // lift one block, or trust an address for good. The list is read once at boot and thereafter pushed —
    // the watcher's five-minute sweep publishes it, and an action publishes it again immediately, so the
    // effect of a click is visible at once instead of up to five minutes later. The browser never polls.
    //
    // `locatable` and `enriched` arrive already decided by the domain and are never re-derived here. That
    // is not ceremony: CrowdSec writes 0/0 for a source it could not place, 0 is falsy in JavaScript, and
    // a plain truthiness check on the two coordinates would quietly drop every genuine location sitting on
    // the equator or the prime meridian.
    async function loadSecurity() {
        try {
            const res = await fetch('/security/decisions', { cache: 'no-store' });
            if (!res.ok) {
                // A failed read is a 502 carrying CrowdSec's own reason, never an empty list — which is
                // why the empty state below can honestly say "nobody is blocked". Saying that when Vaier
                // could not even ask is the one mistake this screen must never make.
                const err = await res.json().catch(() => ({}));
                S.threatsError = err.message || 'Vaier could not ask CrowdSec who is blocked.';
            } else {
                S.threats = await res.json();
                S.threatsError = '';
            }
        } catch (e) {
            S.threatsError = 'Vaier could not ask CrowdSec who is blocked.';
        }
        S.threatsRead = true;

        // Same shape, same reason: a fetch failure here must not read as "nobody accessed anything" — an
        // empty green Map during an outage is as falsely reassuring as an empty red one.
        try {
            const res = await fetch('/security/access-sources', { cache: 'no-store' });
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                S.accessSourcesError = err.message || 'Vaier could not read where allowed accesses came from.';
            } else {
                S.accessSources = await res.json();
                S.accessSourcesError = '';
            }
        } catch (e) {
            S.accessSourcesError = 'Vaier could not read where allowed accesses came from.';
        }
    }

    // Read at boot beside the blocked list, so render() stays a pure function of state — a view that fetched
    // while drawing would repaint mid-read. The list is small and changes only when a person changes it.
    async function loadTrusted() {
        try {
            const res = await fetch('/security/trusted-addresses', { cache: 'no-store' });
            if (!res.ok) {
                S.trustedError = 'Vaier could not read which addresses you have trusted.';
                S.trustedRead = true;
                return;
            }
            S.trusted = await res.json();
            S.trustedError = '';
        } catch (e) {
            S.trustedError = 'Vaier could not read which addresses you have trusted.';
        }
        S.trustedRead = true;
    }

    // An errand answers while nobody asked, so the pane learns about it the same way every other live fact
    // arrives here: the backend pushes, this listens. Opened once, when the shell starts.
    function watchChat() {
        const events = new EventSource('/chat/events');
        events.addEventListener('errand-reported', () => {
            // Mid-answer the thread is being written into; the answer's own pass re-reads both when it lands.
            if (S.chat.busy) return;
            loadConversation();
            loadErrands();
        });
    }

    function watchSecurity() {
        const events = new EventSource('/security/events');
        // SSE replays nothing missed while the stream was down, so re-read on every reconnect after the
        // first — the same edge-triggered re-sync watchFleet does. Not polling: this fires on a reconnect,
        // not on a clock.
        let opened = false;
        events.onopen = () => {
            if (opened) {
                loadSecurity().then(() => { repaintThreats(); repaintAccessSources(); });
                loadTrusted().then(repaintTrusted);
            }
            opened = true;
        };
        events.addEventListener('block-decisions', (e) => {
            try {
                S.threats = JSON.parse(e.data);
                S.threatsRead = true;
                S.threatsError = '';
            } catch (err) { return; }   // a malformed push is not a reason to blank a good list
            repaintThreats();
        });
        // Mirrors block-decisions above: the payload is the same array the GET returns, pushed the moment
        // a new allowed access lands rather than waited out on a clock.
        events.addEventListener('access-sources', (e) => {
            try {
                S.accessSources = JSON.parse(e.data);
                S.accessSourcesError = '';
            } catch (err) { return; }
            repaintAccessSources();
        });
        // Nothing on a clock ever sends this one: the trusted list changes only when a person changes it,
        // and the server pushes it the moment they do. Same contract as above — a malformed push is ignored
        // rather than allowed to blank a list the operator is reading.
        events.addEventListener('trusted-addresses', (e) => {
            try {
                S.trusted = JSON.parse(e.data);
                S.trustedRead = true;
                S.trustedError = '';
            } catch (err) { return; }
            repaintTrusted();
        });
    }

    // A push lands every five minutes whatever the operator is looking at, so repaint only what actually
    // shows threats — and on the Map only the pings, not the whole pane, exactly as paintDots does for
    // liveness. The map itself survives a re-render now (it is built once), but rebuilding a pane for
    // markers that can be swapped in place is still work nobody asked for.
    function repaintThreats() {
        const kind = kindOf(S.path);
        if (kind === 'security') render();
        else if (kind === 'map') paintThreatLayer();
    }

    // The Map is the only screen that draws these, so it is the only one to repaint — and in place, for the
    // reason above. Re-rendering Security here rebuilt a whole view every minute for data it never shows.
    function repaintAccessSources() {
        if (kindOf(S.path) === 'map') paintAccessLayer();
    }

    // The Map draws blocked addresses, never trusted ones — a trusted address is not a threat and has no
    // pin — so unlike repaintThreats this has exactly one screen to repaint.
    function repaintTrusted() {
        if (kindOf(S.path) === 'security') render();
    }


    // Two sections, and the split is the whole of #348. The first is CrowdSec's answer to "who is knocking",
    // which changes on its own every few minutes. The second is the operator's own standing decisions, which
    // change only when they change them — and which, until now, they could neither see nor take back.
    function renderSecurity(pane) {
        const blocked = S.threats.length;
        pane.appendChild(paneHead('Security', false,
            blocked ? blocked + (blocked === 1 ? ' address blocked' : ' addresses blocked') : 'Nothing blocked'));

        const body = el('div', 'ex-pane-body');
        // Who is being kept out is what the page is for, so it leads and wears no heading — the head already
        // counts it, and "Blocked right now" above the list said the subtitle a second time. The heading below
        // is what marks where that list stops and the operator's own decisions begin.
        renderBlocked(body);
        body.appendChild(section('Trusted addresses'));
        renderTrusted(body);
        pane.appendChild(body);
    }

    function renderBlocked(body) {
        if (!S.threatsRead) {
            body.appendChild(note('Reading who CrowdSec is keeping out…', false));
            return;
        }
        if (S.threatsError) {
            body.appendChild(note(S.threatsError, true));
            return;
        }
        if (!S.threats.length) {
            body.appendChild(note('Nobody is blocked right now. CrowdSec is watching the edge, and Vaier '
                + 'emails you the moment it starts turning someone away.', false));
            return;
        }

        const rows = el('div', 'ex-listing is-threats');
        rows.appendChild(listHead(['Source', 'Scenario', 'Expires in', '']));
        S.threats.forEach((d) => rows.appendChild(threatRow(d)));
        body.appendChild(rows);
    }

    // Only the addresses a person chose, because this is where the untrust verb lives. Vaier trusts more
    // than this — your VPN, this server's own container network, and every network it reaches through one of
    // your machines — but those are what stop CrowdSec turning away your own traffic, so removing one is the
    // lockout Vaier emails you about. They are stated below and never drawn as a row: a list that mixed the
    // two kinds would put a whole network one click from the same button.
    function renderTrusted(body) {
        if (!S.trustedRead) {
            body.appendChild(note('Reading which addresses you have trusted…', false));
            return;
        }
        if (S.trustedError) {
            body.appendChild(note(S.trustedError, true));
            return;
        }
        if (!S.trusted.length) {
            body.appendChild(note('You have not trusted any address by hand. Trusting a blocked address '
                + 'above tells CrowdSec never to block it again.', false));
        } else {
            const rows = el('div', 'ex-listing is-trusted');
            rows.appendChild(listHead(['Address', '']));
            S.trusted.forEach((a) => rows.appendChild(trustedRow(a)));
            body.appendChild(rows);
        }
        body.appendChild(note('Your VPN, this server’s own container network, and every network Vaier '
            + 'reaches through one of your machines are trusted too. Those are not listed here and '
            + 'cannot be untrusted — they are what stops CrowdSec turning away your own traffic.', false));
    }

    // The address and one verb. A trusted address has no scenario and no expiry: it is not a ban, it is a
    // decision, and the only thing to say about it is that you made it.
    function trustedRow(a) {
        const row = el('div', 'ex-lrow');

        const src = el('span', 'ex-tsource');
        const ip = el('span', 'ex-tip');
        ip.textContent = a.sourceIp;
        src.appendChild(ip);
        row.appendChild(src);

        const acts = el('div', 'ex-lactions');
        const untrust = el('button', 'ex-btn is-danger');
        untrust.textContent = 'Untrust';
        untrust.title = 'Stop trusting ' + a.sourceIp + '. CrowdSec judges it on its behaviour again.';
        untrust.onclick = () => untrustAddress(a);
        acts.appendChild(untrust);
        row.appendChild(acts);
        return row;
    }

    // No navigation target — a block decision has no Inspector of its own — so the source is plain text,
    // not a button that would promise somewhere to go.
    function threatRow(d) {
        const row = el('div', 'ex-lrow');

        const src = el('span', 'ex-tsource');
        const ip = el('span', 'ex-tip');
        ip.textContent = d.sourceIp;
        src.appendChild(ip);
        // Both the verdict and the rendering come from the domain: `enriched` decides whether CrowdSec
        // placed this source at all, `origin` is how that reads. Joining country and network here instead
        // would be a second copy of a rule the breach mail already owns, and copies drift.
        if (d.enriched) {
            const from = el('span', 'ex-torigin');
            from.textContent = d.origin;
            src.appendChild(from);
        }
        row.appendChild(src);

        [d.scenario, d.duration].forEach((value) => {
            const cell = el('span', 'ex-lmeta');
            const text = el('span');
            text.textContent = value || '—';
            cell.appendChild(text);
            row.appendChild(cell);
        });

        const sub = el('span', 'ex-lsub');
        const subText = el('span');
        subText.textContent = [d.origin, d.scenario, d.duration].filter(Boolean).join(' · ');
        sub.appendChild(subText);
        row.appendChild(sub);

        const acts = el('div', 'ex-lactions');
        const lift = el('button', 'ex-btn');
        lift.textContent = 'Lift the block';
        lift.title = 'Let ' + d.sourceIp + ' back in now. CrowdSec may block it again the next time it '
            + 'trips a scenario.';
        lift.onclick = () => liftBlock(d);
        acts.appendChild(lift);
        const trust = el('button', 'ex-btn');
        trust.textContent = 'Trust this address';
        trust.title = 'Never block ' + d.sourceIp + ' again.';
        trust.onclick = () => trustAddress(d);
        acts.appendChild(trust);
        row.appendChild(acts);
        return row;
    }

    // One-off and self-healing — the address is let back in, and the next scenario it trips blocks it
    // again — so this asks nothing first. Trusting an address is the lasting decision, and that one does.
    async function liftBlock(d) {
        if (!await securityAction('/security/decisions/' + encodeURIComponent(d.sourceIp), 'DELETE', null,
            'Vaier could not lift the block on ' + d.sourceIp + '.')) return;
        toast('Lifted the block on ' + d.sourceIp + '.');
    }

    // The copy says how long the decision lasts, and since #348 the honest answer is "until you take it
    // back", not "forever". It stays honest about the delay, which has not changed: CrowdSec reads its
    // whitelist at startup, and Vaier will not restart the thing guarding the door to apply a rule about who
    // may pass it.
    async function trustAddress(d) {
        const ok = await confirmModal('Trust ' + d.sourceIp + '?',
            'Vaier lifts the block now, and adds ' + d.sourceIp + ' to the networks it never blocks. That '
            + 'lasts until you untrust it here. The trusted list itself reaches CrowdSec when CrowdSec next '
            + 'restarts — Vaier will not restart it for you, because restarting the thing guarding the door '
            + 'is how an operator ends up locked out.',
            'Trust this address');
        if (!ok) return;
        if (!await securityAction('/security/trusted-addresses', 'POST', { sourceIp: d.sourceIp },
            'Vaier could not trust ' + d.sourceIp + '.')) return;
        toast('Now trusting ' + d.sourceIp + '.');
    }

    // The mirror image, and it asks first for the same reason trusting does: this is a standing decision
    // either way. What it is not is a ban — Vaier never blocks an address, so the worst an untrust can do is
    // let CrowdSec judge this one like any other.
    async function untrustAddress(a) {
        const ok = await confirmModal('Stop trusting ' + a.sourceIp + '?',
            'Vaier forgets the decision now, and this blocks nobody — CrowdSec simply judges ' + a.sourceIp
            + ' on its behaviour again. It leaves CrowdSec’s trusted list when CrowdSec next restarts — '
            + 'Vaier will not restart it for you, because restarting the thing guarding the door is how an '
            + 'operator ends up locked out.',
            'Untrust');
        if (!ok) return;
        if (!await securityAction('/security/trusted-addresses/' + encodeURIComponent(a.sourceIp), 'DELETE',
            null, 'Vaier could not untrust ' + a.sourceIp + '.')) return;
        toast('No longer trusting ' + a.sourceIp + '.');
    }

    // The server pushes the new list straight after a successful action, so this does not re-read — but a
    // failure must never read as success, so anything but a 2xx toasts the server's own reason and stops.
    async function securityAction(url, method, body, failMsg) {
        try {
            const res = await fetch(url, body
                ? { method: method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
                : { method: method });
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                toast(err.message || failMsg);
                return false;
            }
            return true;
        } catch (e) {
            toast(failMsg);
            return false;
        }
    }

    // --- fleet credentials: one secret, on every machine that runs a shell ------------------------------
    //
    // Each credential answers one question — is my fleet consistent? — with a coverage strip: one cell per
    // machine that CAN hold the credential, coloured by what the operator should do about it.
    //
    // Machines with no shell are deliberately not in that strip. A phone can never hold a file, so counting
    // one would put all-green permanently out of reach, and an indicator that can never come clean is one you
    // learn to ignore. They are named quietly underneath instead, where they read as a fact about the fleet
    // rather than a fault in it.

    // The operator's words for each standing, never the enum's, and four tones for seven states because
    // there are only four things to do about one: green is done, amber heals itself within five minutes,
    // red wants a person, dim is nobody's fault. `rank` is worst-first — the order the exceptions are read
    // in, so a failure can never sit below a machine that was merely asleep.
    const CRED_STATE = {
        FAILED:      { label: 'Failed',        tone: 'is-failed',      rank: 0 },
        STALE:       { label: 'Out of date',   tone: 'is-drift',       rank: 1 },
        MISSING:     { label: 'Not there yet', tone: 'is-drift',       rank: 2 },
        UNREACHABLE: { label: 'Unreachable',   tone: 'is-unreachable', rank: 3 },
        WITHDRAWN:   { label: 'Withdrawn',     tone: 'is-muted',       rank: 4 },
        CURRENT:     { label: 'In place',      tone: 'is-current',     rank: 5 },
        SKIPPED:     { label: 'No shell',      tone: 'is-muted',       rank: 6 },
    };
    const credState = (s) => CRED_STATE[s] || { label: s, tone: 'is-muted', rank: 3 };

    // Who the strip is drawn from, and the one line the whole design rests on: a machine with no shell Vaier
    // can reach can never hold the file, so it is neither a cell nor part of the tally.
    const canHoldCredential = (m) => m.state !== 'SKIPPED';

    async function loadFleetCredentials() {
        const c = S.credentials;
        c.state = 'loading';
        try {
            const res = await fetch('/fleet-credentials', { cache: 'no-store' });
            if (!res.ok) throw new Error('read failed');
            c.list = await res.json();
            c.state = 'ready';
            c.error = '';
        } catch (e) {
            c.state = 'error';
            // A failed read, said as a failed read: nothing was distributed or withdrawn by looking.
            c.error = 'Vaier could not read what it holds for the fleet. That is this read failing — no '
                + 'credential has moved.';
        }
        if (kindOf(S.path) === 'credentials') render();
    }

    function renderCredentials(pane) {
        const c = S.credentials;
        if (c.state === 'idle') loadFleetCredentials();

        const stored = c.state === 'ready'
            ? (c.list.length ? c.list.length + (c.list.length === 1 ? ' credential' : ' credentials')
                             : 'Nothing stored')
            : '';
        pane.appendChild(paneHead('Credentials', false, stored));

        const body = el('div', 'ex-pane-body');
        pane.appendChild(body);

        if (c.state === 'idle' || c.state === 'loading') {
            body.appendChild(note('Reading what Vaier holds for the fleet…', false));
            return;
        }
        if (c.state === 'error') {
            body.appendChild(note(c.error, true));
            return;
        }
        if (!c.list.length) {
            body.appendChild(credentialsEmpty());
            return;
        }

        // go: what Vaier holds, each card carrying its own coverage and its own verbs.
        const list = el('div', 'ex-creds');
        c.list.forEach((cred) => list.appendChild(credentialCard(cred)));
        body.appendChild(list);

        // do: the one verb that is about the collection rather than about one credential.
        body.appendChild(section('What to do next'));
        const add = el('div', 'ex-cred-actions');
        add.appendChild(selVerb('key', 'Add a credential', 'ex-btn', () => credentialFileDialog(null)));
        body.appendChild(add);

        // know: how Vaier keeps them there, which is the promise the cards above are reporting against.
        body.appendChild(section('About these credentials'));
        body.appendChild(hint('Vaier writes each file as the machine’s own login user under umask 077, then '
            + 'reads owner, mode and digest back off the machine to prove it landed. It puts back anything '
            + 'that drifts on the five-minute sweep — but never re-pushes what you withdrew.'));
    }

    function credentialsEmpty() {
        const wrap = el('div', 'ex-cred-empty');
        wrap.appendChild(note('Vaier holds no fleet credential yet. A fleet credential is one file — a token, '
            + 'a licence, a config — that has to exist identically on every machine that runs a shell Vaier '
            + 'can reach. Store it once here, and Vaier puts it there and keeps it there.', false));
        wrap.appendChild(selVerb('key', 'Add a credential', 'ex-btn is-accent',
            () => credentialFileDialog(null)));
        return wrap;
    }

    // One card, and the strip above everything else in it: "is my fleet consistent?" is answered before a
    // word is read. Only the machines that are not in place are named — a list of everything that is fine is
    // a list nobody finishes.
    function credentialCard(cred) {
        const card = el('div', 'ex-cred');
        const machines = cred.machines || [];
        const holders = machines.filter(canHoldCredential);

        const top = el('div', 'ex-cred-top');
        const name = el('span', 'ex-cred-name');
        name.textContent = cred.name;
        const strip = el('div', 'ex-cred-strip');
        holders.forEach((m) => {
            const cell = el('span', 'ex-cred-cell ' + credState(m.state).tone);
            cell.title = m.machineName + ' — ' + credState(m.state).label;
            strip.appendChild(cell);
        });
        const tally = el('span', 'ex-cred-tally');
        tally.textContent = credentialTally(holders);
        top.append(name, strip, tally);
        card.appendChild(top);

        const meta = el('div', 'ex-cred-meta');
        meta.textContent = cred.targetPath + ' · ' + cred.mode + ' · '
            + (cred.hasSecret ? 'secret held' : 'no secret held');
        card.appendChild(meta);

        card.appendChild(credentialExceptions(cred, holders, machines));

        const actions = el('div', 'ex-cred-actions');
        actions.append(
            selVerb('upload', 'Distribute', 'ex-btn is-accent', () => distributeCredential(cred)),
            selVerb('key', 'Replace secret', 'ex-btn', () => credentialFileDialog(cred)),
            selVerb('cross', 'Withdraw', 'ex-btn', () => withdrawCredential(cred)),
            selVerb('trash', 'Delete', 'ex-btn is-danger', () => deleteCredential(cred)));
        card.appendChild(actions);
        return card;
    }

    // "Not checked yet", never "0 of 0": an empty standings list is the distributor holding no observation —
    // it observes in memory, so a fresh boot has none — and reporting that as nowhere sends an operator to
    // fix a fleet that is fine.
    function credentialTally(holders) {
        if (!holders.length) return 'Not checked yet';
        const inPlace = holders.filter((m) => m.state === 'CURRENT').length;
        return inPlace + ' of ' + holders.length + ' in place';
    }

    function credentialExceptions(cred, holders, machines) {
        const exc = el('div', 'ex-cred-exc');
        holders.filter((m) => m.state !== 'CURRENT')
            .sort((a, b) => credState(a.state).rank - credState(b.state).rank
                || String(a.machineName).localeCompare(String(b.machineName)))
            .forEach((m) => {
                const row = el('div', 'ex-cred-excrow');
                const st = el('span', 'ex-cred-state ' + credState(m.state).tone);
                st.textContent = credState(m.state).label;
                const who = el('span', 'ex-cred-who');
                who.textContent = m.machineName;
                row.append(st, who);
                exc.appendChild(row);
            });

        // Named quietly, where they read as a fact about the fleet rather than a fault in it.
        const skipped = machines.filter((m) => !canHoldCredential(m));
        if (skipped.length) {
            exc.appendChild(note(skipped.map((m) => m.machineName).join(', ')
                + (skipped.length === 1 ? ' runs' : ' run') + ' no shell Vaier can reach, so '
                + (skipped.length === 1 ? 'it is' : 'they are') + ' not counted.', false));
        }
        return exc;
    }

    // Anything but a 2xx must never read as success, so the server's own sentence is toasted and the caller
    // stops. Returns what the endpoint answered — the standings, for the two verbs that recompute them.
    async function credentialAction(url, method, body, failMsg) {
        try {
            const res = await fetch(url, body
                ? { method: method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }
                : { method: method });
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                toast(err.message || failMsg);
                return null;
            }
            return await res.json().catch(() => true);
        } catch (e) {
            toast(failMsg);
            return null;
        }
    }

    // Distributing asks nothing. It is what this entry is for, and a confirm on the one harmless verb is how
    // an operator learns to click through the two that are not.
    async function distributeCredential(cred) {
        toast('Putting ' + cred.name + ' where it belongs…');
        const standings = await credentialAction(
            '/fleet-credentials/' + encodeURIComponent(cred.name) + '/distribute', 'POST', null,
            'Could not distribute ' + cred.name + '.');
        if (!standings) return;
        const holders = (Array.isArray(standings) ? standings : []).filter(canHoldCredential);
        const inPlace = holders.filter((m) => m.state === 'CURRENT').length;
        toast(cred.name + ' is in place on ' + inPlace + ' of ' + holders.length + '.');
        loadFleetCredentials();
    }

    async function withdrawCredential(cred) {
        const ok = await confirmModal('Withdraw ' + cred.name + ' from the fleet?',
            'Vaier removes the file from every machine it could have reached and confirms it is gone. This '
            + 'is how a leaked secret is revoked. Vaier keeps its own copy, so you can put it back — and the '
            + 'five-minute sweep will not, because a healer that re-pushed a withdrawn secret would undo the '
            + 'one place revocation happens.', 'Withdraw');
        if (!ok) return;
        const standings = await credentialAction(
            '/fleet-credentials/' + encodeURIComponent(cred.name) + '/withdraw', 'POST', null,
            'Could not withdraw ' + cred.name + '.');
        if (!standings) return;
        toast(cred.name + ' withdrawn.');
        loadFleetCredentials();
    }

    // Typed by name, because this is the one verb whose damage is silent: nothing on the fleet changes, so
    // there is no failure to notice afterwards — only a secret Vaier no longer has.
    async function deleteCredential(cred) {
        const ok = await confirmByTypingName('Delete ' + cred.name + '?',
            'This forgets Vaier’s copy and reaches no machine: wherever ' + cred.name + ' is already in '
            + 'place, it stays there and Vaier stops watching it. Withdraw it first if you meant to revoke it.',
            cred.name, 'Delete');
        if (!ok) return;
        if (!await credentialAction('/fleet-credentials/' + encodeURIComponent(cred.name), 'DELETE', null,
            'Could not delete ' + cred.name + '.')) return;
        toast(cred.name + ' deleted. Nothing on the fleet changed.');
        loadFleetCredentials();
    }

    // Add, or replace the secret of one already stored. The name is the identity, so it is fixed once
    // stored: an editable name would quietly create a second credential and leave the first on the fleet.
    // There is no field holding the old secret, because Vaier will not show one — a save replaces it whole.
    function credentialFileDialog(existing) {
        const scrim = el('div', 'ex-scrim is-on');
        const dialog = el('div', 'ex-dialog');
        const h = el('div', 'ex-dialog-title');
        h.textContent = existing ? 'Replace secret — ' + existing.name : 'New fleet credential';

        const form = el('div', 'ex-form');
        const field = (label, hintText, control) => {
            const f = el('div', 'ex-field');
            const l = el('label');
            l.textContent = label;
            f.append(l, control);
            if (hintText) { const hn = el('div', 'ex-hint'); hn.textContent = hintText; f.appendChild(hn); }
            return f;
        };

        const name = el('input', 'ex-input');
        name.type = 'text'; name.spellcheck = false; name.autocomplete = 'off';
        name.placeholder = 'anthropic-api-key';
        const path = el('input', 'ex-input');
        path.type = 'text'; path.spellcheck = false; path.autocomplete = 'off';
        path.placeholder = '~/.config/example/token';
        const mode = el('input', 'ex-input');
        mode.type = 'text'; mode.spellcheck = false; mode.autocomplete = 'off';
        mode.value = '0600';
        const secret = el('textarea', 'ex-input ex-cred-secret');
        secret.spellcheck = false;
        secret.placeholder = 'The file’s contents, exactly as they must appear on each machine.';

        if (existing) {
            name.value = existing.name;
            name.disabled = true;
            path.value = existing.targetPath;
            mode.value = existing.mode;
        }

        form.append(
            field('Name', existing ? 'Fixed: this is what the credential is called across the fleet.'
                                   : 'Vaier’s name for it. Not the filename.', name),
            field('File path on each machine', 'Written as that machine’s own login user, so ~ is that '
                + 'user’s home.', path),
            field('Mode', 'Octal, as chmod takes it. 0600 unless something else has to read it.', mode),
            field('Secret', 'Write-only: Vaier never shows this again, and a save replaces it whole.', secret));

        const actions = el('div', 'ex-dialog-actions');
        const cancel = el('button', 'ex-btn'); cancel.textContent = 'Cancel';
        const ok = el('button', 'ex-btn is-accent'); ok.textContent = 'Save';
        actions.append(cancel, ok);

        dialog.append(h, form, actions);
        scrim.appendChild(dialog);
        document.body.appendChild(scrim);

        const close = () => { scrim.remove(); document.removeEventListener('keydown', onKey); };
        const onKey = (e) => { if (e.key === 'Escape') close(); };
        cancel.onclick = close;
        scrim.onclick = (e) => { if (e.target === scrim) close(); };
        document.addEventListener('keydown', onKey);
        (existing ? secret : name).focus();

        ok.onclick = async () => {
            const nm = existing ? existing.name : name.value.trim();
            if (!nm || !path.value.trim() || !mode.value.trim() || !secret.value) {
                toast('A credential needs a name, a path, a mode and a secret.');
                return;
            }
            ok.disabled = true;
            const saved = await credentialAction('/fleet-credentials/' + encodeURIComponent(nm), 'PUT',
                { targetPath: path.value.trim(), mode: mode.value.trim(), content: secret.value },
                'Could not save ' + nm + '.');
            if (!saved) { ok.disabled = false; return; }
            close();
            // Storing is not distributing, and an operator who assumes otherwise leaves the fleet without it.
            toast('Saved ' + nm + '. Nothing has reached a machine yet — Distribute puts it there.');
            loadFleetCredentials();
        };
    }

    // A top-level global that is still bridged (Users, Concepts) — its page, framed whole, until it is ported.
    function renderGlobalBridge(pane) {
        const g = GLOBALS.find((x) => x.name === S.path[0]);
        pane.className = 'ex-pane is-bridged';
        const frame = document.createElement('iframe');
        frame.className = 'ex-bridge';
        frame.src = g.page;
        frame.title = g.label;
        pane.appendChild(frame);
    }

    // --- Settings: Vaier-wide, native, outside the fleet ------------------------------------------------
    //
    // The one native global. It reads its config on view (like disk/archives — never polled) and saves each
    // section on its own against the `/settings/*` endpoints Vaier already had. The nightly-backup schedule
    // lives here: it is the fleet-wide "when", the one backup knob that is the operator's to set — everything
    // else about a backup is Vaier's.

    // What Chat has cost this month, shown in the Chat pane's head. Read at start and after each answer
    // this browser asked for — the only moments it can change from here — never on a timer.
    async function loadSpend() {
        if (!S.chatAvailable) { S.chat.spend = null; return; }
        try {
            const res = await fetch('/chat/spend', { cache: 'no-store' });
            S.chat.spend = res.ok ? await res.json() : null;
        } catch (e) {
            S.chat.spend = null;
        }
        if (kindOf(S.path) === 'chat') render();
    }

    // Whether Chat may be offered — the one bit the menu needs before Settings has been opened.
    async function loadChatAvailability() {
        try {
            const res = await fetch('/chat/availability', { cache: 'no-store' });
            S.chatAvailable = res.ok ? !!(await res.json()).available : false;
        } catch (e) {
            S.chatAvailable = false;
        }
    }

    // ---- Chat: the fleet, answered in sentences ------------------------------------------------------------
    // The conversation lives here for the visit; Vaier keeps none of it yet. Answers arrive as a stream
    // read off one POST — no EventSource, no timer — and are painted as they come.
    let _chatOpen = false;   // whether the Chat pane is the one being visited — the box takes focus once, on entry

    function renderChat(pane) {
        const head = paneHead('Chat', false, 'Marvin answers, looks, proposes, hands over files, and remembers.');
        head.classList.add('ex-chat-head');
        // Marvin's memory and his cost are reached from one menu on the pane's own bar: facts
        // about Chat, kept off the thread and out of the way of typing.
        const acts = el('div', 'ex-pane-actions');
        acts.appendChild(chatMenu());
        head.appendChild(acts);
        pane.appendChild(head);
        const body = el('div', 'ex-pane-body ex-chat');
        pane.appendChild(body);

        if (!S.chatAvailable) {
            body.appendChild(note('Chat needs your Anthropic API key. Add one under Settings and Chat appears here.', false));
            return;
        }

        // The conversation is Vaier's to remember: read once on entry, drawn from here after.
        if (!S.chat.loaded) loadConversation();

        const thread = el('div', 'ex-chat-thread');
        if (S.chat.summary) {
            const brief = el('div', 'ex-chat-brief');
            brief.textContent = 'Earlier, in brief: ' + S.chat.summary;
            thread.appendChild(brief);
        }
        if (S.chat.loaded && !S.chat.turns.length && !S.chat.summary) {
            const intro = el('div', 'ex-chat-intro');
            intro.textContent = 'Ask Marvin about the fleet in your own words. He answers from what Vaier knows right now, and only that, and he will let you know how he feels about it.';
            thread.appendChild(intro);
        }
        S.chat.turns.forEach((t) => thread.appendChild(chatTurn(t)));
        if (S.chat.error) thread.appendChild(note(S.chat.error, true));
        if (S.chat.turns.length || S.chat.summary) {
            const over = el('div', 'ex-chat-over');
            const forget = el('button', 'ex-btn'); forget.textContent = 'Start over';
            forget.disabled = S.chat.busy;
            forget.onclick = startOver;
            over.appendChild(forget);
            thread.appendChild(over);
        }
        body.appendChild(thread);

        // The pane is rebuilt on every render, and the shell renders whenever a stream event lands. A fresh
        // textarea would drop the words being typed mid-sentence, so the draft is state and is put back
        // here — caret and all — and the box takes focus on entry, and after that only if it had it or
        // nothing else does, so a repaint never pulls the cursor out of a dialog.
        const entering = !_chatOpen; _chatOpen = true;
        const was = document.activeElement;
        const hadFocus = was && was.classList && was.classList.contains('ex-chat-box');
        const caret = hadFocus ? [was.selectionStart, was.selectionEnd] : null;
        const row = el('div', 'ex-chat-row');
        const box = el('textarea', 'ex-input ex-chat-box');
        box.rows = 2; box.placeholder = 'Ask Marvin…'; box.spellcheck = true;
        box.disabled = S.chat.busy;
        box.value = S.chat.draft;
        box.oninput = () => { S.chat.draft = box.value; };
        const send = el('button', 'ex-btn is-accent'); send.textContent = S.chat.busy ? 'Answering…' : 'Ask';
        send.disabled = S.chat.busy;
        const go = () => { const q = box.value.trim(); if (q) { box.value = ''; S.chat.draft = ''; askVaier(q); } };
        send.onclick = go;
        box.onkeydown = (e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); go(); } };
        row.append(box, send);
        body.appendChild(row);
        if (!S.chat.busy && (entering || hadFocus || !was || was === document.body)) {
            box.focus();
            if (caret) box.setSelectionRange(caret[0], caret[1]);
        }
        pane.scrollTop = pane.scrollHeight;
    }

    function chatTurn(t) {
        if (t.kind === 'card') return chatCard(t);
        if (t.kind === 'bundle') return chatBundleCard(t);
        const turn = el('div', 'ex-chat-turn ' + (t.role === 'OPERATOR' ? 'is-you' : 'is-vaier'));
        const who = el('div', 'ex-chat-who'); who.textContent = t.role === 'OPERATOR' ? 'You' : 'Marvin';
        const text = el('div', 'ex-chat-text'); text.textContent = t.text || (t.role === 'VAIER' ? '…' : '');
        turn.append(who, text);
        return turn;
    }

    // The kept conversation, once per visit. Nothing here decides anything: what is kept, and for how
    // long, is Vaier's; the pane only shows it.
    async function loadConversation() {
        S.chat.loaded = true;
        try {
            const res = await fetch('/chat/conversation', { cache: 'no-store' });
            if (!res.ok) return;
            const c = await res.json();
            S.chat.turns = (c.turns || []).map((t) => ({ role: t.role, text: t.text }));
            S.chat.summary = c.summary || null;
            loadMemory();
            loadErrands();
        } catch (e) {
            // Nothing kept, or nothing reachable: the pane starts empty either way.
        }
        if (kindOf(S.path) === 'chat') render();
    }

    // The Chat pane's own menu: Memory and Spend, each opening a dialog. Same shape as the Vaier menu in the
    // top bar, so it reads as one thing; closed by a click anywhere else, as every menu is.
    function chatMenu() {
        const wrap = el('div', 'ex-vmenu-wrap ex-chat-menu');
        // A verb like the pane's other verbs, not a second copy of the top bar's button: the word and a
        // small chevron, nothing else.
        const btn = el('button', 'ex-btn ex-chat-menu-btn');
        btn.setAttribute('aria-haspopup', 'true'); btn.setAttribute('aria-expanded', 'false');
        const lbl = el('span'); lbl.textContent = 'Marvin';
        btn.appendChild(lbl);
        btn.insertAdjacentHTML('beforeend', svg('chev', 'ex-ico ex-chat-menu-chev'));
        const menu = el('div', 'ex-vmenu'); menu.setAttribute('role', 'menu');
        const close = () => { menu.classList.remove('is-open'); btn.setAttribute('aria-expanded', 'false'); document.removeEventListener('click', close); };
        btn.onclick = (e) => {
            e.stopPropagation();
            const open = !menu.classList.contains('is-open');
            if (open) { menu.classList.add('is-open'); btn.setAttribute('aria-expanded', 'true'); document.addEventListener('click', close); }
            else close();
        };
        menu.onclick = (e) => e.stopPropagation();
        const memory = el('button', 'ex-vmenu-item'); memory.setAttribute('role', 'menuitem');
        memory.innerHTML = svg('book', 'ex-ico');
        const ml = el('span'); ml.textContent = 'What Marvin remembers (' + S.chat.memory.length + ')';
        memory.appendChild(ml);
        memory.onclick = () => { close(); openMemoryDialog(); };
        const errands = el('button', 'ex-vmenu-item'); errands.setAttribute('role', 'menuitem');
        errands.innerHTML = svg('clock', 'ex-ico');
        const el_ = el('span'); el_.textContent = 'Marvin\u2019s errands (' + S.chat.errands.length + ')';
        errands.appendChild(el_);
        errands.onclick = () => { close(); openErrandsDialog(); };
        const spend = el('button', 'ex-vmenu-item'); spend.setAttribute('role', 'menuitem');
        spend.innerHTML = svg('claude', 'ex-ico');
        const sl = el('span'); sl.textContent = S.chat.spend ? 'Spend this month, ' + S.chat.spend.figure : 'Spend this month';
        spend.appendChild(sl);
        spend.onclick = () => { close(); openSpendDialog(); };
        menu.append(memory, errands, spend);
        wrap.append(btn, menu);
        return wrap;
    }

    // One dialog shell for both, in the shape every other dialog here has: a scrim, a title, a body, Done.
    function chatDialog(title, body) {
        const scrim = el('div', 'ex-scrim is-on');
        const dialog = el('div', 'ex-dialog');
        const h = el('div', 'ex-dialog-title'); h.textContent = title;
        dialog.append(h, body);
        const actions = el('div', 'ex-dialog-actions');
        const done = el('button', 'ex-btn'); done.textContent = 'Done'; actions.appendChild(done);
        dialog.appendChild(actions);
        scrim.appendChild(dialog); document.body.appendChild(scrim);
        const close = () => { scrim.remove(); document.removeEventListener('keydown', onKey); };
        const onKey = (e) => { if (e.key === 'Escape') close(); };
        done.onclick = close; scrim.onclick = (e) => { if (e.target === scrim) close(); };
        document.addEventListener('keydown', onKey);
        return close;
    }

    // Everything Marvin remembers, with a remove button on each: nothing is planted in his memory unseen,
    // and the operator, not the model, has the last word on what stays.
    function openMemoryDialog() {
        const body = el('div', 'ex-dialog-body');
        const paint = () => {
            body.textContent = '';
            if (!S.chat.memory.length) {
                const none = el('div', 'ex-chat-memory-text'); none.textContent = 'Nothing yet. Tell Marvin something worth keeping.';
                body.appendChild(none);
                return;
            }
            const list = el('div', 'ex-chat-memory');
            S.chat.memory.forEach((f) => {
                const row = el('div', 'ex-chat-memory-row');
                const text = el('div', 'ex-chat-memory-text'); text.textContent = f.text;
                const rm = el('button', 'ex-iconbtn is-danger'); rm.innerHTML = svg('trash', 'ex-ico');
                rm.title = 'Forget this'; rm.onclick = () => forgetFact(f).then(paint);
                row.append(text, rm);
                list.appendChild(row);
            });
            body.appendChild(list);
        };
        paint();
        chatDialog('What Marvin remembers', body);
    }

    // What Marvin is still going to do, and the only way to stop him. An errand runs while nobody is
    // watching, so the list is the operator's whole view of it: when it runs, what it does, when it next
    // comes round, and how the last run went.
    function openErrandsDialog() {
        const body = el('div', 'ex-dialog-body');
        const paint = () => {
            body.textContent = '';
            if (!S.chat.errands.length) {
                const none = el('div', 'ex-chat-memory-text');
                none.textContent = 'Nothing yet. Ask Marvin to check something every morning.';
                body.appendChild(none);
                return;
            }
            const list = el('div', 'ex-chat-errands');
            S.chat.errands.forEach((e) => {
                const row = el('div', 'ex-chat-errand-row');
                const lines = el('div', 'ex-chat-errand-lines');
                const when = el('div', 'ex-chat-errand-when'); when.textContent = e.rhythm;
                const what = el('div', 'ex-chat-memory-text'); what.textContent = e.instruction;
                const run = el('div', 'ex-chat-errand-run');
                run.textContent = 'Next ' + localTime(e.nextDue)
                    + (e.lastOutcome ? ' \u00b7 last run ' + e.lastOutcome : '');
                lines.append(when, what, run);
                const rm = el('button', 'ex-iconbtn is-danger'); rm.innerHTML = svg('trash', 'ex-ico');
                rm.title = 'Cancel this errand'; rm.onclick = () => cancelErrand(e).then(paint);
                row.append(lines, rm);
                list.appendChild(row);
            });
            body.appendChild(list);
        };
        paint();
        chatDialog('Marvin\u2019s errands', body);
    }

    // The server sends the time with its offset, so the reader sees it in their own zone rather than the
    // server's. An unreadable time is said as that, never as "Invalid Date".
    function localTime(iso) {
        const at = new Date(iso);
        if (isNaN(at.getTime())) return 'at an unknown time';
        return at.toLocaleString(undefined,
            { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
    }

    // The month's figure, and the tokens behind it. Vaier's own count at list price on the operator's own
    // key; the invoice wins if they differ, and the dialog says so.
    function openSpendDialog() {
        const body = el('div', 'ex-dialog-body');
        const s = S.chat.spend;
        if (!s) {
            body.textContent = 'Nothing counted yet this month.';
        } else {
            const figure = el('div', 'ex-chat-spend-figure');
            figure.textContent = s.figure + ' this month';
            const answers = el('div', 'ex-chat-memory-text');
            answers.textContent = s.calls + (s.calls === 1 ? ' answer' : ' answers') + ' in ' + s.month + '.';
            const rows = el('div', 'ex-chat-spend-rows');
            [['Tokens in', s.inputTokens], ['Tokens out', s.outputTokens],
             ['Written to cache', s.cacheWriteTokens], ['Read from cache', s.cacheReadTokens]].forEach(([k, v]) => {
                const row = el('div', 'ex-chat-spend-row');
                const kk = el('span'); kk.textContent = k;
                const vv = el('span'); vv.textContent = v.toLocaleString();
                row.append(kk, vv); rows.appendChild(row);
            });
            const note = el('div', 'ex-chat-memory-text');
            note.textContent = 'Vaier\u2019s own count, at list price on your own key. The invoice wins if they differ.';
            body.append(figure, answers, rows, note);
        }
        chatDialog('Claude spend', body);
    }

    // What Marvin remembers. Re-read after every answer, because the answer may have remembered something.
    async function loadMemory() {
        try {
            const res = await fetch('/chat/memory', { cache: 'no-store' });
            if (res.ok) S.chat.memory = (await res.json()).facts || [];
        } catch (e) {
            // Shown as empty until the next read; nothing decided on it here.
        }
        if (kindOf(S.path) === 'chat') render();
    }

    // What Marvin is still going to do. Re-read after every answer, because the answer may have sent him on
    // an errand, and on every push, because one may have reported.
    async function loadErrands() {
        try {
            const res = await fetch('/chat/errands', { cache: 'no-store' });
            if (res.ok) S.chat.errands = await res.json();
        } catch (e) {
            // Shown as empty until the next read; nothing decided on it here.
        }
        if (kindOf(S.path) === 'chat') render();
    }

    async function cancelErrand(e) {
        try {
            const res = await fetch(`/chat/errands/${encodeURIComponent(e.id)}`, { method: 'DELETE' });
            if (!res.ok) { toast('Vaier could not cancel that errand.'); return; }
        } catch (err) { toast('Vaier could not cancel that errand.'); return; }
        await loadErrands();
    }

    async function forgetFact(f) {
        try {
            const res = await fetch(`/chat/memory/${encodeURIComponent(f.id)}`, { method: 'DELETE' });
            if (!res.ok) { toast('Vaier could not forget that.'); return; }
        } catch (e) { toast('Vaier could not forget that.'); return; }
        await loadMemory();
    }

    // Start over: the one way to forget, and it is Vaier that forgets — a reload would bring it all back.
    async function startOver() {
        if (S.chat.busy) return;
        try {
            const res = await fetch('/chat/conversation', { method: 'DELETE' });
            if (!res.ok) { toast('Vaier could not forget that conversation.'); return; }
        } catch (e) { toast('Vaier could not forget that conversation.'); return; }
        S.chat.turns = []; S.chat.summary = null; S.chat.error = null;
        render();
    }

    // A proposed action, as a card. The sentence is what the operator reads; the button says the same
    // thing, because it is what will happen. Nothing runs until it is clicked, and a card that was declined
    // says so and runs nothing. A card is never the answer the stream paints into, so it carries none of
    // the answer's classes.
    function chatCard(t) {
        const card = el('div', 'ex-chat-card' + (t.state === 'proposed' ? '' : ' is-' + t.state));
        const sentence = el('div', 'ex-chat-card-sentence');
        sentence.textContent = t.state === 'proposed' ? 'Marvin proposes: ' + t.sentence : t.sentence;
        card.appendChild(sentence);
        if (t.state === 'proposed') {
            const row = el('div', 'ex-chat-card-row');
            const yes = el('button', 'ex-btn is-accent');
            yes.textContent = t.sentence.replace(/\.$/, '');
            yes.onclick = () => confirmAction(t);
            const no = el('button', 'ex-btn'); no.textContent = 'Not now';
            no.onclick = () => declineAction(t);
            row.append(yes, no);
            card.appendChild(row);
        } else {
            const outcome = el('div', 'ex-chat-card-outcome');
            outcome.textContent = t.state === 'working' ? 'Doing it…' : (t.outcome || '');
            card.appendChild(outcome);
        }
        return card;
    }

    // The click. The card is taken once on the server, so a second click cannot run it twice; what came of
    // it is written into the card either way.
    async function confirmAction(t) {
        if (t.state !== 'proposed') return;
        t.state = 'working'; render();
        try {
            const res = await fetch(`/chat/actions/${encodeURIComponent(t.id)}`, { method: 'POST' });
            const body = await res.json().catch(() => ({}));
            t.state = res.ok && body.done ? 'done' : 'failed';
            t.outcome = body.text || 'Vaier could not do that.';
        } catch (e) {
            t.state = 'failed'; t.outcome = 'Vaier could not do that.';
        }
        render();
    }

    // Files handed over, as a card whose button is the download. The browser follows the link and streams
    // the zip to disk, as every Explorer download does; the link lives an hour.
    function chatBundleCard(t) {
        const card = el('div', 'ex-chat-card is-bundle');
        const sentence = el('div', 'ex-chat-card-sentence');
        sentence.textContent = t.name + ' is ready: ' + t.size + '.';
        const row = el('div', 'ex-chat-card-row');
        const get = el('button', 'ex-btn is-accent');
        get.innerHTML = svg('download', 'ex-ico');
        get.append(document.createTextNode('Download ' + t.name));
        get.onclick = () => { window.location.href = t.url; };
        row.appendChild(get);
        card.append(sentence, row);
        return card;
    }

    // "Not now". Told to the server, so the card can never run afterwards and Vaier remembers it was
    // declined; the card says so either way.
    async function declineAction(t) {
        if (t.state !== 'proposed') return;
        t.state = 'declined'; t.outcome = 'Not done.'; render();
        try {
            await fetch(`/chat/actions/${encodeURIComponent(t.id)}`, { method: 'DELETE' });
        } catch (e) {
            // The card expires on its own in ten minutes; declining it is never urgent.
        }
    }

    // One question: append it, open a Vaier turn, then fill that turn as the stream arrives. The
    // conversation so far is Vaier's to remember, so only the question is sent.
    async function askVaier(question) {
        if (S.chat.busy) return;
        S.chat.turns.push({ role: 'OPERATOR', text: question });
        const answer = { role: 'VAIER', text: '' };
        S.chat.turns.push(answer);
        S.chat.busy = true; S.chat.error = null;
        render();
        let finished = false;
        try {
            const res = await fetch('/chat', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ question: question }),
            });
            if (!res.ok) {
                const e = await res.json().catch(() => ({}));
                throw new Error(e.message || 'Vaier could not answer.');
            }
            await readAnswerStream(res.body, (name, data) => {
                if (name === 'text') { answer.text += data; paintLastAnswer(answer.text); paintWorking(null); }
                else if (name === 'working') paintWorking(data);
                else if (name === 'done') finished = true;
                else if (name === 'ping') { /* the pulse that keeps a quiet stream open; nothing to show */ }
                else if (name === 'bundle') {
                    const b = JSON.parse(data);
                    S.chat.turns.splice(S.chat.turns.length - 1, 0,
                        { role: 'VAIER', kind: 'bundle', id: b.id, name: b.name, size: b.size, url: b.url });
                    render();
                }
                else if (name === 'confirm') {
                    // The card goes before the answer being written, so the answer stays the last Vaier
                    // turn and the streaming painter keeps writing into the right element.
                    const c = JSON.parse(data);
                    S.chat.turns.splice(S.chat.turns.length - 1, 0,
                        { role: 'VAIER', kind: 'card', id: c.id, sentence: c.sentence, state: 'proposed' });
                    render();
                }
                else if (name === 'error') throw new Error(data || 'Vaier could not answer.');
            });
            // The stream ended without Vaier saying so: something on the way closed a quiet connection
            // while Marvin was still working. His answer is still made and kept, so say that rather than
            // leave a half-sentence looking like the whole of it.
            if (!finished) {
                S.chat.error = 'The connection dropped while Marvin was still working. His answer lands in '
                    + 'the thread when he finishes; reload to see it.';
            }
        } catch (e) {
            if (!answer.text) S.chat.turns.pop();
            S.chat.error = e.message || 'Vaier could not answer.';
        }
        S.chat.busy = false;
        render();
        loadMemory();
        loadErrands();
        loadSpend();
    }

    // What Marvin is doing right now, under the answer being written — so a long wait says what it waits
    // on. Cleared the moment words arrive; never part of the answer.
    const WORKING = {
        fleet: 'Marvin is looking at the fleet\u2026', waiting_to_join: 'Marvin is checking who is waiting\u2026',
        published_services: 'Marvin is checking the services\u2026', backups: 'Marvin is reading the backup runs\u2026',
        disks: 'Marvin is reading the disks\u2026', container_updates: 'Marvin is checking for container updates\u2026',
        security: 'Marvin is reading the block list\u2026', run_on_machine: 'Marvin is running a command\u2026',
        bundle_files: 'Marvin is gathering the files\u2026', email_bundle: 'Marvin is sending the mail\u2026',
        remember: 'Marvin is making a note\u2026', forget: 'Marvin is forgetting\u2026',
        add_errand: 'Marvin is writing it in his diary\u2026', cancel_errand: 'Marvin is crossing it out\u2026',
    };
    function paintWorking(tool) {
        const turns = document.querySelectorAll('.ex-chat-turn.is-vaier');
        const last = turns[turns.length - 1];
        if (!last) return;
        let line = last.querySelector('.ex-chat-working');
        if (!tool) { if (line) line.remove(); return; }
        if (!line) { line = el('div', 'ex-chat-working'); last.appendChild(line); }
        line.textContent = WORKING[tool] || 'Marvin is writing a card\u2026';
    }

    // Repaint only the answer being written, so the page does not rebuild on every few words.
    function paintLastAnswer(text) {
        const turns = document.querySelectorAll('.ex-chat-turn.is-vaier .ex-chat-text');
        const last = turns[turns.length - 1];
        if (last) { last.textContent = text; const pane = $('exPane'); if (pane) pane.scrollTop = pane.scrollHeight; }
    }

    // A text/event-stream body read by hand: events are separated by a blank line, a multi-line payload
    // arrives as several data: lines, and a chunk boundary can fall anywhere — so buffer until a blank line.
    async function readAnswerStream(stream, onEvent) {
        const reader = stream.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        for (;;) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            let cut;
            while ((cut = buffer.indexOf('\n\n')) >= 0) {
                const raw = buffer.slice(0, cut); buffer = buffer.slice(cut + 2);
                let name = 'message'; const data = [];
                raw.split('\n').forEach((line) => {
                    if (line.startsWith('event:')) name = line.slice(6).trim();
                    // Spring writes `data:` with nothing after the colon, so a chunk that begins with a space
                    // (" are") keeps it. Stripping one, as a browser's EventSource would, glued words together.
                    else if (line.startsWith('data:')) data.push(line.slice(5));
                });
                onEvent(name, data.join('\n'));
            }
        }
    }

    async function loadSettings() {
        if (S.settings.state === 'loading') return;
        S.settings = { ...S.settings, state: 'loading' };
        try {
            const [cfg, ver, upd] = await Promise.all([
                fetch('/settings/config', { cache: 'no-store' }).then((r) => (r.ok ? r.json() : null)),
                fetch('/settings/version').then((r) => (r.ok ? r.json() : {})).catch(() => ({})),
                // Whether a newer Vaier is being served, and how the last update went. Read with the rest of
                // the page rather than polled: an image going stale is not news that decays in seconds.
                fetch('/settings/update', { cache: 'no-store' })
                    .then((r) => (r.ok ? r.json() : {})).catch(() => ({})),
            ]);
            S.settings = { state: cfg ? 'ready' : 'error', config: cfg,
                version: (ver || {}).version || '', update: upd || {} };
        } catch (e) {
            S.settings = { state: 'error', config: null, version: '', update: {} };
        }
        render();
    }

    // Replace Vaier with the newer image. The answer comes back before the work is done and cannot come back
    // after: the container serving this request is the one being replaced. So the toast is the whole report —
    // it says what is about to happen and what to expect, rather than pretending to wait for an outcome this
    // page will not be here to receive. The host decides how it ends, and puts the old build back if the new
    // one never answers; that verdict is waiting on this page the next time it loads.
    async function updateVaier() {
        toast('Vaier is replacing itself. It will go quiet for a moment — reload in a minute.');
        try {
            const res = await fetch('/settings/update', { method: 'POST' });
            if (!res.ok) {
                const body = await res.json().catch(() => ({}));
                toast('Vaier could not start the update (' + (body.detail || 'unknown') + '). Nothing changed.');
            }
        } catch (e) {
            // A dropped connection here is the expected shape of success: the container answering us died.
        }
    }

    // Save one section: PUT/POST the body, then write the outcome into that section's own note. It never
    // re-reads the whole config — a saved section already shows its new value.
    // A key saved or removed changes what the menu offers, so both are re-read before the next paint.
    async function afterKeyChange() {
        S.chat = { turns: [], summary: null, loaded: false, busy: false, error: null, draft: '', memory: [], errands: [], spend: null };
        await Promise.all([loadChatAvailability(), loadSettings()]);
        renderVMenu();
        loadSpend();
    }

    async function saveSetting(url, method, body, noteEl, okText) {
        noteEl.className = 'ex-set-note';
        noteEl.textContent = 'Saving…';
        try {
            const res = await fetch(url, { method: method, headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body) });
            if (res.ok) {
                noteEl.className = 'ex-set-note is-ok';
                noteEl.textContent = okText || 'Saved.';
                return true;
            }
            const err = await res.json().catch(() => ({}));
            noteEl.className = 'ex-set-note is-err';
            noteEl.textContent = err.message || 'Could not save.';
            return false;
        } catch (e) {
            noteEl.className = 'ex-set-note is-err';
            noteEl.textContent = 'Could not save.';
            return false;
        }
    }

    // The last kit written this session, kept out of S.settings (which loadSettings replaces wholesale) so
    // navigating away from Settings and back does not lose the one record of where the copies went. Stamped,
    // because "written" with no time reads as "written now" the second time you see it.
    let _kitWrite = null;

    // What one write achieved, and why it went where it did.
    //
    // The headline is the only question worth asking of a kit — is there now something out there that opens
    // without this server — so it is answered in those words rather than as a count of successful requests.
    // Vaier chose these machines, so it says why for each one, the same way a nudge does: an operator can
    // disagree with a stated reason, but should never have to derive one. Machines that were passed over are
    // folded away; they are reasoning, not trouble, and the fleet has more of them than kit hosts.
    function renderKitWrite(host, r, at) {
        host.textContent = '';
        const kept = (r.chosen || []).length;
        const when = at ? at.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '';

        if (r.survivesLossOfVaier) {
            const line = el('div', 'ex-runline');
            line.textContent = (kept === 1 ? '1 machine holds a copy' : kept + ' machines hold a copy')
                + ' you can read without this server. Written ' + when + '.';
            host.appendChild(line);
        } else {
            // Vaier's own copy was still written, and saying so matters: the operator can open the kit right
            // here, today. It just does not survive the one event the kit exists for.
            host.appendChild(note('No machine took a copy, so nothing written just now outlives this server. '
                + 'Vaier’s own copy is in place — it dies with the machine it is on. The failures below say '
                + 'what each host refused with.', true));
        }

        if (r.fewerCopiesThanIntended && r.survivesLossOfVaier) {
            const short = el('div', 'ex-hint');
            short.textContent = 'Vaier keeps three copies where it can, never two in the same place. This '
                + 'fleet had room for ' + kept + '.';
            host.appendChild(short);
        }

        const rows = (items, cls, name, why) => {
            if (!items || !items.length) return;
            const list = el('div', 'ex-kit-list');
            items.forEach((it) => {
                const row = el('div', 'ex-kit-row' + (cls ? ' ' + cls : ''));
                const n = el('div', 'ex-kit-name'); n.textContent = name(it);
                const w = el('div', 'ex-kit-why'); w.textContent = why(it);
                row.append(n, w);
                list.appendChild(row);
            });
            host.appendChild(list);
        };

        rows(r.chosen, null, (p) => p.machineName, (p) => p.reason);
        // A host that refused is the one thing here that needs acting on, so it is never folded away.
        rows(r.failures, 'is-err', (f) => f.machineName,
            (f) => 'No copy was written: ' + f.reason);

        if (r.skipped && r.skipped.length) {
            const d = disclosure('Why not the other machines?');
            const list = el('div', 'ex-kit-list');
            r.skipped.forEach((sk) => {
                const row = el('div', 'ex-kit-row is-quiet');
                const n = el('div', 'ex-kit-name'); n.textContent = sk.machineName;
                const w = el('div', 'ex-kit-why'); w.textContent = sk.reason;
                row.append(n, w);
                list.appendChild(row);
            });
            d.appendChild(list);
            host.appendChild(d);
        }
    }

    function renderSettings(pane) {
        pane.appendChild(paneHead('Settings', false, 'Vaier-wide configuration'));
        const body = el('div', 'ex-pane-body');

        if (S.settings.state === 'idle' || S.settings.state === 'loading') {
            if (S.settings.state === 'idle') loadSettings();
            body.appendChild(note('Reading settings…', false));
            return pane.appendChild(body);
        }
        if (S.settings.state === 'error' || !S.settings.config) {
            body.appendChild(note('Vaier could not read its settings.', true));
            return pane.appendChild(body);
        }
        const c = S.settings.config;

        const field = (label, hint, control) => {
            const f = el('div', 'ex-field');
            const l = el('label'); l.textContent = label;
            f.append(l, control);
            if (hint) { const hn = el('div', 'ex-hint'); hn.textContent = hint; f.appendChild(hn); }
            return f;
        };
        const input = (value, ph, type) => {
            const i = el('input', 'ex-input');
            i.type = type || 'text'; i.value = value == null ? '' : String(value);
            if (ph) i.placeholder = ph; i.autocomplete = 'off'; i.spellcheck = false;
            return i;
        };
        // A form section: a titled block of fields with its own Save button and inline note.
        const sectionForm = (title) => {
            body.appendChild(section(title));
            const form = el('div', 'ex-form'); body.appendChild(form);
            return form;
        };
        const saveRow = (form, label, onSave) => {
            const row = el('div', 'ex-set-actions');
            const btn = el('button', 'ex-btn is-accent'); btn.textContent = label;
            const noteEl = el('span', 'ex-set-note');
            btn.onclick = () => onSave(noteEl);
            row.append(btn, noteEl); form.appendChild(row);
            return noteEl;
        };

        // --- Nightly backups: the fleet-wide "when" (the one backup knob the operator owns) ---
        const sched = sectionForm('Nightly backups');
        const hour = el('select', 'ex-input');
        for (let h = 0; h < 24; h++) {
            const o = el('option'); o.value = String(h);
            o.textContent = String(h).padStart(2, '0') + ':00'; hour.appendChild(o);
        }
        hour.value = String(c.backupScheduleHour);
        sched.appendChild(field('Runs each night at',
            'In ' + (c.backupScheduleZone || 'the server’s zone') + '. A failed run emails the admins.', hour));
        saveRow(sched, 'Save schedule', (n) => saveSetting('/settings/backup-schedule', 'PUT',
            { backupScheduleHour: parseInt(hour.value, 10) }, n, 'Schedule saved.'));

        // --- The survival kit: the way out of the circle the backups are otherwise in ---
        //
        // Two controls, one decision. The passphrase is the operator's to choose and to keep — never
        // generated, because a strong random string is exactly what nobody carries in their head, and the
        // kit's whole point is that it opens on a day Vaier cannot be asked anything. Typed twice, because a
        // typo here is invisible until the one day it matters.
        const kitForm = sectionForm('Reading your backups without Vaier');
        const kitWhy = el('div', 'ex-hint');
        kitWhy.textContent = 'Every passphrase that unlocks a machine’s backups lives in Vaier, and Vaier’s '
            + 'own backup is encrypted with one of them — so losing this server would leave you holding '
            + 'archives nothing can open. A survival kit is that list, encrypted with a passphrase only you '
            + 'know and copied onto machines Vaier does not run on. Each copy carries the one openssl '
            + 'command that opens it, printed in the clear on the file itself.';
        kitForm.appendChild(kitWhy);

        const kitPass = input('', c.hasSurvivalKitPassphrase
            ? 'A passphrase is set — type a new one to replace it' : 'Choose a passphrase', 'password');
        const kitAgain = input('', 'Type it again', 'password');
        kitForm.append(
            field('Kit passphrase', 'The one thing you keep yourself. Write it down somewhere that is not a '
                + 'computer — Vaier cannot recover it, and a kit nobody can open is not a kit.', kitPass),
            field('Confirm', c.hasSurvivalKitPassphrase
                ? 'Kits already on the fleet still open with the old passphrase until you write them again.'
                : 'Typed twice because a mistyped passphrase looks like a saved one until the day you need it.',
                kitAgain));

        // The write action and everything it reports live below the passphrase, in their own block: saving a
        // passphrase and writing a kit are separate acts, and rolling them into one Save would write kits
        // across the fleet as a side effect of typing a password.
        const kitOut = el('div', 'ex-kit-out');
        const writeRow = el('div', 'ex-set-actions');
        const writeBtn = el('button', 'ex-btn is-accent');
        writeBtn.textContent = 'Write the kit now';
        const writeNote = el('span', 'ex-set-note');
        writeRow.append(writeBtn, writeNote);

        const kitNote = saveRow(kitForm, 'Save passphrase', (n) => {
            const p = kitPass.value;
            if (!p.trim()) {
                n.className = 'ex-set-note is-err'; n.textContent = 'Enter a passphrase.'; return;
            }
            if (p !== kitAgain.value) {
                n.className = 'ex-set-note is-err'; n.textContent = 'The two entries do not match.'; return;
            }
            saveSetting('/settings/survival-kit-passphrase', 'PUT', { passphrase: p }, n,
                'Passphrase saved.').then((ok) => {
                    kitPass.value = ''; kitAgain.value = '';
                    if (!ok) return;
                    c.hasSurvivalKitPassphrase = true;
                    kitPass.placeholder = 'A passphrase is set — type a new one to replace it';
                    armWrite();
                    // Said only when there are kits out there to have gone stale by this.
                    if (_kitWrite) {
                        n.textContent = 'Passphrase saved. Write the kit again so the copies match it.';
                        n.className = 'ex-set-note is-warn';
                    }
                });
        });
        kitForm.append(writeRow, kitOut);

        // The button is only offered when it can do something. Without a passphrase it would write nothing
        // and say 409, which is a fact about an endpoint, not an answer to a person.
        function armWrite() {
            writeBtn.disabled = !c.hasSurvivalKitPassphrase;
            writeBtn.title = c.hasSurvivalKitPassphrase
                ? 'Write the kit and copy it onto the machines Vaier picks'
                : 'Choose a passphrase first — Vaier will not write a kit that anyone could open';
        }
        armWrite();
        if (_kitWrite) renderKitWrite(kitOut, _kitWrite.report, _kitWrite.at);

        writeBtn.onclick = async () => {
            writeBtn.disabled = true;
            writeNote.className = 'ex-set-note';
            writeNote.textContent = 'Writing the kit and copying it out…';
            try {
                const res = await fetch('/survival-kit', { method: 'POST' });
                if (res.ok) {
                    const report = await res.json();
                    _kitWrite = { report, at: new Date() };
                    renderKitWrite(kitOut, report, _kitWrite.at);
                    writeNote.textContent = '';
                } else if (res.status === 409) {
                    writeNote.className = 'ex-set-note is-err';
                    writeNote.textContent = 'Choose a passphrase first, then write the kit.';
                } else {
                    const err = await res.json().catch(() => ({}));
                    writeNote.className = 'ex-set-note is-err';
                    writeNote.textContent = err.message || 'Vaier could not write the kit.';
                }
            } catch (e) {
                writeNote.className = 'ex-set-note is-err';
                writeNote.textContent = 'Vaier could not write the kit.';
            }
            armWrite();
        };

        // --- Email (SMTP): the channel every alert goes out on ---
        const smtp = sectionForm('Email (SMTP)');
        const host = input(c.smtpHost, 'smtp.example.com');
        const port = input(c.smtpPort || 587, '587', 'number');
        const user = input(c.smtpUsername, 'user@example.com');
        const pass = input('', 'Leave blank to keep the stored password', 'password');
        const sender = input(c.smtpSender, 'noreply@example.com');
        const test = input('', 'you@example.com');
        smtp.append(field('Host', null, host), field('Port', null, port), field('Username', null, user),
            field('Password', null, pass), field('Sender', null, sender),
            field('Send a test to', 'Optional — checks the settings above send mail.', test));
        const smtpBody = () => ({ smtpHost: host.value.trim(), smtpPort: parseInt(port.value, 10) || 587,
            smtpUsername: user.value.trim(), smtpPassword: pass.value, smtpSender: sender.value.trim() });
        const smtpNote = saveRow(smtp, 'Save email', (n) => {
            if (!host.value.trim() || !user.value.trim() || !sender.value.trim()) {
                n.className = 'ex-set-note is-err'; n.textContent = 'Host, username and sender are required.'; return;
            }
            saveSetting('/settings/smtp', 'PUT', smtpBody(), n, 'Email settings saved.').then(() => { pass.value = ''; });
        });
        const testBtn = el('button', 'ex-btn'); testBtn.textContent = 'Send test email';
        testBtn.onclick = () => {
            if (!test.value.trim()) { smtpNote.className = 'ex-set-note is-err'; smtpNote.textContent = 'Enter a recipient for the test.'; return; }
            saveSetting('/settings/smtp/test', 'POST', { ...smtpBody(), recipient: test.value.trim() },
                smtpNote, 'Test email sent to ' + test.value.trim() + '.');
        };
        smtp.querySelector('.ex-set-actions').insertBefore(testBtn, smtp.querySelector('.ex-set-note'));

        // --- Chat: your own Anthropic API key, and nothing else about it ---
        const ask = sectionForm('Chat');
        const keyState = el('div', 'ex-hint');
        keyState.textContent = c.hasAnthropicApiKey
            ? 'A key is stored. Chat is in the menu.'
            : 'No key stored. Chat appears in the menu once there is one.';
        // Never prefilled: the key is written once and never read back to a browser.
        const key = input('', c.hasAnthropicApiKey ? 'Paste a new key to replace the stored one' : 'sk-ant-…', 'password');
        ask.append(keyState, field('Anthropic API key',
            'Your own key for the Claude API. Vaier stores it encrypted and never shows it again; it leaves the server only to talk to Claude.',
            key));
        const askNote = saveRow(ask, 'Save key', (n) => {
            if (!key.value.trim()) { n.className = 'ex-set-note is-err'; n.textContent = 'Paste a key first.'; return; }
            saveSetting('/settings/anthropic-api-key', 'PUT', { apiKey: key.value.trim() }, n, 'Key saved. Chat is in the menu.')
                .then((ok) => { key.value = ''; if (ok) afterKeyChange(); });
        });
        if (c.hasAnthropicApiKey) {
            const forget = el('button', 'ex-btn'); forget.textContent = 'Remove key';
            forget.onclick = () => saveSetting('/settings/anthropic-api-key', 'PUT', { apiKey: '' }, askNote, 'Key removed. Chat has left the menu.')
                .then((ok) => { if (ok) afterKeyChange(); });
            ask.querySelector('.ex-set-actions').insertBefore(forget, askNote);
        }

        // --- Disk monitoring ---
        const disk = sectionForm('Disk monitoring');
        const thresh = input(c.diskMonitorThresholdPercent || 85, '85', 'number');
        // This is the fleet-wide DEFAULT, not the threshold: since every filesystem got a watch of its own,
        // it is only what a filesystem is judged at until someone gives it a level on the machine's own disk
        // (DiskWatch.thresholdPercent null means "use this one"). Calling it "Alert above" read as though it
        // were the only threshold there is, which made the per-disk levels look like they were being ignored.
        disk.appendChild(field('Default alert level',
            'Percent full. Every filesystem is judged at this unless you give it a level of its own on the '
            + 'machine’s disk. Vaier emails the admins when a watched filesystem crosses its level.', thresh));
        saveRow(disk, 'Save threshold', (n) => {
            const t = parseInt(thresh.value, 10);
            if (!t || t < 1 || t > 99) { n.className = 'ex-set-note is-err'; n.textContent = 'Enter 1–99.'; return; }
            saveSetting('/settings/disk-monitor', 'PUT', { diskMonitorThresholdPercent: t }, n, 'Threshold saved.');
        });

        // --- Updating Vaier itself ---
        //
        // The one image Vaier may replace is its own, and only because a person pressed this. Everything else
        // in the fleet is detect-only (see ImageUpdateWatcher): pulling someone else's container on a hunch is
        // not Vaier's business. Doing it to yourself, on request, is a different act.
        const upd = S.settings.update || {};
        body.appendChild(section('Vaier'));
        // A rollback is the one outcome nothing else reveals: Vaier is running, just on the build from before.
        if (upd.trouble) {
            body.appendChild(note(upd.outcome === 'ROLLED_BACK'
                ? 'The last update did not come up, so Vaier put the previous build back. You are running the '
                  + 'old one — nothing was lost, and it is safe to try again.'
                : 'The last update could not be carried out (' + (upd.detail || 'unknown')
                  + '). Vaier was not touched.', true));
        }
        const upRow = el('div', 'ex-runline');
        upRow.textContent = upd.available
            ? 'A newer Vaier image is being served.'
            : 'Vaier is running the newest image it can see.';
        body.appendChild(upRow);
        if (upd.available) {
            const upActs = el('div', 'ex-lactions is-static');
            upActs.appendChild(selVerb('arrowup', 'Update Vaier', 'ex-btn is-accent', () => updateVaier()));
            body.appendChild(upActs);
        }

        // --- know: read-only facts, last, because nothing here is a decision ---
        //
        // The wildcard record's verdict used to sit up between two things the operator can change, which put
        // a fact they cannot act on in the middle of the work. It belongs to the row that names it: the kv
        // says which state Vaier found the record in, and the sentence under it says what that means.
        // Vaier grades the verdict (OK / WARNING / ERROR); this only picks a style from the grade, and nothing
        // is drawn before the boot check has run — "not checked yet" is not a problem.
        body.appendChild(section('About this server'));
        body.appendChild(kv([
            ['Version', coord(S.settings.version)],
            ['Domain', coord(c.domain)],
            ['Let’s Encrypt email', coord(c.acmeEmail)],
            ['Wildcard DNS', c.wildcardDnsLabel],
        ]));
        if (c.wildcardDnsStatus) {
            const wcCls = { OK: 'is-ok', WARNING: 'is-warn', ERROR: 'is-err' }[c.wildcardDnsSeverity]
                || 'is-warn';
            const wcNote = el('div', 'ex-set-note ' + wcCls);
            wcNote.textContent = c.wildcardDnsMessage || '';
            body.appendChild(wcNote);
        }

        pane.appendChild(body);
    }

    // --- files, and the time rail through them ----------------------------------------------------------

    // The archive being browsed as an object (the stop we are standing on), or null in the present.
    function currentArchive() {
        if (!S.at) return null;
        const held = S.archives.get(S.path[1]);
        return held && held.list ? held.list.find((a) => a.id === S.at) || null : null;
    }

    // How far back an archive sits from now — the human half of a coordinate. Read once at paint time from
    // the clock, never on a timer: the rule is that the browser never polls, and a relative time that ticked
    // itself forward would be a poll in all but name. Close enough is the point — "3 hours ago" is read
    // against the other stops, not audited.
    function timeAgo(iso) {
        if (!iso) return '';
        const then = new Date(iso);
        if (isNaN(then)) return '';
        const secs = Math.max(0, (Date.now() - then.getTime()) / 1000);
        const mins = secs / 60, hours = mins / 60, days = hours / 24;
        const plural = (n, unit) => Math.round(n) + ' ' + unit + (Math.round(n) === 1 ? '' : 's') + ' ago';
        if (mins < 1) return 'just now';
        if (hours < 1) return plural(mins, 'minute');
        if (days < 1) return plural(hours, 'hour');
        if (days < 30) return plural(days, 'day');
        return plural(days / 30, 'month');
    }

    const archiveLabel = (a) => VaierListing.formatTime(a.createdAt) + ' · ' + timeAgo(a.createdAt);

    // A Unix epoch-seconds stamp — how WireGuard reports a peer's latest handshake and how Vaier records a LAN
    // server's last-seen — rendered as a human "… ago". Zero or missing means never (an empty string, so the kv
    // shows a dash). timeAgo wants an ISO string, so seconds are lifted to milliseconds first.
    function agoFromEpochSeconds(epochSeconds) {
        const secs = Number(epochSeconds);
        if (!secs || isNaN(secs)) return '';
        return timeAgo(new Date(secs * 1000).toISOString());
    }

    // The time rail: one stop per archive, laid out newest-nearest-Now so the whole shell reads left-to-past.
    // It is the only surface that sets the past into motion — every stop's click routes through toArchive,
    // and Now through toPresent, so the light and the reads move together and nowhere else. A machine with no
    // archives grows no rail (an empty fragment appends nothing), so the file browser is untouched where
    // there is no past to show.
    function renderRail(machineId) {
        const held = S.archives.get(machineId);
        const ready = !!held && held.state === 'ready';
        // The rail takes its room from the first paint, before the archive list has landed. It has to: that
        // list is fetched while the directory is already on screen, so a rail that appears when it arrives
        // drops a whole bar into the page and shoves the rows down while the operator is reading them.
        // "Is a rail coming?" is answered by a cheaper question Vaier can settle immediately — does a job
        // back this machine up? The job list is loaded at boot, before the first paint, so the answer
        // costs nothing and is in hand in time. The stops then fill into a track that is already there.
        if (ready ? !held.list.length : !jobsOn(machineId).length) return document.createDocumentFragment();
        const archives = ready ? held.list : [];

        const rail = el('div', 'ex-rail' + (ready ? '' : ' is-waiting'));

        const now = el('button', 'ex-rail-now' + (S.at ? '' : ' is-on'));
        now.textContent = 'Now';
        now.title = 'The live filesystem';
        now.onclick = toPresent;
        rail.appendChild(now);

        const track = el('div', 'ex-rail-track');
        const stops = el('div', 'ex-rail-stops');
        archives.forEach((a) => {
            const stop = el('button', 'ex-rail-stop' + (a.id === S.at ? ' is-on' : ''));
            stop.title = archiveLabel(a);
            stop.setAttribute('aria-label', 'Backup from ' + archiveLabel(a));
            stop.onclick = () => toArchive(machineId, a.id);
            stops.appendChild(stop);
        });
        track.appendChild(stops);
        rail.appendChild(track);

        const when = el('div', 'ex-rail-when');
        const cur = currentArchive();
        if (cur) {
            const stamp = el('span', 'ex-rail-stamp');
            stamp.textContent = VaierListing.formatTime(cur.createdAt);
            const ago = el('span', 'ex-rail-ago');
            ago.textContent = timeAgo(cur.createdAt);
            when.append(stamp, ago);
        } else {
            when.textContent = 'Live filesystem';
        }
        rail.appendChild(when);
        return rail;
    }

    // The Inspector renders a directory from the same cache slot the rail reads, and selecting an unread
    // directory is what fills it. So one SFTP round trip serves both surfaces — and a slow answer can never
    // paint into the wrong place, because the pane always renders the slot belonging to the path it is
    // standing on, whatever landed while it was waiting.
    function renderDirectory(pane) {
        const machineId = S.path[1];
        // Named `machineName` and never `machine`, because everything on this pane except two sentences of
        // prose is addressed by identity — and a variable called `machine` holding a name is exactly how the
        // selection came to be keyed by one, which sent every bulk verb to /machines/<display name>.
        const machineName = nameOf(machineId);
        loadArchives(machineId);                    // fills the rail; not awaited — it re-renders when it lands
        const path = remotePath(S.path);          // null until the machine has said where its tree begins

        // The path lives in the address bar and nowhere else — the head names the machine and how much is
        // here, not the location again. A refresh re-reads this one directory over SFTP: the fleet changes
        // under Vaier (a Transfer just landed, a shell just wrote a file), and the cache is otherwise sticky.
        const loaded = S.dirs.get(dirKey(machineId, path, S.at));
        const head = paneHead(machineName, true, directorySubtitle(loaded));

        // ONE bar. The head, the selection toolbar and the paste bar were the same row shape doing the same
        // job three times — label left, actions right — and stacked they made the top of a pane a pile of
        // bars, with Refresh holding a whole row open by itself most of the time. Every verb that applies
        // right now lives in this one action group.
        //
        // The order is load-bearing: the group hugs the right edge, so appending the contextual verbs first
        // grows it leftward and leaves Refresh and Upload anchored where they were. The two controls reached
        // for idly never move as a selection or a Clipboard comes and goes.
        const actions = document.createElement('div');
        actions.className = 'ex-pane-actions';
        actions.append(selectionVerbs(), pasteVerb(machineId, path), folderVerbs(machineId, path));
        head.appendChild(actions);
        pane.appendChild(head);

        const body = document.createElement('div');
        body.className = 'ex-pane-body';
        // The rail keeps a row of its own, deliberately: it is an axis, not a verb — it is scrubbed, so it
        // needs the width — and it only exists on a machine that has archives.
        body.appendChild(renderRail(machineId));
        // Uploading is present-only, so only there does the pane become a drop target. The dropzone overlays
        // the WHOLE body — a file let go over the listing rows must upload, not make the browser open it and
        // navigate the tab off the Explorer — and stays hidden until a file drag is actually overhead, so at
        // rest it takes no room from the listing at all.
        if (!S.at && path != null) {
            body.classList.add('ex-dropzone-host');
            const zone = renderDropzone(path, machineName);
            body.appendChild(zone);
            armDropTarget(body, zone, machineId, path);
        }
        const rows = document.createElement('div');
        rows.className = 'ex-listing';
        body.appendChild(rows);
        pane.appendChild(body);

        if (dirStateOf(S.path) === 'unread') readDir(machineId, path, S.at);   // it re-renders when it lands

        const entry = S.dirs.get(dirKey(machineId, path, S.at));
        if (!entry || entry.state === 'loading') {
            return rows.appendChild(note('Listing ' + (path == null ? 'the file root' : path)
                + ' on ' + machineName + '…', false));
        }
        // The server's own sentence, verbatim — "Not allowed to read /root as geir." says more than any
        // status code could.
        if (entry.state === 'error') return failureNote(rows, machineId, entry);

        const result = { entries: entry.entries };

        const lhead = document.createElement('div');
        lhead.className = 'ex-lhead';
        // Select-all is about *this* listing: on when every row here is ticked, dashed when only some are. It
        // adds or removes only these rows, leaving the rest of the fleet-wide selection untouched.
        const allOn = result.entries.length > 0 && result.entries.every((e) => isSelected(machineId, e.path, S.at));
        const someOn = result.entries.some((e) => isSelected(machineId, e.path, S.at));
        lhead.appendChild(checkbox(allOn, someOn && !allOn, () => {
            const shouldSelect = !allOn;
            result.entries.forEach((e) => {
                if (isSelected(machineId, e.path, S.at) !== shouldSelect) toggleSel(machineId, e);
            });
            render();
        }, 'Select all'));
        ['Name', 'Size', 'Modified'].forEach((h) => {
            const cell = document.createElement('span');
            cell.textContent = h;
            lhead.appendChild(cell);
        });
        rows.appendChild(lhead);

        if (!result.entries.length) return rows.appendChild(note('This folder is empty.', false));

        result.entries.forEach((entry) => {
            const ticked = isSelected(machineId, entry.path, S.at);
            const row = document.createElement('div');
            row.className = 'ex-lrow' + (ticked ? ' is-ticked' : '');

            const check = checkbox(ticked, false, () => {
                toggleSel(machineId, entry);
                render();
            }, (ticked ? 'Deselect ' : 'Select ') + entry.name);

            // Three shapes for one name, and the entry decides which: a folder is a button (it navigates in
            // place), a file Vaier can display is a link (it opens in a tab), and everything else is plain
            // text. Whether a file can be displayed is the SERVER's verdict, riding on the entry — the
            // allowlist behind it is a security boundary, and a second copy of one in the browser is a copy
            // that drifts. Opening is an addition: the Download button stays on every row either way.
            const name = document.createElement(entry.directory ? 'button' : (entry.viewable ? 'a' : 'span'));
            name.className = 'ex-lname';
            if (!entry.directory && entry.viewable) {
                name.href = viewUrl(machineId, entry);
                name.target = '_blank';
                name.rel = 'noopener noreferrer';
                name.title = 'Open ' + entry.name + ' in a new tab';
            }
            name.innerHTML = svg(entry.directory ? 'dir' : 'file', 'ex-ico');
            const nm = document.createElement('span');
            nm.className = 'ex-nm';
            nm.textContent = entry.name;
            name.appendChild(nm);
            // A backed-up path wears a shield, right in the browser — you see what's protected, top-down, with
            // nothing to cross-reference. A folder that isn't itself backed up but holds something that is wears
            // a half-shield, so the trail down to protected content is visible as you drill in. Both facts are
            // the backend's (the domain owns the rule); the past carries neither, so an archive shows no shields.
            if (!S.at && (entry.backedUp || entry.containsBackedUp)) {
                const shield = el('span', 'ex-shield' + (entry.backedUp ? '' : ' is-partial'));
                shield.innerHTML = svg(entry.backedUp ? 'shield' : 'shieldhalf', 'ex-ico');
                // One half-shield, two ways to earn it — a folder that holds something protected deeper down,
                // and a protected folder with an exclude carving a hole inside it. The wording has to be true
                // of both, and the fact that matters in both is the same: not all of this is in the archive.
                shield.title = entry.backedUp
                    ? 'Backed up' : 'Partly backed up — not everything inside is in the archive';
                name.appendChild(shield);
            }
            if (entry.directory) name.onclick = () => go(S.path.concat([entry.name]));

            const size = document.createElement('span');
            size.className = 'ex-lmeta';
            size.textContent = VaierListing.formatSize(entry);

            const time = document.createElement('span');
            time.className = 'ex-lmeta';
            time.textContent = VaierListing.formatTime(entry.modifiedAt);

            // The same two facts again, joined into one quiet line under the name. A phone has no room for
            // columns — three of them leave a filename about twelve characters wide — so on a narrow screen
            // the columns step aside and this takes over, which is the two-line row every phone file browser
            // settled on. One row, one entry. The stylesheet decides which of the two is showing; both are
            // built here because they are the same facts, and deriving them twice is how they drift apart.
            // A column can hold a dash for "no size here" because the heading above it says what is missing.
            // On one line there is no heading, so a leading "— ·" says nothing at all — it is a placeholder
            // for a column that is not there. A directory simply gives its date.
            const sub = document.createElement('span');
            sub.className = 'ex-lsub';
            sub.textContent = [size.textContent, time.textContent]
                .filter((v) => v && v !== '—').join(' · ');

            row.append(check, name, size, time, sub, rowActions(machineId, entry));
            rows.appendChild(row);
        });
    }

    // Per-entry actions, revealed on hover (and always present to the keyboard): put a file or directory on the
    // Clipboard, or download a file straight to the browser. Copy carries the entry's whole coordinate —
    // machine, path, and the archive being viewed — so a thing copied from the past is pasted as a restore.
    function rowActions(machineId, entry) {
        const box = el('div', 'ex-lactions');

        const copy = el('button', 'ex-iconbtn');
        copy.innerHTML = svg('copy', 'ex-ico');
        copy.title = onClipboard(machineId, entry.path) ? 'On the Clipboard' : 'Copy to the Clipboard';
        copy.setAttribute('aria-label', copy.title);
        if (onClipboard(machineId, entry.path)) copy.classList.add('is-on');
        copy.onclick = () => clipCopy(machineId, entry);
        box.appendChild(copy);

        // A file downloads as itself; a folder downloads as a zip of its whole tree. Downloading the past is
        // fine either way — it is a read, and the invariant is only ever about writing.
        const dl = el('button', 'ex-iconbtn');
        dl.innerHTML = svg('download', 'ex-ico');
        dl.title = entry.directory ? 'Download as zip' : 'Download';
        dl.setAttribute('aria-label', dl.title + ' ' + entry.name);
        dl.onclick = () => download(machineId, entry);
        box.appendChild(dl);

        // Delete is present-only and destructive, so it never appears while time-travelling (you cannot edit
        // the past) and it goes through a typed-name gate before anything is removed.
        if (!S.at) {
            const rm = el('button', 'ex-iconbtn is-danger');
            rm.innerHTML = svg('trash', 'ex-ico');
            rm.title = 'Delete';
            rm.setAttribute('aria-label', 'Delete ' + entry.name);
            rm.onclick = () => deleteEntry(machineId, entry);
            box.appendChild(rm);
        }
        return box;
    }

    // --- the Clipboard: file coordinates in the hand, and the Transfers that carry them ------------------
    //
    // The Clipboard holds coordinates (machine, path, time), not bytes. Paste names the operation by its
    // destination: another machine is a copy, the same machine at a past coordinate is a restore, the browser
    // is a download. This is the copy/paste half; a Transfer (below) is the byte-moving half the backend runs.

    // A coordinate is (machine identity, path, point in time). The identity, not the name: a coordinate has
    // to keep meaning the same file after its machine is renamed, and two machines may yet share a name.
    const clipId = (machineId, path, at) => machineId + DIR_SEP + path + DIR_SEP + (at || '');
    const onClipboard = (machineId, path) =>
        S.clipboard.some((c) => c.machine === machineId && c.path === path && (c.at || '') === (S.at || ''));

    // --- the selection: the same coordinate identity the Clipboard uses, but fleet-wide and persistent -------
    // A ticked item is a whole coordinate (machine, path, archive), keyed exactly like a Clipboard item, so the
    // selection can hold files from many folders and machines at once and survive every navigation. `toggleSel`
    // captures the row's own display facts at tick time, so the bar can name and act on items whose listing is
    // no longer on screen.
    const isSelected = (machineId, path, at) =>
        S.sel.some((s) => clipId(s.machine, s.path, s.at) === clipId(machineId, path, at));
    function toggleSel(machineId, entry) {
        const id = clipId(machineId, entry.path, S.at);
        const had = S.sel.some((s) => clipId(s.machine, s.path, s.at) === id);
        S.sel = had
            ? S.sel.filter((s) => clipId(s.machine, s.path, s.at) !== id)
            // Both shields travel with the selection: the full one says the entry is whole, the half one says
            // it holds backed-up content without being whole. "Stop backing up" needs either (see anyBackedUp),
            // so carrying only the full shield would take the verb away from a folder with a hole in it.
            : S.sel.concat([{ machine: machineId, path: entry.path, at: S.at, name: entry.name,
                              directory: entry.directory, size: entry.size, backedUp: !!entry.backedUp,
                              containsBackedUp: !!entry.containsBackedUp }]);
    }
    // A machine is back-up-eligible only while the fleet HAS a backup server, and never when it IS that server
    // (the store, not a thing that is stored). Present-only items only — you protect the live tree, not an
    // archived shape of it.
    //
    // The first clause is the point: with no server designated, "Back up" used to be offered, clicked, and
    // refused by the backend — the operator discovering the precondition by tripping over it. Everything else
    // behind this button Vaier does for them without asking (it creates the repository, writes the job, folds
    // the paths in, installs borg on the host and trusts its key on the server). The single thing it cannot
    // decide for them is which machine holds the fleet's data, and that decision has a nudge of its own. So
    // until it is made, the verb simply is not there.
    const backupEligible = (machineId) => !!S.backupServer && machineId !== S.backupServer.machineId;
    // Whether there is anything in the archives at or under a selected item — the precondition for "Stop
    // backing up" to have work to do. Either shield qualifies: a folder that is whole, and a folder that is
    // protected but holed (or that merely holds a protected path deeper down) both have data to stop. Gating
    // on the full shield alone would silently drop the verb the moment one exclude appeared inside a folder.
    const anyBackedUp = (s) => !!s.backedUp || !!s.containsBackedUp;
    // Group selected items by their machine, preserving first-seen order — how the per-machine verbs fan out.
    function groupByMachine(items) {
        const groups = new Map();
        items.forEach((s) => { if (!groups.has(s.machine)) groups.set(s.machine, []); groups.get(s.machine).push(s); });
        return groups;
    }

    // Copy toggles: a second click on an entry already held takes it back off, so the same button both puts a
    // thing on the Clipboard and reconsiders it, and its lit state always tells the truth.
    function clipCopy(machineId, entry) {
        const id = clipId(machineId, entry.path, S.at);
        const at = S.at;
        const has = S.clipboard.some((c) => clipId(c.machine, c.path, c.at) === id);
        S.clipboard = has
            ? S.clipboard.filter((c) => clipId(c.machine, c.path, c.at) !== id)
            : S.clipboard.concat([{ machine: machineId, path: entry.path, at: at,
                                    name: entry.name, directory: entry.directory, size: entry.size }]);
        render();
    }

    const clipClear = () => { S.clipboard = []; render(); };
    const clipRemove = (id) => { S.clipboard = S.clipboard.filter((c) => clipId(c.machine, c.path, c.at) !== id); render(); };

    // Where a viewable file opens. A separate endpoint from the download, because they are separate verbs:
    // /files/download always saves, /files/view always displays, and neither has a mode that behaves like the
    // other. The coordinate is the same (path, at) the listing carries, so a file opened from an archive shows
    // its past self — a view is a read, and reads are allowed in the past.
    function viewUrl(machineId, entry, at) {
        at = arguments.length >= 3 ? at : S.at;
        const params = new URLSearchParams({ path: entry.path });
        if (at) params.set('at', at);
        return '/machines/' + encodeURIComponent(machineId) + '/files/view?' + params.toString();
    }

    // A download is a paste whose destination is the browser: Vaier streams the file's bytes straight through.
    // The coordinate travels as the same (path, at) the listing carries, so a file from an archive downloads
    // its past self. A hidden anchor click is how a browser is handed a stream to save. `at` defaults to the
    // archive currently being viewed (a per-row download), but a selected item passes its own — the archive it
    // was ticked in — so a one-item download from the selection reads the right past even after navigating away.
    function download(machineId, entry, at) {
        at = arguments.length >= 3 ? at : S.at;
        const params = new URLSearchParams({ path: entry.path });
        if (at) params.set('at', at);
        // What to call the machine in the filename when the download is a whole root, which has no
        // basename of its own. The path names it by identity; this is only for the person saving it.
        params.set('name', nameOf(machineId));
        const a = document.createElement('a');
        a.href = '/machines/' + encodeURIComponent(machineId) + '/files/download?' + params.toString();
        // A folder arrives as a zip; the server sets the real filename, this is just the browser's hint.
        a.download = entry.directory ? entry.name + '.zip' : entry.name;
        document.body.appendChild(a);
        a.click();
        a.remove();
        // Zipping a folder streams the whole tree over SFTP first, so the browser's Save dialog can lag well
        // behind the click. Say so, rather than leave the operator wondering whether anything happened.
        if (entry.directory) toast('Preparing ' + entry.name + '.zip — the download will begin shortly.');
    }

    // Delete, behind a gate you have to mean. A destructive act with no undo and no trash folder gets the same
    // treatment #321 asks for: name what is affected, and make the operator type the machine's name before the
    // button will fire. A folder says that everything inside goes with it. On success the listing is re-read,
    // so the thing that is gone stops being shown.
    async function deleteEntry(machineId, entry) {
        const machineName = nameOf(machineId);
        const what = entry.directory ? 'folder' : 'file';
        // The same button means two different things depending on which machine you are standing on
        // (#346). Where Vaier logs in as root it can delete what the machine needs to run, not merely
        // what you wanted to keep — so the gate says which of the two this is.
        const m = machineById(machineId);
        const body = entry.path + ' on ' + machineName
            + (entry.directory ? '\n\nEverything inside this folder is deleted too.' : '')
            + (m && m.effectiveUserPrivileged
                ? '\n\nVaier acts as ' + m.effectiveUsername + ' on ' + machineName + ', which is '
                  + 'privileged — this can remove something the machine needs to run.' : '')
            + '\n\nThis cannot be undone. Type the machine name to confirm.';
        const ok = await confirmTyped('Delete this ' + what + '?', body, machineName, 'Delete');
        if (!ok) return;
        try {
            const res = await fetch('/machines/' + encodeURIComponent(machineId)
                + '/files?path=' + encodeURIComponent(entry.path), { method: 'DELETE' });
            if (!res.ok) {
                const err = await res.json().catch(() => null);
                return toast((err && err.message) || ('Could not delete ' + entry.name + '.'));
            }
            toast('Deleted ' + entry.name + '.');
            refreshDir(machineId, remotePath(S.path));   // the listing no longer holds it
        } catch (e) {
            toast('Could not reach Vaier to delete ' + entry.name + '.');
        }
    }

    // A tri-state tick: on, off, or dashed (some-but-not-all, for select-all). A real checkbox so the
    // keyboard and screen readers get it for free; the label carries the accessible name.
    function checkbox(checked, indeterminate, onchange, label) {
        const wrap = el('label', 'ex-check');
        const input = document.createElement('input');
        input.type = 'checkbox';
        input.checked = checked;
        input.indeterminate = !!indeterminate;
        input.setAttribute('aria-label', label);
        input.onclick = (e) => e.stopPropagation();   // ticking a row must not also open it
        input.onchange = onchange;
        wrap.appendChild(input);
        return wrap;
    }

    // The selection's verbs, over everything ticked across the whole fleet: Copy adds it all to the Clipboard;
    // Download hands the browser one zip of the lot (a single item downloads as itself); Back up, Stop backing
    // up and Delete each fan out per machine over the live items. How many are ticked — and across how many
    // machines — is said once by the head's subtitle (directorySubtitle), not repeated here.
    function selectionVerbs() {
        const sel = S.sel;
        if (!sel.length) return document.createDocumentFragment();

        // Present-only items are the ones a write can touch — an archived (past) coordinate is read-only.
        const live = sel.filter((s) => !s.at);
        const backupItems = live.filter((s) => backupEligible(s.machine));
        const unbackupItems = backupItems.filter(anyBackedUp);

        // Its own group, so one hairline can separate what you have PICKED from what acts on this folder.
        const actions = el('div', 'ex-verbgroup ex-selverbs');
        actions.appendChild(selVerb('copy', 'Copy', 'ex-btn', () => selCopy()));
        actions.appendChild(selVerb('download', 'Download', 'ex-btn', () => selDownload()));
        // Back up is the whole idea: pick what matters and protect it. Live items only, and never the backup
        // server itself. Vaier makes the repository, the job and the schedule behind the one click, per machine.
        if (backupItems.length) {
            actions.appendChild(selVerb('shield', 'Back up', 'ex-btn is-accent', () => selBackup()));
            if (unbackupItems.length) {
                actions.appendChild(selVerb('cross', 'Stop backing up', 'ex-btn', () => selUnbackup()));
            }
        }
        if (live.length) actions.appendChild(selVerb('trash', 'Delete', 'ex-btn is-danger', () => selDelete()));
        const clear = el('button', 'ex-iconbtn');
        clear.innerHTML = svg('cross', 'ex-ico');
        clear.title = 'Clear selection';
        clear.setAttribute('aria-label', 'Clear selection');
        clear.onclick = () => { S.sel = []; render(); };
        actions.appendChild(clear);
        return actions;
    }

    function selVerb(icon, text, cls, onclick) {
        const b = el('button', cls);
        b.innerHTML = svg(icon, 'ex-ico');
        const s = el('span');
        s.textContent = text;
        b.appendChild(s);
        b.onclick = onclick;
        return b;
    }

    // A quiet progressive-disclosure fold — the shell's one home for rare or advanced controls, kept out of
    // sight until asked for. Native <details> so it is keyboard- and screen-reader-friendly for free; the
    // caller appends the body after the returned summary. Deliberately plain: this shell spends its boldness
    // elsewhere, and mechanism the operator rarely touches should not shout.
    // Two folds, and they must not look alike. A fold has been doing two unrelated jobs: hiding REFERENCE
    // nobody needs most of the time (connection details, storage details), and hiding things that can HURT
    // (regenerate a config, remove a machine). Same control, same look, opposite consequences — so an
    // operator opening one had no way to know which they had reached for.
    function disclosure(summaryText) {
        const d = el('details', 'ex-adv');
        const s = el('summary', 'ex-adv-sum');
        s.textContent = summaryText;
        d.appendChild(s);
        return d;
    }

    // The other kind: what is behind it can undo work. Marked in the warning colour and named by what it
    // does rather than by how advanced it is — "Advanced" said nothing about consequence, which is the one
    // thing this fold exists to say.
    function dangerFold(summaryText) {
        const d = el('details', 'ex-adv is-danger');
        const s = el('summary', 'ex-adv-sum');
        s.innerHTML = svg('warn', 'ex-ico');
        const t = el('span');
        t.textContent = summaryText;
        s.appendChild(t);
        d.appendChild(s);
        return d;
    }

    function categoryLabel(category) {
        const hit = DEVICE_CATEGORIES.find(([v]) => v === (category || ''));
        return hit ? hit[1] : (category || '');
    }

    // The device-category picker, pre-selected to a machine's current shape. Shared by the add and edit forms —
    // the empty option clears the override back to what Vaier detects.
    function catSelect(current) {
        const sel = el('select', 'ex-input');
        DEVICE_CATEGORIES.forEach(([v, t]) => {
            const o = el('option'); o.value = v; o.textContent = t;
            if (v === (current || '')) o.selected = true;
            sel.appendChild(o);
        });
        return sel;
    }

    // A small PATCH helper for the fire-and-refresh machine/service edits: send, on success reload the fleet and
    // repaint where we stand; on failure toast the server's own reason. Returns whether it worked.
    async function patchJson(url, body, failMsg, after) {
        try {
            const res = await fetch(url, { method: 'PATCH', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body) });
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                toast(err.message || failMsg);
                return false;
            }
            if (after) await after();
            return true;
        } catch (e) {
            toast(failMsg);
            return false;
        }
    }

    function selCopy() {
        const sel = S.sel;
        sel.forEach((s) => {
            const id = clipId(s.machine, s.path, s.at);
            if (!S.clipboard.some((c) => clipId(c.machine, c.path, c.at) === id)) {
                S.clipboard.push({ machine: s.machine, path: s.path, at: s.at,
                                   name: s.name, directory: s.directory, size: s.size });
            }
        });
        toast(sel.length + (sel.length === 1 ? ' item' : ' items') + ' copied to the Clipboard.');
        S.sel = [];
        render();
    }

    // One item downloads as itself (a file streamed, a folder as its own zip); two or more are handed to the
    // browser as a single zip built server-side across every machine and archive in the selection (#323). The
    // multi-item path submits a hidden form so the browser streams the zip straight to disk — no size cap, and
    // nothing buffered in the tab.
    function selDownload() {
        const sel = S.sel;
        if (!sel.length) return;
        if (sel.length === 1) {
            const s = sel[0];
            download(s.machine, { path: s.path, name: s.name, directory: s.directory }, s.at);
        } else {
            submitZipDownload(sel);
            toast('Preparing a zip of ' + sel.length + ' items — the download will begin shortly.');
        }
        S.sel = [];
        render();
    }

    // Hand the whole selection to the multi-coordinate zip endpoint. A form POST (not an anchor) because the
    // selection is too big and structured for a URL, and a navigated POST still streams the response to disk.
    function submitZipDownload(sel) {
        const form = document.createElement('form');
        form.method = 'POST';
        form.action = '/machines/files/download-zip';
        form.style.display = 'none';
        const input = document.createElement('input');
        input.type = 'hidden';
        input.name = 'selection';
        input.value = JSON.stringify(sel.map((s) => ({
            machineId: s.machine, machine: nameOf(s.machine), path: s.path, at: s.at || null })));
        form.appendChild(input);
        document.body.appendChild(form);
        form.submit();
        form.remove();
    }

    // Re-read the directory on screen, if one is — so the shields (or a deletion) show at once. Items on other
    // machines in the selection refresh when their listing is next visited; only the visible one needs a nudge.
    function refreshCurrentDir() {
        const k = kindOf(S.path);
        if (k === 'files' || k === 'dir') refreshDir(S.path[1], remotePath(S.path));
    }

    // Back the selection up — the one gesture that is the whole feature. Live items only, fanned out per machine:
    // for each, the browser sends only the paths, and the backend makes the repository if there is none, makes
    // the job if there is none, and folds the paths in (a child of something already protected just disappears
    // into it). Then the jobs reload and the shields appear where the selection was. Vaier holds the complexity;
    // the operator held down a checkbox — on as many machines at once as they liked.
    async function selBackup() {
        const groups = groupByMachine(S.sel.filter((s) => !s.at && backupEligible(s.machine)));
        if (!groups.size) return;
        let done = 0;
        const failed = [];
        for (const [machineId, items] of groups) {
            const paths = items.map((i) => i.path);
            try {
                const res = await fetch('/machines/' + encodeURIComponent(machineId) + '/backup/paths', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ paths: paths }),
                });
                if (!res.ok) { failed.push(nameOf(machineId)); continue; }
                const body = await res.json().catch(() => ({}));
                done += paths.length;
                // First back-up on a machine: the backend rings the host to be readied (borg installed, key
                // trusted) and tells us here. The install runs detached; we watch prepare-client-settled.
                if (body && body.provisioning) startReadying(machineId, body.provisioning);
            } catch (e) { failed.push(nameOf(machineId)); }
        }
        await loadBackup();   // the jobs changed — reload so the backup entries are current
        if (done) toast(done + (done === 1 ? ' item is' : ' items are') + ' backed up now, and nightly'
            + (groups.size > 1 ? ' across ' + groups.size + ' machines.' : '.'));
        if (failed.length) toast('Vaier could not back up on ' + failed.join(', ') + '.');
        S.sel = [];
        refreshCurrentDir();   // the shields are stamped by the backend — re-read to show them
        render();
    }

    // Ready a host that a run found had no borg client. The same work the first back-up does silently, asked
    // for on purpose this time — so a machine whose job already exists is not stranded, which is exactly what
    // used to happen: preparation only ever ran on a machine's FIRST back-up, and adding paths to a machine
    // that already had a job deliberately never re-did it. No endpoint was opened; this route outlived the
    // page that used to call it.
    async function readyClient(job) {
        const machineName = nameOf(job.machineId);
        S.readying.delete(job.machineId);
        try {
            const res = await fetch('/backup-jobs/' + encodeURIComponent(job.machineId) + '/prepare-client',
                { method: 'POST' });
            if (!res.ok) {
                toast('Vaier could not start getting ' + machineName + ' ready.');
                return;
            }
            startReadying(job.machineId, await res.json());
        } catch (e) {
            toast('Vaier could not start getting ' + machineName + ' ready.');
        }
    }

    // What Vaier does behind the first back-up: ready the host (install borg, trust its key). It's silent by
    // design — a quiet "Getting X ready…" and nothing more — unless it hits the one wall it can't pass on its
    // own, a host where it lacks the root to install borg, in which case it names the single command to run.
    function startReadying(machineId, p) {
        const machineName = nameOf(machineId);
        if (p.scriptOnly && p.stagedScriptPath) {
            // A command someone has to retype into another machine cannot live in a toast — it is gone
            // before it has been read, let alone copied. It is kept against the machine and rendered on its
            // Backup entry, where the failure that needs it is already reported, until the readying lands.
            S.readying.set(machineId, 'sudo bash ' + p.stagedScriptPath);
            toast('One command left to run on ' + machineName + ' — it is on its Backup entry.');
            render();
            return;
        }
        if (p.started) {
            S.preparing.add(machineId);
            toast('Getting ' + machineName + ' ready to back up…');
            render();
        } else if (p.message) {
            toast(p.message);
        }
    }

    // Stop backing the selection up, per machine. How a folder stops depends on how it was protected: one the
    // job names outright simply goes (with everything under it), while one that only sits INSIDE a protected
    // folder cannot be dropped without losing its siblings, so the backend records it as an exclusion instead.
    // That decision is the backend's, and so is the account of what it did — we count what it says stopped, not
    // what we sent. A path nothing was backing up stops nothing, and telling an operator their data stopped
    // being protected when it did not is the one lie a backup tool must never tell. If a machine ends up
    // protecting nothing at all, the backend forgets that machine's job entirely (archives already made are
    // untouched). Only ever the live, already-backed-up items.
    async function selUnbackup() {
        const groups = groupByMachine(
            S.sel.filter((s) => !s.at && anyBackedUp(s) && backupEligible(s.machine)));
        if (!groups.size) return;
        let done = 0;
        const failed = [];
        for (const [machineId, items] of groups) {
            const paths = items.map((i) => i.path);
            try {
                const res = await fetch('/machines/' + encodeURIComponent(machineId) + '/backup/paths', {
                    method: 'DELETE',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ paths: paths }),
                });
                if (!res.ok && res.status !== 204) { failed.push(nameOf(machineId)); continue; }
                if (res.status === 204) {
                    // The job itself is gone: nothing is backed up on that machine any more, so everything
                    // asked for really did stop. This is the one branch where our own count is the true one.
                    done += paths.length;
                    continue;
                }
                const body = await res.json().catch(() => null);
                done += body && body.stopped ? body.stopped.length : 0;
            } catch (e) { failed.push(nameOf(machineId)); }
        }
        await loadBackup();
        if (done) toast('Stopped backing up ' + done + (done === 1 ? ' item.' : ' items.'));
        else if (!failed.length) toast('Nothing changed — Vaier was not backing that up.');
        if (failed.length) toast('Vaier could not stop backing up on ' + failed.join(', ') + '.');
        S.sel = [];
        refreshCurrentDir();   // re-read so the shields clear
        render();
    }

    // One gate for the whole batch. The past cannot be deleted, so only live items go. A single machine keeps the
    // strong machine-name gate; several require the word "delete" and the body names every machine that will be
    // touched. A failure on one item is reported and the rest still go; the visible listing is re-read at the end.
    async function selDelete() {
        const items = S.sel.filter((s) => !s.at);
        if (!items.length) return;
        const groups = groupByMachine(items);
        const machineNames = [...groups.keys()].map(nameOf);
        const single = machineNames.length === 1;
        const preview = items.slice(0, 6).map((s) => (single ? '' : nameOf(s.machine) + ':') + s.path).join('\n')
            + (items.length > 6 ? '\n…and ' + (items.length - 6) + ' more' : '');
        const ok = await confirmTyped(
            'Delete ' + items.length + (items.length === 1 ? ' item?' : ' items?'),
            preview + '\n\nEverything selected is deleted, folders and all they contain'
            + (single ? '' : ', across ' + machineNames.length + ' machines (' + machineNames.join(', ') + ')')
            + '. This cannot be undone. Type ' + (single ? 'the machine name' : '“delete”') + ' to confirm.',
            single ? machineNames[0] : 'delete', 'Delete');
        if (!ok) return;
        let failed = 0;
        for (const [machineId, its] of groups) {
            for (const s of its) {
                try {
                    const res = await fetch('/machines/' + encodeURIComponent(machineId)
                        + '/files?path=' + encodeURIComponent(s.path), { method: 'DELETE' });
                    if (!res.ok) failed++;
                } catch (e) { failed++; }
            }
        }
        const total = items.length;
        toast(failed ? ('Deleted ' + (total - failed) + ' of ' + total + '; ' + failed + ' could not be removed.')
                     : ('Deleted ' + total + (total === 1 ? ' item.' : ' items.')));
        S.sel = [];
        refreshCurrentDir();
        render();
    }

    // Paste belongs to a directory in the present, and only there — you can only paste into the present, so
    // time-travelling hides the verb rather than offering a write that would be refused. It also needs a real
    // destination path (the machine has said where its tree begins) and a non-empty Clipboard.
    //
    // The old bar carried a "Clipboard: 3 items" label beside a "Paste here" button. In one shared row the
    // label has no room and "here" has no referent, so the count moves into the button and it says exactly
    // what it will do. What is ON the Clipboard is still listed in the tray, which is where it belongs.
    function pasteVerb(machineId, destPath) {
        if (S.at || !S.clipboard.length || destPath == null) return document.createDocumentFragment();

        const group = el('div', 'ex-verbgroup');
        const n = S.clipboard.length;
        const paste = el('button', 'ex-btn is-accent');
        paste.innerHTML = svg('clip', 'ex-ico');
        const verb = el('span');
        verb.textContent = 'Paste ' + n + (n === 1 ? ' item' : ' items');
        paste.appendChild(verb);
        paste.onclick = () => pasteHere(machineId, destPath);
        group.appendChild(paste);
        return group;
    }

    // --- Upload: files from this browser into the folder in front of you --------------------------------
    //
    // The mirror of a download, and the one operation in the Explorer whose bytes start on the operator's own
    // machine rather than on the fleet. Not a Transfer: a Transfer has a source machine and a point in time,
    // and this has neither.

    // The always-available affordance: an icon button in the pane head, beside Refresh.
    //
    // It first shipped as a bar above the listing and that was simply wrong. The selection bar and the paste
    // bar earn their row by appearing only when something is ticked or held; an upload control is available in
    // every folder, so a permanent bar bought nothing and cost a row on every listing — three stacked trays
    // once you were also selecting and pasting. A folder's verbs already have a home in the pane head, at no
    // vertical cost, and that is where a permanent one belongs.
    //
    // Present-only, like every write — an archive is read-only, so there is nowhere in the past to put a file
    // — and it needs a real destination (the machine has said where its tree begins). Absent either, nothing
    // is drawn.
    function renderUploadAction(machineId, destPath) {
        if (S.at || destPath == null) return document.createDocumentFragment();

        // A real file input, kept out of sight and clicked by the button: the OS picker is the only way to
        // reach the filesystem, and a bare <input type=file> cannot be styled to belong in this row. Files
        // only — the input never gets the directory-picking attribute, because uploading a folder means
        // recreating a tree on the far side, which is a different operation with its own failure modes and is
        // not this one.
        const input = document.createElement('input');
        input.type = 'file';
        input.multiple = true;
        input.className = 'ex-upload-input';
        input.tabIndex = -1;        // the button is the control; the input must not be a second tab stop
        input.setAttribute('aria-hidden', 'true');
        input.onchange = () => {
            uploadFiles(machineId, destPath, Array.from(input.files));
            input.value = '';   // so picking the same file twice in a row still fires
        };

        // The same ex-iconbtn shape as Refresh beside it — one row of a folder's verbs, all reading alike.
        const pick = el('button', 'ex-iconbtn');
        pick.innerHTML = svg('upload', 'ex-ico');
        pick.title = 'Upload files';
        pick.setAttribute('aria-label', 'Upload files to this folder');
        pick.onclick = () => input.click();

        const frag = document.createDocumentFragment();
        frag.append(pick, input);
        return frag;
    }

    // The drop affordance, drawn hidden and lit by the drag itself. The original reasoning holds — dropping
    // files onto a folder is invisible until something says you can — but it is only worth saying at the
    // moment it is true.
    //
    // An overlay across the pane body, not a tray sliding in above the listing. Two reasons, and the second is
    // the one that decided it: the body *is* the drop target, so lighting exactly it is the honest statement
    // of where you may let go; and a tray would push the listing down at the instant the operator is holding a
    // file over the rows they are aiming at, moving the target under the pointer. The overlay shifts nothing.
    function renderDropzone(destPath, machineName) {
        const zone = el('div', 'ex-dropzone');
        const inner = el('div', 'ex-dropzone-inner');
        inner.innerHTML = svg('upload', 'ex-dropzone-ico');
        const title = el('div', 'ex-dropzone-title');
        title.textContent = 'Drop to upload';
        // Which folder on which machine, because the pane behind the overlay is now dimmed out of reading.
        const where = el('div', 'ex-dropzone-where');
        where.textContent = destPath + ' on ' + machineName;
        inner.append(title, where);
        zone.appendChild(inner);
        return zone;
    }

    // Take a drop of files anywhere on {@code host} — the whole directory pane body — lighting {@code zone}
    // (the overlay) while a file drag is overhead.
    //
    // Arming the WHOLE body, not just the lit overlay, is the point. A browser's default action for a dropped
    // file is to open it, which navigates the tab away from the Explorer and loses everything on screen — so a
    // file let go over the listing, the obvious place to aim, would throw the operator out of the page. The
    // body swallows the drop wherever it lands; the overlay only says so.
    //
    // Only a drag carrying FILES lights it. Dragging selected text across a listing is an ordinary thing to do
    // and must not offer to upload it.
    //
    // The depth counter is the awkward part of the HTML drag API: dragenter/dragleave fire for every child
    // element the pointer crosses, so a plain "off on leave" flickers the overlay across every row.
    function armDropTarget(host, zone, machineId, destPath) {
        let depth = 0;
        const carriesFiles = (e) => Array.from((e.dataTransfer && e.dataTransfer.types) || []).includes('Files');
        const off = () => { depth = 0; zone.classList.remove('is-on'); };
        host.addEventListener('dragover', (e) => {
            if (!carriesFiles(e)) return;
            e.preventDefault();
            e.dataTransfer.dropEffect = 'copy';
        });
        host.addEventListener('dragenter', (e) => {
            if (!carriesFiles(e)) return;
            e.preventDefault();
            depth++;
            zone.classList.add('is-on');
        });
        host.addEventListener('dragleave', () => { if (--depth <= 0) off(); });
        host.addEventListener('drop', (e) => {
            e.preventDefault();
            off();
            // Only files. A dragged folder also arrives in `files`, as an entry no reader can open — so its
            // names are collected once (the entries are valid only for the life of this event) and dropped
            // from the list, and the operator is told plainly rather than watching an upload fail.
            const folderNames = Array.from(e.dataTransfer.items || [])
                .map((i) => (i.webkitGetAsEntry ? i.webkitGetAsEntry() : null))
                .filter((entry) => entry && entry.isDirectory)
                .map((entry) => entry.name);
            if (folderNames.length) toast('Folders cannot be uploaded — drop the files inside them.');
            uploadFiles(machineId, destPath,
                Array.from(e.dataTransfer.files || []).filter((f) => !folderNames.includes(f.name)));
        });
    }

    // Send the picked files one at a time. One request per file, in sequence, so each gets its own progress
    // and its own answer — which is what makes a per-file "Replace it?" possible at all, and keeps a fleet
    // machine behind WireGuard from being asked to write six streams at once.
    async function uploadFiles(machineId, destPath, files) {
        if (!files.length) return;
        for (const file of files) {
            await startUpload(machineId, destPath, file, false);
        }
    }

    // One file, up. Progress comes from the browser's OWN send events (XHR upload.onprogress) — this is the
    // one operation in the shell where the browser is the thing doing the work, so its progress is its own to
    // report; nothing is polled and there is no endpoint that could be asked. XHR rather than fetch precisely
    // because fetch still cannot report request-body progress.
    //
    // A name already taken comes back 409 rather than being overwritten, and that is the whole design: the
    // operator is asked, then the same file is sent again with `overwrite`. Confirming is the only path to a
    // replacement — nothing here retries by itself.
    function startUpload(machineId, destPath, file, overwrite) {
        const id = 'up-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8);
        const record = { id: id, name: file.name, machine: machineId, path: destPath,
                         sent: 0, total: file.size, state: 'running' };
        S.uploads.set(id, record);
        renderClip();

        const params = new URLSearchParams({ path: destPath });
        if (overwrite) params.set('overwrite', 'true');
        const form = new FormData();
        form.append('file', file);

        return new Promise((resolve) => {
            const xhr = new XMLHttpRequest();
            xhr.open('POST', '/machines/' + encodeURIComponent(machineId) + '/files/upload?' + params.toString());
            xhr.upload.onprogress = (e) => {
                if (!e.lengthComputable) return;
                record.sent = e.loaded;
                record.total = e.total;
                renderClip();
            };
            xhr.onload = async () => {
                if (xhr.status >= 200 && xhr.status < 300) {
                    record.state = 'done';
                    record.sent = record.total;
                    renderClip();
                    refreshDir(machineId, destPath);   // the folder holds something it did not a moment ago
                    return resolve(true);
                }
                const said = refusal(xhr);
                // 409 is the backend refusing to overwrite silently. Chat, and only on a yes send it again with
                // the flag — and only once: a second 409 (a folder wears the name) is a refusal no replacement
                // settles, so it is reported rather than asked about again.
                if (xhr.status === 409 && !overwrite) {
                    S.uploads.delete(id);
                    renderClip();
                    const ok = await confirmModal('Replace ' + file.name + '?', said, 'Replace');
                    if (!ok) return resolve(false);
                    return resolve(await startUpload(machineId, destPath, file, true));
                }
                record.state = 'failed';
                record.error = said;
                renderClip();
                resolve(false);
            };
            xhr.onerror = () => {
                record.state = 'failed';
                record.error = 'Could not reach Vaier to upload ' + file.name + '.';
                renderClip();
                resolve(false);
            };
            xhr.send(form);
        });
    }

    // The server's own sentence for a refusal — "\"notes.txt\" is already in /home/geir." says more than any
    // status code, and it is the text the Replace question is asked with.
    function refusal(xhr) {
        try {
            const body = JSON.parse(xhr.responseText);
            if (body && body.message) return body.message;
        } catch (e) { /* a non-JSON body is an infrastructure failure, not the domain speaking */ }
        return 'Vaier could not upload the file.';
    }

    // Paste every held coordinate into the directory in front of you: one Transfer per item. A big file gets a
    // second thought first — the copy streams flat-memory whatever the size, but a multi-GB volume crossing
    // WireGuard is worth confirming. A copy onto its own coordinate is a no-op the backend refuses; we skip it
    // here too, so pasting into a folder you copied from does nothing rather than erroring.
    async function pasteHere(destMachine, destPath) {
        const items = S.clipboard.filter((c) => !(c.machine === destMachine && parentDir(c.path) === destPath && !c.at));
        if (!items.length) { toast('Nothing to paste here — those items already live in this folder.'); return; }

        const heavy = items.filter((c) => !c.directory && c.size > TRANSFER_WARN_BYTES);
        const folders = items.filter((c) => c.directory);
        if (heavy.length || folders.length) {
            const lines = [];
            heavy.forEach((c) => lines.push(c.name + ' — ' + VaierListing.formatSize({ size: c.size }) + ', over the fleet'));
            if (folders.length) lines.push(folders.length + (folders.length === 1 ? ' folder' : ' folders')
                + ' — every file inside is copied');
            const ok = await confirmModal('Copy across the fleet?',
                'These cross WireGuard between machines:\n' + lines.join('\n'), 'Copy');
            if (!ok) return;
        }
        items.forEach((c) => startTransfer(c, destMachine, destPath));
    }

    const parentDir = (p) => { const i = p.lastIndexOf('/'); return i <= 0 ? '/' : p.slice(0, i); };

    // Re-read one directory over SFTP, dropping its cached slot so the read really happens. render() then
    // sees it unread and fetches it (renderDirectory), exactly as a first open would.
    function refreshDir(machine, path) {
        S.dirs.delete(dirKey(machine, path, S.at));
        render();
    }

    // Ask the backend to carry one coordinate into a destination directory. The Transfer comes back with an
    // id; from there its progress arrives over the transfers stream, so nothing here polls.
    async function startTransfer(item, destMachine, destPath) {
        try {
            const res = await fetch('/transfers', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    sourceMachineId: item.machine, sourceMachine: nameOf(item.machine),
                    sourcePath: item.path, at: item.at || null,
                    destMachineId: destMachine, destMachine: nameOf(destMachine), destPath: destPath }),
            });
            if (!res.ok) {
                const err = await res.json().catch(() => null);
                return toast((err && err.message) || ('Could not start the copy of ' + item.name + '.'));
            }
            const t = await res.json();
            S.transfers.set(t.id, t);
            render();
        } catch (e) {
            toast('Could not reach Vaier to start the copy.');
        }
    }

    // The tray: the one app-level surface the shell has (the iframe that trapped every overlay is gone). It
    // holds what is on the Clipboard and every Transfer in flight, docked out of the way until there is
    // something to show. It never sets data-past — a Transfer is always a move in the present.
    function renderClip() {
        const host = $('exClip');
        host.textContent = '';
        const live = Array.from(S.transfers.values());
        const ups = Array.from(S.uploads.values());
        if (!S.clipboard.length && !live.length && !ups.length) { host.classList.remove('is-on'); return; }
        host.classList.add('is-on');

        if (S.clipboard.length) {
            const head = el('div', 'ex-clip-head');
            const t = el('span', 'ex-clip-title');
            t.textContent = 'Clipboard';
            const clear = el('button', 'ex-clip-clear');
            clear.textContent = 'Clear';
            clear.onclick = clipClear;
            head.append(t, clear);
            host.appendChild(head);

            S.clipboard.forEach((c) => {
                const item = el('div', 'ex-clip-item');
                item.innerHTML = svg(c.directory ? 'dir' : 'file', 'ex-ico');
                const co = el('span', 'ex-clip-coord');
                co.textContent = c.machine + ':' + c.path + (c.at ? ' (past)' : '');
                co.title = co.textContent;
                const x = el('button', 'ex-iconbtn');
                x.innerHTML = svg('cross', 'ex-ico');
                x.title = 'Remove';
                x.setAttribute('aria-label', 'Remove ' + c.name + ' from the Clipboard');
                x.onclick = () => clipRemove(clipId(c.machine, c.path, c.at));
                item.append(co, x);
                host.appendChild(item);
            });
        }

        // Newest first, so the one you just started is at the top. Uploads share the tray with Transfers and
        // wear the same row: both are bytes in flight with a destination, and the operator watches them the
        // same way. Only where the progress comes from differs, and that is not something a row should show.
        ups.slice().reverse().forEach((up) => host.appendChild(uploadRow(up)));
        live.slice().reverse().forEach((tr) => host.appendChild(transferRow(tr)));
    }

    // One upload in the tray, in the Transfer row's own clothes. The bar is always determinate: the browser
    // holds the file, so it knows the total from the first byte — there is no indeterminate case to draw.
    function uploadRow(up) {
        const row = el('div', 'ex-xfer is-' + up.state);

        const line = el('div', 'ex-xfer-line');
        line.innerHTML = svg(up.state === 'done' ? 'check' : up.state === 'failed' ? 'warn' : 'upload', 'ex-ico');
        const coord = el('span', 'ex-xfer-coord');
        coord.textContent = up.name + ' → ' + nameOf(up.machine);
        coord.title = up.name + '  →  ' + nameOf(up.machine) + ':' + up.path;
        line.appendChild(coord);
        const x = el('button', 'ex-iconbtn ex-xfer-x');
        x.innerHTML = svg('cross', 'ex-ico');
        x.title = 'Dismiss';
        x.setAttribute('aria-label', 'Dismiss this upload');
        x.onclick = () => { S.uploads.delete(up.id); renderClip(); };
        line.appendChild(x);
        row.appendChild(line);

        if (up.state === 'failed') {
            row.appendChild(note(up.error || 'The upload failed.', true));
        } else {
            const bar = el('div', 'ex-xfer-bar');
            const fill = el('div', 'ex-xfer-fill');
            fill.style.width = (up.total ? Math.min(100, Math.round((up.sent / up.total) * 100)) : 100) + '%';
            bar.appendChild(fill);
            row.appendChild(bar);

            const meta = el('div', 'ex-xfer-meta');
            meta.textContent = up.state === 'done'
                ? 'Uploaded · ' + VaierListing.formatSize({ size: up.total })
                : VaierListing.formatSize({ size: up.sent }) + ' of ' + VaierListing.formatSize({ size: up.total });
            row.appendChild(meta);
        }

        // The same pure-CSS lifetime a finished Transfer gets: a landed upload clears itself, a failure stays
        // until dismissed so its reason is never lost. No JS clock, so nothing here is a timer either.
        if (up.state === 'done') {
            row.classList.add('is-expiring');
            row.addEventListener('animationend', (e) => {
                if (e.animationName === 'ex-xfer-expire') { S.uploads.delete(up.id); renderClip(); }
            });
        }
        return row;
    }

    function transferRow(tr) {
        const row = el('div', 'ex-xfer is-' + (tr.state || 'running').toLowerCase());

        const line = el('div', 'ex-xfer-line');
        const icon = tr.state === 'DONE' ? 'check' : tr.state === 'FAILED' ? 'warn' : 'copy';
        line.innerHTML = svg(icon, 'ex-ico');
        const coord = el('span', 'ex-xfer-coord');
        coord.textContent = shortName(tr.sourcePath) + ' → ' + tr.destMachine;
        coord.title = tr.sourceMachine + ':' + tr.sourcePath + '  →  ' + tr.destMachine + ':' + tr.destPath;
        line.appendChild(coord);
        const x = el('button', 'ex-iconbtn ex-xfer-x');
        x.innerHTML = svg('cross', 'ex-ico');
        x.title = 'Dismiss';
        x.setAttribute('aria-label', 'Dismiss this transfer');
        x.onclick = () => { S.transfers.delete(tr.id); renderClip(); };
        line.appendChild(x);
        row.appendChild(line);

        if (tr.state === 'FAILED') {
            row.appendChild(note(tr.error || 'The copy failed.', true));
        } else {
            const bar = el('div', 'ex-xfer-bar');
            const fill = el('div', 'ex-xfer-fill');
            const pct = tr.totalBytes ? Math.min(100, Math.round((tr.bytesCopied / tr.totalBytes) * 100)) : null;
            fill.style.width = (tr.state === 'DONE' ? 100 : (pct == null ? 40 : pct)) + '%';
            if (pct == null && tr.state !== 'DONE') fill.classList.add('is-indeterminate');
            bar.appendChild(fill);
            row.appendChild(bar);

            const meta = el('div', 'ex-xfer-meta');
            meta.textContent = tr.state === 'DONE'
                ? 'Copied · ' + VaierListing.formatSize({ size: tr.bytesCopied })
                : VaierListing.formatSize({ size: tr.bytesCopied })
                  + (tr.totalBytes ? ' of ' + VaierListing.formatSize({ size: tr.totalBytes }) : '');
            row.appendChild(meta);
        }

        // A finished copy clears itself after a moment, on a pure-CSS lifetime (no JS clock, same discipline as
        // the toast); a failure stays until dismissed so its reason is never lost. The X removes either at once.
        if (tr.state === 'DONE') {
            row.classList.add('is-expiring');
            row.addEventListener('animationend', (e) => {
                if (e.animationName === 'ex-xfer-expire') { S.transfers.delete(tr.id); renderClip(); }
            });
        }
        return row;
    }

    const shortName = (p) => { const i = p.lastIndexOf('/'); return i < 0 ? p : p.slice(i + 1); };

    // The transfers stream — the byte-moving half. The backend runs the copy and pushes progress; the browser
    // listens and repaints the tray, never polling. A settled transfer stays in the tray as its own record
    // (done or failed) until the operator has seen it; it is not swept from under them.
    function watchTransfers() {
        const events = new EventSource('/transfers/events');
        events.addEventListener('transfer-progress', (e) => {
            const d = JSON.parse(e.data);
            const tr = S.transfers.get(d.id);
            if (tr) { tr.bytesCopied = d.bytesCopied; tr.totalBytes = d.totalBytes; renderClip(); }
        });
        events.addEventListener('transfer-settled', (e) => {
            const d = JSON.parse(e.data);
            const tr = S.transfers.get(d.id);
            if (!tr) return loadTransfers();   // settled one we never saw start (another tab, a reconnect)
            tr.state = d.state;
            tr.error = d.error;
            // A landed copy changed a directory. Drop its cached slot so the new file shows — and repaint the
            // whole shell, not just the tray, in case that directory is the one on screen (then it re-reads).
            if (d.state === 'DONE') { S.dirs.delete(dirKey(tr.destMachine, tr.destPath, '')); render(); }
            else renderClip();
        });
    }

    async function loadTransfers() {
        try {
            const res = await fetch('/transfers', { cache: 'no-store' });
            if (!res.ok) return;
            (await res.json()).forEach((t) => S.transfers.set(t.id, t));
            renderClip();
        } catch (e) { /* no transfers is not a failure */ }
    }

    // --- app-level chrome the shell finally has: a toast, and a typed confirm ----------------------------

    // A brief, self-dismissing line for the things that used to have nowhere to go — a copy that could not
    // start, a paste with nothing to do. Errors state what happened; they do not apologise.
    function toast(message) {
        const host = $('exToast');
        const t = el('div', 'ex-toast-item');
        const msg = el('span', 'ex-toast-msg');
        msg.textContent = message;
        const x = el('button', 'ex-toast-x');
        x.innerHTML = svg('cross', 'ex-ico');
        x.title = 'Dismiss';
        x.setAttribute('aria-label', 'Dismiss');
        x.onclick = () => t.remove();
        t.append(msg, x);
        // Two ways out, no JS clock either way: the X closes it now, and a pure-CSS lifetime closes it on its
        // own (animationend removes it — hovering pauses that countdown so a message being read does not
        // vanish). The shell keeps no timer of its own; the backend keeps time and the stream delivers it.
        t.addEventListener('animationend', () => t.remove());
        host.appendChild(t);
    }

    // A confirmation the operator has to mean — the size warning before a heavy copy. Built on demand over its
    // own scrim (the shell has an overlay layer now), resolves true on confirm and false on cancel or Escape.
    function confirmModal(title, bodyText, confirmLabel) {
        return new Promise((resolve) => {
            const scrim = el('div', 'ex-scrim is-on');
            const dialog = el('div', 'ex-dialog');
            const h = el('div', 'ex-dialog-title');
            h.textContent = title;
            const b = el('div', 'ex-dialog-body');
            b.textContent = bodyText;   // never markup: it carries machine names and paths, the operator's own text
            const actions = el('div', 'ex-dialog-actions');
            const cancel = el('button', 'ex-btn');
            cancel.textContent = 'Cancel';
            const ok = el('button', 'ex-btn is-accent');
            ok.textContent = confirmLabel || 'Confirm';
            actions.append(cancel, ok);
            dialog.append(h, b, actions);
            scrim.appendChild(dialog);
            document.body.appendChild(scrim);

            const close = (result) => { scrim.remove(); document.removeEventListener('keydown', onKey); resolve(result); };
            const onKey = (e) => { if (e.key === 'Escape') close(false); };
            scrim.onclick = (e) => { if (e.target === scrim) close(false); };
            cancel.onclick = () => close(false);
            ok.onclick = () => close(true);
            document.addEventListener('keydown', onKey);
            ok.focus();
        });
    }

    // confirmModal with a lock on it, for the verbs whose damage is silent. The operator has to write the
    // name out, so the dialog cannot be cleared by the reflex that clears every other one.
    function confirmByTypingName(title, bodyText, name, confirmLabel) {
        return new Promise((resolve) => {
            const scrim = el('div', 'ex-scrim is-on');
            const dialog = el('div', 'ex-dialog');
            const h = el('div', 'ex-dialog-title');
            h.textContent = title;
            const b = el('div', 'ex-dialog-body');
            b.textContent = bodyText;   // never markup: it carries the operator's own names and paths
            const form = el('div', 'ex-form');
            const f = el('div', 'ex-field');
            const l = el('label');
            l.textContent = 'Type ' + name + ' to confirm';
            const input = el('input', 'ex-input');
            input.type = 'text'; input.spellcheck = false; input.autocomplete = 'off';
            f.append(l, input);
            form.appendChild(f);
            const actions = el('div', 'ex-dialog-actions');
            const cancel = el('button', 'ex-btn');
            cancel.textContent = 'Cancel';
            const ok = el('button', 'ex-btn is-danger');
            ok.textContent = confirmLabel || 'Delete';
            ok.disabled = true;
            actions.append(cancel, ok);
            dialog.append(h, b, form, actions);
            scrim.appendChild(dialog);
            document.body.appendChild(scrim);

            const close = (result) => { scrim.remove(); document.removeEventListener('keydown', onKey); resolve(result); };
            const onKey = (e) => { if (e.key === 'Escape') close(false); };
            input.oninput = () => { ok.disabled = input.value.trim() !== name; };
            input.onkeydown = (e) => { if (e.key === 'Enter' && !ok.disabled) close(true); };
            scrim.onclick = (e) => { if (e.target === scrim) close(false); };
            cancel.onclick = () => close(false);
            ok.onclick = () => close(true);
            document.addEventListener('keydown', onKey);
            input.focus();
        });
    }

    // --- a refused host key, and the one action that clears it (#345) -----------------------------------
    //
    // Vaier has always NAMED the remedy for a changed host key — "clear its pinned key and reconnect" — and
    // then offered no way to perform it. The endpoint existed; nothing in the UI called it, so the only route
    // out was an API client. This is that route, put where the refusal is actually met.
    //
    // It appears only in the error state a real mismatch produces, never as routine machine maintenance. A
    // pin that can be cleared from a machine's page on any ordinary day is a pin that gets cleared out of
    // habit, which is exactly the failure the pin exists to prevent.

    /** The two fingerprints out of an ApiError detail ("pinned=…;presented=…"), or null if it says neither. */
    function refusedFingerprints(detail) {
        if (!detail) return null;
        const pinned = /(?:^|;)pinned=([^;]*)/.exec(detail);
        const presented = /(?:^|;)presented=([^;]*)/.exec(detail);
        if (!pinned && !presented) return null;
        return { pinned: pinned ? pinned[1] : '', presented: presented ? presented[1] : '' };
    }

    // A failed read, painted. The server's own sentence verbatim, as it has always been — plus, for the one
    // failure Vaier can actually do something about, the action that does it.
    function failureNote(host, machineId, held) {
        host.appendChild(note(held.error, true));
        if (held.errorCode !== 'HOST_KEY_MISMATCH') return;
        const row = el('div', 'ex-lactions is-static');
        row.appendChild(selVerb('shield', 'Clear pinned key', 'ex-btn',
            () => clearHostKeyDialog(machineId, refusedFingerprints(held.errorDetail))));
        host.appendChild(row);
    }

    // The confirmation, and the confirmation is the point. A changed host key has two causes: a machine you
    // rebuilt, and a machine somebody is impersonating — and the pin exists for the second one. So this is
    // not "are you sure". It shows what Vaier knows (both fingerprints, stacked to scan) and asks the operator
    // to assert the one thing only they can know: that they changed this machine. Typing its name is that
    // assertion; the mechanics are confirmTyped's, so the gate behaves exactly like every other one here.
    function clearHostKeyDialog(machineId, prints) {
        const machineName = nameOf(machineId);
        const scrim = el('div', 'ex-scrim is-on');
        const dialog = el('div', 'ex-dialog');
        const h = el('div', 'ex-dialog-title');
        h.textContent = 'Clear the pinned key for ' + machineName + '?';
        const b = el('div', 'ex-dialog-body');
        b.textContent = machineName + ' is presenting a different SSH host key than the one Vaier pinned, so '
            + 'Vaier is refusing to connect.\n\nEither you rebuilt, reinstalled or changed this machine\u2019s '
            + 'SSH server — or something is impersonating it. The pin exists for the second case.';
        dialog.append(h, b);

        // Both fingerprints, so the assertion below is informed rather than a shrug. Monospace and stacked
        // full-width (see .ex-dialog .ex-kv): these are read character by character or not at all.
        if (prints) {
            dialog.appendChild(kv([['Pinned', coord(prints.pinned)], ['Now offered', coord(prints.presented)]]));
        }

        const ask = el('div', 'ex-dialog-body');
        ask.textContent = 'Type ' + machineName + ' to confirm that you changed this machine. Vaier pins the '
            + 'new key on the next connect, so it is never left unpinned.';
        const input = el('input', 'ex-dialog-input');
        input.type = 'text';
        input.placeholder = machineName;
        input.autocomplete = 'off';
        input.spellcheck = false;

        const actions = el('div', 'ex-dialog-actions');
        const cancel = el('button', 'ex-btn');
        cancel.textContent = 'Cancel';
        const ok = el('button', 'ex-btn is-danger');
        ok.textContent = 'Clear pinned key';
        ok.disabled = true;
        actions.append(cancel, ok);
        dialog.append(ask, input, actions);
        scrim.appendChild(dialog);
        document.body.appendChild(scrim);

        const close = () => { scrim.remove(); document.removeEventListener('keydown', onKey); };
        const armed = () => input.value === machineName;
        const onKey = (e) => { if (e.key === 'Escape') close(); };
        input.oninput = () => { ok.disabled = !armed(); };
        input.onkeydown = (e) => { if (e.key === 'Enter' && armed()) clear(); };
        scrim.onclick = (e) => { if (e.target === scrim) close(); };
        cancel.onclick = close;
        ok.onclick = () => { if (armed()) clear(); };
        document.addEventListener('keydown', onKey);
        input.focus();

        async function clear() {
            ok.disabled = true;
            try {
                const res = await fetch('/machines/' + encodeURIComponent(machineId) + '/host-key',
                    { method: 'DELETE' });
                if (!res.ok && res.status !== 204) {
                    toast('Could not clear the pinned key for ' + machineName + '.');
                    ok.disabled = false;
                    return;
                }
                toast('Pinned key cleared for ' + machineName + '. The next connect pins its new key.');
                close();
                // The refusal was a failed read cached against this machine; drop it so the retry is real.
                S.disks.delete(machineId);
                Array.from(S.dirs.entries()).forEach(([k, entry]) => {
                    if (entry.machine === machineId && entry.state === 'error') S.dirs.delete(k);
                });
                await loadFleet();
                render();
            } catch (e) {
                toast('Could not reach Vaier to clear the pinned key.');
                ok.disabled = false;
            }
        }
    }

    // The destructive gate: a confirm whose button stays disabled until the operator types {@code required}
    // (the machine's name) exactly. Typing the name is the deliberate act — you cannot fat-finger a delete.
    function confirmTyped(title, bodyText, required, confirmLabel) {
        return new Promise((resolve) => {
            const scrim = el('div', 'ex-scrim is-on');
            const dialog = el('div', 'ex-dialog');
            const h = el('div', 'ex-dialog-title');
            h.textContent = title;
            const b = el('div', 'ex-dialog-body');
            b.textContent = bodyText;
            const input = el('input', 'ex-dialog-input');
            input.type = 'text';
            input.placeholder = required;
            input.autocomplete = 'off';
            input.spellcheck = false;
            const actions = el('div', 'ex-dialog-actions');
            const cancel = el('button', 'ex-btn');
            cancel.textContent = 'Cancel';
            const ok = el('button', 'ex-btn is-danger');
            ok.textContent = confirmLabel || 'Confirm';
            ok.disabled = true;
            actions.append(cancel, ok);
            dialog.append(h, b, input, actions);
            scrim.appendChild(dialog);
            document.body.appendChild(scrim);

            const close = (r) => { scrim.remove(); document.removeEventListener('keydown', onKey); resolve(r); };
            const armed = () => input.value === required;
            const onKey = (e) => { if (e.key === 'Escape') close(false); };
            input.oninput = () => { ok.disabled = !armed(); };
            input.onkeydown = (e) => { if (e.key === 'Enter' && armed()) close(true); };
            scrim.onclick = (e) => { if (e.target === scrim) close(false); };
            cancel.onclick = () => close(false);
            ok.onclick = () => { if (armed()) close(true); };
            document.addEventListener('keydown', onKey);
            input.focus();
        });
    }

    // --- ⌘K: one namespace, one search ------------------------------------------------------------------

    // Directories are entries now, so ⌘K must find them — and it finds them by walking childrenOf, which only
    // ever reads the cache. That is the whole trick: the palette can see every directory the operator has
    // already opened, and is structurally incapable of touching one they have not. A palette that crawled the
    // fleet over SFTP to build an index would hang the moment it met a sleeping machine.
    // Every entry twice over: the path Vaier addresses it by, and the path a person reads it as. They differ at
    // exactly one segment — a machine is addressed by identity and read by name — and that one segment was the
    // whole bug. The index used to keep only the address, so the palette matched (and displayed)
    // /fleet/7a6d0e35-25d9-420c-b7bc-1815ce7e0dc1/files, and typing a machine's name found nothing at all.
    // Nobody noticed while a rail was there to carry you; ⌘K and the crumb bar are the way across now.
    function index() {
        const out = [];
        (function walk(path, words) {
            out.push({ path: path, kind: kindOf(path), label: '/' + words.join('/') });
            childrenOf(path).forEach((kid) =>
                walk(path.concat([kid.name]), words.concat([kid.label || kid.name])));
        })(['fleet'], ['fleet']);
        // Vaier's own entries are not of the fleet, so the walk above cannot reach them — and they are now
        // behind a menu rather than standing in the fleet, which makes finding them here matter more, not less.
        GLOBALS.filter(offered).forEach((g) => out.push({ path: [g.name], kind: kindOf([g.name]), label: '/' + g.label }));
        return out;
    }

    function matches(query) {
        const needle = query.trim().toLowerCase();
        // Everything except the fleet root itself — "/fleet" is where the crumb bar's first segment already
        // goes, and an entry for the place you can always reach in one click is a wasted row.
        const all = index().filter((e) => e.label !== '/fleet');
        if (!needle) return all.filter((e) => e.kind === 'machine').slice(0, 9);
        return all.filter((e) => e.label.toLowerCase().includes(needle)).slice(0, 40);
    }

    function paintPalette(query) {
        const list = $('exPalList');
        list.textContent = '';
        const found = matches(query);
        if (!found.length) {
            const empty = document.createElement('div');
            empty.className = 'ex-pal-empty';
            empty.textContent = 'Nothing in the fleet matches that.';
            list.appendChild(empty);
            return;
        }
        found.forEach((entry, i) => {
            const item = document.createElement('button');
            item.className = 'ex-pal-item' + (i === S.palSel ? ' is-on' : '');
            // The last segment, exactly as every entry surface does it — a machine's icon reads off its identity and
            // a global's off its own name, and both of those are the tail of the path. Reading path[1] instead
            // gave every Vaier entry the fallback file glyph.
            item.innerHTML = entryIco(entry.kind, entry.path[entry.path.length - 1]);

            const pth = document.createElement('span');
            pth.className = 'ex-pth';
            highlight(pth, entry.label, query.trim());
            item.appendChild(pth);

            const kind = document.createElement('span');
            kind.className = 'ex-kind';
            kind.textContent = entry.kind;
            item.appendChild(kind);

            item.onclick = () => jump(entry.path);
            list.appendChild(item);
        });
    }

    // The match is lit inside the path, and the path is never markup — it carries a machine's name, which is
    // the operator's own text.
    function highlight(host, text, needle) {
        if (!needle) return host.appendChild(document.createTextNode(text));
        const at = text.toLowerCase().indexOf(needle.toLowerCase());
        if (at < 0) return host.appendChild(document.createTextNode(text));
        const mark = document.createElement('mark');
        mark.textContent = text.slice(at, at + needle.length);
        host.appendChild(document.createTextNode(text.slice(0, at)));
        host.appendChild(mark);
        host.appendChild(document.createTextNode(text.slice(at + needle.length)));
    }

    function openPalette() {
        $('exScrim').classList.add('is-on');
        $('exPalInput').value = '';
        S.palSel = 0;
        paintPalette('');
        $('exPalInput').focus();
    }

    const closePalette = () => $('exScrim').classList.remove('is-on');

    function jump(path) {
        closePalette();
        go(path);
    }

    // --- the address ------------------------------------------------------------------------------------
    //
    // Where you are IS the URL. Reload, Back, Forward, bookmark and open-in-new-tab all work because the
    // address is the only place the location lives: S.path and S.at are read back out of it and never set
    // behind its back. Before this the whole location was a variable, so every reload dumped you at the
    // fleet root and there was no way to hand anyone a link to a folder.
    //
    // A hash, deliberately, and not the History API: the shell is a static file, so a real path like
    // /explorer/fleet/<id>/files/home would 404 unless Vaier grew a catch-all forward — new backend surface
    // inside the forward-auth chain, bought for nothing the hash does not already give.
    //
    // Segments are percent-encoded, so a file called "report Q1?.pdf" survives the round trip: the only bare
    // "/" is a separator and the only bare "?" opens the query.
    const ROUTE_DEFAULT = ['fleet'];

    function hrefFor(path, at) {
        return '#/' + path.map(encodeURIComponent).join('/')
            + (at ? '?at=' + encodeURIComponent(at) : '');
    }

    function parseHash() {
        const raw = location.hash.replace(/^#\/?/, '');
        const cut = raw.indexOf('?');
        const body = cut < 0 ? raw : raw.slice(0, cut);
        const at = cut < 0 ? null : new URLSearchParams(raw.slice(cut + 1)).get('at');
        const path = body.split('/').filter((s) => s !== '').map(decodeURIComponent);
        return { path: path.length ? path : ROUTE_DEFAULT.slice(), at: at || null };
    }

    // The address this shell last applied, normalised through hrefFor. Compared against rather than
    // location.hash itself, because the browser is free to re-encode what it was handed — and a raw string
    // comparison against a re-encoded hash would make every Back look like a new place.
    let _route = null;

    // --- navigation --------------------------------------------------------------------------------------

    // Selecting an entry is the only thing that opens a shell. The dock's open is deliberately not
    // idempotent — asking twice means you want two shells, and an operator does — so it must be reached by an
    // explicit selection and nowhere else. Hung off the Inspector's renderer instead, every repaint (a
    // machine coming online, say) would quietly start another tmux session on the host that nobody asked for.
    function go(path) {
        // Time is a coordinate on a machine's files, and only there. Stepping off that context — onto a
        // different machine, or an entry that has no past (a container, a shell, the disk) — returns you to
        // the present. The past has no meaning there, and carrying the amber light onto a thing that has none
        // would be a claim about it. Descending deeper into the same files keeps the archive you are in.
        const stillInPast = S.at && path[1] === S.path[1]
            && (kindOf(path) === 'files' || kindOf(path) === 'dir');
        navigate(path, stillInPast ? S.at : null);
    }

    function navigate(path, at) {
        const href = hrefFor(path, at);
        // The same address again is not a move — it is a card for the entry you
        // are already standing on. Repaint, and leave the history alone: a Back that walks through the same
        // folder five times is a Back that is broken.
        if (href === _route) return render();
        _route = href;
        location.hash = href;
        // Applied here rather than waiting for hashchange, which fires on the next turn — a click should not
        // paint a frame late. The listener below skips what this already applied.
        applyRoute(path, at);
    }

    // Back, Forward, a pasted link, an edited address. Whatever the browser hands us is the truth.
    window.addEventListener('hashchange', () => {
        const r = parseHash();
        const href = hrefFor(r.path, r.at);
        if (href === _route) return;   // our own write, already painted
        _route = href;
        applyRoute(r.path, r.at);
    });

    function applyRoute(path, at) {
        // The selection deliberately survives navigation (#323): each ticked item carries its own coordinate
        // (machine, path, archive), so you can gather files from different folders — and different machines —
        // before downloading, copying or deleting them together. It is cleared only by acting on it or by the
        // bar's own Clear, never by moving around.
        // "Checked just now" is only true just now. It is the receipt for an action, not a fact about the
        // fleet, so it does not follow the operator around — left on screen it would quietly become a lie,
        // which is the one thing this feature cannot afford. The verdicts it settled are on the rows already.
        if (key(path) !== key(S.path)) _updateCheck = null;
        const timeChanged = (at || null) !== (S.at || null);
        S.path = path;
        S.at = at || null;
        // Scrubbing the rail — including arriving on a link that already names an archive. Setting the time
        // re-reads exactly the directory you are standing on and every directory above it at that archive, so
        // the pane and every crumb above it relight together instead of flashing empty. A bounded walk down
        // one path, never a crawl across the fleet.
        if (timeChanged && S.path.length > 1) readPathChain(S.path[1]);
        render();
    }

    const toArchive = (machine, id) => navigate(S.path, id);
    const toPresent = () => navigate(S.path, null);

    // Read the files subtree from its root down to where you are standing, at the current time. Only the
    // directories that are still unread in this light are fetched; the rest are already cached. remotePath
    // resolves every depth in one pass because the past's root is known to be "/" without asking (see
    // remotePath), so this does not have to wait for each parent to report a root before reading its child.
    function readPathChain(machine) {
        const inFiles = S.path[1] === machine
            && (kindOf(S.path) === 'files' || kindOf(S.path) === 'dir');
        const full = inFiles ? S.path : ['fleet', machine, 'files'];
        for (let depth = 3; depth <= full.length; depth++) {
            const sub = full.slice(0, depth);
            if (dirStateOf(sub) === 'unread') readDir(machine, remotePath(sub), S.at);
        }
    }

    function render() {
        // The past is a different light, not a badge: one attribute on the shell, and every surface crossfades
        // to the amber past palette (explorer-shell.css). Only the present is ever cold-lit.
        document.querySelector('.ex-app').setAttribute('data-past', S.at ? '1' : '0');
        // On a phone the selection bar is fixed to the foot of the screen (it is the only verb surface there,
        // so it has to be thumb-reachable). A fixed bar is out of the flow and would sit over the last row of
        // the very listing you are picking from, so the shell says when one exists and the pane keeps room
        // under itself. Nothing on a wide screen reads this class.
        app.classList.toggle('has-sel', S.sel.length > 0);
        renderCrumbs();
        renderPane();
        renderClip();
    }

    // --- the fleet, and the stream that keeps it honest ---------------------------------------------------

    // The address carries identities, so the listing reader and the terminal dock are handed one directly and
    // have no name to convert on the way. What they need from this scope is the other direction: what to
    // CALL a machine they are already addressing correctly.
    window.vaierMachineName = nameOf;

    // The phones waiting to be let in. Each carries the instant it stops waiting, so a paint can drop one
    // that timed out without asking the server again.
    async function loadEnrolmentRequests() {
        try {
            const res = await fetch('/vpn/enrolments');
            const rows = res.ok ? await res.json() : [];
            S.enrolmentRequests = rows.map((r) => Object.assign({}, r,
                { expiresAt: Date.now() + (r.expiresInSeconds || 0) * 1000 }));
        } catch (e) {
            S.enrolmentRequests = [];
        }
    }
    const liveEnrolmentRequests = () => S.enrolmentRequests.filter((r) => r.expiresAt > Date.now());

    async function loadFleet() {
        try {
            const res = await fetch('/machines');
            S.machines = res.ok ? await res.json() : [];
        } catch (e) {
            S.machines = [];
        }
        try {
            // The peers carry what /machines cannot: the tunnel address and the live connection state. The
            // stats that arrive on the stream are keyed by the peer's id, so keep the id -> machine map too.
            const res = await fetch('/vpn/peers');
            const peers = res.ok ? await res.json() : [];
            // Keyed by machine identity, which is what a row from /machines carries. It used to be keyed by
            // display name, and that was the live bug: one character of disagreement between the two lists
            // made a peer look like a LAN server, and a delete went to /lan-servers/<name> — 404 in silence,
            // no log line, no error, the confirm dialog just closing while the peer stayed where it was.
            // A peer with no stored config has no identity (it is in no registry); it is indexed by its
            // WireGuard id alone, and simply does not join.
            S.peers = new Map();
            S.peersById = new Map();
            peers.forEach((p) => {
                if (p.machineId) S.peers.set(p.machineId, p);
                S.peersById.set(p.id, p);
            });
        } catch (e) { /* a fleet with no peers is a fleet, not a failure */ }

        await loadLanServers();

        try {
            const res = await fetch('/vpn/peers/server-location');
            S.serverLocation = res.ok ? await res.json() : null;
        } catch (e) { S.serverLocation = null; }

        // The fleet just changed shape. A machine that left must not leave its directories behind in the
        // cache, and a read still in flight for it must not be able to put them back (readDir checks that
        // its slot is still its own).
        pruneDirs();
    }

    // The other half of the fleet's liveness. /lan-servers hands us `status` — a MachineStatus the domain has
    // already decided — so the browser never recombines reachability with the Docker scrape.
    async function loadLanServers() {
        try {
            const res = await fetch('/lan-servers');
            const servers = res.ok ? await res.json() : [];
            S.lan = new Map(servers.map((s) => [s.machineId, s]));
        } catch (e) { /* a fleet with no LAN servers is a fleet, not a failure */ }
    }

    // Which ONE machine THIS browser's claim cookie is bound to — decided server-side, so a cleared cookie,
    // a revoked claim or a claim superseded from another browser all just come back null. Read once at boot
    // and after claimDevice/forgetPosition settle; never derived from document.cookie, which cannot read it.
    async function loadMyDevice() {
        try {
            const res = await fetch('/vpn/peers/my-device');
            S.myDeviceMachineId = res.ok ? (await res.json()).machineId : null;
        } catch (e) { S.myDeviceMachineId = null; }
    }

    // The discovered-machines snapshot — read when the fleet is looked at, and again whenever a scan settles
    // (the backend pushes lan-scan-updated on the vpn-peers stream, so this never polls). An unreadable
    // response (offline, a fleet with no scannable LAN yet) just leaves the section empty. Guarded so a
    // re-render can't re-fetch it.
    let _lanScanLoading = false;
    let _showIgnoredLan = false;   // whether the dismissed finds are revealed under the "Show ignored" toggle
    let _showIgnoredServices = false;   // the same, for a machine's ignored publishable ports
    // Set by the Add-a-machine modal's discover screen while it is open; loadLanScan calls it after a scan
    // settles so the candidate list repaints over the same stream the shell already holds — never a timer.
    let _lanScanModalRefresh = null;
    async function loadLanScan() {
        if (_lanScanLoading) return;
        _lanScanLoading = true;
        try {
            const res = await fetch('/lan-scan', { cache: 'no-store' });
            S.lanScan = res.ok ? await res.json() : { status: 'IDLE', machines: [] };
        } catch (e) { S.lanScan = { status: 'IDLE', machines: [] }; }
        _lanScanLoading = false;
        render();
        if (_lanScanModalRefresh) _lanScanModalRefresh();
    }

    // Kick off a scan. With an anchor it targets that one LAN (the picker's default — small and fast); with
    // none it sweeps every LAN (the Machines page's fleet-wide Rescan). It runs on the backend and settles
    // onto lan-scan-updated (no polling); here we only mark it running so the button says so, and wait to be
    // told it finished.
    async function scanLan(anchor) {
        try {
            const url = anchor ? '/lan-scan?anchor=' + encodeURIComponent(anchor) : '/lan-scan';
            const res = await fetch(url, { method: 'POST' });
            if (res.status === 404) { toast('Vaier no longer knows that network.'); return; }
            if (!res.ok && res.status !== 202) { toast('Vaier could not start the scan.'); return; }
            toast(anchor ? 'Scanning that network…' : 'Scanning the LAN…');
            if (S.lanScan) { S.lanScan.status = 'SCANNING'; render(); }
        } catch (e) { toast('Vaier could not start the scan.'); }
    }

    // The LANs an operator can pick to scan — each relay's LAN plus the Vaier-server LAN, resolved server-side
    // so the browser never reconstructs the server's own CIDR. Read once when the picker opens.
    let _lanScanLansLoading = false;
    async function loadLanScanLans() {
        if (_lanScanLansLoading) return;
        _lanScanLansLoading = true;
        try {
            const res = await fetch('/lan-scan/lans', { cache: 'no-store' });
            S.lanScanLans = res.ok ? await res.json() : [];
        } catch (e) { S.lanScanLans = []; }
        _lanScanLansLoading = false;
        if (_lanScanModalRefresh) _lanScanModalRefresh();
    }

    // Dismiss a discovered thing so it stops cluttering the list. It survives the next scan (the ignore is
    // keyed on the relay + address, persisted server-side) and can be brought back under "Show ignored".
    function ignoreDiscovered(d) {
        saveJson('/lan-scan/ignore', 'POST', { key: d.ignoreKey },
            'Ignored ' + registerName(d) + '.', () => loadLanScan(), 'Could not ignore that.');
    }

    function unignoreDiscovered(d) {
        saveJson('/lan-scan/unignore', 'POST', { key: d.ignoreKey },
            registerName(d) + ' is back in the list.', () => loadLanScan(), 'Could not unignore that.');
    }

    // --- discovered-machine presentation — lead with WHAT it is, not its reverse-DNS name -----------------
    // The scan runs from the EC2 box, so every RFC1918 IP reverse-resolves to an AWS placeholder
    // (ip-192-168-3-1.eu-central-1.compute.internal) — noise, never a real name. Suppress those; keep a
    // genuine hostname (rare on a home LAN) if one shows up.
    function realHostname(d) {
        const h = (d.hostname || '').trim();
        if (!h) return null;
        if (/\.internal$/i.test(h)) return null;           // *.compute.internal / *.internal — AWS placeholder
        if (/^ip-[\d-]+\./i.test(h)) return null;          // ip-192-168-3-1.* — the IP restated as a name
        if (h === d.ipAddress) return null;
        return h;
    }

    const CATEGORY_LABEL = {
        SERVER: 'Server', NAS: 'NAS', PRINTER: 'Printer', ROUTER: 'Router', GATEWAY: 'Gateway',
        CAMERA: 'Camera', MEDIA: 'Media device', IOT: 'Smart device', PHONE: 'Phone',
        LAPTOP: 'Laptop', DESKTOP: 'Desktop', GENERIC: 'Unknown device',
    };

    // The one-line human name for a discovered thing: its guessed function when the ports say something
    // ("Docker host", "Web service"), otherwise the device category Vaier inferred. Never the raw hostname.
    function discoveredLabel(d) {
        switch (d.role) {
            case 'DOCKER_HOST': return 'Docker host';
            case 'WEB_UI': return 'Web service';
            case 'PRINTER': return 'Printer';
            case 'SSH_HOST': return 'Server';
            default: return CATEGORY_LABEL[d.deviceCategory] || 'Unknown device';
        }
    }

    // The name a registration lands under — a real hostname wins; otherwise the guessed kind, made unique by
    // the address's last octet so two "Web service" finds don't collide.
    function registerName(d) {
        const host = realHostname(d);
        if (host) return host;
        const octet = (d.ipAddress || '').split('.').pop();
        return discoveredLabel(d) + (octet ? ' ' + octet : '');
    }

    // A plain-language read of what the open ports mean, or null when they say nothing worth a word.
    function portHint(ports) {
        if (!ports || !ports.length) return null;
        const p = new Set(ports);
        if (p.has(2375) || p.has(2376)) return 'runs Docker';
        if (p.has(80) || p.has(443) || p.has(8080) || p.has(8443) || p.has(5000)) return 'serves a web page';
        if (p.has(9100) || p.has(631) || p.has(515)) return 'accepts print jobs';
        if (p.has(22)) return 'accepts SSH';
        return null;
    }

    // Resolve a scan's relay anchor (a WireGuard identity like "apalveien5") back to the machine's display
    // name ("Apalveien 5") when we know it, so the readout reads the way the shell does.
    // A scanned host is tagged with the anchor it was reached through: a relay peer's WireGuard id, or the
    // literal server-LAN name. The peer id is stable across a rename, so it resolves to the machine directly
    // — this used to fuzzy-match the anchor against every machine's NAME, which found the wrong machine the
    // moment two of them normalised to the same string.
    const relayMachine = (anchor) => {
        const peer = anchor ? S.peersById.get(anchor) : null;
        return peer ? machineById(peer.machineId) : null;
    };
    function relayName(anchor) {
        if (!anchor) return null;
        const m = relayMachine(anchor);
        return m ? m.name : anchor;
    }

    // Sort the useful finds to the top: a server/Docker/web host is worth registering; a printer or an unknown
    // appliance can wait at the bottom. Ties fall back to numeric IP order for stability.
    const DISCOVERED_RANK = { DOCKER_HOST: 0, WEB_UI: 1, SSH_HOST: 2, PRINTER: 4, UNKNOWN: 5 };
    const discoveredRank = (d) => (DISCOVERED_RANK[d.role] === undefined ? 3 : DISCOVERED_RANK[d.role]);
    const ipOrder = (ip) => (ip || '').split('.').map((n) => ('00' + n).slice(-3)).join('');

    // One discovered-machine row: the device icon, its human name (a real hostname if any, else the guessed
    // kind), a plain-language line (IP · relay · what the ports mean), and the actions handed in.
    function discoveredRow(d, buttons) {
        const row = el('div', 'ex-disc-row');
        const ic = el('span', 'ex-disc-icon');
        ic.innerHTML = svg(iconByCategory(d.deviceCategory), 'ex-disc-svg');   // trusted: key is an ICON name
        const info = el('div', 'ex-disc-info');
        const host = realHostname(d);
        const line = el('div', 'ex-disc-line');
        const nm = el('span', 'ex-disc-name'); nm.textContent = host || discoveredLabel(d);
        line.appendChild(nm);
        if (host) {                                    // a real name leads; the guess becomes a chip
            const chip = el('span', 'ex-disc-kind'); chip.textContent = discoveredLabel(d);
            line.appendChild(chip);
        }
        const meta = el('span', 'ex-disc-meta');
        const bits = [d.ipAddress];
        const relay = relayName(d.relayAnchor);
        if (relay) bits.push('via ' + relay);
        const hint = portHint(d.openPorts);
        if (hint) bits.push(hint);
        meta.textContent = bits.join(' · ');
        info.append(line, meta);
        const acts = el('div', 'ex-lactions is-static');
        buttons.forEach((b) => acts.appendChild(b));
        row.append(ic, info, acts);
        return row;
    }

    function watchFleet() {
        // A phone asking to join is pushed here the moment it asks; so is its answer, from whichever
        // signed-in browser gave it. The list is re-read on each edge, never on a timer.
        const asks = new EventSource('/vpn/enrolments/events');
        ['requested', 'approved', 'refused'].forEach((name) => {
            asks.addEventListener(name, () => loadEnrolmentRequests().then(render));
        });

        const events = new EventSource('/vpn/peers/events');
        // SSE does not replay events missed while the stream was down (an idle tab, a network blip, a Vaier
        // redeploy). EventSource reconnects on its own, but a dot flip or a peer up/down that fired during the
        // gap would sit stale until a manual refresh. So on every reconnect (onopen after the first), re-sync
        // the state those events carry — peer liveness and LAN status — and repaint. Not polling: this fires
        // only on a reconnect edge, not on a timer.
        let opened = false;
        events.onopen = () => {
            if (opened) {
                // A settled update may have fired into the gap, and there is no replay. Stop waiting on the
                // ones we can no longer be told about: a button stuck on "Updating…" until the page is
                // reloaded is a worse lie than offering it again, and the containers we re-read below are the
                // honest answer about what is actually running.
                _updating.clear();
                Promise.all([loadFleet(), loadLanServers(), loadDiskStandings(),
                    loadClaudeStandings(), loadContainers()])
                    .then(render);
            }
            opened = true;
        };
        // A peer was added, renamed or removed — the fleet's own shape changed.
        events.addEventListener('peers-updated', () => loadFleet().then(render));
        // Liveness. The backend polls WireGuard and pushes what it sees; the browser only ever listens, and
        // repaints the dots where they stand rather than re-rendering the pane under the operator.
        events.addEventListener('peers-stats', (e) => {
            try {
                JSON.parse(e.data).forEach((stat) => {
                    const peer = S.peersById.get(stat.name);
                    if (peer) {
                        peer.connected = stat.connected;
                        peer.latestHandshake = stat.latestHandshake;
                    }
                });
                paintDots();
            } catch (err) {
                console.error('Failed to apply peers-stats update:', err);
            }
        });
        // A LAN server's reachability or Docker scrape changed. Both LanServerReachabilityService and
        // LanServerScrapeService publish this on the `vpn-peers` topic — the stream we are already holding
        // open — so LAN liveness costs no second connection, no new endpoint and no timer. Re-read the
        // statuses and repaint the dots where they stand, without re-rendering the pane under the operator.
        events.addEventListener('lan-servers-updated', () => loadLanServers().then(paintDots));
        // A LAN scan finished on the backend — re-read the discovered snapshot (it renders when it lands).
        events.addEventListener('lan-scan-updated', () => loadLanScan());
        // RemoteDiskWatcher's existing 5-minute sweep found (or stopped finding) an SSH server on a machine —
        // published only on the crossing, never every sweep. sshServerPresence rides on /machines, so a full
        // reload picks it up; re-rendering greys or lifts the SSH-access checkbox, Open shell, and the
        // Files/Disk entries without the operator needing to reload the page.
        events.addEventListener('ssh-server-presence-changed', () => loadFleet().then(render));
        // A machine's disk standing moved on that same 5-minute sweep — published only on the change, with an
        // empty body, so this re-reads the one memory-backed endpoint and repaints the marks. A machine that
        // stopped being readable is not corrected here by guesswork: the sweep says so, or it says nothing.
        events.addEventListener('disk-standing-changed', () => loadDiskStandings().then(render));
        // And where a machine stands on Claude, learned on that same trip and published the same way: only on
        // a change, empty body, one re-read of the one memory-backed endpoint.
        events.addEventListener('claude-standing-changed', () => loadClaudeStandings().then(render));
        // An update this browser (or another one) asked for has settled. It rides on this stream rather than
        // one of its own for the same reason everything else here does — the fleet already holds it open — and
        // it is what ends the wait: a pull is minutes, so the request returned 202 and nothing has been asking
        // since. The payload carries the sentence the domain wrote, so both browsers say the same thing.
        events.addEventListener('container-update-settled', (e) => {
            try {
                onUpdateSettled(JSON.parse(e.data)).then(render);
            } catch (err) {
                console.error('Failed to apply container-update-settled:', err);
            }
        });
    }

    // The fleet's second stream, and the last one. `published-services` is a different topic on a different
    // controller — PublishingService, the published-services controller and DockerEventListener all publish
    // on it — and the fleet already holds both streams open, so this is the shape the codebase has, not a
    // new one. It is what keeps the services (and the containers behind them) honest without a single poll:
    // the backend watches, the backend pushes, the browser listens.
    function watchServices() {
        const events = new EventSource('/published-services/events');
        const refresh = () => {
            loadContainers();                       // re-renders when it lands
            loadServices().then(render);
        };
        // Same reconnect re-sync as the fleet stream: a redeploy or blip drops this too, and a missed
        // service/container change would otherwise wait for a manual refresh. Re-sync on reconnect only.
        let opened = false;
        events.onopen = () => {
            if (opened) refresh();
            opened = true;
        };
        // A route was published, updated, unpublished — or a container behind one changed state
        // (DockerEventListener publishes `container-state-changed` on this same event).
        events.addEventListener('service-updated', refresh);
        events.addEventListener('publish-traefik-active', refresh);
        events.addEventListener('publish-rolled-back', refresh);
    }

    // --- the operator ---------------------------------------------------------------------------------------

    const ICON_LOGOUT = '<svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.3" '
        + 'stroke-linecap="round" stroke-linejoin="round"><path d="M6 2H3a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"/>'
        + '<polyline points="10 11 14 8 10 5"/><line x1="14" y1="8" x2="5" y2="8"/></svg>';

    async function loadUser() {
        const host = $('topbarUser');
        try {
            const res = await fetch('/users/me', { cache: 'no-store' });
            if (!res.ok) return;
            const me = await res.json();
            if (!me.username) return;

            const label = me.displayname || me.username;
            const name = document.createElement('span');
            name.className = 'display-name';
            name.title = label;
            name.textContent = label;

            // The photo is a non-essential enhancement — never let it break the name + Logout controls.
            let profile = name;
            let photoUrl = null;
            try {
                photoUrl = await VaierAvatar.photoUrl({
                    provider: me.provider, providerUserId: me.providerUserId, email: me.email, size: 48 });
            } catch (e) { /* keep the name text */ }
            if (photoUrl) {
                const img = document.createElement('img');
                img.className = 'topbar-avatar';
                img.src = photoUrl;
                img.alt = label;
                img.title = label;
                img.onerror = () => img.replaceWith(name);
                profile = img;
            }

            const logout = document.createElement('a');
            logout.href = me.logoutUrl || '#';
            logout.className = 'topbar-item';
            logout.title = 'Logout';
            logout.setAttribute('aria-label', 'Logout');
            logout.innerHTML = ICON_LOGOUT;   // trusted constant SVG
            host.replaceChildren(profile, logout);
        } catch (e) { /* the shell works without a name on it */ }
    }

    // --- wiring -----------------------------------------------------------------------------------------

    $('exPalBtn').onclick = openPalette;

    $('exScrim').onclick = (e) => { if (e.target === $('exScrim')) closePalette(); };

    // --- the Vaier menu ---------------------------------------------------------------------------------
    //
    // Settings, Users, Security and Concepts are Vaier's, not the fleet's — siblings of the fleet root rather
    // than things inside it. Listing them in the fleet pane would say they are part of the fleet, so they sit
    // in the chrome, where they are one reach away from any depth on any screen.

    function setVMenu(open) {
        $('exVMenuBtn').setAttribute('aria-expanded', open ? 'true' : 'false');
        $('exVMenu').classList.toggle('is-open', open);
    }

    function vMenuItem(icon, label, path, desc) {
        const item = el('button', 'ex-vmenu-item' + (desc ? ' has-desc' : ''));
        item.setAttribute('role', 'menuitem');
        item.innerHTML = svg(icon, 'ex-ico');
        const text = el('span', 'ex-vmenu-text');
        const lbl = el('span');
        lbl.textContent = label;
        text.appendChild(lbl);
        if (desc) {
            const d = el('span', 'ex-vmenu-desc');
            d.textContent = desc;
            text.appendChild(d);
        }
        item.appendChild(text);
        item.onclick = () => { setVMenu(false); go(path); };
        return item;
    }

    function renderVMenu() {
        const menu = $('exVMenu');
        menu.textContent = '';
        // Everything that is not a machine, grouped by the job it belongs to rather than listed flat. Fleet
        // has to be here: a global's crumb bar is one segment long ("Settings" is not inside anything), so
        // without this, standing on Settings left no way back to the fleet at all.
        // It heads its own group, with the fleet-wide acts under it.
        let group = null;
        GLOBALS.forEach((g) => {
            if (!offered(g)) return;
            if (g.group !== group) {
                group = g.group;
                menu.appendChild(vMenuGroup(group));
                if (group === 'Your fleet') {
                    menu.appendChild(vMenuItem('fleet', 'Fleet', ROUTE_DEFAULT.slice()));
                }
            }
            menu.appendChild(vMenuItem(g.icon, g.label, [g.name], g.desc));
        });
    }

    // Chat is in the menu only while an Anthropic API key is stored: without one there is nothing to ask,
    // and an entry that opens onto "go to Settings first" is a door painted on a wall.
    function offered(g) {
        return g.name !== 'chat' || S.chatAvailable;
    }

    function vMenuGroup(text) {
        const g = el('div', 'ex-vmenu-group');
        g.textContent = text;
        return g;
    }

    $('exVMenuBtn').onclick = (e) => {
        e.stopPropagation();
        setVMenu($('exVMenu').className.indexOf('is-open') < 0);
    };
    // A click anywhere else closes it — the reflex every menu has.
    document.addEventListener('click', () => setVMenu(false));
    $('exVMenu').onclick = (e) => e.stopPropagation();
    $('exPalInput').oninput = () => { S.palSel = 0; paintPalette($('exPalInput').value); };
    $('exPalInput').onkeydown = (e) => {
        const found = matches($('exPalInput').value);
        if (e.key === 'ArrowDown') {
            e.preventDefault();
            S.palSel = Math.min(S.palSel + 1, found.length - 1);
            paintPalette($('exPalInput').value);
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            S.palSel = Math.max(S.palSel - 1, 0);
            paintPalette($('exPalInput').value);
        } else if (e.key === 'Enter') {
            e.preventDefault();
            if (found[S.palSel]) jump(found[S.palSel].path);
        } else if (e.key === 'Escape') {
            closePalette();
        }
    };
    document.addEventListener('keydown', (e) => {
        if ((e.metaKey || e.ctrlKey) && e.key === 'k') { e.preventDefault(); openPalette(); }
        if (e.key === 'Escape') setVMenu(false);
    });

    // On a phone the soft keyboard shrinks the visual viewport but not the layout viewport, which would leave
    // the terminal's bottom rows (and the prompt) hidden behind the keyboard. Bind the shell's height to the
    // visual viewport so the focused shell sits above it.
    if (window.visualViewport) {
        const syncViewport = () => {
            document.body.style.height = window.visualViewport.height + 'px';
        };
        window.visualViewport.addEventListener('resize', syncViewport);
        window.visualViewport.addEventListener('scroll', syncViewport);
    }

    async function init() {
        renderVMenu();
        // Read (and clear) a join code first, so normalising the address below does not write it straight
        // back into the bar.
        const approval = pendingApproval();
        // Where the address says we are, before anything is fetched — so a reload or a pasted link paints the
        // right place on the first frame rather than landing on the fleet and jumping.
        const start = parseHash();
        _route = hrefFor(start.path, start.at);
        S.path = start.path;
        S.at = start.at;
        // The address is normalised into the bar even when the visitor arrived with none, so the very first
        // thing in history is a real location and the first Back is not a step out of the app.
        location.replace(location.pathname + location.search + _route);

        // The services are awaited because a machine cannot be honest without them: a `services` entry exists
        // only on a machine that actually publishes something, and an entry that grew a moment later would
        // have been lying for that moment.
        await Promise.all([loadFleet(), loadServices(), loadBackup(), loadMyDevice(), loadEnrolmentRequests(),
            loadChatAvailability()]);
        // The menu was drawn before Vaier had said whether Chat may be offered; now it has, draw it again.
        renderVMenu();
        loadSpend();
        // A link into a folder needs the chain above it read before remotePath can resolve where the machine's
        // tree begins. Standing anywhere else this is a no-op.
        if (S.path.length > 3) readPathChain(S.path[1]);
        render();
        // A phone is waiting on the other end of this, so it comes before the background loads. The code is
        // only a way of pointing at a request Vaier already holds; one it does not hold is nothing to open.
        if (approval) {
            const request = liveEnrolmentRequests().find((r) => r.code === approval);
            if (request) addMachineFork('enrol', null, request);
            else toast('No phone is waiting with code ' + approval + '. Ask it to join again.');
        }
        // Not awaited and never toasted — a claimed device quietly refreshing its own position is a background
        // habit the operator opted into once, not an event of the visit.
        maybeAutoSharePosition();

        // The containers are not awaited. The fleet-wide Docker scrape can take seconds against a sleeping
        // host, and no part of the first paint depends on it — the `containers` entry is decided by
        // /machines' runsDocker, not by what the scrape finds. It fills itself in when it lands, which is
        // also what lets ⌘K find a container the operator never went looking for.
        loadContainers();
        // The fleet's disk pressure, read once here rather than on view — a machine card carries its disk
        // mark whether or not anyone opens that machine, and render() must never fetch. Not awaited: it is a
        // memory-backed read that wakes nothing, and no part of the first paint depends on it.
        loadDiskStandings().then(render);
        // And where the fleet stands on Claude, for the same reason and at the same cost: memory-backed,
        // nothing woken, and a mark a machine card carries whether or not anyone opens that machine.
        loadClaudeStandings().then(render);

        watchFleet();
        watchServices();
        watchTransfers();
        watchBackups();
        watchSecurity();
        watchChat();
        loadTransfers();
        // Not awaited, and read at boot rather than on view, because the Map draws threats too — an
        // operator who opens the Map first would otherwise see an honest-looking map with nobody on it.
        loadSecurity().then(() => { repaintThreats(); repaintAccessSources(); });
        // The Map has no use for this one, but render() must never fetch, so it is read here too.
        loadTrusted().then(repaintTrusted);
        loadUser();
    }

    init();
})();
