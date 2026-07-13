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
    const latestRuns = {};       // jobName -> RunResponse | null
    const pendingRunJobs = new Set();  // job names awaiting a `run-settled` SSE event (never polled)
    let pendingProvision = null; // { serverName } while awaiting a `provision-settled` SSE event
    let pendingPrepare = null;   // { job, machineName } while awaiting a prepare-client SSE settle event
    let editingServerName = null;
    let editingRepoName = null;  // null while creating
    let editingJobName = null;
    let authorizeServerName = null;
    let confirmCallback = null;

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

    function formatTime(iso) {
        if (!iso) return '';
        const d = new Date(iso);
        if (isNaN(d.getTime())) return '';
        return d.toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
    }

    // Crypto-strong, alphanumeric passphrase — no quotes or shell metacharacters, so it is safe to
    // hand to borg over SSH. Rejection sampling keeps the character distribution uniform (no modulo bias).
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

    function blockButton(id, blocked, why) {
        const btn = document.getElementById(id);
        if (!btn) return;
        btn.disabled = blocked;
        if (blocked) btn.title = why; else btn.removeAttribute('title');
    }

    function updateChain() {
        setStage('stageServers', stageState(servers.length, true));
        setStage('stageRepos', stageState(repos.length, servers.length > 0));
        setStage('stageJobs', stageState(jobs.length, repos.length > 0));
        setStage('stageSchedule', jobs.some(j => j.enabled) ? 'done' : 'blocked');

        setNode('serverNode', '1', servers.length > 0);
        setNode('repoNode', '2', repos.length > 0);
        setNode('jobNode', '3', jobs.length > 0);

        blockButton('newRepoBtn', servers.length === 0, 'Add a backup server first');
        blockButton('newJobBtn', repos.length === 0, 'Add a backup repository first');
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

    function renderServers() {
        const list = document.getElementById('serverList');
        if (servers.length === 0) {
            list.innerHTML = `<div class="bk-empty"><b>Start here.</b>
                Point Vaier at the machine that will hold the fleet's archives. If Vaier can reach it over
                SSH it will install and start borg there itself.</div>`;
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
                    </div>
                    <div class="bk-tags">${managed}</div>
                </div>
                <div class="bk-item-actions">
                    <button class="btn btn-small ${primary.cls} js-primary">${escapeHtml(primary.label)}</button>
                </div>`;
            el.querySelector('.js-primary').addEventListener('click', primary.onClick);
            el.querySelector('.bk-item-actions').appendChild(actionMenu([
                secondary,
                { label: 'Setup script', onClick: () => openSetupModal(server) },
                { label: 'Edit', onClick: () => openServerModal(server) },
                { label: 'Delete', cls: 'btn-danger', onClick: () => confirmDeleteServer(server) }
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

    function openServerModal(server) {
        editingServerName = server ? server.name : null;
        clearModalError('serverModalError');
        document.getElementById('serverModalTitle').textContent = server ? 'Edit backup server' : 'New backup server';
        const nameEl = document.getElementById('serverName');
        nameEl.value = server ? server.name : '';
        nameEl.disabled = !!server;
        document.getElementById('serverNameNote').style.display = server ? 'none' : '';
        fillSelect('serverMachine', machines, server ? server.machineName : null,
            machines.length ? 'Select a machine' : 'No machines yet — add one under Infrastructure');
        document.getElementById('serverHost').value = server ? server.host : '';
        document.getElementById('serverSshPort').value = server ? server.sshPort : '8022';
        document.getElementById('serverBorgUser').value = server ? server.borgUser : 'borg';
        document.getElementById('serverBaseRepoPath').value = server ? server.baseRepoPath : 'home/borg/backups';
        document.getElementById('serverDataPath').value = server ? server.serverDataPath : '';
        document.getElementById('serverManaged').checked = server ? server.managed : true;
        openModal('serverModal');
        if (!server) nameEl.focus();
    }

    async function saveServer() {
        clearModalError('serverModalError');
        const name = document.getElementById('serverName').value.trim();
        const machineName = document.getElementById('serverMachine').value;
        const host = document.getElementById('serverHost').value.trim();
        const dataPath = document.getElementById('serverDataPath').value.trim();
        if (!name) { showModalError('serverModalError', 'A server name is required.'); return; }
        if (!machineName) { showModalError('serverModalError', 'Choose the machine that hosts the server.'); return; }
        if (!host) { showModalError('serverModalError', 'A host is required.'); return; }
        if (!dataPath) { showModalError('serverModalError', 'A server data path is required.'); return; }
        const sshPortRaw = document.getElementById('serverSshPort').value.trim();
        const body = {
            machineName,
            host,
            sshPort: sshPortRaw === '' ? null : parseInt(sshPortRaw, 10),
            borgUser: document.getElementById('serverBorgUser').value.trim() || 'borg',
            baseRepoPath: document.getElementById('serverBaseRepoPath').value.trim() || 'home/borg/backups',
            serverDataPath: dataPath,
            managed: document.getElementById('serverManaged').checked
        };
        const btn = document.getElementById('serverSaveBtn');
        btn.disabled = true;
        try {
            await jsonOrThrow(await fetch('/backup-servers/' + encodeURIComponent(name), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            }));
            closeModal('serverModal');
            msg('Server "' + name + '" saved.', 'success');
            await loadServers();
        } catch (e) {
            showModalError('serverModalError', e.message);
        } finally {
            btn.disabled = false;
        }
    }

    function confirmDeleteServer(server) {
        openConfirm('Delete backup server',
            'Delete server "' + server.name + '"? Repositories on it will lose their destination.',
            async () => {
                await jsonOrThrow(await fetch('/backup-servers/' + encodeURIComponent(server.name), { method: 'DELETE' }));
                msg('Server "' + server.name + '" deleted.', 'success');
                await loadServers();
            });
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

    function renderRepos() {
        const list = document.getElementById('repoList');
        if (repos.length === 0) {
            list.innerHTML = servers.length === 0
                ? `<div class="bk-empty"><b>Waiting on step 1.</b>
                    A repository lives inside a backup server, so there's nowhere to put one yet.</div>`
                : `<div class="bk-empty"><b>Name a repository.</b>
                    Vaier derives its path on ${escapeHtml(servers[0].name)} and generates a passphrase for
                    it. Give each machine its own.</div>`;
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
                </div>
                <div class="bk-item-actions">
                    <button class="btn btn-small btn-secondary js-archives">Browse archives</button>
                </div>`;
            el.querySelector('.js-archives').addEventListener('click', () => browseArchives(repo));
            // No Initialise action: a run creates the repository if it is absent. The endpoint that does it
            // borrows a host from a job targeting the repository, so a button here could only ever fail on a
            // repository no job references yet — the moment its name most invites pressing it.
            el.querySelector('.bk-item-actions').appendChild(actionMenu([
                { label: 'Edit', onClick: () => openRepoModal(repo) },
                { label: 'Delete', cls: 'btn-danger', onClick: () => confirmDeleteRepo(repo) }
            ]));
            list.appendChild(el);
        });
    }

    // A repository/server name is a shell/path token server-side, so it is confined to [A-Za-z0-9_-]
    // (BackupRepository.name / BackupServer.name). Slug the field as the operator types — spaces and other
    // characters become hyphens — so "NUC 02" is accepted as "NUC-02" and a name that would be rejected
    // (400) can never be submitted. The replacement is 1:1, so the caret position is preserved.
    function slugName(raw) {
        return raw.replace(/[^A-Za-z0-9_-]/g, '-');
    }

    function liveSlugName(ev) {
        const el = ev.target;
        const caret = el.selectionStart;
        const slugged = slugName(el.value);
        if (slugged !== el.value) {
            el.value = slugged;
            el.setSelectionRange(caret, caret);
        }
    }

    // Live preview of where a new/edited repository will point, mirroring the server's derivation
    // (base/<name>) unless a custom path overrides it.
    function updateDerivedPath() {
        const el = document.getElementById('repoDerived');
        const serverName = document.getElementById('repoServer').value;
        const name = document.getElementById('repoName').value.trim();
        const custom = document.getElementById('repoPath').value.trim();
        const server = servers.find(s => s.name === serverName);
        let path;
        if (custom) {
            path = custom;
        } else if (server && name) {
            path = server.baseRepoPath + '/' + name;
        } else {
            el.innerHTML = '';
            return;
        }
        el.innerHTML = `<span class="bk-meta-k">Points at</span><code>${escapeHtml(path)}</code>`;
    }

    function openRepoModal(repo) {
        editingRepoName = repo ? repo.name : null;
        clearModalError('repoModalError');
        document.getElementById('repoModalTitle').textContent = repo ? 'Edit backup repository' : 'New backup repository';
        const nameEl = document.getElementById('repoName');
        nameEl.value = repo ? repo.name : '';
        nameEl.disabled = !!repo;
        document.getElementById('repoNameNote').style.display = repo ? 'none' : '';
        fillSelect('repoServer', servers.map(s => s.name), repo ? repo.serverName : null,
            servers.length ? 'Select a server' : 'No servers yet — add one above');

        // Custom path (advanced): only pre-open/pre-fill when editing a repo that carries an explicit override.
        // The response repoPath is the effective path, so a value equal to the derived base/<name> is treated
        // as "derived" and left collapsed.
        const server = repo ? servers.find(s => s.name === repo.serverName) : null;
        const derived = server ? server.baseRepoPath + '/' + repo.name : null;
        const hasCustom = !!repo && repo.repoPath && repo.repoPath !== derived;
        document.getElementById('repoPath').value = hasCustom ? repo.repoPath : '';
        setDisclosure(hasCustom);

        const passGroup = document.getElementById('repoPassphraseGroup');
        const passEl = document.getElementById('repoPassphrase');
        const warn = document.getElementById('repoPassphraseWarn');
        if (repo) {
            // Editing: never auto-generate. Blank keeps the stored secret.
            passEl.value = '';
            passEl.placeholder = 'Leave blank to keep the stored passphrase';
            warn.textContent = repo.hasPassphrase
                ? 'A passphrase is stored. Leave blank to keep it, or type a new one to replace it.'
                : 'No passphrase stored yet. Type one, or generate a strong one.';
            warn.classList.remove('bk-passphrase-strong');
        } else {
            // Creating: pre-fill a strong passphrase, shown once.
            passEl.value = generatePassphrase(32);
            passEl.placeholder = '';
            warn.textContent = 'Auto-generated. Vaier stores this encrypted — copy it somewhere safe now, it can\'t be shown again.';
            warn.classList.add('bk-passphrase-strong');
        }
        passGroup.style.display = '';
        document.getElementById('repoAppendOnly').checked = repo ? repo.appendOnly : false;
        updateDerivedPath();
        openModal('repoModal');
        if (!repo) nameEl.focus();
    }

    function setDisclosure(open) {
        const toggle = document.getElementById('repoPathToggle');
        toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
        toggle.classList.toggle('open', open);
        document.getElementById('repoPathAdvanced').style.display = open ? '' : 'none';
    }

    async function saveRepo() {
        clearModalError('repoModalError');
        const name = document.getElementById('repoName').value.trim();
        const serverName = document.getElementById('repoServer').value;
        const repoPath = document.getElementById('repoPath').value.trim();
        const passphrase = document.getElementById('repoPassphrase').value;
        if (!name) { showModalError('repoModalError', 'A repository name is required.'); return; }
        if (!serverName) { showModalError('repoModalError', 'Choose a backup server.'); return; }
        if (!editingRepoName && !passphrase.trim()) {
            showModalError('repoModalError', 'A passphrase is required when creating a repository.');
            return;
        }
        const body = {
            serverName,
            repoPath: repoPath === '' ? null : repoPath,   // blank derives base/<name> server-side
            passphrase,                                     // blank on edit keeps the stored secret
            appendOnly: document.getElementById('repoAppendOnly').checked
        };
        const btn = document.getElementById('repoSaveBtn');
        btn.disabled = true;
        try {
            const saved = await jsonOrThrow(await fetch('/backup-repositories/' + encodeURIComponent(name), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            }));
            closeModal('repoModal');
            msg('Repository "' + name + '" saved — points at ' + (saved ? saved.repoPath : '') + '.', 'success');
            await loadRepositories();
        } catch (e) {
            showModalError('repoModalError', e.message);
        } finally {
            btn.disabled = false;
        }
    }

    function confirmDeleteRepo(repo) {
        openConfirm('Delete backup repository',
            'Delete repository "' + repo.name + '"? Jobs targeting it will no longer have a destination.',
            async () => {
                await jsonOrThrow(await fetch('/backup-repositories/' + encodeURIComponent(repo.name), { method: 'DELETE' }));
                msg('Repository "' + repo.name + '" deleted.', 'success');
                await loadRepositories();
            });
    }

    async function browseArchives(repo) {
        document.getElementById('archivesModalTitle').textContent = 'Archives · ' + repo.name;
        const body = document.getElementById('archivesBody');
        body.innerHTML = '<div class="loading">Loading archives…</div>';
        openModal('archivesModal');
        try {
            const archives = await jsonOrThrow(await fetch('/backup-repositories/' + encodeURIComponent(repo.name) + '/archives', { cache: 'no-store' }));
            if (!archives.length) {
                body.innerHTML = `<div class="bk-empty">No archives found.<br>Archives appear here once a job has run against this repository from a reachable machine.</div>`;
                return;
            }
            const rows = archives.map(a => `
                <div class="bk-archive-row">
                    <span class="bk-archive-name">${escapeHtml(a.name)}</span>
                    <span class="bk-archive-time">${escapeHtml(formatTime(a.time))}</span>
                </div>`).join('');
            body.innerHTML = `<div class="bk-archive-list">${rows}</div>`;
        } catch (e) {
            body.innerHTML = `<div class="error">Couldn't list archives: ${escapeHtml(e.message)}</div>`;
        }
    }

    // --- Jobs ---
    async function loadJobs() {
        const list = document.getElementById('jobList');
        try {
            jobs = await jsonOrThrow(await fetch('/backup-jobs', { cache: 'no-store' }));
            renderJobs();
            updateChain();
            jobs.forEach(refreshRunBadge);
        } catch (e) {
            if (handleGated(e)) return;
            list.innerHTML = `<div class="error">Couldn't load jobs: ${escapeHtml(e.message)}</div>`;
        }
    }

    // A run that went wrong and has something to say about it. The happy path never has diagnostics, so a
    // clean run gets no disclosure and the row stays quiet.
    function hasDiagnostics(run) {
        return !!run && !!run.diagnostics && run.diagnostics.trim() !== ''
            && (run.status === 'WARNING' || run.status === 'FAILED' || run.status === 'UNKNOWN');
    }

    function statusPill(run) {
        const t = formatTime(run.finishedAt || run.startedAt);
        const time = t ? `<span class="bk-run-time">${escapeHtml(t)}</span>` : '';
        switch (run.status) {
            case 'RUNNING': return '<span class="status status-running"><span class="status-indicator"></span>Running</span>';
            case 'SUCCESS': return `<span class="status status-ok"><span class="status-indicator"></span>Success</span>${time}`;
            case 'WARNING': return `<span class="status status-warn"><span class="status-indicator"></span>Warnings</span>${time}`;
            case 'FAILED':  return `<span class="status status-error"><span class="status-indicator"></span>Failed</span>${time}`;
            default:        return `<span class="status status-unknown"><span class="status-indicator"></span>Unknown</span>${time}`;
        }
    }

    // The pill, wrapped in the page's existing disclosure control when — and only when — the run left
    // diagnostics worth reading. Reuses .bk-disclosure/.bk-disclosure-caret from the repo-path advanced row.
    function statusBadge(run) {
        if (!run) return '<span class="status status-unknown"><span class="status-indicator"></span>Never run</span>';
        const pill = statusPill(run);
        if (!hasDiagnostics(run)) return pill;
        return `<button type="button" class="bk-disclosure bk-run-disclosure" aria-expanded="false"
                    title="Show what borg reported">${pill}<span class="bk-disclosure-caret">›</span></button>`;
    }

    // The diagnostic lines themselves: borg's skipped-file / error output, as the backend's run diagnostics
    // (the JSON stats blob is already stripped server-side). Untrusted remote paths — always escaped.
    function diagnosticsPanel(run) {
        if (!hasDiagnostics(run)) return '';
        const tone = run.status === 'WARNING' ? 'warn' : 'err';
        return `<pre class="bk-run-diag ${tone}" hidden>${escapeHtml(run.diagnostics)}</pre>`;
    }

    // Wire the disclosure inside one job row. Scoped to the row, so no ids and no collisions between jobs.
    function bindDiagnostics(item) {
        const toggle = item.querySelector('.bk-run-disclosure');
        const panel = item.querySelector('.bk-run-diag');
        if (!toggle || !panel) return;
        toggle.addEventListener('click', () => {
            const open = toggle.getAttribute('aria-expanded') !== 'true';
            toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
            toggle.classList.toggle('open', open);
            panel.hidden = !open;
        });
    }

    function renderJobs() {
        const list = document.getElementById('jobList');
        if (jobs.length === 0) {
            list.innerHTML = repos.length === 0
                ? `<div class="bk-empty"><b>Waiting on step 2.</b>
                    A job needs a repository to write into.</div>`
                : `<div class="bk-empty"><b>Back up a machine.</b>
                    Pick the machine, the paths, and the repository the archives land in. Vaier installs borg
                    on the machine and trusts its key when you check readiness.</div>`;
            return;
        }
        list.innerHTML = '';
        jobs.forEach(job => {
            const el = document.createElement('div');
            el.className = 'bk-item';
            el.dataset.job = job.name;
            const sources = (job.sourcePaths || []).length;
            el.innerHTML = `
                <div class="bk-item-main">
                    <div class="bk-item-title">
                        <span class="bk-item-name">${escapeHtml(job.name)}</span>
                        <span class="bk-run-badge">${statusBadge(latestRuns[job.name])}</span>
                    </div>
                    <div class="bk-meta">
                        <span><span class="bk-meta-k">Machine</span><span class="bk-meta-v">${escapeHtml(job.machineName)}</span></span>
                        <span><span class="bk-meta-k">Repo</span><span class="bk-meta-v">${escapeHtml(job.repositoryName)}</span></span>
                        <span><span class="bk-meta-k">Sources</span><span class="bk-meta-v">${sources} path${sources === 1 ? '' : 's'}</span></span>
                    </div>
                    <div class="bk-tags">
                        <span class="bk-retention">keep <b>${escapeHtml(String(job.keepDaily))}d</b> · <b>${escapeHtml(String(job.keepWeekly))}w</b> · <b>${escapeHtml(String(job.keepMonthly))}m</b></span>
                        <label class="bk-toggle"><input type="checkbox" class="js-enabled" ${job.enabled ? 'checked' : ''}> Enabled</label>
                    </div>
                    <div class="bk-run-diag-slot">${diagnosticsPanel(latestRuns[job.name])}</div>
                </div>
                <div class="bk-item-actions">
                    <button class="btn btn-small btn-success js-run">Run now</button>
                </div>`;
            bindDiagnostics(el);
            el.querySelector('.js-run').addEventListener('click', () => runJob(job));
            el.querySelector('.js-enabled').addEventListener('change', ev => toggleEnabled(job, ev.target.checked));
            el.querySelector('.bk-item-actions').appendChild(actionMenu([
                { label: 'Check readiness', onClick: () => checkReadiness(job) },
                { label: 'Edit', onClick: () => openJobModal(job) },
                { label: 'Delete', cls: 'btn-danger', onClick: () => confirmDeleteJob(job) }
            ]));
            list.appendChild(el);
        });
    }

    // Repaint just one job's last-run badge (and its diagnostics) without re-rendering the whole list.
    function paintBadge(jobName) {
        const item = document.querySelector(`.bk-item[data-job="${CSS.escape(jobName)}"]`);
        if (!item) return;
        const badge = item.querySelector('.bk-run-badge');
        const slot = item.querySelector('.bk-run-diag-slot');
        if (badge) badge.innerHTML = statusBadge(latestRuns[jobName]);
        if (slot) slot.innerHTML = diagnosticsPanel(latestRuns[jobName]);
        bindDiagnostics(item);   // the toggle is fresh markup, so it needs its listener back
    }

    async function refreshRunBadge(job) {
        try {
            const res = await fetch('/backup-jobs/' + encodeURIComponent(job.name) + '/runs', { cache: 'no-store' });
            if (res.status === 404) { latestRuns[job.name] = null; paintBadge(job.name); return; }
            latestRuns[job.name] = await jsonOrThrow(res);
        } catch (_) {
            // Leave the badge as-is on a transient error.
        }
        paintBadge(job.name);
    }

    function jobBody() {
        const lines = id => document.getElementById(id).value.split('\n').map(s => s.trim()).filter(Boolean);
        return {
            machineName: document.getElementById('jobMachine').value,
            repositoryName: document.getElementById('jobRepository').value,
            sourcePaths: lines('jobSourcePaths'),
            excludes: lines('jobExcludes'),
            keepDaily: parseInt(document.getElementById('jobKeepDaily').value, 10) || 0,
            keepWeekly: parseInt(document.getElementById('jobKeepWeekly').value, 10) || 0,
            keepMonthly: parseInt(document.getElementById('jobKeepMonthly').value, 10) || 0,
            compression: document.getElementById('jobCompression').value.trim() || 'zstd,6',
            enabled: document.getElementById('jobEnabled').checked,
            backupAsRoot: document.getElementById('jobBackupAsRoot').checked
        };
    }

    function openJobModal(job) {
        editingJobName = job ? job.name : null;
        clearModalError('jobModalError');
        document.getElementById('jobModalTitle').textContent = job ? 'Edit backup job' : 'New backup job';
        const nameEl = document.getElementById('jobName');
        nameEl.value = job ? job.name : '';
        nameEl.disabled = !!job;
        document.getElementById('jobNameNote').style.display = job ? 'none' : '';
        fillSelect('jobMachine', machines, job ? job.machineName : null,
            machines.length ? 'Select a machine' : 'No machines yet — add one under Infrastructure');
        fillSelect('jobRepository', repos.map(r => r.name), job ? job.repositoryName : null,
            repos.length ? 'Select a repository' : 'No repositories yet — add one above');
        document.getElementById('jobSourcePaths').value = job ? (job.sourcePaths || []).join('\n') : '';
        document.getElementById('jobExcludes').value = job ? (job.excludes || []).join('\n') : '';
        document.getElementById('jobKeepDaily').value = job ? job.keepDaily : 7;
        document.getElementById('jobKeepWeekly').value = job ? job.keepWeekly : 4;
        document.getElementById('jobKeepMonthly').value = job ? job.keepMonthly : 6;
        document.getElementById('jobCompression').value = job ? job.compression : 'zstd,6';
        document.getElementById('jobEnabled').checked = job ? job.enabled : true;
        // Opt-in, never inherited: a new job starts as the SSH user, like every job that exists today.
        document.getElementById('jobBackupAsRoot').checked = job ? !!job.backupAsRoot : false;
        openModal('jobModal');
        if (!job) nameEl.focus();
    }

    async function saveJob() {
        clearModalError('jobModalError');
        const name = document.getElementById('jobName').value.trim();
        const body = jobBody();
        if (!name) { showModalError('jobModalError', 'A job name is required.'); return; }
        if (!body.machineName) { showModalError('jobModalError', 'Choose the machine to back up.'); return; }
        if (!body.repositoryName) { showModalError('jobModalError', 'Choose a backup repository.'); return; }
        if (body.sourcePaths.length === 0) { showModalError('jobModalError', 'Add at least one source path.'); return; }
        const btn = document.getElementById('jobSaveBtn');
        btn.disabled = true;
        try {
            await jsonOrThrow(await fetch('/backup-jobs/' + encodeURIComponent(name), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            }));
            closeModal('jobModal');
            msg('Job "' + name + '" saved.', 'success');
            await loadJobs();
        } catch (e) {
            showModalError('jobModalError', e.message);
        } finally {
            btn.disabled = false;
        }
    }

    // The enabled flag rides on the whole job spec, so a toggle re-saves the job with the flag flipped.
    async function toggleEnabled(job, enabled) {
        try {
            const body = {
                machineName: job.machineName, repositoryName: job.repositoryName,
                sourcePaths: job.sourcePaths, excludes: job.excludes,
                keepDaily: job.keepDaily, keepWeekly: job.keepWeekly, keepMonthly: job.keepMonthly,
                compression: job.compression, enabled
            };
            await jsonOrThrow(await fetch('/backup-jobs/' + encodeURIComponent(job.name), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            }));
            job.enabled = enabled;
            updateChain();   // the nightly schedule's reach just changed
            msg('Job "' + job.name + '" ' + (enabled ? 'enabled' : 'disabled') + '.', 'success');
        } catch (e) {
            msg(e.message, 'error');
            await loadJobs();
        }
    }

    function confirmDeleteJob(job) {
        openConfirm('Delete backup job',
            'Delete job "' + job.name + '"? Existing archives in the repository are not removed.',
            async () => {
                await jsonOrThrow(await fetch('/backup-jobs/' + encodeURIComponent(job.name), { method: 'DELETE' }));
                pendingRunJobs.delete(job.name);
                delete latestRuns[job.name];
                msg('Job "' + job.name + '" deleted.', 'success');
                await loadJobs();
            });
    }

    async function runJob(job) {
        msg('Starting run for "' + job.name + '"…', '');
        try {
            const run = await jsonOrThrow(await fetch('/backup-jobs/' + encodeURIComponent(job.name) + '/runs', { method: 'POST' }));
            latestRuns[job.name] = run;
            paintBadge(job.name);
            // Show RUNNING, then wait for the backend-pushed `run-settled` SSE event — the frontend never polls.
            pendingRunJobs.add(job.name);
            msg('Run started for "' + job.name + '".', 'success');
        } catch (e) {
            msg(e.message, 'error');
        }
    }

    // Handle a backend-pushed run settle event (SSE). Guarded by pendingRunJobs so an event for another job
    // never refreshes the wrong card: only re-fetch the job we launched, and only its latest run.
    async function onRunSettled(payload) {
        if (!payload || !payload.jobName || !pendingRunJobs.has(payload.jobName)) return;
        const jobName = payload.jobName;
        pendingRunJobs.delete(jobName);
        try {
            const res = await fetch('/backup-jobs/' + encodeURIComponent(jobName) + '/runs', { cache: 'no-store' });
            if (res.ok) { latestRuns[jobName] = await res.json(); paintBadge(jobName); }
        } catch (_) { /* the event already told us the outcome; the badge repaints on the next load */ }
        // Point the operator at the detail that now exists on the row, instead of stating a dead end.
        if (payload.status === 'SUCCESS') msg('Backup of "' + jobName + '" finished.', 'success');
        else if (payload.status === 'WARNING') msg('Backup of "' + jobName + '" completed with warnings. Open the Warnings badge on the job to see which files were skipped.', 'warning');
        else if (payload.status === 'FAILED') msg('Backup of "' + jobName + '" failed — admins were emailed. Open the Failed badge on the job to see what borg reported.', 'error');
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

    // --- Generic confirm ---
    function openConfirm(title, text, callback) {
        confirmCallback = callback;
        document.getElementById('confirmTitle').textContent = title;
        document.getElementById('confirmText').textContent = text;
        openModal('confirmModal');
    }

    async function runConfirm() {
        if (!confirmCallback) return;
        const btn = document.getElementById('confirmOkBtn');
        btn.disabled = true;
        try {
            await confirmCallback();
            closeModal('confirmModal');
            confirmCallback = null;
        } catch (e) {
            closeModal('confirmModal');
            confirmCallback = null;
            msg(e.message, 'error');
        } finally {
            btn.disabled = false;
        }
    }

    // --- Wiring ---
    function wire() {
        document.getElementById('newServerBtn').addEventListener('click', () => openServerModal(null));
        document.getElementById('serverCancelBtn').addEventListener('click', () => closeModal('serverModal'));
        document.getElementById('serverSaveBtn').addEventListener('click', saveServer);
        document.getElementById('serverName').addEventListener('input', liveSlugName);

        document.getElementById('setupCloseBtn').addEventListener('click', () => closeModal('setupModal'));

        document.getElementById('serverProvisionCloseBtn').addEventListener('click', () => {
            pendingProvision = null;
            closeModal('serverProvisionModal');
        });

        document.getElementById('authorizeCloseBtn').addEventListener('click', () => closeModal('authorizeModal'));
        document.getElementById('authorizeOkBtn').addEventListener('click', authorizeHost);

        document.getElementById('newRepoBtn').addEventListener('click', () => openRepoModal(null));
        document.getElementById('repoCancelBtn').addEventListener('click', () => closeModal('repoModal'));
        document.getElementById('repoSaveBtn').addEventListener('click', saveRepo);
        document.getElementById('repoServer').addEventListener('change', updateDerivedPath);
        document.getElementById('repoName').addEventListener('input', liveSlugName);
        document.getElementById('repoName').addEventListener('input', updateDerivedPath);
        document.getElementById('repoPath').addEventListener('input', updateDerivedPath);
        document.getElementById('repoPathToggle').addEventListener('click', () =>
            setDisclosure(document.getElementById('repoPathToggle').getAttribute('aria-expanded') !== 'true'));
        document.getElementById('repoPassRegenBtn').addEventListener('click', () => {
            document.getElementById('repoPassphrase').value = generatePassphrase(32);
        });
        document.getElementById('repoPassCopyBtn').addEventListener('click', ev =>
            copyToClipboard(document.getElementById('repoPassphrase').value, ev.currentTarget));

        document.getElementById('newJobBtn').addEventListener('click', () => openJobModal(null));
        document.getElementById('jobCancelBtn').addEventListener('click', () => closeModal('jobModal'));
        document.getElementById('jobSaveBtn').addEventListener('click', saveJob);

        document.getElementById('archivesCloseBtn').addEventListener('click', () => closeModal('archivesModal'));
        document.getElementById('provisionCloseBtn').addEventListener('click', () => closeModal('provisionModal'));

        document.getElementById('confirmCancelBtn').addEventListener('click', () => { closeModal('confirmModal'); confirmCallback = null; });
        document.getElementById('confirmOkBtn').addEventListener('click', runConfirm);

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
    // backend sweep does all the host-side polling and pushes when a borg-client install, an on-demand run,
    // or a server provision finishes.
    function subscribeToBackupEvents() {
        try {
            const es = new EventSource('/backup-jobs/events');
            const on = (name, handler) => es.addEventListener(name, e => {
                let payload = null;
                try { payload = JSON.parse(e.data); } catch (_) { /* ignore malformed */ }
                if (payload) handler(payload);
            });
            on('prepare-client-settled', onPrepareSettled);
            on('run-settled', onRunSettled);
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
