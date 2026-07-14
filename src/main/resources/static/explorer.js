// Explorer — one file tree spanning the fleet (#321, slice 1: browse).
//
// Every machine Vaier can reach over SSH is a root in one tree, so "where is that file?" is answered in a
// single place rather than by opening a shell per host. Listings come over SFTP, on demand, one directory at
// a time — a fleet's filesystem is far too big to fetch eagerly, and a directory you never open should cost
// nothing.
//
// This slice browses and nothing else: no time rail, no clipboard, no coverage. Those arrive with their own
// slices, and shipping their controls dead would be a lie about what works.
//
// Superseded by the Explorer shell (#323): this page is the backup while the tree is built, and it reads a
// directory through the same explorer-listing.js the shell does, so the two can never disagree about what a
// directory holds.
(function () {
    'use strict';

    let machines = [];
    let machine = null;

    // Where this machine's file tree begins, and where in it we are standing — both learnt from the machine
    // itself, never assumed (#326). The NAS's SFTP subsystem is chrooted into /volume1, so its tree begins
    // there and "/" is a path it cannot answer at all. Null until it has told us.
    let root = null;
    let cwd = null;

    const listing = VaierListing.createBrowser();   // owns the newest-listing-wins guard
    const formatSize = VaierListing.formatSize;
    const formatTime = VaierListing.formatTime;

    const $ = (id) => document.getElementById(id);

    // --- formatting -------------------------------------------------------------------------------

    function icon(isDir) {
        const path = isDir
            ? '<path d="M1.5 4.2a1 1 0 0 1 1-1h3l1.4 1.6h6.6a1 1 0 0 1 1 1v6.5a1 1 0 0 1-1 1h-11a1 1 0 0 1-1-1z"/>'
            : '<path d="M3.5 1.8h6l3 3v9.4a1 1 0 0 1-1 1h-8a1 1 0 0 1-1-1V2.8a1 1 0 0 1 1-1z"/>'
              + '<path d="M9.3 1.9v3.2h3.1"/>';
        return '<svg class="ex-ico" viewBox="0 0 16 16" fill="none" stroke="currentColor" '
            + 'stroke-width="1.3" stroke-linejoin="round">' + path + '</svg>';
    }

    // --- painting ---------------------------------------------------------------------------------

    function paintFleet() {
        const host = $('exFleet');
        host.textContent = '';
        machines.forEach((m) => {
            const btn = document.createElement('button');
            btn.className = 'ex-machine' + (m.name === machine ? ' is-active' : '');
            btn.innerHTML = '<span class="ex-machine-name"></span>';
            btn.querySelector('.ex-machine-name').textContent = m.name;
            btn.onclick = () => openMachine(m.name);
            host.appendChild(btn);
        });
    }

    function paintCrumbs() {
        const host = $('exCrumbs');
        host.textContent = '';
        if (!machine) return;

        const machineLabel = document.createElement('span');
        machineLabel.className = 'ex-crumb-sep';
        machineLabel.textContent = machine + ' ·';
        host.appendChild(machineLabel);

        if (root == null || cwd == null) return;   // the machine has not said where its tree begins yet

        // The first crumb IS the machine's root — on the NAS that reads /volume1, not "/". There is nothing
        // above it to climb to, and offering "/" would offer a path SFTP can never answer.
        const rootCrumb = document.createElement('button');
        rootCrumb.className = 'ex-crumb';
        rootCrumb.textContent = root;
        rootCrumb.onclick = () => browse(root);
        host.appendChild(rootCrumb);

        // Only the part of the path below the root is walkable, so only that part becomes crumbs.
        const below = cwd === root ? '' : cwd.slice(root === '/' ? 1 : root.length + 1);
        const parts = below.split('/').filter(Boolean);

        let acc = root === '/' ? '' : root;
        parts.forEach((part, i) => {
            acc += '/' + part;
            const here = i === parts.length - 1;
            const sep = document.createElement('span');
            sep.className = 'ex-crumb-sep';
            sep.textContent = '/';
            host.appendChild(sep);
            if (here) {
                const span = document.createElement('span');
                span.className = 'ex-crumb-here';
                span.textContent = part;
                host.appendChild(span);
            } else {
                const at = acc;
                const btn = document.createElement('button');
                btn.className = 'ex-crumb';
                btn.textContent = part;
                btn.onclick = () => browse(at);
                host.appendChild(btn);
            }
        });
    }

    function note(text, isError) {
        const el = document.createElement('div');
        el.className = 'ex-note' + (isError ? ' is-error' : '');
        el.textContent = text;
        return el;
    }

    function paintRows(entries) {
        const host = $('exRows');
        host.textContent = '';

        if (!entries.length) {
            host.appendChild(note('This folder is empty.', false));
            $('exCount').textContent = '0 items';
            return;
        }

        entries.forEach((entry) => {
            const row = document.createElement('div');
            row.className = 'ex-row';

            const name = document.createElement(entry.directory ? 'button' : 'div');
            name.className = 'ex-name' + (entry.directory ? ' is-dir' : '');
            name.innerHTML = icon(entry.directory) + '<span class="ex-nm"></span>';
            name.querySelector('.ex-nm').textContent = entry.name;
            if (entry.directory) name.onclick = () => browse(entry.path);

            const size = document.createElement('div');
            size.className = 'ex-size';
            size.textContent = formatSize(entry);

            const time = document.createElement('div');
            time.className = 'ex-time';
            time.textContent = formatTime(entry.modifiedAt);

            row.append(name, size, time);
            host.appendChild(row);
        });

        $('exCount').textContent = entries.length + (entries.length === 1 ? ' item' : ' items');
    }

    // --- loading ----------------------------------------------------------------------------------

    // A machine that is asleep, unreachable or missing a credential is an ordinary state for a fleet, not a
    // crash — say which one it is, in the terms the operator can act on. So is a path outside the machine's
    // SFTP root: "/volume2 is not reachable over SFTP..." is the server's own sentence, and it is shown as
    // such — never as an empty folder.
    //
    // `path` is null when we do not yet know where this machine's tree begins; the machine answers with the
    // directory it actually read, and the crumbs are painted from that.
    async function browse(path) {
        cwd = path;
        paintCrumbs();
        $('exCount').textContent = '';
        $('exRows').textContent = '';
        $('exRows').appendChild(note(
            'Listing ' + (path == null ? 'the file root' : path) + ' on ' + machine + '…', false));

        const result = await listing.list(machine, path);
        if (result.stale) return;             // a newer listing has already been asked for

        if (result.error) {
            $('exRows').textContent = '';
            $('exRows').appendChild(note(result.error, true));
            $('exCount').textContent = '';
            return;
        }
        // Where the machine says its tree begins, and which directory it actually read.
        root = result.root;
        cwd = result.path;
        paintCrumbs();
        paintRows(result.entries);
    }

    function openMachine(name) {
        machine = name;
        root = null;
        cwd = null;
        paintFleet();
        browse(null);   // the machine says where its tree begins — the browser does not assume "/"
    }

    async function init() {
        try {
            const res = await fetch('/machines');
            const all = res.ok ? await res.json() : [];
            machines = all.filter((m) => m.sshAccess);
        } catch (e) {
            machines = [];
        }

        if (!machines.length) {
            $('exFleet').appendChild(note('No machines.', false));
            $('exRows').appendChild(note(
                'Explorer browses machines Vaier can reach over SSH. Give a machine an SSH credential on its '
                + 'Infrastructure card and it will appear here.', false));
            return;
        }

        paintFleet();
        openMachine(machines[0].name);
    }

    init();
})();
