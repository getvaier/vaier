// The Explorer shell — the fleet as one tree (#323, slice A).
//
// Vaier's domain is already a namespace: a file has a coordinate (machine, path, point in time), and so does a
// container, a published service, an archive. Vaier sits at the VPN hub and is the only machine with SSH to
// every other, so it is the only place a tree spanning the fleet can exist. This is that tree, and the pane
// beside it is a renderer chosen by what the selected entry *is*. Nothing else is navigation.
//
// Slice A builds the shell and moves the terminal dock into it. Machines, files and shells are real entries;
// the sections not yet ported are bridged (see BRIDGES below) so nothing an operator relies on disappears
// while the rest of the tree grows.
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
        infra:   '<rect x="1" y="2" width="14" height="10" rx="1"/><path d="M5 15h6M8 12v3"/>',
        archive: '<path d="M2 5h12v8H2z"/><path d="M1.5 3h13v2h-13zM6.5 8h3"/>',
        users:   '<circle cx="6" cy="6" r="2.4"/><path d="M1.8 13.5c.3-2.4 2.2-3.8 4.2-3.8s3.9 1.4 4.2 3.8"/><path d="M11 4.2a2.2 2.2 0 0 1 0 4.3M12 9.9c1.4.5 2.3 1.8 2.5 3.6"/>',
        gear:    '<circle cx="8" cy="8" r="2.2"/><path d="M8 1.6v1.8M8 12.6v1.8M14.4 8h-1.8M3.4 8H1.6M12.5 3.5l-1.3 1.3M4.8 11.2l-1.3 1.3M12.5 12.5l-1.3-1.3M4.8 4.8L3.5 3.5"/>',
        book:    '<path d="M2.5 2.5h7a2 2 0 0 1 2 2v9"/><path d="M2.5 2.5v9a2 2 0 0 0 2 2h7"/><path d="M4.5 5.5H9M4.5 8H8"/>',
        box:     '<rect x="2.2" y="4.4" width="11.6" height="8.6" rx="1"/><path d="M2.2 7.3h11.6M5.6 4.4v2.9"/>',
        route:   '<circle cx="8" cy="8" r="6.2"/><path d="M1.9 8h12.2"/><path d="M8 1.8c1.7 1.9 2.6 3.9 2.6 6.2S9.7 12.3 8 14.2C6.3 12.3 5.4 10.3 5.4 8S6.3 3.7 8 1.8z"/>',
        disk:    '<rect x="2" y="3.4" width="12" height="9.2" rx="1"/><circle cx="8" cy="8" r="2.3"/><circle cx="8" cy="8" r=".45" fill="currentColor" stroke="none"/>',
        copy:    '<rect x="5.5" y="5.5" width="8" height="8.2" rx="1.2"/><path d="M3.4 10.5H3a1 1 0 0 1-1-1V3.2a1 1 0 0 1 1-1h6.3a1 1 0 0 1 1 1v.4"/>',
        download:'<path d="M8 2v7.5"/><path d="M4.7 6.5L8 9.8l3.3-3.3"/><path d="M2.5 13.5h11"/>',
        clip:    '<rect x="3" y="2.6" width="10" height="11.4" rx="1.2"/><path d="M5.8 2.6V2a.9.9 0 0 1 .9-.9h2.6a.9.9 0 0 1 .9.9v.6z"/><path d="M5.6 7.2h4.8M5.6 9.7h4.8M5.6 12.2h2.8"/>',
        check:   '<path d="M2.8 8.4l3.1 3.4L13.2 4.6"/>',
        warn:    '<path d="M8 2.4l6.1 11.1H1.9z"/><path d="M8 6.4v3.3"/><circle cx="8" cy="11.4" r=".5" fill="currentColor" stroke="none"/>',
        cross:   '<path d="M4 4l8 8M12 4l-8 8"/>',
        refresh: '<path d="M13.4 8a5.4 5.4 0 1 1-1.6-3.8"/><path d="M13.6 2.4v3.1h-3.1"/>',
        trash:   '<path d="M3 4.5h10M6.5 4.5V3a.8.8 0 0 1 .8-.8h1.4a.8.8 0 0 1 .8.8v1.5"/><path d="M4.2 4.5l.6 8a1 1 0 0 0 1 .9h4.4a1 1 0 0 0 1-.9l.6-8"/><path d="M6.7 7v4M9.3 7v4"/>',
        shield:  '<path d="M8 1.7l5.1 1.9v3.9c0 3.2-2.1 5.4-5.1 6.5-3-1.1-5.1-3.3-5.1-6.5V3.6z"/><path d="M5.7 8l1.6 1.7L10.4 6"/>',
        shieldhalf: '<path d="M8 1.7l5.1 1.9v3.9c0 3.2-2.1 5.4-5.1 6.5-3-1.1-5.1-3.3-5.1-6.5V3.6z"/><path d="M3 8.3h10"/>',
        map:     '<path d="M8 1.7c-2.5 0-4.4 1.9-4.4 4.3 0 3.1 4.4 8.3 4.4 8.3s4.4-5.2 4.4-8.3c0-2.4-1.9-4.3-4.4-4.3z"/><circle cx="8" cy="6" r="1.6"/>',
        // Device forms, matched to the Infrastructure page's machine icons (vpn-peers-helpers.js) so a machine
        // wears the same shape in the tree as on its card — just smaller. Keyed by device category, lowercased.
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
        return '<svg class="' + cls + '" viewBox="0 0 16 16" fill="none" stroke="currentColor" '
            + 'stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round">' + ICON[name] + '</svg>';
    }

    // --- the bridge: TRANSITIONAL, and deleted a slice at a time ---------------------------------------
    //
    // Infrastructure, Backups, Users, Settings and Concepts are not part of the tree yet. Rather than leave
    // them unreachable from the shell — or, worse, ship the shell as a second place the operator has to give
    // up on and go back to admin.html — each appears as an entry whose Inspector is the existing page, framed
    // whole. This is scaffolding, not architecture: slices C and E replace these entries with real ones
    // (containers, services, disk, DNS, access, settings), and each one that lands deletes its line here.
    // When the last is gone, this array, the iframe, and admin.html itself go with it.
    const BRIDGES = [
        { name: 'infrastructure', label: 'Infrastructure', icon: 'infra',   page: 'vpn-peers.html',
          note: 'Machines, services, the map' },
        { name: 'backups',        label: 'Backups',        icon: 'archive', page: 'backups.html',
          note: 'Jobs, runs and archives' },
    ];

    // Vaier-wide entries that are NOT of the fleet — they belong to Vaier, not to any machine — so they sit at
    // the top level of the tree, outside `fleet`. Settings is native now; Users and Concepts still bridge their
    // pages until they are ported (a later slice). This is why the tree is a forest, not one root.
    const GLOBALS = [
        { name: 'settings', label: 'Settings', icon: 'gear',  native: true },
        { name: 'users',    label: 'Users',    icon: 'users', page: 'users.html' },
        { name: 'concepts', label: 'Concepts', icon: 'book',  page: 'concepts.html' },
    ];

    const MACHINE_TYPE = {
        MOBILE_CLIENT:  'Mobile client',
        WINDOWS_CLIENT: 'Windows client',
        UBUNTU_SERVER:  'Ubuntu server',
        WINDOWS_SERVER: 'Windows server',
        LAN_SERVER:     'LAN server',
    };

    // The Vaier server is a machine in the fleet like any other (#311) — but it is *this* machine, the one
    // serving the page. Mirrors LanAnchor.VAIER_SERVER_NAME.
    const VAIER_SERVER = 'Vaier server';

    // --- state ----------------------------------------------------------------------------------------

    const S = {
        path: ['fleet'],                 // the selected entry, as its path
        open: new Set(['/fleet']),
        machines: [],                    // GET /machines
        peers: new Map(),                // machine name -> its live WireGuard peer (tunnel address, liveness)
        peerNames: new Map(),            // peer id -> machine name (the SSE keys stats by id, not by name)
        lan: new Map(),                  // machine name -> its LAN server (the domain's MachineStatus)
        serverLocation: null,            // GET /vpn/peers/server-location — the Vaier server's geo (Frankfurt), for the map
        dirs: new Map(),                 // dirKey -> one directory's read: its state, its children, its reader
        services: [],                    // GET /published-services/discover — the whole fleet's routes
        publishable: [],                 // GET /published-services/publishable — container ports that could be published (and which are ignored)
        access: {},                      // GET /access/services — dnsAddress -> the groups allowed through
        containers: new Map(),           // machine name -> its containers, as Vaier last scraped them
        containersRead: false,           // whether the fleet-wide Docker scrape has landed at least once
        disks: new Map(),                // machine name -> its filesystems: state, the list, the failure's words
        roots: new Map(),                // (machine, at) -> where a file tree begins (its SFTP root, #326)
        at: null,                        // the archive being browsed, or null for the present — the live filesystem
        archives: new Map(),             // machine name -> { state, list, error }: its archives, the rail's stops
        clipboard: [],                   // held file coordinates {machine, path, at, name, directory, size} — the Clipboard
        transfers: new Map(),            // id -> a live/settled Transfer, streamed in over the transfers SSE topic
        sel: new Set(),                  // paths ticked in the current directory listing (multi-select)
        backupServer: null,              // GET /backup-servers — the fleet's one backup server, or null when none is designated
        backupRepos: [],                 // GET /backup-repositories — the repositories that live on it
        repoArchives: new Map(),         // repo name -> { state, list, error }: the archives in a repository, read when looked at
        backupJobs: [],                  // GET /backup-jobs — the jobs, each backing one machine up to a repository
        jobRuns: new Map(),              // job name -> { state, run }: its last run, read on view and on the run-settled push
        preparing: new Set(),            // machine names Vaier is readying to back up (first back-up), cleared on prepare-client-settled
        settings: { state: 'idle', config: null, version: '', edition: '' },   // the native Settings entry, read on view
        palSel: 0,
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

    // --- what an entry IS, read off its own path -------------------------------------------------------
    //
    // The path is the whole model. A tree that also kept a parallel object graph would be two truths about
    // where you are, and they would drift the first time a machine was renamed under one of them.

    function kindOf(path) {
        if (path.length === 1) {
            if (path[0] === 'fleet') return 'fleet';
            const g = GLOBALS.find((x) => x.name === path[0]);
            if (g) return g.native ? 'settings' : 'gbridge';
            return 'fleet';
        }
        if (path.length === 2) {
            if (path[1] === 'map') return 'map';
            return BRIDGES.some((b) => b.name === path[1]) ? 'bridge' : 'machine';
        }
        if (path[2] === 'shell') return 'shell';
        if (path[2] === 'backup') return path.length === 3 ? 'backup' : 'repo';
        if (path[2] === 'disk') return 'disk';
        if (path[2] === 'containers') return path.length === 3 ? 'containers' : 'container';
        if (path[2] === 'services') return path.length === 3 ? 'services' : 'service';
        if (path[2] === 'files') return path.length === 3 ? 'files' : 'dir';
        return 'fleet';
    }

    const machineOf = (path) => S.machines.find((m) => m.name === path[1]) || null;
    const bridgeOf = (path) => BRIDGES.find((b) => b.name === path[1]) || null;

    // Machines are ordered the way the Infrastructure page orders them, so the two never disagree: the Vaier
    // server first, then the servers, then the clients, each group alphabetical. Server-ness is the machine's
    // type (the domain's isServerType — Ubuntu/Windows/LAN server), the same split vpn-peers.js draws.
    const SERVER_TYPES = new Set(['UBUNTU_SERVER', 'WINDOWS_SERVER', 'LAN_SERVER']);
    const machineRank = (m) => (m.name === VAIER_SERVER ? 0 : (SERVER_TYPES.has(m.type) ? 1 : 2));
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
    // A published service is one thing with three homes: a container on a machine, a Traefik route, and a DNS
    // record. The tree files it under the machine, and it decides which machine by the rule vpn-peers.js
    // already uses — a LAN service by its LAN server (falling back to the relay peer when no registered LAN
    // server matches its address), the hub's own routes on the Vaier server, everything else by its host. A
    // second rule here would put the same service under two different machines in two different pages.
    function machineOfService(s) {
        if (s.isLanService) return s.lanServerName || s.hostName;
        if (!s.hostName || !String(s.hostName).trim() || s.hostName === VAIER_SERVER) return VAIER_SERVER;
        return s.hostName;
    }

    // A route is addressed by its DNS name, and a path-prefixed route shares that name with its siblings —
    // so the entry is named by both, exactly as the unpublish call is.
    const serviceName = (s) => (s.shortName || s.name) + (s.pathPrefix || '');

    const servicesOn = (machine) => S.services.filter((s) => machineOfService(s) === machine);
    const containersOn = (machine) => S.containers.get(machine) || [];
    // A publishable candidate names the machine it runs on by peer id (like the container scrape), so it maps
    // back to the machine's canonical name through the same id→name map. Falls through for LAN/Vaier servers,
    // whose peerName already is the name.
    const candidateMachine = (c) => S.peerNames.get(c.peerName) || c.peerName;
    const candidatesOn = (machine) => S.publishable.filter((c) => candidateMachine(c) === machine);
    // The repositories that live on a backup server, filtered by the server they name. One server, so this is
    // every repository Vaier knows — but the filter keeps it honest if a stale repo names a server that is gone.
    const reposOn = (server) => (server ? S.backupRepos.filter((r) => r.serverName === server.name) : []);
    // The backup jobs that back a machine up. A job names the machine it protects, so this is how a machine
    // learns it is backed up — and grows a `backup` entry even when it is not the server.
    const jobsOn = (machine) => S.backupJobs.filter((j) => j.machineName === machine);

    // Whether an entry is inside its machine's backup is NOT decided here — the containment rule (a source path
    // covers itself and its descendants) lives in the domain, and the backend stamps each listed entry with
    // `backedUp`. The shell only reads that flag; it never re-derives the rule in JS.

    function childrenOf(path) {
        const kind = kindOf(path);
        if (kind === 'fleet') {
            return [{ name: 'map', kind: 'map', label: 'Map' }]
                .concat(sortedMachines().map((m) => ({ name: m.name, kind: 'machine' })))
                .concat(BRIDGES.map((b) => ({ name: b.name, kind: 'bridge', label: b.label })));
        }
        if (kind === 'machine') {
            const m = machineOf(path);
            if (!m) return [];
            // Honest and conditional: a machine grows only the entries Vaier can actually reach on it. No SSH
            // means no files, no shell and no disk; a machine that runs no Docker must not grow an empty
            // `containers` entry that opens onto nothing. /machines already carries both facts — the tree
            // asks them rather than assuming every machine is the same machine.
            const kids = [];
            if (m.sshAccess) kids.push({ name: 'files', kind: 'files' }, { name: 'shell', kind: 'shell' });
            if (m.runsDocker) kids.push({ name: 'containers', kind: 'containers' });
            // A machine grows a `services` entry when it publishes something, has a container port that could be
            // published, or is a server (so a non-container service on it — a printer's page, a LAN app — can
            // still be published by hand). Clients, which host nothing, stay quiet.
            if (servicesOn(m.name).length || candidatesOn(m.name).length
                || SERVER_TYPES.has(m.type) || m.name === VAIER_SERVER) {
                kids.push({ name: 'services', kind: 'services' });
            }
            if (m.sshAccess) kids.push({ name: 'disk', kind: 'disk' });
            // A machine grows a `backup` entry when it plays any part in fleet backup: it is the one backup
            // server (name-equality — the backend refuses a second), or a job backs it up. The entry reads both
            // ways; here we only decide whether it exists.
            if ((S.backupServer && S.backupServer.machineName === m.name) || jobsOn(m.name).length) {
                kids.push({ name: 'backup', kind: 'backup' });
            }
            return kids;
        }
        if (kind === 'backup') {
            // Only the server's `backup` entry has children — its repositories. A client machine's `backup`
            // entry shows its job inline and has none, so it must not inherit the server's repos as its own.
            const isServer = S.backupServer && S.backupServer.machineName === path[1];
            return isServer ? reposOn(S.backupServer).map((r) => ({ name: r.name, kind: 'repo' })) : [];
        }
        if (kind === 'containers') {
            return containersOn(path[1]).map((c) => ({ name: c.containerName, kind: 'container' }));
        }
        if (kind === 'services') {
            return servicesOn(path[1]).map((s) => ({ name: serviceName(s), kind: 'service' }));
        }
        if (kind === 'files' || kind === 'dir') {
            // Whatever the cache already holds, and nothing more. This function is called on every repaint,
            // so it must never be able to start a read — otherwise a tree that merely redraws would walk the
            // fleet over SFTP, and the fleet is on the far side of a VPN.
            const entry = S.dirs.get(dirKey(path[1], remotePath(path), S.at));
            if (!entry || entry.state !== 'ready') return [];
            // Only directories become entries in the rail. The rail carries structure; the Inspector lists the
            // contents. Duplicating every file into the rail would drown the structure the rail exists to show.
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

    const isDirKind = (kind) => kind === 'files' || kind === 'dir';

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
    //   services   — the published routes. Read at start because the tree cannot be honest without them: the
    //                `services` entry exists only on a machine that actually has some.
    //   disk       — one machine, read when its disk entry is looked at. A fleet-wide df on page load would
    //                wake every sleeping machine to answer a question nobody asked.

    // Vaier scrapes Docker per kind of machine, so this is three endpoints and one map. All three already
    // exist — this is the Infrastructure page's own data, re-filed under the machines it belongs to.
    //
    // The filing is the whole difficulty. A peer's containers arrive keyed by the peer's *id* — the WireGuard
    // directory name, "apalveien5" — while the tree, /machines and every SSH lookup speak the canonical
    // machine name, "Apalveien 5". Filing them by id would hang them under a machine that does not exist in
    // the tree, and every peer would show an empty `containers` entry while Vaier could see its containers
    // perfectly well. The shell already holds the id -> name map for exactly this reason (the stats stream is
    // keyed by id too), so containers go through it. LAN servers and the Vaier server already carry their
    // canonical names.
    async function loadContainers() {
        const next = new Map();
        try {
            const [peers, server, lan] = await Promise.all([
                fetch('/docker-services/peers').then((r) => (r.ok ? r.json() : [])),
                fetch('/docker-services/vaier-server').then((r) => (r.ok ? r.json() : null)),
                fetch('/docker-services/lan-servers').then((r) => (r.ok ? r.json() : [])),
            ]);
            (peers || []).forEach((p) => {
                next.set(S.peerNames.get(p.peerName) || p.peerName, p.containers || []);
            });
            (lan || []).forEach((l) => next.set(l.name, l.containers || []));
            if (server) next.set(VAIER_SERVER, server.containers || []);
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

    // One machine's filesystems, read when they are looked at (#323 slice C, every filesystem since #325).
    // Vaier has computed this on a schedule since the disk alerts shipped and only ever emailed about it;
    // this is the same reading, looked at.
    async function loadDisk(machine) {
        const held = S.disks.get(machine);
        if (held && held.state === 'loading') return;
        S.disks.set(machine, { state: 'loading', filesystems: null, error: null });
        try {
            const res = await fetch('/machines/' + encodeURIComponent(machine) + '/disk');
            const body = await res.json();
            if (res.ok) {
                S.disks.set(machine, { state: 'ready', filesystems: body, error: null });
            } else {
                // The server's own sentence, verbatim — "Vaier could not read the disk on X. The machine may
                // be asleep..." says everything a status code cannot. A disk Vaier failed to read is never
                // painted as a disk with room on it.
                S.disks.set(machine, { state: 'error', filesystems: null,
                    error: body.message || 'Vaier could not read the disks on ' + machine + '.' });
            }
        } catch (e) {
            S.disks.set(machine, { state: 'error', filesystems: null,
                error: 'Vaier could not read the disks on ' + machine + '.' });
        }
        render();
    }

    // One machine's archives — the stops on its time rail (#323 slice D). Read once when its files are first
    // looked at, never polled: a new nightly backup lands on a reload, not under the operator's cursor. A
    // machine with no backup job answers with an empty list and simply grows no rail — the file browser is
    // exactly what it was. A failure is the same quiet outcome: no rail, and the present still browses.
    async function loadArchives(machine) {
        const held = S.archives.get(machine);
        if (held && (held.state === 'loading' || held.state === 'ready')) return;   // once per machine
        S.archives.set(machine, { state: 'loading', list: [], error: null });
        try {
            const res = await fetch('/machines/' + encodeURIComponent(machine) + '/archives');
            S.archives.set(machine, { state: 'ready', list: res.ok ? await res.json() : [], error: null });
        } catch (e) {
            S.archives.set(machine, { state: 'ready', list: [], error: null });
        }
        render();
    }

    // The fleet's one backup server, and the repositories that live on it. Loaded before the first paint, like
    // the services are, because the tree cannot be honest without it: a machine grows a `backup` entry only if
    // it is the designated backup server, and a tree that grew one a moment later would have been lying for that
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
                       shell: 'shell', containers: 'box', container: 'box', services: 'route',
                       service: 'route', disk: 'disk', backup: 'archive', repo: 'box', map: 'map' };

    // A machine wears its device's shape — server, NAS, printer — the same icon its Infrastructure card uses,
    // read off its device category. A category with no icon (or a machine not yet loaded) falls back to the
    // generic machine glyph, never to a blank.
    function machineIcon(name) {
        const m = S.machines.find((x) => x.name === name);
        const cat = m && m.deviceCategory ? String(m.deviceCategory).toLowerCase() : '';
        return ICON[cat] ? cat : 'machine';
    }

    const iconFor = (kind, name) => {
        if (kind === 'bridge') return (BRIDGES.find((b) => b.name === name) || {}).icon || 'file';
        if (kind === 'settings' || kind === 'gbridge') return (GLOBALS.find((g) => g.name === name) || {}).icon || 'file';
        if (kind === 'machine') return machineIcon(name);
        return ICON_FOR[kind] || 'file';
    };

    // Mono for anything with a coordinate, sans for anything human. A machine name, a path, a container name
    // and a DNS name are addresses; "Backups" is a word.
    const MONO_KINDS = new Set(['machine', 'files', 'dir', 'file', 'containers', 'container', 'services',
                                'service', 'disk', 'backup', 'repo']);

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
    // vpn-peers.js picks the same four. Note what is deliberately NOT green: UNKNOWN means no probe has run
    // yet, and painting that up would be Vaier claiming to know something it does not. Grey is the honest
    // answer to a question nobody has asked yet.
    const STATUS_DOT = {
        'OK':       'is-up',
        'DEGRADED': 'is-degraded',   // on the network, but its Docker scrape is failing — up, and not well
        'DOWN':     'is-down',
        'UNKNOWN':  'is-idle',
    };

    function livenessOf(name) {
        // We are standing inside the answer.
        if (name === VAIER_SERVER) return 'is-up';

        const peer = S.peers.get(name);
        if (peer) return peer.connected ? 'is-up' : 'is-down';

        const server = S.lan.get(name);
        if (server) return STATUS_DOT[server.status] || 'is-idle';

        return 'is-idle';   // a machine with no liveness source at all — honest, not a bug
    }

    function paintDots() {
        document.querySelectorAll('[data-ex-dot]').forEach((dot) => {
            dot.className = 'ex-dot ' + livenessOf(dot.getAttribute('data-ex-dot'));
        });
    }

    function dot(name) {
        const el = document.createElement('span');
        el.className = 'ex-dot ' + livenessOf(name);
        el.setAttribute('data-ex-dot', name);
        return el;
    }

    // --- the tree ---------------------------------------------------------------------------------------

    function renderTree() {
        const tree = $('exTree');
        tree.textContent = '';

        const label = document.createElement('div');
        label.className = 'ex-col-label';
        label.textContent = 'Fleet';
        tree.appendChild(label);
        tree.appendChild(branch(['fleet'], 'fleet', 'fleet', 0));

        // The Vaier-wide entries, at the top level, outside the fleet.
        const vlabel = document.createElement('div');
        vlabel.className = 'ex-col-label';
        vlabel.textContent = 'Vaier';
        tree.appendChild(vlabel);
        GLOBALS.forEach((g) => tree.appendChild(
            branch([g.name], g.native ? 'settings' : 'gbridge', g.label, 0)));
    }

    function branch(path, kind, label, depth) {
        const frag = document.createDocumentFragment();
        const k = key(path);
        const kids = childrenOf(path);
        const isOpen = S.open.has(k);
        const here = key(S.path);
        // Directories are real entries now, so the rail shows exactly where you are — no ancestor stands in
        // for you any more.
        const selected = here === k;

        // A directory nobody has read yet is expandable on faith: what it holds is only known once it is read,
        // and refusing to offer the twist until then would make an unread directory look like a leaf. One that
        // was read and holds no directories really is a leaf; one that failed is not expandable, it is broken.
        const state = isDirKind(kind) ? dirStateOf(path) : null;
        const busy = state === 'loading';
        const failed = state === 'error';
        const expandable = kids.length > 0 || state === 'unread' || busy;

        const row = document.createElement('button');
        row.className = 'ex-row' + (selected ? ' is-sel' : '') + (failed ? ' is-failed' : '');
        row.style.paddingLeft = (8 + depth * 13) + 'px';
        row.innerHTML = svg('chev', 'ex-twist' + (expandable ? (isOpen ? ' is-open' : '') : ' is-leaf')
                + (busy ? ' is-busy' : ''))
            + svg(iconFor(kind, path[path.length - 1]), 'ex-ico');

        const name = document.createElement('span');
        name.className = 'ex-lbl ' + (MONO_KINDS.has(kind) ? 'is-id' : 'is-word');
        name.textContent = label;
        row.appendChild(name);

        // A directory that could not be read wears its failure in the rail, and says why on hover. The reason
        // itself is the server's own sentence ("Not allowed to read /root as geir.") — selecting the row shows
        // it in full, in the Inspector.
        if (failed) {
            const entry = S.dirs.get(dirKey(path[1], remotePath(path), S.at));
            row.title = (entry && entry.error) || 'This directory could not be read.';
        }

        if (kind === 'machine') row.appendChild(dot(path[1]));

        row.onclick = () => {
            if (expandable) {
                // Clicking the entry you are already standing on folds it away; clicking a new one opens it.
                if (selected && isOpen) S.open.delete(k); else S.open.add(k);
            }
            // Expanding a directory also selects it, so the Inspector follows — and selecting it is what
            // starts the read (see renderDirectory), so the rail and the Inspector are filled by one SFTP
            // round trip rather than two. Collapsing selects it too, which is to say it does not navigate
            // away from where you already are.
            go(path);
        };
        frag.appendChild(row);

        if (isOpen && kids.length) {
            const box = document.createElement('div');
            box.style.position = 'relative';
            kids.forEach((kid) => {
                box.appendChild(branch(path.concat([kid.name]), kid.kind, kid.label || kid.name, depth + 1));
            });
            // One hairline per level (styled in explorer-shell.css); only where it falls is known here.
            const guide = document.createElement('span');
            guide.className = 'ex-guide';
            guide.style.left = (14 + depth * 13) + 'px';
            box.appendChild(guide);
            frag.appendChild(box);
        }
        return frag;
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
            // A top-level global reads by its label ("Settings"), not its lowercase address.
            const text = (i === 0 && seg !== 'fleet')
                ? ((GLOBALS.find((g) => g.name === seg) || {}).label || seg) : seg;
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
    }

    // --- the Inspector ----------------------------------------------------------------------------------

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

    function card(icon, name, nameIsId, noteText, onClick, dotName) {
        const btn = document.createElement('button');
        btn.className = 'ex-card';

        const top = document.createElement('div');
        top.className = 'ex-card-top';
        top.innerHTML = svg(icon, 'ex-ico');
        const nm = document.createElement('span');
        nm.className = 'ex-card-name' + (nameIsId ? '' : ' is-word');
        nm.textContent = name;
        top.appendChild(nm);
        if (dotName) top.appendChild(dot(dotName));
        btn.appendChild(top);

        const n = document.createElement('div');
        n.className = 'ex-card-note';
        n.textContent = noteText;
        btn.appendChild(n);

        btn.onclick = onClick;
        return btn;
    }

    function kv(rows) {
        const dl = document.createElement('dl');
        dl.className = 'ex-kv';
        rows.forEach(([term, value]) => {
            const dt = document.createElement('dt');
            dt.textContent = term;
            const dd = document.createElement('dd');
            dd.textContent = value == null || value === '' ? '—' : String(value);
            dl.append(dt, dd);
        });
        return dl;
    }

    function renderPane() {
        const pane = $('exPane');
        pane.className = 'ex-pane';
        pane.textContent = '';
        pane.scrollTop = 0;

        const kind = kindOf(S.path);
        if (kind === 'fleet') return renderFleet(pane);
        if (kind === 'map') return renderMap(pane);
        if (kind === 'settings') return renderSettings(pane);
        if (kind === 'gbridge') return renderGlobalBridge(pane);
        if (kind === 'machine') return renderMachine(pane);
        if (kind === 'bridge') return renderBridge(pane);
        if (kind === 'shell') return renderShell(pane);
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
        const online = S.machines.filter((m) => livenessOf(m.name) === 'is-up').length;
        pane.appendChild(paneHead('Fleet', false,
            S.machines.length + (S.machines.length === 1 ? ' machine · ' : ' machines · ') + online
            + ' online'));

        const body = document.createElement('div');
        body.className = 'ex-pane-body';

        body.appendChild(section('Machines'));
        const grid = document.createElement('div');
        grid.className = 'ex-grid';
        if (!S.machines.length) {
            body.appendChild(note('No machines yet. Add one on Infrastructure and it will appear here.',
                false));
        } else {
            sortedMachines().forEach((m) => {
                const address = tunnelAddress(m);
                grid.appendChild(card(machineIcon(m.name), m.name, true,
                    MACHINE_TYPE[m.type] + (address ? ' · ' + address : ''),
                    () => { S.open.add(key(['fleet', m.name])); go(['fleet', m.name]); }, m.name));
            });
            body.appendChild(grid);
        }

        body.appendChild(section('Not in the tree yet'));
        const rest = document.createElement('div');
        rest.className = 'ex-grid';
        BRIDGES.forEach((b) => {
            rest.appendChild(card(b.icon, b.label, false, b.note, () => go(['fleet', b.name])));
        });
        body.appendChild(rest);

        pane.appendChild(body);
    }

    // The fleet on a map — where the machines physically are, from the geo Vaier already resolves onto each
    // peer (latitude/longitude/city). Leaflet, loaded from explorer.html; if it did not load, the entry says so
    // rather than breaking. The map is torn down and rebuilt on each render (rare — peer stats only repaint the
    // dots, so the map is not thrashed), and requestAnimationFrame — never a timer — settles its size.
    // The fleet on a map — a faithful port of the Infrastructure page's map (vpn-peers-map.js). Clustered so
    // co-located machines gather and spiderfy on click; a client shows twice — a weak marker where it connects
    // from and a firm one where its traffic surfaces (the Vaier server); LAN servers sit at their relay; the
    // Vaier server itself is the big marker in Frankfurt. Leaflet + markercluster load from explorer.html.
    let _map = null;
    const MAP_STATUS = { OK: 'up', DEGRADED: 'degraded', DOWN: 'down', UNKNOWN: 'unknown' };
    const iconByCategory = (cat) => { const k = cat ? String(cat).toLowerCase() : ''; return ICON[k] ? k : 'generic'; };

    function mapMarker(latlng, iconKind, statusKey, opts) {
        const cls = ['map-marker', statusKey];
        if (opts && opts.big) cls.push('large');
        if (opts && opts.weak) cls.push('weak');
        const sz = opts && opts.big ? 36 : 28;
        return L.marker(latlng, {
            icon: L.divIcon({ html: '<div class="' + cls.join(' ') + '">' + svg(iconKind, 'ex-ico') + '</div>',
                className: '', iconSize: [sz, sz], iconAnchor: [sz / 2, sz / 2] }),
            riseOnHover: true,
        });
    }
    // A popup, built from DOM elements (no interpolation) — a bold title then muted/mono lines.
    function mapPopup(title, lines) {
        const box = el('div');
        const t = el('strong'); t.textContent = title; box.appendChild(t);
        lines.forEach((ln) => {
            if (!ln || !ln.text) return;
            box.appendChild(el('br'));
            const s = el('span');
            if (ln.mono) s.style.fontFamily = 'monospace';
            if (ln.muted) { s.style.color = '#888'; s.style.fontSize = '0.85em'; }
            s.textContent = ln.text; box.appendChild(s);
        });
        return box;
    }

    function renderMap(pane) {
        pane.appendChild(paneHead('Map', false, 'Where the fleet is'));
        const body = el('div', 'ex-pane-body ex-map-body');
        const holder = el('div', 'ex-map');
        body.appendChild(holder);
        pane.appendChild(body);
        if (typeof L === 'undefined') { body.appendChild(note('The map could not load its library.', true)); return; }
        if (_map) { try { _map.remove(); } catch (e) { /* gone */ } _map = null; }

        const map = L.map(holder, { worldCopyJump: true }).setView([20, 0], 1);
        _map = map;
        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',
            { maxZoom: 18, attribution: '© OpenStreetMap' }).addTo(map);
        const cluster = L.markerClusterGroup({ showCoverageOnHover: false, spiderfyOnMaxZoom: true, maxClusterRadius: 30 });
        map.addLayer(cluster);

        const loc = S.serverLocation;
        const hasLoc = loc && loc.latitude != null && loc.longitude != null;
        const coords = [];
        const add = (marker, ll) => { cluster.addLayer(marker); coords.push(ll); };

        Array.from(S.peers.values()).forEach((p) => {
            if (p.latitude == null || p.longitude == null) return;
            const statusKey = p.connected ? 'up' : 'down';
            const iconKind = iconByCategory(p.deviceCategory);
            const place = [p.city, p.country].filter(Boolean).join(', ');
            if (p.isClient) {
                const weak = mapMarker([p.latitude, p.longitude], iconKind, statusKey, { weak: true });
                weak.bindPopup(mapPopup(p.name, [{ text: 'connecting from', muted: true },
                    { text: p.endpointIp, mono: true }, { text: place }, { text: 'approx. ISP location', muted: true }]));
                add(weak, [p.latitude, p.longitude]);
                if (hasLoc) {
                    const firm = mapMarker([loc.latitude, loc.longitude], iconKind, statusKey);
                    firm.bindPopup(mapPopup(p.name, [{ text: 'internet via Vaier', muted: true },
                        { text: [loc.city, loc.country].filter(Boolean).join(', ') }]));
                    add(firm, [loc.latitude, loc.longitude]);
                }
            } else {
                const firm = mapMarker([p.latitude, p.longitude], iconKind, statusKey);
                firm.bindPopup(mapPopup(p.name, [{ text: p.endpointIp, mono: true }, { text: place }]));
                add(firm, [p.latitude, p.longitude]);
            }
        });

        Array.from(S.lan.values()).forEach((s) => {
            if (!s.relayPeerName) return;
            let lat, lon;
            if (s.relayPeerName === VAIER_SERVER) {
                if (!hasLoc) return; lat = loc.latitude; lon = loc.longitude;
            } else {
                const relay = S.peers.get(s.relayPeerName);
                if (!relay || relay.latitude == null || relay.longitude == null) return;
                lat = relay.latitude; lon = relay.longitude;
            }
            const firm = mapMarker([lat, lon], iconByCategory(s.deviceCategory), MAP_STATUS[s.status] || 'unknown');
            firm.bindPopup(mapPopup(s.name, [{ text: 'Behind ' + s.relayPeerName, muted: true },
                { text: s.lanAddress, mono: true },
                { text: s.runsDocker ? 'Docker on :' + s.dockerPort : '', muted: true }]));
            add(firm, [lat, lon]);
        });

        if (hasLoc) {
            const m = mapMarker([loc.latitude, loc.longitude], 'server', 'up', { big: true });
            m.bindPopup(mapPopup('Vaier server', [{ text: loc.publicHost, mono: true },
                { text: [loc.city, loc.country].filter(Boolean).join(', ') }]));
            add(m, [loc.latitude, loc.longitude]);
        }

        if (coords.length) map.fitBounds(L.latLngBounds(coords).pad(0.3), { maxZoom: 5 });
        else body.appendChild(note('No machine has a known location yet.', false));
        requestAnimationFrame(() => { try { map.invalidateSize(); } catch (e) { /* torn down */ } });
    }

    // A peer answers at its tunnel address, a LAN server on its LAN — the same rule the SSH connection
    // itself is resolved by, so what the Inspector shows is where Vaier would actually go.
    function tunnelAddress(m) {
        const peer = S.peers.get(m.name);
        if (peer && peer.tunnelIp) return peer.tunnelIp;
        return m.lanAddress || '';
    }

    function renderMachine(pane) {
        const m = machineOf(S.path);
        if (!m) return pane.appendChild(note('That machine is no longer in the fleet.', true));

        const peer = S.peers.get(m.name);
        const head = paneHead(m.name, true, MACHINE_TYPE[m.type]);
        head.querySelector('.ex-pane-title').appendChild(dot(m.name));
        pane.appendChild(head);

        const body = document.createElement('div');
        body.className = 'ex-pane-body';
        body.appendChild(kv([
            ['Tunnel address', tunnelAddress(m)],
            ['LAN', m.lanCidr || m.lanAddress],
            ['Endpoint', m.endpointIp ? m.endpointIp + ':' + (m.endpointPort || '') : ''],
            ['Latest handshake', peer ? peer.latestHandshake : ''],
            ['Transfer', m.transferRx || m.transferTx
                ? (m.transferTx || '0') + ' up / ' + (m.transferRx || '0') + ' down' : ''],
            ['Docker', m.runsDocker ? (m.dockerPort ? 'Yes — port ' + m.dockerPort : 'Yes') : 'No'],
            ['Device category', m.deviceCategory],
        ]));

        body.appendChild(section('Inside this machine'));
        const inside = childrenOf(S.path);
        if (!inside.length) {
            body.appendChild(note('Vaier cannot reach anything inside this machine. It has no SSH access, so '
                + 'no files, no shell and no disk reading; it runs no Docker Vaier knows of; and nothing is '
                + 'published from it. Give it an SSH credential on its Infrastructure card and it opens up.',
                false));
        } else {
            const grid = document.createElement('div');
            grid.className = 'ex-grid';
            const NOTE = {
                files:      'Browse over SFTP',
                shell:      'A terminal on this machine',
                containers: containersOn(m.name).length + ' seen by Vaier',
                services:   servicesOn(m.name).length + ' published from here',
                disk:       'Its filesystems, and how full they are',
                backup:     'The fleet backs up here',
            };
            inside.forEach((kid) => {
                grid.appendChild(card(iconFor(kid.kind, kid.name), kid.name, kid.kind !== 'shell',
                    NOTE[kid.name], () => go(['fleet', m.name, kid.name])));
            });
            body.appendChild(grid);
        }

        // Designation lives on the machine now, not on a page apart. Any machine can become the fleet's one
        // backup server — but only while none is yet: the machine that already is it wears a `backup` entry
        // above, and every other machine stays quiet, because there is exactly one and moving it means removing
        // it first. So this offer shows on every machine only in the bootstrapping moment before a server exists.
        if (!S.backupServer) {
            body.appendChild(section('Fleet backup'));
            body.appendChild(note('No machine is the fleet’s backup server yet. Make this one the place the '
                + 'fleet backs its archives up to.', false));
            const act = el('div', 'ex-lactions is-static');
            act.appendChild(selVerb('archive', 'Make this the fleet’s backup server', 'ex-btn',
                () => designateBackupServer(m)));
            body.appendChild(act);
        }

        // The SSH login Vaier holds for this machine — what opens its files, shell, disk and backups. Offered on
        // any machine Vaier would SSH (a server or a LAN server), never on a phone or laptop client.
        if (m.type !== 'MOBILE_CLIENT' && m.type !== 'WINDOWS_CLIENT') {
            body.appendChild(section('SSH credential'));
            const cred = el('div', 'ex-lactions is-static');
            cred.appendChild(selVerb('gear', m.sshAccess ? 'Edit SSH credential' : 'Set SSH credential', 'ex-btn',
                () => credentialDialog(m.name)));
            body.appendChild(cred);
        }

        // Removing a machine is destructive — its WireGuard peer (or LAN-server registration) is deleted and it
        // can no longer reach the VPN — so it takes the typed-name gate. The Vaier server is this machine; it is
        // never offered for removal.
        if (m.name !== VAIER_SERVER) {
            body.appendChild(section('This machine'));
            const rm = el('div', 'ex-lactions is-static');
            rm.appendChild(selVerb('trash', 'Remove machine', 'ex-btn is-danger', () => removeMachine(m)));
            body.appendChild(rm);
        }
        pane.appendChild(body);
    }

    // --- adding and removing a machine -----------------------------------------------------------------
    //
    // A machine is a WireGuard peer. Adding one asks a name and a type (and, for a server, the LAN behind it);
    // Vaier assigns the keys and the tunnel address, and hands back the config to install — shown once, because
    // the download endpoints are one-shot. Removing deletes the peer (or a LAN-server registration) behind the
    // typed-name gate. LAN servers are usually found by the scan, so the add form offers the peer kinds.

    function addMachine() {
        machineForm().then((body) => { if (body) createPeer(body); });
    }

    async function createPeer(body) {
        try {
            const res = await fetch('/vpn/peers', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body),
            });
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                toast(err.message || 'Vaier could not add that machine.');
                return;
            }
            const created = await res.json();
            await loadFleet();
            toast(created.name + ' added.');
            createResult(created);   // its config, shown once — download it now
        } catch (e) {
            toast('Vaier could not add that machine.');
        }
    }

    async function removeMachine(m) {
        const isPeer = S.peers.has(m.name);
        const ok = await confirmTyped('Remove ' + m.name + '?',
            'This deletes ' + m.name + ' from the fleet — its ' + (isPeer ? 'WireGuard peer' : 'registration')
            + ' is removed and it can no longer reach the VPN. This cannot be undone. Type the machine name to '
            + 'confirm.', m.name, 'Remove');
        if (!ok) return;
        const url = isPeer ? '/vpn/peers/' + encodeURIComponent(S.peers.get(m.name).id)
                           : '/lan-servers/' + encodeURIComponent(m.name);
        try {
            const res = await fetch(url, { method: 'DELETE' });
            if (!res.ok && res.status !== 204) { toast('Vaier could not remove ' + m.name + '.'); return; }
        } catch (e) { toast('Vaier could not remove ' + m.name + '.'); return; }
        await loadFleet();
        toast(m.name + ' removed.');
        go(['fleet']);
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

    // The add-machine form: name, type, and — only for a server — the LAN behind it. Resolves the POST body or
    // null. The tunnel address and the keys are Vaier's to assign, so they are never asked.
    function machineForm() {
        return new Promise((resolve) => {
            const scrim = el('div', 'ex-scrim is-on');
            const dialog = el('div', 'ex-dialog');
            const h = el('div', 'ex-dialog-title'); h.textContent = 'Add a machine';
            const sub = el('div', 'ex-dialog-body');
            sub.textContent = 'A new WireGuard peer. Vaier assigns its address and keys and hands you the config '
                + 'to install.';
            const form = el('div', 'ex-form');

            const field = (label, hint, control) => {
                const f = el('div', 'ex-field');
                const l = el('label'); l.textContent = label; f.append(l, control);
                if (hint) { const hn = el('div', 'ex-hint'); hn.textContent = hint; f.appendChild(hn); }
                return f;
            };
            const name = el('input', 'ex-input'); name.type = 'text'; name.placeholder = 'e.g. Roon server';
            name.autocomplete = 'off'; name.spellcheck = false;
            const type = el('select', 'ex-input');
            [['MOBILE_CLIENT', 'Phone / mobile'], ['WINDOWS_CLIENT', 'Windows client'],
             ['UBUNTU_SERVER', 'Ubuntu server'], ['WINDOWS_SERVER', 'Windows server']].forEach(([v, t]) => {
                const o = el('option'); o.value = v; o.textContent = t; type.appendChild(o);
            });
            const lanCidr = el('input', 'ex-input'); lanCidr.type = 'text';
            lanCidr.placeholder = 'e.g. 192.168.1.0/24'; lanCidr.autocomplete = 'off'; lanCidr.spellcheck = false;
            const lanField = field('LAN behind it', 'The subnet this server routes to, so the fleet can reach it.', lanCidr);
            const desc = el('input', 'ex-input'); desc.type = 'text'; desc.autocomplete = 'off';

            const isServer = () => SERVER_TYPES.has(type.value);
            const syncLan = () => { lanField.style.display = isServer() ? '' : 'none'; };
            type.onchange = syncLan;

            form.append(field('Name', null, name), field('Type', null, type), lanField,
                field('Description', 'Optional.', desc));

            const actions = el('div', 'ex-dialog-actions');
            const cancel = el('button', 'ex-btn'); cancel.textContent = 'Cancel';
            const ok = el('button', 'ex-btn is-accent'); ok.textContent = 'Add machine';
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
                close({ name: name.value.trim(), peerType: type.value,
                    lanCidr: isServer() ? lanCidr.value.trim() : '', lanAddress: '',
                    description: desc.value.trim() });
            };
            document.addEventListener('keydown', onKey);
            syncLan(); sync(); name.focus();
        });
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
    function credentialDialog(machine) {
        const scrim = el('div', 'ex-scrim is-on');
        const dialog = el('div', 'ex-dialog');
        const h = el('div', 'ex-dialog-title'); h.textContent = 'SSH credential — ' + machine;
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
        form.append(field('Username', null, username), field('Auth method', null, method), pwF, keyF, passF);
        const syncMethod = () => {
            const isKey = method.value === 'PRIVATE_KEY';
            pwF.style.display = isKey ? 'none' : '';
            keyF.style.display = isKey ? '' : 'none';
            passF.style.display = isKey ? '' : 'none';
        };
        method.onchange = syncMethod; syncMethod();

        const actions = el('div', 'ex-dialog-actions');
        const del = el('button', 'ex-btn is-danger'); del.textContent = 'Delete';
        del.style.display = 'none'; del.style.marginRight = 'auto';
        const cancel = el('button', 'ex-btn'); cancel.textContent = 'Cancel';
        const ok = el('button', 'ex-btn is-accent'); ok.textContent = 'Save';
        actions.append(del, cancel, ok);
        dialog.append(h, status, form, actions);
        scrim.appendChild(dialog); document.body.appendChild(scrim);

        const close = () => { scrim.remove(); document.removeEventListener('keydown', onKey); };
        const onKey = (e) => { if (e.key === 'Escape') close(); };
        cancel.onclick = close;
        scrim.onclick = (e) => { if (e.target === scrim) close(); };
        document.addEventListener('keydown', onKey);
        username.focus();

        fetch('/machines/' + encodeURIComponent(machine) + '/ssh-credential').then(async (r) => {
            if (r.status === 404) { status.textContent = 'No credential stored yet.'; return; }
            if (!r.ok) { status.textContent = 'Could not read the credential status.'; return; }
            const v = await r.json();
            username.value = v.username || '';
            method.value = v.authMethod || 'PASSWORD'; syncMethod();
            del.style.display = v.hasSecret ? '' : 'none';
            status.textContent = 'Stored for ' + (v.username || '') + ' · '
                + (v.authMethod || '').toLowerCase().replace('_', ' ') + '. Enter the secret again to replace it.';
        }).catch(() => { status.textContent = 'Could not reach Vaier.'; });

        ok.onclick = async () => {
            const secret = method.value === 'PRIVATE_KEY' ? keyArea.value : password.value;
            if (!username.value.trim()) { toast('Enter a username.'); return; }
            if (!secret.trim()) { toast('Enter the ' + (method.value === 'PRIVATE_KEY' ? 'private key' : 'password') + '.'); return; }
            ok.disabled = true;
            try {
                const r = await fetch('/machines/' + encodeURIComponent(machine) + '/ssh-credential', {
                    method: 'PUT', headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ username: username.value.trim(), authMethod: method.value,
                        secret: secret, passphrase: passphrase.value || null }),
                });
                if (!r.ok) { const e = await r.json().catch(() => ({})); toast(e.message || 'Could not save the credential.'); ok.disabled = false; return; }
                toast('SSH credential saved for ' + machine + '.');
                close(); await loadFleet(); render();
            } catch (e) { toast('Could not save the credential.'); ok.disabled = false; }
        };

        del.onclick = async () => {
            const sure = await confirmModal('Delete the SSH credential for ' + machine + '?',
                'Vaier forgets the login it holds for ' + machine + '. Its files, shell, disk and backups go dark '
                + 'until you set one again.', 'Delete');
            if (!sure) return;
            try {
                const r = await fetch('/machines/' + encodeURIComponent(machine) + '/ssh-credential', { method: 'DELETE' });
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
        const machine = S.path[1];
        const found = containersOn(machine);
        pane.appendChild(paneHead(machine + ' / containers', true,
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

        const rows = document.createElement('div');
        rows.className = 'ex-listing is-wide';
        rows.appendChild(listHead(['Name', 'Image', 'State']));
        found.forEach((c) => {
            rows.appendChild(listRow(
                'box', c.containerName, () => go(['fleet', machine, 'containers', c.containerName]),
                [c.image || '—', c.state || 'unknown'],
                c.state === 'running' ? 'OK' : 'DOWN'));
        });
        body.appendChild(rows);
        pane.appendChild(body);
    }

    function renderContainer(pane) {
        const machine = S.path[1];
        const name = S.path[3];
        const c = containersOn(machine).find((x) => x.containerName === name);
        if (!c) {
            return pane.appendChild(note('Vaier no longer sees a container by that name on ' + machine + '.',
                true));
        }

        pane.appendChild(paneHead(name, true, machine));
        const body = document.createElement('div');
        body.className = 'ex-pane-body';

        const ports = (c.ports || []).map((p) => (p.publicPort ? p.publicPort + '→' : '')
            + p.privatePort + '/' + (p.type || 'tcp')).join('  ');

        body.appendChild(kv([
            ['Image', c.image],
            ['Version', c.version],
            ['State', c.state],
            ['Ports', ports],
            ['Networks', (c.networks || []).join(', ')],
            ['Container id', (c.containerId || '').slice(0, 12)],
        ]));

        body.appendChild(note('This is everything Vaier knows about the container: it reads Docker, and it '
            + 'has no endpoint to control one. Nothing here can start or stop it, and Vaier will not show '
            + 'you a control it cannot honour. Use the machine’s shell for that.', false));
        pane.appendChild(body);
    }

    // --- services: a route, a machine and a DNS record are one thing ------------------------------------

    function renderServices(pane) {
        const machine = S.path[1];
        const found = servicesOn(machine);
        const open = candidatesOn(machine).filter((c) => !c.ignored);
        const hidden = candidatesOn(machine).filter((c) => c.ignored);
        pane.appendChild(paneHead(machine + ' / services', true,
            found.length + (found.length === 1 ? ' published service' : ' published services')));

        const body = el('div', 'ex-pane-body');

        // What is published — the live routes, each openable, each unpublishable.
        body.appendChild(section('Published'));
        if (!found.length) {
            body.appendChild(note('Nothing is published from this machine yet.', false));
        } else {
            const rows = el('div', 'ex-listing is-wide');
            rows.appendChild(listHead(['Published at', 'Backend', 'State']));
            found.forEach((s) => rows.appendChild(listRow('route', s.dnsAddress || serviceName(s),
                () => go(['fleet', machine, 'services', serviceName(s)]),
                [(s.hostAddress || '') + (s.hostPort ? ':' + s.hostPort : ''), s.state || 'UNKNOWN'], s.state)));
            body.appendChild(rows);
        }

        // Container ports Vaier could put on the internet — publish one, or ignore it so it stops being offered.
        if (open.length) {
            body.appendChild(section('Ready to publish'));
            open.forEach((c) => body.appendChild(candidateRow(machine, c, [
                selVerb('route', 'Publish', 'ex-btn is-accent', () => publishCandidate(machine, c)),
                selVerb('cross', 'Ignore', 'ex-btn', () => ignoreCandidate(machine, c)),
            ])));
        }
        if (hidden.length) {
            body.appendChild(section('Ignored'));
            hidden.forEach((c) => body.appendChild(candidateRow(machine, c, [
                selVerb('check', 'Unignore', 'ex-btn', () => unignoreCandidate(machine, c)),
            ])));
        }
        // A service that isn't a container Vaier discovered — a LAN app, a device's own web page — is published
        // by hand: name a port on this machine and Vaier makes the route and the DNS record just the same.
        body.appendChild(section('Publish a service by hand'));
        const manual = el('div', 'ex-lactions is-static');
        manual.appendChild(selVerb('route', 'Publish a service', 'ex-btn', () => lanPublish(machine)));
        body.appendChild(manual);
        pane.appendChild(body);
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

    async function reloadServices(machine) {
        await loadServices();
        go(['fleet', machine, 'services']);   // stay on the entry; it repaints from the fresh data
    }

    // Publish a container port: the only choices that are the operator's are the subdomain (defaulted from the
    // container name by the domain) and whether it sits behind login. Everything else — the DNS record, the
    // Traefik route — Vaier does. So the form is two fields.
    function publishCandidate(machine, c) {
        publishForm(c).then((body) => {
            if (!body) return;
            saveJson('/published-services/publish', 'POST', {
                address: c.address, port: c.port, subdomain: body.subdomain, requiresAuth: body.requiresAuth,
                rootRedirectPath: c.rootRedirectPath || '', directUrlDisabled: false, pathPrefix: '',
            }, 'Publishing ' + body.subdomain + '…', () => reloadServices(machine),
               'Could not publish that.');
        });
    }

    function ignoreCandidate(machine, c) {
        saveJson('/published-services/publishable/ignore', 'POST', { key: c.ignoreKey },
            'Ignored ' + c.containerName + '.', () => reloadServices(machine), 'Could not ignore that.');
    }

    function unignoreCandidate(machine, c) {
        saveJson('/published-services/publishable/unignore', 'POST', { key: c.ignoreKey },
            c.containerName + ' can be published again.', () => reloadServices(machine), 'Could not unignore that.');
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
            sub.textContent = 'Put ' + c.address + ':' + c.port + ' on the internet. Vaier makes the DNS record '
                + 'and the route; you choose the name and whether it needs a login.';
            const form = el('div', 'ex-form');
            const subF = el('div', 'ex-field');
            const subL = el('label'); subL.textContent = 'Subdomain';
            const subIn = el('input', 'ex-input'); subIn.type = 'text'; subIn.value = c.suggestedSubdomain || '';
            subIn.autocomplete = 'off'; subIn.spellcheck = false;
            subF.append(subL, subIn);
            const authRow = el('label', 'ex-check-row');
            const auth = el('input'); auth.type = 'checkbox'; auth.checked = true;
            const atxt = el('span'); atxt.textContent = 'Require a login to reach it';
            authRow.append(auth, atxt);
            form.append(subF, authRow);

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
            ok.onclick = () => { if (subIn.value.trim()) close({ subdomain: subIn.value.trim(), requiresAuth: auth.checked }); };
            document.addEventListener('keydown', onKey);
            sync(); subIn.focus();
        });
    }

    // Publish a service by hand — a port on the machine that Vaier did not find as a container. The machine is
    // the context; the operator names the subdomain, the port, whether it speaks http or https, and whether it
    // needs a login. Vaier makes the DNS record and the route.
    function lanPublish(machine) {
        lanPublishForm(machine).then((body) => {
            if (!body) return;
            saveJson('/published-services/lan', 'POST', {
                subdomain: body.subdomain, machineName: machine, port: body.port, protocol: body.protocol,
                requireAuth: body.requireAuth, directUrlDisabled: false, rootRedirectPath: '', pathPrefix: '',
            }, 'Publishing ' + body.subdomain + '…', () => reloadServices(machine), 'Could not publish that.');
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
                protocol: protocol.value, requireAuth: auth.checked }); };
            document.addEventListener('keydown', onKey);
            sync(); subIn.focus();
        });
    }

    function renderService(pane) {
        const machine = S.path[1];
        const s = servicesOn(machine).find((x) => serviceName(x) === S.path[3]);
        if (!s) return pane.appendChild(note('That service is no longer published from ' + machine + '.',
            true));

        const head = paneHead(s.dnsAddress || serviceName(s), true, machine);
        const actions = document.createElement('div');
        actions.className = 'ex-pane-actions';
        const del = document.createElement('button');
        del.className = 'ex-btn is-danger';
        del.textContent = 'Unpublish';
        del.onclick = () => unpublish(s, machine);
        actions.appendChild(del);
        head.appendChild(actions);
        pane.appendChild(head);

        const body = document.createElement('div');
        body.className = 'ex-pane-body';

        const groups = S.access[s.dnsAddress] || [];
        body.appendChild(kv([
            ['DNS record', s.dnsAddress],
            ['DNS state', s.dnsState],
            ['Route', s.state],
            ['Backend', (s.hostAddress || '') + (s.hostPort ? ':' + s.hostPort : '')],
            ['Path prefix', s.pathPrefix],
            ['Container image', s.image],
            ['Version', s.version],
            ['Auth', s.authMode === 'social' ? 'Social login' : 'Open to anyone'],
            ['Allowed groups', groups.length ? groups.join(', ') : 'Any signed-in user'],
        ]));

        // The point of the single namespace, said plainly. These three are not three things that happen to
        // share a name — they are one service, and when one of them is wrong the service is down.
        body.appendChild(note('A published service is one thing with three homes: a container on '
            + machine + ', a route through Traefik, and a DNS record at ' + (s.dnsAddress || 'its name')
            + '. Unpublishing removes the route and the DNS record. The container keeps running — the '
            + 'machine that hosts it does not notice.', false));
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
        const machine = S.path[1];
        pane.appendChild(paneHead(machine + ' / disk', true, 'Filesystems'));

        const body = document.createElement('div');
        body.className = 'ex-pane-body';
        pane.appendChild(body);

        if (!S.disks.has(machine)) loadDisk(machine);      // it re-renders when it lands

        const held = S.disks.get(machine);
        if (!held || held.state === 'loading') {
            return body.appendChild(note('Reading the disks on ' + machine + '…', false));
        }
        if (held.state === 'error') return body.appendChild(note(held.error, true));

        held.filesystems.forEach(fs => body.appendChild(filesystemBlock(fs, machine)));

        body.appendChild(note('Vaier reads these with df over SSH, on a schedule, and emails the admins when '
            + 'a watched filesystem crosses its threshold. A filesystem with no threshold of its own is '
            + 'judged against the fleet-wide one, set on Settings. Mute the ones that are full by design — '
            + 'a DSM system partition sits near-full forever — but mute them deliberately: everything Vaier '
            + 'has not been told about is watched.', false));
    }

    // One filesystem: what it is, how full, what it is judged against, and whether Vaier is watching at all.
    // Mount point and device are coordinates, so they are mono — the shell's one type rule.
    function filesystemBlock(fs, machine) {
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
        block.appendChild(watchControl(fs, machine));
        return block;
    }

    // The mute switch and the threshold. Both write to PUT /machines/{machine}/disk/watch — the mount point
    // travels in the body, because a mount point is full of slashes and a path variable carrying them is an
    // encoding bug waiting to happen.
    function watchControl(fs, machine) {
        const ctl = document.createElement('div');
        ctl.className = 'ex-fs-ctl';

        const watchLabel = document.createElement('label');
        const watch = document.createElement('input');
        watch.type = 'checkbox';
        watch.checked = fs.watched;
        watch.addEventListener('change',
            () => saveWatch(machine, fs.mountPoint, watch.checked, fs.thresholdPercent));
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
            () => saveWatch(machine, fs.mountPoint, fs.watched, Number(thresh.value)));
        const pct = document.createElement('span');
        pct.textContent = '%';
        threshLabel.append(threshText, thresh, pct);
        ctl.appendChild(threshLabel);
        return ctl;
    }

    // Write the watch, then re-read the machine's disks. The re-read is the point: the breach verdict is the
    // domain's, so a new threshold has to come back FROM the server rather than being recomputed here. The
    // email and this pane can never disagree, because only one of them ever decides.
    async function saveWatch(machine, mountPoint, watched, thresholdPercent) {
        try {
            const res = await fetch('/machines/' + encodeURIComponent(machine) + '/disk/watch', {
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
        S.disks.delete(machine);
        loadDisk(machine);
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
    // DELETE /published-services/{dnsName}. It tears down a Traefik route and a DNS record, so it asks first.
    async function unpublish(s, machine) {
        const label = s.dnsAddress || serviceName(s);
        if (!confirm('Unpublish ' + label + '?\n\nThis removes its Traefik route and its DNS record. '
            + 'The container on ' + machine + ' keeps running.')) return;

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
        go(['fleet', machine, 'services']);
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

    function listRow(icon, name, onClick, meta, state) {
        const row = document.createElement('div');
        row.className = 'ex-lrow';

        const btn = document.createElement('button');
        btn.className = 'ex-lname';
        btn.innerHTML = svg(icon, 'ex-ico');
        const nm = document.createElement('span');
        nm.className = 'ex-nm';
        nm.textContent = name;
        btn.appendChild(nm);
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
        return row;
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
    // they live here, because designating the backup server is now a thing you do to a machine in the tree.

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
        const machine = S.path[1];
        const s = S.backupServer;
        const isServer = s && s.machineName === machine;
        const jobs = jobsOn(machine);
        if (!isServer && !jobs.length) {
            return pane.appendChild(note('This machine has no part in fleet backup — it is not the backup '
                + 'server, and no job backs it up.', true));
        }
        pane.appendChild(paneHead(machine + ' / backup', true,
            isServer ? 'The fleet’s backup server' : 'How this machine is backed up'));
        const body = el('div', 'ex-pane-body');
        if (isServer) renderServerBackup(body, machine, s);
        if (jobs.length) renderJobsBackup(body, machine, jobs);
        pane.appendChild(body);
    }

    // The machine that hosts the fleet's borg: its coordinates, the repositories on it (each an entry of its
    // own), and the identity actions. The operations that poll for an outcome stay on the Backups bridge.
    function renderServerBackup(body, machine, s) {
        body.appendChild(kv([
            ['Server name', s.name],
            ['Reached at', s.host + ':' + s.sshPort],
            ['Borg user', s.borgUser],
            ['Base repo path', '/' + s.baseRepoPath],
            ['Server data path', s.serverDataPath],
            ['Stood up by Vaier', s.managed ? 'Yes' : 'No — adopted'],
        ]));

        // The repositories that live here — each an entry of its own (open it for its path, archives and the
        // Edit/Delete of it). This is where a repository is made now, not on the Backups page.
        body.appendChild(section('Repositories'));
        const repos = reposOn(s);
        if (!repos.length) {
            body.appendChild(note('No repositories on this server yet. Add the first one below.', false));
        } else {
            const list = el('div', 'ex-brepos');
            repos.forEach((r) => {
                const row = el('div', 'ex-brepo');
                const nm = el('button', 'ex-brepo-name is-link');
                nm.textContent = r.name;
                nm.onclick = () => go(['fleet', machine, 'backup', r.name]);
                const path = el('span', 'ex-brepo-path');
                path.textContent = r.repoPath || '';
                row.append(nm, path);
                list.appendChild(row);
            });
            body.appendChild(list);
        }
        const repoActs = el('div', 'ex-lactions is-static');
        repoActs.appendChild(selVerb('box', 'New repository', 'ex-btn', () => newRepository(s)));
        body.appendChild(repoActs);

        // Actions: identity only. The operations that poll for an outcome live on the Backups bridge.
        body.appendChild(section('Designation'));
        const acts = el('div', 'ex-lactions is-static');
        acts.appendChild(selVerb('gear', 'Edit coordinates', 'ex-btn', () => editBackupServer(s)));
        acts.appendChild(selVerb('trash', 'Remove designation', 'ex-btn is-danger', () => removeBackupServer(s)));
        body.appendChild(acts);

        const bridge = el('div', 'ex-note');
        bridge.appendChild(document.createTextNode('Provision the server and authorize clients on the '));
        const link = el('a', 'ex-link');
        link.href = '/backups.html';
        link.textContent = 'Backups page';
        bridge.appendChild(link);
        bridge.appendChild(document.createTextNode('.'));
        body.appendChild(bridge);
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
            if (body) putBackupServer(serverNameFor(m.name), m.name, body, 'designated');
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
            if (body) putBackupServer(s.name, s.machineName, body, 'saved');
        });
    }

    async function putBackupServer(name, machineName, body, verbedPast) {
        try {
            const res = await fetch('/backup-servers/' + encodeURIComponent(name), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ machineName: machineName, host: body.host, sshPort: body.sshPort,
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
            go(['fleet', machineName, 'backup']);   // the role now has a coordinate — stand on it
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
            + 'borg server on ' + s.machineName + ' or its repositories — it forgets that this machine is the '
            + 'backup server. Type the server name to confirm.', s.name, 'Remove designation');
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
        const machine = s.machineName;
        await loadBackup();
        toast('Backup server removed.');
        go(['fleet', machine]);   // the `backup` entry is gone; fall back to the machine
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
                field('Base repo path', 'No leading slash — repositories derive under this.', baseRepoPath),
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
    // single calls, none of which poll, so the whole of repository management lives here rather than on the
    // Backups page. The archives are read when the repository is looked at (borg list runs on a job's host),
    // never polled — a nightly archive lands on a reload, not under the cursor.

    function renderRepo(pane) {
        const machine = S.path[1];
        const name = S.path[3];
        const s = S.backupServer;
        const r = reposOn(s).find((x) => x.name === name);
        if (!r) return pane.appendChild(note('That repository is no longer on this server.', true));

        pane.appendChild(paneHead(name, true, 'Backup repository on ' + (s ? s.name : '')));
        const body = el('div', 'ex-pane-body');
        body.appendChild(kv([
            ['Repository path', r.repoPath],
            ['Append-only', r.appendOnly ? 'Yes' : 'No'],
            ['Passphrase', r.hasPassphrase ? 'Stored in the vault' : 'None'],
        ]));

        body.appendChild(section('Repository'));
        const acts = el('div', 'ex-lactions is-static');
        acts.appendChild(selVerb('gear', 'Edit', 'ex-btn', () => editRepository(r)));
        acts.appendChild(selVerb('trash', 'Delete', 'ex-btn is-danger', () => deleteRepository(r)));
        body.appendChild(acts);

        // The archives inside, read on view. A repository nothing targets has no host to read it from, so the
        // answer is an empty list, not an error — the note says which case it is.
        body.appendChild(section('Archives'));
        const held = S.repoArchives.get(name);
        if (!held || held.state === 'loading') {
            body.appendChild(note('Reading the archives in this repository…', false));
            loadRepoArchives(name);
        } else if (held.state === 'error') {
            body.appendChild(note(held.error, true));
        } else if (!held.list.length) {
            body.appendChild(note('No archives to show. Nothing has been backed up into this repository yet, or '
                + 'no job targets it — borg list runs on a job’s host, so without one Vaier has nowhere to read '
                + 'it from.', false));
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
                    error: b.message || 'Vaier could not read this repository’s archives.' });
            }
        } catch (e) {
            S.repoArchives.set(name, { state: 'error', list: [],
                error: 'Vaier could not read this repository’s archives.' });
        }
        render();
    }

    // Make a repository on the server. The server is the context, so the form asks only the repository's own
    // name, an optional path override (it derives under the server's base path when blank), append-only, and a
    // passphrase — pre-filled with a strong generated one, shown once, because a repository that cannot be
    // unlocked is a repository that cannot be restored. Vaier stores it encrypted in the vault regardless.
    function newRepository(s) {
        repoForm({
            title: 'New repository on ' + s.name,
            intro: 'A named borg store on ' + s.name + '. Its path derives under the server’s base path unless '
                + 'you override it.',
            existing: null,
            confirmLabel: 'Create',
        }).then((body) => {
            if (body) putRepository(body.name, s.name, body, 'created');
        });
    }

    function editRepository(r) {
        repoForm({
            title: 'Edit ' + r.name,
            intro: 'The path override and append-only setting of this repository. Leave the passphrase blank to '
                + 'keep the stored one.',
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
                toast(err.message || 'Vaier could not save the repository.');
                return;
            }
            await loadBackup();
            S.repoArchives.delete(name);   // a re-saved repo re-reads its archives fresh
            toast('Repository ' + verbedPast + '.');
            go(['fleet', S.path[1], 'backup', name]);
        } catch (e) {
            toast('Vaier could not save the repository.');
        }
    }

    // Deleting a repository forgets it in Vaier — it does not erase the borg store on the server. That is the
    // safe default (the archives on disk are the whole point of a backup), so it takes a plain confirm rather
    // than the typed-name gate a file delete does.
    async function deleteRepository(r) {
        const ok = await confirmModal('Delete repository ' + r.name + '?',
            'Vaier forgets this repository — it does not erase the borg store on ' + r.serverName + ' or the '
            + 'archives in it. Any backup job pointing at it will have nowhere to write until you re-add it.',
            'Delete');
        if (!ok) return;
        try {
            const res = await fetch('/backup-repositories/' + encodeURIComponent(r.name), { method: 'DELETE' });
            if (!res.ok && res.status !== 404) {
                toast('Vaier could not delete the repository.');
                return;
            }
        } catch (e) {
            toast('Vaier could not delete the repository.');
            return;
        }
        const machine = S.path[1];
        S.repoArchives.delete(r.name);
        await loadBackup();
        toast('Repository deleted.');
        go(['fleet', machine, 'backup']);   // back up to the server
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
                field('Passphrase', creating ? 'Save this — it unlocks the repository. Vaier also keeps it '
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
    // over SSH. Rejection sampling keeps the distribution uniform (no modulo bias). The same generator the
    // Backups page used before repository creation moved here.
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
    // `run-settled` on the backups stream when borg finishes (watchBackups). The Backups page keeps the guided
    // provisioning that first gets a host ready — installing borg, the root grant — a bridge for now.

    const RUN_DOT = { SUCCESS: 'is-up', WARNING: 'is-degraded', FAILED: 'is-down',
                      RUNNING: 'is-idle', UNKNOWN: 'is-idle' };

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
    function renderJobsBackup(body, machine, jobs) {
        if (S.preparing.has(machine)) {
            const prep = el('div', 'ex-runline');
            prep.textContent = 'Getting this machine ready to back up — installing borg and trusting its key…';
            body.appendChild(prep);
        }
        jobs.forEach((job) => renderOneJob(body, machine, job, jobs.length > 1));
    }

    function renderOneJob(body, machine, job, named) {
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
                nm.onclick = () => openTo(['fleet', machine, 'files'].concat(sp.split('/').filter(Boolean)));
                row.appendChild(nm);
                list.appendChild(row);
            });
            body.appendChild(list);
        }

        body.appendChild(section('Schedule'));
        const sched = el('div', 'ex-runline');
        sched.textContent = 'Runs every night. A failed run emails the admins.';
        body.appendChild(sched);

        // The last run, read on view and refreshed when the backend says it settled.
        const held = S.jobRuns.get(job.name);
        if (held === undefined) loadJobRun(job.name);
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
        // A failed OR a warning run says why, right here: the skipped-file and error lines the backend pulled
        // out of borg's own output (a domain decision — BackupRun.diagnostics). A warning reads amber and a
        // failure red, the same two colours the run's dot uses, so "what went wrong" is never a mystery.
        if (held && held.state === 'ready' && held.run.diagnostics
            && (held.run.status === 'FAILED' || held.run.status === 'WARNING')) {
            const n = note(held.run.diagnostics, held.run.status === 'FAILED');
            if (held.run.status === 'WARNING') n.classList.add('is-warn');
            body.appendChild(n);
        }

        const running = held && held.state === 'ready' && held.run.status === 'RUNNING';
        const acts = el('div', 'ex-lactions is-static');
        const run = selVerb('refresh', running ? 'Backing up…' : 'Back up now', 'ex-btn is-accent',
            () => runNow(job));
        if (running) run.disabled = true;
        acts.append(run);
        acts.appendChild(selVerb('cross', 'Stop backing up this machine', 'ex-btn is-danger',
            () => stopMachineBackup(job)));
        body.appendChild(acts);
    }

    // Open the tree to a path and select it — used by the protected-path links, which jump you into the file
    // browser where the backup set is actually edited.
    function openTo(path) {
        for (let i = 1; i < path.length; i++) S.open.add(key(path.slice(0, i + 1)));
        go(path);
    }

    // The last run, read on view (404 means it has never run) and again on the run-settled push. Not skipped on
    // 'ready' — the push calls this to replace a RUNNING run with its outcome — but skipped while a read is in
    // flight, and only ever the latest run, so a machine's whole history is not dragged into the browser.
    async function loadJobRun(name) {
        const held = S.jobRuns.get(name);
        if (held && held.state === 'loading') return;
        S.jobRuns.set(name, { state: 'loading', run: held ? held.run : null });
        try {
            const res = await fetch('/backup-jobs/' + encodeURIComponent(name) + '/runs', { cache: 'no-store' });
            if (res.status === 404) {
                S.jobRuns.set(name, { state: 'none', run: null });
            } else if (res.ok) {
                S.jobRuns.set(name, { state: 'ready', run: await res.json() });
            } else {
                S.jobRuns.set(name, { state: 'error', run: null });
            }
        } catch (e) {
            S.jobRuns.set(name, { state: 'error', run: null });
        }
        render();
    }

    // Start a run now. The POST returns the RUNNING run at once; the outcome arrives on the backups stream
    // (watchBackups) as run-settled, so this shows RUNNING and never waits or polls for the end.
    async function runNow(job) {
        try {
            const res = await fetch('/backup-jobs/' + encodeURIComponent(job.name) + '/runs', { method: 'POST' });
            if (!res.ok) {
                toast(res.status === 404
                    ? 'Cannot start the backup — its repository or the server is missing.'
                    : 'Vaier could not start the backup.');
                return;
            }
            S.jobRuns.set(job.name, { state: 'ready', run: await res.json() });   // RUNNING
            toast('Backing up ' + job.machineName + '…');
            render();
        } catch (e) {
            toast('Vaier could not start the backup.');
        }
    }

    // Enable/disable rides on the whole job spec — the flag has no endpoint of its own, so a toggle re-saves the
    // job with it flipped. Everything else is carried through unchanged.
    // Stop backing this machine up entirely — Vaier forgets the job and its schedule. It never touches the
    // archives already made; they stay on the server. (Removing individual paths is done in the file browser;
    // this is the one-click "stop everything".) A plain confirm: the data you'd hate to lose is not at risk.
    async function stopMachineBackup(job) {
        const ok = await confirmModal('Stop backing up ' + job.machineName + '?',
            'Vaier stops backing up ' + job.machineName + ' — nothing new is protected and the nightly run '
            + 'stops. The archives already made are untouched; they stay on the server.', 'Stop backing up');
        if (!ok) return;
        try {
            const res = await fetch('/backup-jobs/' + encodeURIComponent(job.name), { method: 'DELETE' });
            if (!res.ok && res.status !== 404) { toast('Vaier could not stop the backup.'); return; }
        } catch (e) { toast('Vaier could not stop the backup.'); return; }
        const machine = job.machineName;
        S.jobRuns.delete(job.name);
        await loadBackup();
        toast('Stopped backing up ' + machine + '.');
        go(['fleet', machine]);   // the `backup` entry is gone now; the machine always stands
    }

    // The backups stream — the fourth and last the shell holds. It carries a run's outcome: when a launched
    // backup settles, the backend pushes run-settled { jobName, status }, and we re-read exactly that job's last
    // run. No polling: the browser waits to be told, the same discipline the transfers and services streams keep.
    function watchBackups() {
        const events = new EventSource('/backup-jobs/events');
        events.addEventListener('run-settled', (e) => {
            const d = JSON.parse(e.data);
            if (S.backupJobs.some((j) => j.name === d.jobName)) loadJobRun(d.jobName);
        });
        // The other half of the readying we started under a first back-up: the detached borg install has
        // finished on the host. Clear the "getting ready" state and say how it went — no polling, we were told.
        events.addEventListener('prepare-client-settled', (e) => {
            const d = JSON.parse(e.data);   // { machineName, state }
            if (!S.preparing.has(d.machineName)) return;
            S.preparing.delete(d.machineName);
            toast(d.state === 'SUCCESS'
                ? d.machineName + ' is ready — its backup will run tonight, or now if you like.'
                : 'Vaier could not finish getting ' + d.machineName + ' ready. Check its readiness on the '
                  + 'Backups page.');
            loadBackup();
            render();
        });
    }

    // The terminal itself is in the dock, not in the pane: the tree, the address bar and the shell are all on
    // screen at once, which is the whole point of moving the dock in here.
    function renderShell(pane) {
        pane.appendChild(paneHead(S.path[1] + ' / shell', true, 'A terminal on this machine'));
        const body = document.createElement('div');
        body.className = 'ex-pane-body';
        body.appendChild(note('The shell is open in the dock below. It stays open while you move around the '
            + 'tree — nothing here is loaded in a frame, so no navigation can tear the session down.', false));
        pane.appendChild(body);
    }

    function renderBridge(pane) {
        const bridge = bridgeOf(S.path);
        pane.className = 'ex-pane is-bridged';
        // TRANSITIONAL (#323): the page as it is today, framed whole, until this section becomes real entries
        // in the tree. Delete with the slice that ports it.
        const frame = document.createElement('iframe');
        frame.className = 'ex-bridge';
        frame.src = bridge.page;
        frame.title = bridge.label;
        pane.appendChild(frame);
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
    // lives here now, not on the Backups page: it is the fleet-wide "when", the one backup knob that is the
    // operator's to set — everything else about a backup is Vaier's.

    async function loadSettings() {
        if (S.settings.state === 'loading') return;
        S.settings = { ...S.settings, state: 'loading' };
        try {
            const [cfg, ver, lic] = await Promise.all([
                fetch('/settings/config', { cache: 'no-store' }).then((r) => (r.ok ? r.json() : null)),
                fetch('/settings/version').then((r) => (r.ok ? r.json() : {})).catch(() => ({})),
                fetch('/license').then((r) => (r.ok ? r.json() : {})).catch(() => ({})),
            ]);
            S.settings = { state: cfg ? 'ready' : 'error', config: cfg,
                version: (ver || {}).version || '', edition: (lic || {}).edition || '' };
        } catch (e) {
            S.settings = { state: 'error', config: null, version: '', edition: '' };
        }
        render();
    }

    // Save one section: PUT/POST the body, then write the outcome into that section's own note. It never
    // re-reads the whole config — a saved section already shows its new value.
    async function saveSetting(url, method, body, noteEl, okText) {
        noteEl.className = 'ex-set-note';
        noteEl.textContent = 'Saving…';
        try {
            const res = await fetch(url, { method: method, headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body) });
            if (res.ok) {
                noteEl.className = 'ex-set-note is-ok';
                noteEl.textContent = okText || 'Saved.';
            } else {
                const err = await res.json().catch(() => ({}));
                noteEl.className = 'ex-set-note is-err';
                noteEl.textContent = err.message || 'Could not save.';
            }
        } catch (e) {
            noteEl.className = 'ex-set-note is-err';
            noteEl.textContent = 'Could not save.';
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

        // --- AWS credentials: only meaningful in Route53 DNS mode ---
        if (c.dnsProvider === 'ROUTE53') {
            const aws = sectionForm('AWS credentials');
            const cur = input(c.awsKeyHint, 'Not configured'); cur.disabled = true;
            const newKey = input('', 'AKIA…'); const newSecret = input('', 'Secret', 'password');
            aws.append(field('Current key', null, cur), field('New key', null, newKey),
                field('New secret', 'Both are needed to change the credentials.', newSecret));
            saveRow(aws, 'Save credentials', (n) => {
                if (!newKey.value.trim() || !newSecret.value.trim()) {
                    n.className = 'ex-set-note is-err'; n.textContent = 'Both key and secret are required.'; return;
                }
                saveSetting('/settings/aws', 'PUT', { awsKey: newKey.value.trim(), awsSecret: newSecret.value.trim() },
                    n, 'Credentials saved.').then(() => { newKey.value = ''; newSecret.value = ''; });
            });
        }

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

        // --- Disk monitoring ---
        const disk = sectionForm('Disk monitoring');
        const thresh = input(c.diskMonitorThresholdPercent || 85, '85', 'number');
        disk.appendChild(field('Alert above', 'Percent full. Vaier emails the admins when a filesystem crosses this.', thresh));
        saveRow(disk, 'Save threshold', (n) => {
            const t = parseInt(thresh.value, 10);
            if (!t || t < 1 || t > 99) { n.className = 'ex-set-note is-err'; n.textContent = 'Enter 1–99.'; return; }
            saveSetting('/settings/disk-monitor', 'PUT', { diskMonitorThresholdPercent: t }, n, 'Threshold saved.');
        });

        // --- About: read-only facts ---
        body.appendChild(section('About'));
        body.appendChild(kv([
            ['Version', S.settings.version],
            ['Edition', S.settings.edition],
            ['Domain', c.domain],
            ['Let’s Encrypt email', c.acmeEmail],
            ['DNS', c.dnsProvider],
        ]));

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

    // The time rail: one stop per archive, laid out newest-nearest-Now so the whole shell reads left-to-past.
    // It is the only surface that sets the past into motion — every stop's click routes through toArchive,
    // and Now through toPresent, so the light and the reads move together and nowhere else. A machine with no
    // archives grows no rail (an empty fragment appends nothing), so the file browser is untouched where
    // there is no past to show.
    function renderRail(machine) {
        const held = S.archives.get(machine);
        if (!held || held.state !== 'ready' || !held.list.length) return document.createDocumentFragment();

        const rail = el('div', 'ex-rail');

        const now = el('button', 'ex-rail-now' + (S.at ? '' : ' is-on'));
        now.textContent = 'Now';
        now.title = 'The live filesystem';
        now.onclick = toPresent;
        rail.appendChild(now);

        const track = el('div', 'ex-rail-track');
        const stops = el('div', 'ex-rail-stops');
        held.list.forEach((a) => {
            const stop = el('button', 'ex-rail-stop' + (a.id === S.at ? ' is-on' : ''));
            stop.title = archiveLabel(a);
            stop.setAttribute('aria-label', 'Backup from ' + archiveLabel(a));
            stop.onclick = () => toArchive(machine, a.id);
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
        const machine = S.path[1];
        loadArchives(machine);                    // fills the rail; not awaited — it re-renders when it lands
        const path = remotePath(S.path);          // null until the machine has said where its tree begins

        // The path lives in the address bar and nowhere else — the head names the machine and how much is
        // here, not the location again. A refresh re-reads this one directory over SFTP: the fleet changes
        // under Vaier (a Transfer just landed, a shell just wrote a file), and the cache is otherwise sticky.
        const loaded = S.dirs.get(dirKey(machine, path, S.at));
        const count = loaded && loaded.state === 'ready' ? loaded.entries.length : null;
        const sub = count == null ? null : count + (count === 1 ? ' item' : ' items');
        const head = paneHead(machine, true, sub);
        const actions = document.createElement('div');
        actions.className = 'ex-pane-actions';
        const refresh = el('button', 'ex-iconbtn');
        refresh.innerHTML = svg('refresh', 'ex-ico');
        refresh.title = 'Refresh';
        refresh.setAttribute('aria-label', 'Refresh this folder');
        refresh.onclick = () => refreshDir(machine, path);
        actions.appendChild(refresh);
        head.appendChild(actions);
        pane.appendChild(head);

        const body = document.createElement('div');
        body.className = 'ex-pane-body';
        body.appendChild(renderRail(machine));    // above the listing; nothing when the machine has no archives
        body.appendChild(renderPasteBar(machine, path));   // "Paste here", only in the present with a full Clipboard
        const rows = document.createElement('div');
        rows.className = 'ex-listing';
        body.appendChild(rows);
        pane.appendChild(body);

        if (dirStateOf(S.path) === 'unread') readDir(machine, path, S.at);   // it re-renders when it lands

        const entry = S.dirs.get(dirKey(machine, path, S.at));
        if (!entry || entry.state === 'loading') {
            return rows.appendChild(note('Listing ' + (path == null ? 'the file root' : path)
                + ' on ' + machine + '…', false));
        }
        // The server's own sentence, verbatim — "Not allowed to read /root as geir." says more than any
        // status code could.
        if (entry.state === 'error') return rows.appendChild(note(entry.error, true));

        const result = { entries: entry.entries };

        // A toolbar rises when anything is ticked (Gmail's move): the bulk verbs act on the whole selection
        // at once. Above the listing so it does not shift the rows.
        const selBar = renderSelectionBar(machine, result.entries);
        if (selBar) body.insertBefore(selBar, rows);

        const lhead = document.createElement('div');
        lhead.className = 'ex-lhead';
        // Select-all: on when every row is ticked, dashed when only some are.
        const allOn = result.entries.length > 0 && result.entries.every((e) => S.sel.has(e.path));
        const someOn = result.entries.some((e) => S.sel.has(e.path));
        lhead.appendChild(checkbox(allOn, someOn && !allOn, () => {
            if (allOn) result.entries.forEach((e) => S.sel.delete(e.path));
            else result.entries.forEach((e) => S.sel.add(e.path));
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
            const ticked = S.sel.has(entry.path);
            const row = document.createElement('div');
            row.className = 'ex-lrow' + (ticked ? ' is-ticked' : '');

            const check = checkbox(ticked, false, () => {
                if (S.sel.has(entry.path)) S.sel.delete(entry.path); else S.sel.add(entry.path);
                render();
            }, (ticked ? 'Deselect ' : 'Select ') + entry.name);

            const name = document.createElement(entry.directory ? 'button' : 'span');
            name.className = 'ex-lname';
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
                shield.title = entry.backedUp ? 'Backed up' : 'Something inside is backed up';
                name.appendChild(shield);
            }
            if (entry.directory) name.onclick = () => go(S.path.concat([entry.name]));

            const size = document.createElement('span');
            size.className = 'ex-lmeta';
            size.textContent = VaierListing.formatSize(entry);

            const time = document.createElement('span');
            time.className = 'ex-lmeta';
            time.textContent = VaierListing.formatTime(entry.modifiedAt);

            row.append(check, name, size, time, rowActions(machine, entry));
            rows.appendChild(row);
        });
    }

    // Per-entry actions, revealed on hover (and always present to the keyboard): put a file or directory on the
    // Clipboard, or download a file straight to the browser. Copy carries the entry's whole coordinate —
    // machine, path, and the archive being viewed — so a thing copied from the past is pasted as a restore.
    function rowActions(machine, entry) {
        const box = el('div', 'ex-lactions');

        const copy = el('button', 'ex-iconbtn');
        copy.innerHTML = svg('copy', 'ex-ico');
        copy.title = onClipboard(machine, entry.path) ? 'On the Clipboard' : 'Copy to the Clipboard';
        copy.setAttribute('aria-label', copy.title);
        if (onClipboard(machine, entry.path)) copy.classList.add('is-on');
        copy.onclick = () => clipCopy(machine, entry);
        box.appendChild(copy);

        // A file downloads as itself; a folder downloads as a zip of its whole tree. Downloading the past is
        // fine either way — it is a read, and the invariant is only ever about writing.
        const dl = el('button', 'ex-iconbtn');
        dl.innerHTML = svg('download', 'ex-ico');
        dl.title = entry.directory ? 'Download as zip' : 'Download';
        dl.setAttribute('aria-label', dl.title + ' ' + entry.name);
        dl.onclick = () => download(machine, entry);
        box.appendChild(dl);

        // Delete is present-only and destructive, so it never appears while time-travelling (you cannot edit
        // the past) and it goes through a typed-name gate before anything is removed.
        if (!S.at) {
            const rm = el('button', 'ex-iconbtn is-danger');
            rm.innerHTML = svg('trash', 'ex-ico');
            rm.title = 'Delete';
            rm.setAttribute('aria-label', 'Delete ' + entry.name);
            rm.onclick = () => deleteEntry(machine, entry);
            box.appendChild(rm);
        }
        return box;
    }

    // --- the Clipboard: file coordinates in the hand, and the Transfers that carry them ------------------
    //
    // The Clipboard holds coordinates (machine, path, time), not bytes. Paste names the operation by its
    // destination: another machine is a copy, the same machine at a past coordinate is a restore, the browser
    // is a download. This is the copy/paste half; a Transfer (below) is the byte-moving half the backend runs.

    const clipId = (machine, path, at) => machine + DIR_SEP + path + DIR_SEP + (at || '');
    const onClipboard = (machine, path) =>
        S.clipboard.some((c) => c.machine === machine && c.path === path && (c.at || '') === (S.at || ''));

    // Copy toggles: a second click on an entry already held takes it back off, so the same button both puts a
    // thing on the Clipboard and reconsiders it, and its lit state always tells the truth.
    function clipCopy(machine, entry) {
        const id = clipId(machine, entry.path, S.at);
        const at = S.at;
        const has = S.clipboard.some((c) => clipId(c.machine, c.path, c.at) === id);
        S.clipboard = has
            ? S.clipboard.filter((c) => clipId(c.machine, c.path, c.at) !== id)
            : S.clipboard.concat([{ machine: machine, path: entry.path, at: at,
                                    name: entry.name, directory: entry.directory, size: entry.size }]);
        render();
    }

    const clipClear = () => { S.clipboard = []; render(); };
    const clipRemove = (id) => { S.clipboard = S.clipboard.filter((c) => clipId(c.machine, c.path, c.at) !== id); render(); };

    // A download is a paste whose destination is the browser: Vaier streams the file's bytes straight through.
    // The coordinate travels as the same (path, at) the listing carries, so a file from an archive downloads
    // its past self. A hidden anchor click is how a browser is handed a stream to save.
    function download(machine, entry) {
        const params = new URLSearchParams({ path: entry.path });
        if (S.at) params.set('at', S.at);
        const a = document.createElement('a');
        a.href = '/machines/' + encodeURIComponent(machine) + '/files/download?' + params.toString();
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
    async function deleteEntry(machine, entry) {
        const what = entry.directory ? 'folder' : 'file';
        const body = entry.path + ' on ' + machine
            + (entry.directory ? '\n\nEverything inside this folder is deleted too.' : '')
            + '\n\nThis cannot be undone. Type the machine name to confirm.';
        const ok = await confirmTyped('Delete this ' + what + '?', body, machine, 'Delete');
        if (!ok) return;
        try {
            const res = await fetch('/machines/' + encodeURIComponent(machine)
                + '/files?path=' + encodeURIComponent(entry.path), { method: 'DELETE' });
            if (!res.ok) {
                const err = await res.json().catch(() => null);
                return toast((err && err.message) || ('Could not delete ' + entry.name + '.'));
            }
            toast('Deleted ' + entry.name + '.');
            refreshDir(machine, remotePath(S.path));   // the listing no longer holds it
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

    // The selection toolbar — one set of verbs over everything ticked. Copy adds them all to the Clipboard;
    // Download sends each (a folder as its zip); Delete takes them all through one typed gate. Present-only
    // for Delete, same as the per-row verb.
    function renderSelectionBar(machine, entries) {
        const chosen = entries.filter((e) => S.sel.has(e.path));
        if (!chosen.length) return null;

        const bar = el('div', 'ex-selbar');
        const count = el('div', 'ex-selbar-txt');
        count.textContent = chosen.length + (chosen.length === 1 ? ' selected' : ' selected');
        bar.appendChild(count);

        const actions = el('div', 'ex-selbar-actions');
        actions.appendChild(selVerb('copy', 'Copy', 'ex-btn', () => selCopy(machine, chosen)));
        actions.appendChild(selVerb('download', 'Download', 'ex-btn', () => selDownload(machine, chosen)));
        // Back up is the whole idea: pick what matters and protect it. Present-only (you protect the live tree,
        // not an archived shape of it) and never on the backup server itself (it stores the archives, it is not
        // a thing that is backed up). Vaier makes the repository, the job and the schedule behind the one click.
        if (!S.at && (!S.backupServer || machine !== S.backupServer.machineName)) {
            actions.appendChild(selVerb('shield', 'Back up', 'ex-btn is-accent', () => selBackup(machine, chosen)));
            if (chosen.some((e) => e.backedUp)) {
                actions.appendChild(selVerb('cross', 'Stop backing up', 'ex-btn', () => selUnbackup(machine, chosen)));
            }
        }
        if (!S.at) actions.appendChild(selVerb('trash', 'Delete', 'ex-btn is-danger', () => selDelete(machine, chosen)));
        const clear = el('button', 'ex-iconbtn');
        clear.innerHTML = svg('cross', 'ex-ico');
        clear.title = 'Clear selection';
        clear.setAttribute('aria-label', 'Clear selection');
        clear.onclick = () => { S.sel = new Set(); render(); };
        actions.appendChild(clear);
        bar.appendChild(actions);
        return bar;
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

    function selCopy(machine, chosen) {
        chosen.forEach((entry) => {
            const id = clipId(machine, entry.path, S.at);
            if (!S.clipboard.some((c) => clipId(c.machine, c.path, c.at) === id)) {
                S.clipboard.push({ machine: machine, path: entry.path, at: S.at,
                                   name: entry.name, directory: entry.directory, size: entry.size });
            }
        });
        toast(chosen.length + (chosen.length === 1 ? ' item' : ' items') + ' copied to the Clipboard.');
        S.sel = new Set();
        render();
    }

    function selDownload(machine, chosen) {
        chosen.forEach((entry) => download(machine, entry));   // each in the same click gesture
        S.sel = new Set();
        render();
    }

    // Back the selection up — the one gesture that is the whole feature. The browser sends only the paths; the
    // backend makes the repository if there is none, makes the job if there is none, and folds the paths in
    // (a child of something already protected just disappears into it). Then the jobs reload and the shields
    // appear where the selection was. Vaier holds the complexity; the operator held down a checkbox.
    async function selBackup(machine, chosen) {
        const paths = chosen.map((e) => e.path);
        try {
            const res = await fetch('/machines/' + encodeURIComponent(machine) + '/backup/paths', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ paths: paths }),
            });
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                toast(err.message || 'Vaier could not back that up.');
                return;
            }
            const body = await res.json().catch(() => ({}));
            await loadBackup();   // the job changed — reload so the backup entry is current
            toast(paths.length + (paths.length === 1 ? ' item is' : ' items are') + ' backed up on ' + machine
                + ' now, and nightly.');
            // First back-up on a machine: the backend rings the host to be readied (borg installed, key trusted)
            // and tells us here. The install runs detached; we watch prepare-client-settled to know it landed.
            if (body && body.provisioning) startReadying(machine, body.provisioning);
            S.sel = new Set();
            // The shields are stamped on the listing by the backend, so re-read this directory to show them.
            refreshDir(machine, remotePath(S.path));
        } catch (e) {
            toast('Vaier could not back that up.');
        }
    }

    // What Vaier does behind the first back-up: ready the host (install borg, trust its key). It's silent by
    // design — a quiet "Getting X ready…" and nothing more — unless it hits the one wall it can't pass on its
    // own, a host where it lacks the root to install borg, in which case it names the single command to run.
    function startReadying(machine, p) {
        if (p.scriptOnly && p.stagedScriptPath) {
            toast('Almost there — to finish setting ' + machine + ' up, run this on it once: sudo bash '
                + p.stagedScriptPath);
            return;
        }
        if (p.started) {
            S.preparing.add(machine);
            toast('Getting ' + machine + ' ready to back up…');
            render();
        } else if (p.message) {
            toast(p.message);
        }
    }

    // Stop backing the selection up. Removing a folder clears anything under it too; if nothing is left backed
    // up on the machine, the backend forgets the job entirely (the archives already made are untouched).
    async function selUnbackup(machine, chosen) {
        const paths = chosen.map((e) => e.path);
        try {
            const res = await fetch('/machines/' + encodeURIComponent(machine) + '/backup/paths', {
                method: 'DELETE',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ paths: paths }),
            });
            if (!res.ok && res.status !== 204) {
                const err = await res.json().catch(() => ({}));
                toast(err.message || 'Vaier could not stop backing that up.');
                return;
            }
            await loadBackup();
            toast('Stopped backing up ' + paths.length + (paths.length === 1 ? ' item.' : ' items.'));
            S.sel = new Set();
            refreshDir(machine, remotePath(S.path));   // re-read so the shields clear
        } catch (e) {
            toast('Vaier could not stop backing that up.');
        }
    }

    // One gate for the whole batch: name the count, and delete each in turn once the machine name is typed.
    // A failure on one is reported and the rest still go; the listing is re-read once at the end.
    async function selDelete(machine, chosen) {
        const names = chosen.slice(0, 6).map((c) => c.path).join('\n')
            + (chosen.length > 6 ? '\n…and ' + (chosen.length - 6) + ' more' : '');
        const ok = await confirmTyped('Delete ' + chosen.length + ' items?',
            names + '\n\nEverything selected is deleted, folders and all they contain. This cannot be undone. '
            + 'Type the machine name to confirm.', machine, 'Delete');
        if (!ok) return;
        let failed = 0;
        for (const entry of chosen) {
            try {
                const res = await fetch('/machines/' + encodeURIComponent(machine)
                    + '/files?path=' + encodeURIComponent(entry.path), { method: 'DELETE' });
                if (!res.ok) failed++;
            } catch (e) { failed++; }
        }
        toast(failed ? ('Deleted ' + (chosen.length - failed) + ' of ' + chosen.length + '; ' + failed + ' could not be removed.')
                     : ('Deleted ' + chosen.length + (chosen.length === 1 ? ' item.' : ' items.')));
        S.sel = new Set();
        refreshDir(machine, remotePath(S.path));
    }

    // "Paste here" belongs to a directory in the present, and only there — the invariant is that you can only
    // paste into the present, so time-travelling hides it rather than offering a write that would be refused.
    // It also needs a real destination path (the machine has said where its tree begins) and a non-empty
    // Clipboard. Absent any of those, nothing is drawn.
    function renderPasteBar(machine, destPath) {
        if (S.at || !S.clipboard.length || destPath == null) return document.createDocumentFragment();

        const bar = el('div', 'ex-pastebar');
        const label = el('div', 'ex-pastebar-txt');
        const n = S.clipboard.length;
        label.textContent = 'Clipboard: ' + n + (n === 1 ? ' item' : ' items');
        bar.appendChild(label);

        const paste = el('button', 'ex-btn is-accent');
        paste.innerHTML = svg('clip', 'ex-ico');
        const verb = el('span');
        verb.textContent = 'Paste here';
        paste.appendChild(verb);
        paste.onclick = () => pasteHere(machine, destPath);
        bar.appendChild(paste);
        return bar;
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
                body: JSON.stringify({ sourceMachine: item.machine, sourcePath: item.path, at: item.at || null,
                                       destMachine: destMachine, destPath: destPath }),
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
        if (!S.clipboard.length && !live.length) { host.classList.remove('is-on'); return; }
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

        // Newest transfer first, so the one you just started is at the top.
        live.slice().reverse().forEach((tr) => host.appendChild(transferRow(tr)));
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
    function index() {
        const out = [];
        (function walk(path) {
            out.push({ path: path, kind: kindOf(path) });
            childrenOf(path).forEach((kid) => walk(path.concat([kid.name])));
        })(['fleet']);
        return out;
    }

    function matches(query) {
        const needle = query.trim().toLowerCase();
        const all = index().filter((e) => e.path.length > 1);
        if (!needle) return all.filter((e) => e.kind === 'machine').slice(0, 9);
        return all.filter((e) => key(e.path).toLowerCase().includes(needle)).slice(0, 40);
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
            item.innerHTML = svg(iconFor(entry.kind, entry.path[1]), 'ex-ico');

            const pth = document.createElement('span');
            pth.className = 'ex-pth';
            highlight(pth, key(entry.path), query.trim());
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
        for (let i = 1; i < path.length; i++) S.open.add(key(path.slice(0, i + 1)));
        closePalette();
        go(path);
    }

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
        if (!stillInPast) S.at = null;
        // A tick belongs to the listing it was made in; navigating anywhere clears the multi-selection.
        if (key(path) !== key(S.path)) S.sel = new Set();
        S.path = path;
        if (kindOf(path) === 'shell' && window.TerminalDock) TerminalDock.open(path[1]);
        render();
    }

    // Scrubbing the rail. Setting the time re-reads exactly the directory you are standing on — and every
    // directory above it — at that archive, so the tree and the Inspector relight together instead of the
    // open branches beneath you flashing empty. It is a bounded walk down one path (the very directories that
    // were opened to get here), never a crawl across the fleet — the same discipline the rest of the tree
    // keeps. Then the shell crossfades to the new light, once, in render.
    function toArchive(machine, id) {
        if (S.at === id) return;
        S.at = id;
        readPathChain(machine);
        render();
    }

    function toPresent() {
        if (!S.at) return;
        S.at = null;
        readPathChain(S.path[1]);
        render();
    }

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
        renderTree();
        renderCrumbs();
        renderPane();
        renderClip();
    }

    // --- the fleet, and the stream that keeps it honest ---------------------------------------------------

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
            S.peers = new Map();
            S.peerNames = new Map();
            peers.forEach((p) => {
                S.peers.set(p.name, p);
                S.peerNames.set(p.id, p.name);
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
            S.lan = new Map(servers.map((s) => [s.name, s]));
        } catch (e) { /* a fleet with no LAN servers is a fleet, not a failure */ }
    }

    function watchFleet() {
        const events = new EventSource('/vpn/peers/events');
        // A peer was added, renamed or removed — the tree's own shape changed.
        events.addEventListener('peers-updated', () => loadFleet().then(render));
        // Liveness. The backend polls WireGuard and pushes what it sees; the browser only ever listens, and
        // repaints the dots where they stand rather than re-rendering the pane under the operator.
        events.addEventListener('peers-stats', (e) => {
            try {
                JSON.parse(e.data).forEach((stat) => {
                    const name = S.peerNames.get(stat.name) || stat.name;
                    const peer = S.peers.get(name);
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
    }

    // The fleet's second stream, and the last one. `published-services` is a different topic on a different
    // controller — PublishingService, the published-services controller and DockerEventListener all publish
    // on it — and vpn-peers.js already holds both streams open, so this is the shape the codebase has, not a
    // new one. It is what keeps the services (and the containers behind them) honest without a single poll:
    // the backend watches, the backend pushes, the browser listens.
    function watchServices() {
        const events = new EventSource('/published-services/events');
        const refresh = () => {
            loadContainers();                       // re-renders when it lands
            loadServices().then(render);
        };
        // A route was published, updated, unpublished — or a container behind one changed state
        // (DockerEventListener publishes `container-state-changed` on this same event).
        events.addEventListener('service-updated', refresh);
        events.addEventListener('publish-traefik-active', refresh);
        events.addEventListener('publish-rolled-back', refresh);
    }

    // --- the dock ------------------------------------------------------------------------------------------

    function watchDock() {
        if (!window.TerminalDock) return;
        const panel = $('terminalPanel');
        TerminalDock.onChange = (open) => {
            panel.style.display = open > 0 ? 'flex' : 'none';
            if (open > 0) TerminalDock.refitActive();
        };
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
    $('exAddBtn').onclick = () => addMachine();
    $('exScrim').onclick = (e) => { if (e.target === $('exScrim')) closePalette(); };
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
    });

    // On a phone the soft keyboard shrinks the visual viewport but not the layout viewport, which would leave
    // the terminal's bottom rows (and the prompt) hidden behind the keyboard. Bind the shell's height to the
    // visual viewport so the focused shell sits above it.
    if (window.visualViewport) {
        const syncViewport = () => {
            document.body.style.height = window.visualViewport.height + 'px';
            if (window.TerminalDock) TerminalDock.refitActive();
        };
        window.visualViewport.addEventListener('resize', syncViewport);
        window.visualViewport.addEventListener('scroll', syncViewport);
    }

    async function init() {
        watchDock();
        // The services are awaited because the tree cannot be honest without them: a `services` entry exists
        // only on a machine that actually publishes something, and a tree that grew one a moment later would
        // have been lying for that moment.
        await Promise.all([loadFleet(), loadServices(), loadBackup()]);
        render();

        // The containers are not awaited. The fleet-wide Docker scrape can take seconds against a sleeping
        // host, and no part of the first paint depends on it — the `containers` entry is decided by
        // /machines' runsDocker, not by what the scrape finds. It fills itself in when it lands, which is
        // also what lets ⌘K find a container the operator never went looking for.
        loadContainers();

        watchFleet();
        watchServices();
        watchTransfers();
        watchBackups();
        loadTransfers();
        loadUser();
    }

    init();
})();
