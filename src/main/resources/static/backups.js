/* Fleet Backup admin page. All fetch + render logic for backup servers, backup repositories, backup
   jobs, backup runs and the nightly schedule. Follows the conventions in vpn-peers.js / users.html:
   escapeHtml on every interpolated value, event listeners wired by closure (never a name spliced into
   an onclick string), and a fixed bottom-centre toast overlay for feedback. The whole feature is Enterprise-gated;
   a Community instance sees the gate state instead of the console. */

(function () {
    'use strict';

    // --- State ---
    let servers = [];
    let repos = [];
    let jobs = [];
    let machines = [];           // machine names from /machines (peers + LAN servers + vaier-server)
    let pendingProvision = null; // { serverName } while awaiting a `provision-settled` SSE event
    let pendingPrepare = null;   // { job, machineName } while awaiting a prepare-client SSE settle event
    let authorizeServerName = null;

    // --- Small helpers ---
    function escapeHtml(s) {
        const d = document.createElement('div');
        d.textContent = s == null ? '' : s;
        return d.innerHTML;
    }

    function msg(text, type) {
        const el = document.getElementById('bk-message');
        if (!type) { el.innerHTML = ''; return; }
        el.innerHTML = `<div class="${type}">${escapeHtml(text)}</div>`;
        // Non-alarming toasts (a finished backup, warnings) clear themselves; errors stay until dismissed.
        if (type === 'success' || type === 'warning') setTimeout(() => { el.innerHTML = ''; }, 5000);
    }

    function openModal(id) { document.getElementById(id).classList.add('active'); }
    function closeModal(id) { document.getElementById(id).classList.remove('active'); }

    function showModalError(elId, text) {
        const el = document.getElementById(elId);
        el.className = 'error';
        el.textContent = text;
        el.style.display = '';
    }
    function clearModalError(elId) {
        const el = document.getElementById(elId);
        el.style.display = 'none';
        el.textContent = '';
    }

    // Read a JSON body, throwing the server's message on a non-2xx (mirrors the users.html pattern).
    async function jsonOrThrow(res) {
        if (!res.ok) {
            const body = await res.json().catch(() => ({}));
            throw new Error(body.message || 'HTTP ' + res.status);
        }
        return res.status === 204 ? null : res.json();
    }

    async function copyToClipboard(text, btn) {
        const original = btn.textContent;
        try {
            await navigator.clipboard.writeText(text);
            btn.textContent = 'Copied';
        } catch (_) {
            btn.textContent = 'Copy failed';
        }
        setTimeout(() => { btn.textContent = original; }, 1600);
    }

    function checkMark(ok) {
        return ok ? '<span class="bk-check-mark pass">✓</span>' : '<span class="bk-check-mark fail">✕</span>';
    }

    // --- Overflow menu ---
    // A card shows one verb inline; the rest live here. Built as elements rather than markup so an action
    // is a closure over its own entity, never a name spliced into an onclick string.

    // An open menu hangs over the cards below it. Without a backdrop, a click aimed at a card behind it
    // fires that card's action immediately — "Run now" and "Provision" take no confirmation. The backdrop
    // sits under the menu but over the page, so the first click anywhere outside the menu only closes it.
    function menuBackdrop() {
        let el = document.getElementById('bk-menu-backdrop');
        if (!el) {
            el = document.createElement('div');
            el.id = 'bk-menu-backdrop';
            document.body.appendChild(el);
        }
        return el;
    }

    function closeMenus() {
        document.querySelectorAll('.bk-menu.open').forEach(m => {
            m.classList.remove('open');
            m.querySelector('.bk-menu-btn').setAttribute('aria-expanded', 'false');
        });
        menuBackdrop().classList.remove('active');
    }

    function actionMenu(actions) {
        const menu = document.createElement('div');
        menu.className = 'bk-menu';

        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'bk-menu-btn';
        btn.textContent = '⋯';
        btn.setAttribute('aria-haspopup', 'true');
        btn.setAttribute('aria-expanded', 'false');
        btn.setAttribute('aria-label', 'More actions');
        btn.addEventListener('click', ev => {
            ev.stopPropagation();
            const wasOpen = menu.classList.contains('open');
            closeMenus();
            if (!wasOpen) {
                menu.classList.add('open');
                btn.setAttribute('aria-expanded', 'true');
                menuBackdrop().classList.add('active');
            }
        });

        const list = document.createElement('div');
        list.className = 'bk-menu-list';
        list.setAttribute('role', 'menu');
        actions.forEach(action => {
            const item = document.createElement('button');
            item.type = 'button';
            item.className = 'btn btn-small ' + (action.cls || 'btn-secondary');
            item.setAttribute('role', 'menuitem');
            item.textContent = action.label;
            item.addEventListener('click', () => { closeMenus(); action.onClick(); });
            list.appendChild(item);
        });

        menu.append(btn, list);
        return menu;
    }

    // --- The chain ---
    // Each stage is done once it holds something, current when you can act on it now, and blocked while its
    // prerequisite is missing. A blocked stage refuses its "New…" button, so the page can't be worked
    // out of order.
    function setStage(id, state) {
        const el = document.getElementById(id);
        if (!el) return;
        el.classList.toggle('is-done', state === 'done');
        el.classList.toggle('is-current', state === 'current');
        el.classList.toggle('is-blocked', state === 'blocked');
    }

    function stageState(count, prerequisiteMet) {
        if (count > 0) return 'done';
        return prerequisiteMet ? 'current' : 'blocked';
    }

    // A satisfied stage trades its numeral for a checkmark — the step is behind you.
    function setNode(id, numeral, done) {
        const el = document.getElementById(id);
        if (el) el.textContent = done ? '✓' : numeral;
    }

    function updateChain() {
        setStage('stageServers', stageState(servers.length, true));
        setStage('stageRepos', stageState(repos.length, servers.length > 0));
        setStage('stageJobs', stageState(jobs.length, repos.length > 0));
        setStage('stageSchedule', jobs.some(j => j.enabled) ? 'done' : 'blocked');

        setNode('serverNode', '1', servers.length > 0);
        setNode('repoNode', '2', repos.length > 0);
        setNode('jobNode', '3', jobs.length > 0);

        updateScheduleSummary();
    }

    // What the next run on the nightly schedule will actually pick up.
    function updateScheduleSummary() {
        const el = document.getElementById('scheduleSummary');
        if (!el) return;
        if (jobs.length === 0) { el.textContent = 'No jobs yet — nothing runs tonight.'; return; }
        const hourValue = document.getElementById('scheduleHour').value;
        const zone = scheduleZone ? ' ' + scheduleZone : '';
        const hour = hourValue === ''
            ? ''
            : ' · next run ' + String(hourValue).padStart(2, '0') + ':00' + zone;
        const enabled = jobs.filter(j => j.enabled).length;
        el.textContent = `${enabled} of ${jobs.length} job${jobs.length === 1 ? '' : 's'} enabled` + hour;
    }

    // --- Enterprise gate ---
    const GATE_ICON = '<svg width="40" height="40" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" stroke-linejoin="round"><rect x="1.5" y="2.5" width="13" height="3" rx="0.5"/><path d="M2.5 5.5v7a1 1 0 0 0 1 1h9a1 1 0 0 0 1-1v-7"/><line x1="6" y1="8.5" x2="10" y2="8.5"/></svg>';

    function showGate() {
        document.getElementById('bk-app').style.display = 'none';
        const gate = document.getElementById('bk-gate');
        gate.style.display = '';
        gate.innerHTML = `<div class="bk-gate">${GATE_ICON}
            <h2>Fleet Backup is an Enterprise feature</h2>
            <p>Stand up a borg backup server, trust each machine that backs up to it, and add a repository
               by name. Run jobs on demand or on a nightly schedule, and get an email when a run fails or the
               server goes quiet. Upgrade to Vaier Enterprise to switch it on.</p>
        </div>`;
    }

    async function isEnterprise() {
        try {
            const license = await (await fetch('/license', { cache: 'no-store' })).json();
            return license.edition === 'ENTERPRISE';
        } catch (_) {
            return false;
        }
    }

    // A 402 from any /backup-* call means the licence lapsed mid-session — fall back to the gate.
    function handleGated(e) {
        if (e && /\b402\b/.test(e.message)) { showGate(); return true; }
        return false;
    }

    // --- Backup servers ---
    async function loadServers() {
        const list = document.getElementById('serverList');
        try {
            servers = await jsonOrThrow(await fetch('/backup-servers', { cache: 'no-store' }));
            renderServers();
            updateChain();
        } catch (e) {
            if (handleGated(e)) return;
            list.innerHTML = `<div class="error">Couldn't load servers: ${escapeHtml(e.message)}</div>`;
        }
    }

    // The link to where the backup server's identity now lives — you designate it in the Explorer, not here.
    const DESIGNATE_LINK =
        '<a class="bk-designate-link" href="/explorer.html">Designate the backup server in the Explorer</a>';

    // The fleet has exactly one backup server, and its identity is designated in the Explorer. This section
    // renders that server read-only — its coordinates plus the operational actions that stay here (provision,
    // authorize a client, download the setup script) — and points at the Explorer to change the record.
    function renderServers() {
        const list = document.getElementById('serverList');
        if (servers.length === 0) {
            list.innerHTML = `<div class="bk-empty"><b>No backup server yet.</b>
                You designate the backup server in the Explorer — pick the machine that will hold the fleet's
                archives there, then provision and use it from here.
                <div class="bk-designate-note">${DESIGNATE_LINK}</div></div>`;
            return;
        }
        list.innerHTML = '';
        servers.forEach(server => {
            const el = document.createElement('div');
            el.className = 'bk-item';
            const managed = server.managed
                ? '<span class="badge badge-success">managed</span>'
                : '<span class="badge bk-badge-muted">registered</span>';
            // A managed server's next move is to provision it; one Vaier only registers is ready to trust
            // machines. Whichever isn't the primary action drops into the overflow menu.
            const primary = server.managed
                ? { label: 'Provision', cls: 'btn-success', onClick: () => provisionServer(server) }
                : { label: 'Authorize host…', cls: 'btn-secondary', onClick: () => openAuthorizeModal(server) };
            const secondary = server.managed
                ? { label: 'Authorize host…', onClick: () => openAuthorizeModal(server) }
                : { label: 'Provision', onClick: () => provisionServer(server) };
            el.innerHTML = `
                <div class="bk-item-main">
                    <div class="bk-item-title"><span class="bk-item-name">${escapeHtml(server.name)}</span></div>
                    <div class="bk-meta">
                        <span><span class="bk-meta-k">Machine</span><span class="bk-meta-v">${escapeHtml(server.machineName)}</span></span>
                        <span><span class="bk-meta-k">Address</span><span class="bk-meta-v">${escapeHtml(server.host)}:${escapeHtml(String(server.sshPort))}</span></span>
                        <span><span class="bk-meta-k">User</span><span class="bk-meta-v">${escapeHtml(server.borgUser)}</span></span>
                        <span><span class="bk-meta-k">Base repo path</span><span class="bk-meta-v">${escapeHtml(server.baseRepoPath)}</span></span>
                        <span><span class="bk-meta-k">Data path</span><span class="bk-meta-v">${escapeHtml(server.serverDataPath)}</span></span>
                    </div>
                    <div class="bk-tags">${managed}</div>
                    <div class="bk-designate-note">${DESIGNATE_LINK}</div>
                </div>
                <div class="bk-item-actions">
                    <button class="btn btn-small ${primary.cls} js-primary">${escapeHtml(primary.label)}</button>
                </div>`;
            el.querySelector('.js-primary').addEventListener('click', primary.onClick);
            el.querySelector('.bk-item-actions').appendChild(actionMenu([
                secondary,
                { label: 'Setup script', onClick: () => openSetupModal(server) }
            ]));
            list.appendChild(el);
        });
    }

    function fillSelect(selectId, values, selected, placeholder) {
        const sel = document.getElementById(selectId);
        const opts = [];
        if (placeholder) opts.push(`<option value="" disabled ${selected ? '' : 'selected'}>${escapeHtml(placeholder)}</option>`);
        values.forEach(v => {
            opts.push(`<option value="${escapeHtml(v)}" ${v === selected ? 'selected' : ''}>${escapeHtml(v)}</option>`);
        });
        sel.innerHTML = opts.join('');
    }

    // --- Setup script ---
    function openSetupModal(server) {
        document.getElementById('setupModalTitle').textContent = 'Setup script · ' + server.name;
        document.getElementById('setupLead').textContent =
            'Download this, copy it to ' + server.machineName + ', and run it there with sudo to stand up the '
            + 'borg server.';
        document.getElementById('setupDownloadBtn').onclick = () => downloadSetup(server.name);
        openModal('setupModal');
    }

    function downloadSetup(name) {
        const a = document.createElement('a');
        a.href = '/backup-servers/' + encodeURIComponent(name) + '/setup.sh';
        a.download = name + '-setup.sh';
        document.body.appendChild(a);
        a.click();
        a.remove();
    }

    // --- Server provisioning ---
    function provisionState(state) {
        switch (state) {
            case 'SUCCESS': return '<span class="status status-ok"><span class="status-indicator"></span>Succeeded</span>';
            case 'FAILED':  return '<span class="status status-error"><span class="status-indicator"></span>Failed</span>';
            default:        return '<span class="status status-running"><span class="status-indicator"></span>Running</span>';
        }
    }

    async function provisionServer(server) {
        pendingProvision = null;
        document.getElementById('serverProvisionTitle').textContent = 'Provision · ' + server.name;
        const body = document.getElementById('serverProvisionBody');
        body.innerHTML = '<div class="loading">Starting…</div>';
        openModal('serverProvisionModal');
        try {
            const result = await jsonOrThrow(await fetch(
                '/backup-servers/' + encodeURIComponent(server.name) + '/provision', { method: 'POST' }));
            if (result.scriptOnly) {
                renderScriptOnly(body, server, result);
                return;
            }
            if (result.started) {
                // The setup runs detached on the host; the frontend NEVER polls. A backend sweep watches it
                // and pushes a `provision-settled` SSE event, which onProvisionSettled handles.
                pendingProvision = { serverName: server.name };
                body.innerHTML = `<div class="bk-provision-status">${provisionState('RUNNING')}
                    <span class="bk-field-note">Provisioning started — pulling the borg-server image and starting it.</span></div>`;
                return;
            }
            renderServerProvisionOutcome(body, result.provisioned ? 'SUCCESS' : 'FAILED', result.message || '');
        } catch (e) {
            body.innerHTML = `<div class="error">${escapeHtml(e.message)}</div>`;
        }
    }

    // Handle a backend-pushed provision settle event (SSE). If it's for the server we launched a provision
    // on, re-fetch its status once for the log tail and render the outcome. The browser never polled.
    async function onProvisionSettled(payload) {
        if (!pendingProvision || !payload || payload.serverName !== pendingProvision.serverName) return;
        const serverName = pendingProvision.serverName;
        pendingProvision = null;
        const body = document.getElementById('serverProvisionBody');
        try {
            const status = await jsonOrThrow(await fetch(
                '/backup-servers/' + encodeURIComponent(serverName) + '/provision/status', { cache: 'no-store' }));
            if (body) renderServerProvisionOutcome(body, status.state, status.logTail || '');
        } catch (_) {
            if (body) renderServerProvisionOutcome(body, payload.state, '');
        }
        if (payload.state === 'SUCCESS') msg('Server "' + serverName + '" provisioned.', 'success');
        else msg('Provisioning "' + serverName + '" failed — see the log.', 'error');
    }

    // Vaier couldn't drive docker over SSH. If it staged the script on the host, show the one command to run;
    // otherwise fall back to downloading setup.sh. Directive guidance, not an error.
    function renderScriptOnly(body, server, result) {
        if (result.stagedScriptPath) {
            const cmd = 'sudo bash ' + result.stagedScriptPath;
            body.innerHTML = `
                <div class="bk-guidance">
                    <div class="bk-guidance-title">Run the staged setup script on the host</div>
                    <p>Vaier can't drive docker over SSH on ${escapeHtml(server.machineName)}, so it placed the
                       setup script on the host. Run this on ${escapeHtml(server.machineName)} to finish:</p>
                </div>
                <div class="bk-code-block" style="margin-top:0.75rem">
                    <code id="serverProvisionStagedCmd"></code>
                    <button class="btn btn-small btn-secondary" id="serverProvisionCopyBtn">Copy</button>
                </div>`;
            document.getElementById('serverProvisionStagedCmd').textContent = cmd;
            document.getElementById('serverProvisionCopyBtn').onclick =
                ev => copyToClipboard(cmd, ev.currentTarget);
            return;
        }
        body.innerHTML = `
            <div class="bk-guidance">
                <div class="bk-guidance-title">Run the setup script on the host</div>
                <p>Vaier can't drive docker over SSH on ${escapeHtml(server.machineName)} and couldn't stage the
                   script there. Download <code>setup.sh</code>, copy it to the host, and run it with sudo.</p>
                ${result.message ? `<p class="bk-field-note">${escapeHtml(result.message)}</p>` : ''}
            </div>
            <div class="modal-actions" style="padding:0;border:0;margin-top:1rem">
                <button class="btn btn-primary" id="serverProvisionSetupBtn">Download setup.sh</button>
            </div>`;
        document.getElementById('serverProvisionSetupBtn').onclick = () => downloadSetup(server.name);
    }

    function renderServerProvisionOutcome(body, state, logTail) {
        const tail = logTail
            ? `<pre class="bk-log-tail">${escapeHtml(logTail)}</pre>`
            : '';
        body.innerHTML = `<div class="bk-provision-status">${provisionState(state)}</div>${tail}`;
    }

    // --- Authorize a host ---
    function openAuthorizeModal(server) {
        authorizeServerName = server.name;
        clearModalError('authorizeError');
        document.getElementById('authorizeTitle').textContent = 'Authorize a host · ' + server.name;
        document.getElementById('authorizeResult').innerHTML = '';
        fillSelect('authorizeMachine', machines, null,
            machines.length ? 'Select a machine' : 'No machines yet — add one under Infrastructure');
        document.getElementById('authorizeOkBtn').disabled = false;
        openModal('authorizeModal');
    }

    async function authorizeHost() {
        clearModalError('authorizeError');
        const machineName = document.getElementById('authorizeMachine').value;
        if (!machineName) { showModalError('authorizeError', 'Choose a machine to authorize.'); return; }
        const btn = document.getElementById('authorizeOkBtn');
        btn.disabled = true;
        document.getElementById('authorizeResult').innerHTML = '<div class="loading">Trusting the host key…</div>';
        try {
            const result = await jsonOrThrow(await fetch(
                '/backup-servers/' + encodeURIComponent(authorizeServerName) +
                '/authorize/' + encodeURIComponent(machineName), { method: 'POST' }));
            renderAuthorizeResult('authorizeResult', result, machineName);
        } catch (e) {
            document.getElementById('authorizeResult').innerHTML = '';
            showModalError('authorizeError', e.message);
        } finally {
            btn.disabled = false;
        }
    }

    // Surface alreadyTrusted distinctly from a freshly-added key so a no-op reads as reassurance, not action.
    function renderAuthorizeResult(elId, result, machineName) {
        const el = document.getElementById(elId);
        if (!result.authorized) {
            el.innerHTML = `<div class="bk-check-row"><span class="bk-check-mark fail">✕</span>
                <span>${escapeHtml(result.message || 'Could not authorize ' + machineName + '.')}</span></div>`;
            return;
        }
        const heading = result.alreadyTrusted
            ? escapeHtml(machineName) + ' was already trusted — no change.'
            : escapeHtml(machineName) + ' is now trusted on this server.';
        // A second row reports whether Vaier could pin the server's host key on this client (no TOFU). When it
        // could not — an adopted server that never ran the setup script — say so and how to fix it.
        const pin = result.hostKeyPinned
            ? `<div class="bk-check-row"><span class="bk-check-mark pass">✓</span>
                <span>Server host key pinned on ${escapeHtml(machineName)}.</span></div>`
            : `<div class="bk-check-row"><span class="bk-check-mark warn">!</span>
                <span>Host key not pinned — re-run the setup script on the server, then authorize again.</span></div>`;
        el.innerHTML = `<div class="bk-check-row"><span class="bk-check-mark pass">✓</span><span>${heading}</span></div>`
            + pin;
    }

    // --- Repositories ---
    async function loadRepositories() {
        const list = document.getElementById('repoList');
        try {
            repos = await jsonOrThrow(await fetch('/backup-repositories', { cache: 'no-store' }));
            renderRepos();
            updateChain();
        } catch (e) {
            if (handleGated(e)) return;
            list.innerHTML = `<div class="error">Couldn't load repositories: ${escapeHtml(e.message)}</div>`;
        }
    }

    // Repositories are created, edited, deleted and browsed in the Explorer now, on the backup server's
    // `backup` entry. This link points there from both the empty state and the read-only list.
    const MANAGE_REPOS_LINK =
        '<a class="bk-designate-link" href="/explorer.html">Manage repositories in the Explorer</a>';

    // Read-only: each repository's name, its effective path, and its badges. There are no actions here —
    // adding, editing, deleting and browsing archives all live in the Explorer.
    function renderRepos() {
        const list = document.getElementById('repoList');
        if (repos.length === 0) {
            list.innerHTML = `<div class="bk-empty"><b>No repositories yet.</b>
                You add repositories in the Explorer, on the backup server's backup entry.
                <div class="bk-designate-note">${MANAGE_REPOS_LINK}</div></div>`;
            return;
        }
        list.innerHTML = '';
        repos.forEach(repo => {
            const el = document.createElement('div');
            el.className = 'bk-item';
            const tags = [];
            if (repo.appendOnly) tags.push('<span class="badge bk-badge-muted">append-only</span>');
            tags.push(repo.hasPassphrase
                ? '<span class="badge bk-badge-lock">passphrase set</span>'
                : '<span class="badge bk-badge-muted">no passphrase</span>');
            el.innerHTML = `
                <div class="bk-item-main">
                    <div class="bk-item-title"><span class="bk-item-name">${escapeHtml(repo.name)}</span></div>
                    <div class="bk-meta">
                        <span><span class="bk-meta-k">Server</span><span class="bk-meta-v">${escapeHtml(repo.serverName)}</span></span>
                        <span><span class="bk-meta-k">Path</span><span class="bk-meta-v">${escapeHtml(repo.repoPath)}</span></span>
                    </div>
                    <div class="bk-tags">${tags.join('')}</div>
                </div>`;
            list.appendChild(el);
        });
        const note = document.createElement('div');
        note.className = 'bk-designate-note';
        note.innerHTML = MANAGE_REPOS_LINK;
        list.appendChild(note);
    }

    // --- Jobs ---
    async function loadJobs() {
        const list = document.getElementById('jobList');
        try {
            jobs = await jsonOrThrow(await fetch('/backup-jobs', { cache: 'no-store' }));
            renderJobs();
            updateChain();
        } catch (e) {
            if (handleGated(e)) return;
            list.innerHTML = `<div class="error">Couldn't load jobs: ${escapeHtml(e.message)}</div>`;
        }
    }

    // Jobs are created, edited, deleted, run and enabled in the Explorer now, on each machine's `backup`
    // entry. This link points there from both the empty state and the read-only list.
    const MANAGE_JOBS_LINK =
        '<a class="bk-designate-link" href="/explorer.html">Manage backup jobs in the Explorer</a>';

    // Read-only: each job's name, machine, target repository, source-path count, retention, and an
    // enabled/disabled badge. There are no actions on the row except the provisioning wizard's readiness
    // check — creating, editing, deleting, running and enabling all live in the Explorer.
    function renderJobs() {
        const list = document.getElementById('jobList');
        if (jobs.length === 0) {
            list.innerHTML = `<div class="bk-empty"><b>No backup jobs yet.</b>
                You create jobs in the Explorer, on each machine's backup entry — pick the paths, the
                repository the archives land in, and how long to keep them.
                <div class="bk-designate-note">${MANAGE_JOBS_LINK}</div></div>`;
            return;
        }
        list.innerHTML = '';
        jobs.forEach(job => {
            const el = document.createElement('div');
            el.className = 'bk-item';
            el.dataset.job = job.name;
            const sources = (job.sourcePaths || []).length;
            const enabled = job.enabled
                ? '<span class="badge badge-success">enabled</span>'
                : '<span class="badge bk-badge-muted">disabled</span>';
            el.innerHTML = `
                <div class="bk-item-main">
                    <div class="bk-item-title">
                        <span class="bk-item-name">${escapeHtml(job.name)}</span>
                    </div>
                    <div class="bk-meta">
                        <span><span class="bk-meta-k">Machine</span><span class="bk-meta-v">${escapeHtml(job.machineName)}</span></span>
                        <span><span class="bk-meta-k">Repo</span><span class="bk-meta-v">${escapeHtml(job.repositoryName)}</span></span>
                        <span><span class="bk-meta-k">Sources</span><span class="bk-meta-v">${sources} path${sources === 1 ? '' : 's'}</span></span>
                    </div>
                    <div class="bk-tags">
                        <span class="bk-retention">keep <b>${escapeHtml(String(job.keepDaily))}d</b> · <b>${escapeHtml(String(job.keepWeekly))}w</b> · <b>${escapeHtml(String(job.keepMonthly))}m</b></span>
                        ${enabled}
                    </div>
                </div>
                <div class="bk-item-actions">
                    <button class="btn btn-small btn-secondary js-readiness">Check readiness</button>
                </div>`;
            el.querySelector('.js-readiness').addEventListener('click', () => checkReadiness(job));
            list.appendChild(el);
        });
        const note = document.createElement('div');
        note.className = 'bk-designate-note';
        note.innerHTML = MANAGE_JOBS_LINK;
        list.appendChild(note);
    }

    // Readiness for the provisioning wizard. borgAuthOk and versionsCompatible are shown prominently:
    // a green borg + reachable NAS still reads as NOT ready when the client key isn't trusted, and an
    // inline "Authorize host" is offered right there to fix it.
    async function checkReadiness(job) {
        document.getElementById('provisionModalTitle').textContent = 'Host readiness · ' + job.name;
        const body = document.getElementById('provisionBody');
        body.innerHTML = '<div class="loading">Checking…</div>';
        openModal('provisionModal');
        try {
            const c = await jsonOrThrow(await fetch('/backup-jobs/' + encodeURIComponent(job.name) + '/provision/check', { cache: 'no-store' }));
            renderReadiness(body, job, c);
        } catch (e) {
            body.innerHTML = `<div class="error">${escapeHtml(e.message)}</div>`;
        }
    }

    function renderReadiness(body, job, c) {
        const version = c.borgVersion ? `<span class="bk-check-sub">${escapeHtml(c.borgVersion)}</span>` : '';
        const serverVer = c.serverBorgVersion
            ? `<span class="bk-check-sub">client vs ${escapeHtml(c.serverBorgVersion)}</span>` : '';
        const repo = repos.find(r => r.name === job.repositoryName);
        const serverName = repo ? repo.serverName : null;
        const authAction = (!c.borgAuthOk && serverName)
            ? `<button class="btn btn-small btn-secondary bk-check-action" id="readinessAuthorizeBtn">Authorize host</button>`
            : '';
        // When borg is missing, offer to install it right here — the exact fix for the run that would die
        // with "borg: not found". Mirrors the inline "Authorize host" action on the key-trust row.
        const prepareAction = !c.borgInstalled
            ? `<button class="btn btn-small btn-secondary bk-check-action" id="readinessPrepareBtn">Prepare client</button>`
            : '';
        // Only a job that opted in to "Back up as root" is judged on it. A job that runs as the SSH user is
        // not "not ready" for lacking a grant it will never use, so the row simply isn't there.
        // When borg is present but the grant is missing, re-running Prepare client is the fix (it installs
        // the sudoers rule), so the action is offered right on the row that failed.
        const rootFixAction = (c.backupAsRoot && !c.rootBorgOk && c.borgInstalled)
            ? `<button class="btn btn-small btn-secondary bk-check-action" id="readinessRootPrepareBtn">Prepare client</button>`
            : '';
        const rootRow = c.backupAsRoot
            ? `<div class="bk-check-row">${checkMark(c.rootBorgOk)}<span>borg can run as root on ${escapeHtml(job.machineName)}</span>${rootFixAction}</div>`
            : '';
        body.innerHTML = `<div class="bk-check-list">
            <div class="bk-check-row">${checkMark(c.borgInstalled)}<span>borg installed on ${escapeHtml(job.machineName)}</span>${version}${prepareAction}</div>
            <div class="bk-check-row">${checkMark(c.borgSupported)}<span>borg version supported</span></div>
            <div class="bk-check-row">${checkMark(c.nasReachable)}<span>server reachable from the machine</span></div>
            <div class="bk-check-row bk-check-key">${checkMark(c.borgAuthOk)}<span>client key trusted on the server</span>${authAction}</div>
            <div class="bk-check-row bk-check-key">${checkMark(c.versionsCompatible)}<span>borg versions compatible</span>${serverVer}</div>
            ${rootRow}
        </div>
        <div id="readinessPrepareOut"></div>`;
        const rootPrepareBtn = document.getElementById('readinessRootPrepareBtn');
        if (rootPrepareBtn) {
            rootPrepareBtn.addEventListener('click', () => prepareClient(job, rootPrepareBtn));
        }
        const prepareBtn = document.getElementById('readinessPrepareBtn');
        if (prepareBtn) {
            prepareBtn.addEventListener('click', () => prepareClient(job, prepareBtn));
        }
        const authBtn = document.getElementById('readinessAuthorizeBtn');
        if (authBtn) {
            authBtn.addEventListener('click', async () => {
                authBtn.disabled = true;
                authBtn.textContent = 'Authorizing…';
                try {
                    const result = await jsonOrThrow(await fetch(
                        '/backup-servers/' + encodeURIComponent(serverName) +
                        '/authorize/' + encodeURIComponent(job.machineName), { method: 'POST' }));
                    if (result.authorized) {
                        msg(result.alreadyTrusted
                            ? job.machineName + ' was already trusted.'
                            : job.machineName + ' is now trusted — re-checking.', 'success');
                        await checkReadiness(job);   // re-run so borgAuthOk flips
                    } else {
                        authBtn.disabled = false;
                        authBtn.textContent = 'Authorize host';
                        msg(result.message || 'Could not authorize the host.', 'error');
                    }
                } catch (e) {
                    authBtn.disabled = false;
                    authBtn.textContent = 'Authorize host';
                    msg(e.message, 'error');
                }
            });
        }
    }

    // Install borg on the job's client host. On `started` the install runs detached on the host; the
    // frontend NEVER polls — a backend sweep watches the install and pushes a `prepare-client-settled` SSE
    // event when it finishes, which onPrepareSettled handles. On `scriptOnly` Vaier staged the script and we
    // show the one `sudo bash <path>` command to run (directive guidance, not an error).
    async function prepareClient(job, btn) {
        const out = document.getElementById('readinessPrepareOut');
        btn.disabled = true;
        btn.textContent = 'Preparing…';
        try {
            const result = await jsonOrThrow(await fetch(
                '/backup-jobs/' + encodeURIComponent(job.name) + '/prepare-client', { method: 'POST' }));
            if (result.scriptOnly) {
                renderPrepareScriptOnly(out, job, result);
                btn.disabled = false;
                btn.textContent = 'Prepare client';
                return;
            }
            if (result.started) {
                // Remember which job/machine we're waiting on; the SSE listener settles it. No polling.
                pendingPrepare = { job, machineName: job.machineName };
                out.innerHTML = `<div class="bk-provision-status" style="margin-top:0.75rem">${provisionState('RUNNING')}
                    <span class="bk-field-note">Installing borg on ${escapeHtml(job.machineName)}…</span></div>`;
                return;
            }
            out.innerHTML = `<pre class="bk-log-tail">${escapeHtml(result.message || 'Could not prepare the client.')}</pre>`;
            btn.disabled = false;
            btn.textContent = 'Prepare client';
        } catch (e) {
            out.innerHTML = `<div class="error">${escapeHtml(e.message)}</div>`;
            btn.disabled = false;
            btn.textContent = 'Prepare client';
        }
    }

    // Handle a backend-pushed prepare-client settle event (SSE). If it's for the machine we launched a
    // prepare on and the readiness modal is still showing it, react: on success re-check readiness so the
    // "borg installed" row flips green; on failure surface it. The browser never polled to learn this.
    function onPrepareSettled(payload) {
        if (!pendingPrepare || !payload || payload.machineName !== pendingPrepare.machineName) return;
        const job = pendingPrepare.job;
        pendingPrepare = null;
        if (payload.state === 'SUCCESS') {
            msg('borg installed on ' + job.machineName + ' — re-checking.', 'success');
            checkReadiness(job);   // one-shot re-fetch, not a poll
            return;
        }
        const out = document.getElementById('readinessPrepareOut');
        if (out) {
            out.innerHTML = `<div class="bk-provision-status" style="margin-top:0.75rem">${provisionState('FAILED')}</div>
                <p class="bk-field-note">Installing borg on ${escapeHtml(job.machineName)} failed. Try the staged
                   script, or install the <code>borgbackup</code> package on the host, then re-check.</p>`;
        }
        const btn = document.getElementById('readinessPrepareBtn');
        if (btn) { btn.disabled = false; btn.textContent = 'Prepare client'; }
        msg('Preparing "' + job.machineName + '" failed.', 'error');
    }

    // Vaier can SSH the host but can't gain root (no passwordless sudo), so it staged the install script.
    // Show the one command to run — never a raw curl | sudo bash.
    function renderPrepareScriptOnly(out, job, result) {
        if (result.stagedScriptPath) {
            const cmd = 'sudo bash ' + result.stagedScriptPath;
            out.innerHTML = `
                <div class="bk-guidance" style="margin-top:0.75rem">
                    <div class="bk-guidance-title">Run the staged install script on the host</div>
                    <p>Vaier can't gain root over SSH on ${escapeHtml(job.machineName)}, so it placed the borg
                       install script on the host. Run this on ${escapeHtml(job.machineName)} to install borg:</p>
                </div>
                <div class="bk-code-block" style="margin-top:0.5rem">
                    <code id="prepareStagedCmd"></code>
                    <button class="btn btn-small btn-secondary" id="prepareCopyBtn">Copy</button>
                </div>`;
            document.getElementById('prepareStagedCmd').textContent = cmd;
            document.getElementById('prepareCopyBtn').onclick =
                ev => copyToClipboard(cmd, ev.currentTarget);
            return;
        }
        out.innerHTML = `
            <div class="bk-guidance" style="margin-top:0.75rem">
                <div class="bk-guidance-title">Install borg on the host</div>
                <p>Vaier can't install borg over SSH on ${escapeHtml(job.machineName)}. Install the
                   <code>borgbackup</code> package on the host, then re-check readiness.</p>
                ${result.message ? `<p class="bk-field-note">${escapeHtml(result.message)}</p>` : ''}
            </div>`;
    }

    // --- Machines (pickers) ---
    async function loadMachines() {
        try {
            // /machines returns peers AND LAN servers (e.g. NAS) AND the Vaier server — /vpn/peers omits
            // the LAN servers a backup server usually runs on, so this is the correct source for every picker.
            const all = await jsonOrThrow(await fetch('/machines', { cache: 'no-store' }));
            machines = all.map(m => m.name).filter(Boolean).sort((a, b) => a.localeCompare(b));
        } catch (_) {
            machines = [];
        }
    }

    // --- Nightly schedule ---
    let scheduleZone = '';   // the zone the backend's scheduler reads the hour in, e.g. "Europe/Oslo"

    async function loadSchedule() {
        const sel = document.getElementById('scheduleHour');
        sel.innerHTML = Array.from({ length: 24 }, (_, h) =>
            `<option value="${h}">${String(h).padStart(2, '0')}:00</option>`).join('');
        try {
            const cfg = await jsonOrThrow(await fetch('/settings/config', { cache: 'no-store' }));
            const hour = Number.isInteger(cfg.backupScheduleHour) ? cfg.backupScheduleHour : 2;
            sel.value = String(hour);
            // Name the zone rather than say "server local time": the hour is read in the backend's zone,
            // which is not necessarily the operator's, and a wrong guess costs two hours of surprise.
            scheduleZone = cfg.backupScheduleZone || '';
            if (scheduleZone) document.getElementById('scheduleZone').textContent = scheduleZone;
        } catch (_) {
            sel.value = '2';
        }
    }

    async function saveSchedule() {
        const hour = parseInt(document.getElementById('scheduleHour').value, 10);
        const note = document.getElementById('scheduleNote');
        note.textContent = 'Saving…';
        try {
            await jsonOrThrow(await fetch('/settings/backup-schedule', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ backupScheduleHour: hour })
            }));
            note.textContent = 'Saved.';
            updateScheduleSummary();
            setTimeout(() => { note.textContent = ''; }, 4000);
        } catch (e) {
            note.textContent = e.message;
        }
    }

    // --- Wiring ---
    function wire() {
        document.getElementById('setupCloseBtn').addEventListener('click', () => closeModal('setupModal'));

        document.getElementById('serverProvisionCloseBtn').addEventListener('click', () => {
            pendingProvision = null;
            closeModal('serverProvisionModal');
        });

        document.getElementById('authorizeCloseBtn').addEventListener('click', () => closeModal('authorizeModal'));
        document.getElementById('authorizeOkBtn').addEventListener('click', authorizeHost);

        document.getElementById('provisionCloseBtn').addEventListener('click', () => closeModal('provisionModal'));

        document.getElementById('saveScheduleBtn').addEventListener('click', saveSchedule);

        // Click on the dim backdrop closes a modal (matches vpn-peers/users). Any click outside an open
        // overflow menu closes it; so does Escape, which keeps it reachable from the keyboard alone.
        window.addEventListener('click', e => {
            closeMenus();
            if (e.target.classList.contains('modal')) {
                if (e.target.id === 'serverProvisionModal') pendingProvision = null;
                e.target.classList.remove('active');
            }
        });
        window.addEventListener('keydown', e => { if (e.key === 'Escape') closeMenus(); });
    }

    // The backup UI never polls: it opens ONE backend SSE stream and reacts to pushed settle events. A
    // backend sweep does all the host-side polling and pushes when a borg-client install or a server
    // provision finishes — the two wizards that still run from this bridge page.
    function subscribeToBackupEvents() {
        try {
            const es = new EventSource('/backup-jobs/events');
            const on = (name, handler) => es.addEventListener(name, e => {
                let payload = null;
                try { payload = JSON.parse(e.data); } catch (_) { /* ignore malformed */ }
                if (payload) handler(payload);
            });
            on('prepare-client-settled', onPrepareSettled);
            on('provision-settled', onProvisionSettled);
        } catch (_) { /* SSE unavailable — the action still works, just without live settle */ }
    }

    async function init() {
        wire();
        if (!(await isEnterprise())) { showGate(); return; }
        document.getElementById('bk-app').style.display = '';
        subscribeToBackupEvents();
        await loadMachines();
        // Loaded in chain order, not in parallel: a stage's empty state names the prerequisite it is
        // waiting on, so repositories must already know whether a server exists before they render.
        await loadSchedule();
        await loadServers();
        await loadRepositories();
        await loadJobs();
    }

    init();
})();
