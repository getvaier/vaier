// Explorer — one file tree spanning the fleet (#321, slice 1: browse).
//
// Every machine Vaier can reach over SSH is a root in one tree, so "where is that file?" is answered in a
// single place rather than by opening a shell per host. Listings come over SFTP, on demand, one directory at
// a time — a fleet's filesystem is far too big to fetch eagerly, and a directory you never open should cost
// nothing.
//
// This slice browses and nothing else: no time rail, no clipboard, no coverage. Those arrive with their own
// slices, and shipping their controls dead would be a lie about what works.
(function () {
    'use strict';

    const ROOT = '/';

    let machines = [];
    let machine = null;
    let cwd = ROOT;
    let inFlight = 0;          // only the newest listing may paint; a slow host must not overwrite a fast one

    const $ = (id) => document.getElementById(id);

    // --- formatting -------------------------------------------------------------------------------

    function formatSize(entry) {
        if (entry.directory) return '—';
        const bytes = entry.size;
        if (bytes == null) return '—';
        const units = ['B', 'K', 'M', 'G', 'T'];
        let n = bytes;
        let i = 0;
        while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
        return (n < 10 && i > 0 ? n.toFixed(1) : Math.round(n)) + units[i];
    }

    function formatTime(iso) {
        if (!iso) return '';
        const d = new Date(iso);
        if (isNaN(d)) return '';
        return d.toLocaleString(undefined, {
            day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false,
        });
    }

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

        const parts = cwd.split('/').filter(Boolean);
        const rootCrumb = document.createElement('button');
        rootCrumb.className = 'ex-crumb';
        rootCrumb.textContent = '/';
        rootCrumb.onclick = () => browse(ROOT);
        host.appendChild(rootCrumb);

        let acc = '';
        parts.forEach((part, i) => {
            acc += '/' + part;
            const here = i === parts.length - 1;
            if (i > 0) {
                const sep = document.createElement('span');
                sep.className = 'ex-crumb-sep';
                sep.textContent = '/';
                host.appendChild(sep);
            }
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
    // crash — say which one it is, in the terms the operator can act on.
    async function browse(path) {
        cwd = path;
        paintCrumbs();
        $('exCount').textContent = '';
        $('exRows').textContent = '';
        $('exRows').appendChild(note('Listing ' + path + ' on ' + machine + '…', false));

        const ticket = ++inFlight;
        try {
            const res = await fetch('/machines/' + encodeURIComponent(machine)
                + '/files?path=' + encodeURIComponent(path));
            if (ticket !== inFlight) return;   // a newer listing has already been asked for

            if (!res.ok) {
                // Vaier answers a failure with an ApiError envelope, and its message is already written for
                // the operator — "Not allowed to read /root as geir." says more than any status code could.
                const err = await res.json().catch(() => null);
                $('exRows').textContent = '';
                $('exRows').appendChild(note(
                    (err && err.message) || 'Could not list ' + path + ' on ' + machine + '.', true));
                $('exCount').textContent = '';
                return;
            }
            const entries = await res.json();
            if (ticket !== inFlight) return;
            paintRows(entries);
        } catch (e) {
            if (ticket !== inFlight) return;
            $('exRows').textContent = '';
            $('exRows').appendChild(note('Could not reach ' + machine + '.', true));
        }
    }

    function openMachine(name) {
        machine = name;
        paintFleet();
        browse(ROOT);
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
