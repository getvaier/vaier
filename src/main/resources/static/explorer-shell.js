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
        { name: 'users',          label: 'Users',          icon: 'users',   page: 'users.html',
          note: 'Who may reach what' },
        { name: 'settings',       label: 'Settings',       icon: 'gear',    page: 'settings.html',
          note: 'AWS, mail, disk alerts' },
        { name: 'concepts',       label: 'Concepts',       icon: 'book',    page: 'concepts.html',
          note: 'How Vaier is put together' },
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
        dirs: new Map(),                 // dirKey -> one directory's read: its state, its children, its reader
        palSel: 0,
    };

    // Directory reads go through VaierListing — one reader per directory, created in readDir. A single shared
    // reader would make concurrent expands cancel one another.
    const $ = (id) => document.getElementById(id);
    const key = (path) => '/' + path.join('/');

    // --- what an entry IS, read off its own path -------------------------------------------------------
    //
    // The path is the whole model. A tree that also kept a parallel object graph would be two truths about
    // where you are, and they would drift the first time a machine was renamed under one of them.

    function kindOf(path) {
        if (path.length === 1) return 'fleet';
        if (path.length === 2) {
            return BRIDGES.some((b) => b.name === path[1]) ? 'bridge' : 'machine';
        }
        if (path[2] === 'shell') return 'shell';
        if (path[2] === 'files') return path.length === 3 ? 'files' : 'dir';
        return 'fleet';
    }

    const machineOf = (path) => S.machines.find((m) => m.name === path[1]) || null;
    const bridgeOf = (path) => BRIDGES.find((b) => b.name === path[1]) || null;

    // A tree path under `files` and a path on the machine are the same path, written twice.
    const remotePath = (path) => '/' + path.slice(3).join('/');

    function childrenOf(path) {
        const kind = kindOf(path);
        if (kind === 'fleet') {
            return S.machines.map((m) => ({ name: m.name, kind: 'machine' }))
                .concat(BRIDGES.map((b) => ({ name: b.name, kind: 'bridge', label: b.label })));
        }
        if (kind === 'machine') {
            const m = machineOf(path);
            // Only a machine Vaier can reach over SSH has anything inside it to show yet.
            return m && m.sshAccess
                ? [{ name: 'files', kind: 'files' }, { name: 'shell', kind: 'shell' }]
                : [];
        }
        if (kind === 'files' || kind === 'dir') {
            // Whatever the cache already holds, and nothing more. This function is called on every repaint,
            // so it must never be able to start a read — otherwise a tree that merely redraws would walk the
            // fleet over SFTP, and the fleet is on the far side of a VPN.
            const entry = S.dirs.get(dirKey(path[1], remotePath(path)));
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
    const dirKey = (machine, path) => machine + DIR_SEP + path;

    const dirStateOf = (path) => {
        const entry = S.dirs.get(dirKey(path[1], remotePath(path)));
        return entry ? entry.state : 'unread';
    };

    const isDirKind = (kind) => kind === 'files' || kind === 'dir';

    async function readDir(machine, rpath) {
        const k = dirKey(machine, rpath);
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

        const result = await entry.reader.list(machine, rpath);

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
        }
        render();
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
                       shell: 'shell' };
    const iconFor = (kind, name) => (kind === 'bridge'
        ? (BRIDGES.find((b) => b.name === name) || {}).icon
        : ICON_FOR[kind]) || 'file';

    // Mono for anything with a coordinate, sans for anything human. A machine name and a path are addresses;
    // "Backups" is a word.
    const MONO_KINDS = new Set(['machine', 'files', 'dir', 'file']);

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
            const entry = S.dirs.get(dirKey(path[1], remotePath(path)));
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
            if (i === S.path.length - 1) {
                const here = document.createElement('span');
                here.className = 'ex-crumb-here';
                here.textContent = seg;
                bar.appendChild(here);
            } else {
                const crumb = document.createElement('button');
                crumb.className = 'ex-crumb';
                crumb.textContent = seg;
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
        if (kind === 'machine') return renderMachine(pane);
        if (kind === 'bridge') return renderBridge(pane);
        if (kind === 'shell') return renderShell(pane);
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
            S.machines.forEach((m) => {
                const address = tunnelAddress(m);
                grid.appendChild(card('machine', m.name, true,
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
        if (!m.sshAccess) {
            body.appendChild(note('Vaier has no SSH access to this machine, so it has no files and no shell '
                + 'to open. Give it an SSH credential on its Infrastructure card and both appear here.',
                false));
        } else {
            const grid = document.createElement('div');
            grid.className = 'ex-grid';
            grid.appendChild(card('dir', 'files', true, 'Browse over SFTP',
                () => go(['fleet', m.name, 'files'])));
            grid.appendChild(card('shell', 'shell', false, 'A terminal on this machine',
                () => go(['fleet', m.name, 'shell'])));
            body.appendChild(grid);
        }
        pane.appendChild(body);
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

    // --- files ------------------------------------------------------------------------------------------

    // The Inspector renders a directory from the same cache slot the rail reads, and selecting an unread
    // directory is what fills it. So one SFTP round trip serves both surfaces — and a slow answer can never
    // paint into the wrong place, because the pane always renders the slot belonging to the path it is
    // standing on, whatever landed while it was waiting.
    function renderDirectory(pane) {
        const machine = S.path[1];
        const path = remotePath(S.path);

        pane.appendChild(paneHead(path, true, machine));

        const body = document.createElement('div');
        body.className = 'ex-pane-body';
        const rows = document.createElement('div');
        rows.className = 'ex-listing';
        body.appendChild(rows);
        pane.appendChild(body);

        if (dirStateOf(S.path) === 'unread') readDir(machine, path);   // it re-renders when it lands

        const entry = S.dirs.get(dirKey(machine, path));
        if (!entry || entry.state === 'loading') {
            return rows.appendChild(note('Listing ' + path + ' on ' + machine + '…', false));
        }
        // The server's own sentence, verbatim — "Not allowed to read /root as geir." says more than any
        // status code could.
        if (entry.state === 'error') return rows.appendChild(note(entry.error, true));

        const result = { entries: entry.entries };

        const head = document.createElement('div');
        head.className = 'ex-lhead';
        ['Name', 'Size', 'Modified'].forEach((h) => {
            const cell = document.createElement('span');
            cell.textContent = h;
            head.appendChild(cell);
        });
        rows.appendChild(head);

        if (!result.entries.length) return rows.appendChild(note('This folder is empty.', false));

        result.entries.forEach((entry) => {
            const row = document.createElement('div');
            row.className = 'ex-lrow';

            const name = document.createElement(entry.directory ? 'button' : 'span');
            name.className = 'ex-lname';
            name.innerHTML = svg(entry.directory ? 'dir' : 'file', 'ex-ico');
            const nm = document.createElement('span');
            nm.className = 'ex-nm';
            nm.textContent = entry.name;
            name.appendChild(nm);
            if (entry.directory) name.onclick = () => go(S.path.concat([entry.name]));

            const size = document.createElement('span');
            size.className = 'ex-lmeta';
            size.textContent = VaierListing.formatSize(entry);

            const time = document.createElement('span');
            time.className = 'ex-lmeta';
            time.textContent = VaierListing.formatTime(entry.modifiedAt);

            row.append(name, size, time);
            rows.appendChild(row);
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
        S.path = path;
        if (kindOf(path) === 'shell' && window.TerminalDock) TerminalDock.open(path[1]);
        render();
    }

    function render() {
        renderTree();
        renderCrumbs();
        renderPane();
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
        await loadFleet();
        render();
        watchFleet();
        loadUser();
    }

    init();
})();
