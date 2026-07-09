/* Fleet Backup admin page (Slice 9). All fetch + render logic for backup repositories, backup jobs,
   backup runs and the nightly schedule. Follows the conventions in vpn-peers.js / users.html:
   escapeHtml on every interpolated value, event listeners wired by closure (never a name spliced into
   an onclick string), and a top-of-page toast for feedback. The whole feature is Enterprise-gated;
   a Community instance sees the gate state instead of the console. */

(function () {
    'use strict';

    // --- State ---
    let repos = [];
    let jobs = [];
    let machines = [];           // peer names from /vpn/peers
    const latestRuns = {};       // jobName -> RunResponse | null
    const pollTimers = {};       // jobName -> interval id
    let editingRepoName = null;  // null while creating
    let editingJobName = null;
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
        if (type === 'success') setTimeout(() => { el.innerHTML = ''; }, 5000);
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

    // --- Enterprise gate ---
    const GATE_ICON = '<svg width="40" height="40" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.1" stroke-linecap="round" stroke-linejoin="round"><rect x="1.5" y="2.5" width="13" height="3" rx="0.5"/><path d="M2.5 5.5v7a1 1 0 0 0 1 1h9a1 1 0 0 0 1-1v-7"/><line x1="6" y1="8.5" x2="10" y2="8.5"/></svg>';

    function showGate() {
        document.getElementById('bk-app').style.display = 'none';
        const gate = document.getElementById('bk-gate');
        gate.style.display = '';
        gate.innerHTML = `<div class="bk-gate">${GATE_ICON}
            <h2>Fleet Backup is an Enterprise feature</h2>
            <p>Back up your machines to a borg repository on the NAS, run jobs on demand or on a nightly
               schedule, and get an email when a run fails. Upgrade to Vaier Enterprise to switch it on.</p>
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

    // --- Repositories ---
    async function loadRepositories() {
        const list = document.getElementById('repoList');
        try {
            repos = await jsonOrThrow(await fetch('/backup-repositories', { cache: 'no-store' }));
            renderRepos();
        } catch (e) {
            if (handleGated(e)) return;
            list.innerHTML = `<div class="error">Couldn't load repositories: ${escapeHtml(e.message)}</div>`;
        }
    }

    function renderRepos() {
        const list = document.getElementById('repoList');
        if (repos.length === 0) {
            list.innerHTML = `<div class="bk-empty">No backup repositories yet.<br>Add one to point at the borg repo on your NAS.</div>`;
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
                        <span><span class="bk-meta-k">NAS</span><span class="bk-meta-v">${escapeHtml(repo.nasHost)}:${escapeHtml(String(repo.sshPort))}</span></span>
                        <span><span class="bk-meta-k">User</span><span class="bk-meta-v">${escapeHtml(repo.borgUser)}</span></span>
                        <span><span class="bk-meta-k">Path</span><span class="bk-meta-v">${escapeHtml(repo.repoPath)}</span></span>
                    </div>
                    <div class="bk-tags">${tags.join('')}</div>
                </div>
                <div class="bk-item-actions">
                    <button class="btn btn-small btn-secondary js-archives">Browse archives</button>
                    <button class="btn btn-small btn-secondary js-init">Initialise</button>
                    <button class="btn btn-small btn-secondary js-edit">Edit</button>
                    <button class="btn btn-small btn-danger js-delete">Delete</button>
                </div>`;
            el.querySelector('.js-archives').addEventListener('click', () => browseArchives(repo));
            el.querySelector('.js-init').addEventListener('click', () => initRepository(repo));
            el.querySelector('.js-edit').addEventListener('click', () => openRepoModal(repo));
            el.querySelector('.js-delete').addEventListener('click', () => confirmDeleteRepo(repo));
            list.appendChild(el);
        });
    }

    function openRepoModal(repo) {
        editingRepoName = repo ? repo.name : null;
        clearModalError('repoModalError');
        document.getElementById('repoModalTitle').textContent = repo ? 'Edit backup repository' : 'New backup repository';
        const nameEl = document.getElementById('repoName');
        nameEl.value = repo ? repo.name : '';
        nameEl.disabled = !!repo;
        document.getElementById('repoNameNote').style.display = repo ? 'none' : '';
        document.getElementById('repoNasHost').value = repo ? repo.nasHost : '';
        document.getElementById('repoSshPort').value = repo ? repo.sshPort : '';
        document.getElementById('repoBorgUser').value = repo ? repo.borgUser : '';
        document.getElementById('repoPath').value = repo ? repo.repoPath : '';
        const pass = document.getElementById('repoPassphrase');
        pass.value = '';
        pass.placeholder = repo ? 'Leave blank to keep the stored passphrase' : 'Enter a passphrase';
        document.getElementById('repoAppendOnly').checked = repo ? repo.appendOnly : false;
        openModal('repoModal');
        if (!repo) nameEl.focus();
    }

    async function saveRepo() {
        clearModalError('repoModalError');
        const name = document.getElementById('repoName').value.trim();
        const nasHost = document.getElementById('repoNasHost').value.trim();
        const repoPath = document.getElementById('repoPath').value.trim();
        const passphrase = document.getElementById('repoPassphrase').value;
        if (!name || !nasHost || !repoPath) {
            showModalError('repoModalError', 'Name, NAS host and repository path are required.');
            return;
        }
        if (!editingRepoName && !passphrase) {
            showModalError('repoModalError', 'A passphrase is required when creating a repository.');
            return;
        }
        const sshPortRaw = document.getElementById('repoSshPort').value.trim();
        const body = {
            nasHost,
            sshPort: sshPortRaw === '' ? null : parseInt(sshPortRaw, 10),
            borgUser: document.getElementById('repoBorgUser').value.trim(),
            repoPath,
            passphrase,
            appendOnly: document.getElementById('repoAppendOnly').checked
        };
        const btn = document.getElementById('repoSaveBtn');
        btn.disabled = true;
        try {
            await jsonOrThrow(await fetch('/backup-repositories/' + encodeURIComponent(name), {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(body)
            }));
            closeModal('repoModal');
            msg('Repository "' + name + '" saved.', 'success');
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

    async function initRepository(repo) {
        document.getElementById('provisionModalTitle').textContent = 'Initialise · ' + repo.name;
        const body = document.getElementById('provisionBody');
        body.innerHTML = '<div class="loading">Initialising repository…</div>';
        openModal('provisionModal');
        try {
            const res = await fetch('/backup-repositories/' + encodeURIComponent(repo.name) + '/provision/init', { method: 'POST' });
            const result = await jsonOrThrow(res);
            const mark = result.initialized
                ? '<span class="bk-check-mark pass">✓</span>'
                : '<span class="bk-check-mark fail">✕</span>';
            const heading = result.alreadyExisted
                ? 'Repository already initialised'
                : result.initialized ? 'Repository initialised' : 'Initialisation did not complete';
            body.innerHTML = `<div class="bk-check-list">
                <div class="bk-check-row">${mark}<span>${escapeHtml(heading)}</span></div>
                <div class="bk-field-note">${escapeHtml(result.message || '')}</div>
            </div>`;
        } catch (e) {
            body.innerHTML = `<div class="error">${escapeHtml(e.message)}</div>`;
        }
    }

    // --- Jobs ---
    async function loadJobs() {
        const list = document.getElementById('jobList');
        try {
            jobs = await jsonOrThrow(await fetch('/backup-jobs', { cache: 'no-store' }));
            renderJobs();
            jobs.forEach(refreshRunBadge);
        } catch (e) {
            if (handleGated(e)) return;
            list.innerHTML = `<div class="error">Couldn't load jobs: ${escapeHtml(e.message)}</div>`;
        }
    }

    function statusBadge(run) {
        if (!run) return '<span class="status status-unknown"><span class="status-indicator"></span>Never run</span>';
        const t = formatTime(run.finishedAt || run.startedAt);
        const time = t ? `<span class="bk-run-time">${escapeHtml(t)}</span>` : '';
        switch (run.status) {
            case 'RUNNING': return '<span class="status status-running"><span class="status-indicator"></span>Running</span>';
            case 'SUCCESS': return `<span class="status status-ok"><span class="status-indicator"></span>Success</span>${time}`;
            case 'FAILED':  return `<span class="status status-error"><span class="status-indicator"></span>Failed</span>${time}`;
            default:        return `<span class="status status-unknown"><span class="status-indicator"></span>Unknown</span>${time}`;
        }
    }

    function renderJobs() {
        const list = document.getElementById('jobList');
        if (jobs.length === 0) {
            list.innerHTML = `<div class="bk-empty">No backup jobs yet.<br>Add a job to back up a machine to one of your repositories.</div>`;
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
                </div>
                <div class="bk-item-actions">
                    <button class="btn btn-small btn-success js-run">Run now</button>
                    <button class="btn btn-small btn-secondary js-check">Check readiness</button>
                    <button class="btn btn-small btn-secondary js-edit">Edit</button>
                    <button class="btn btn-small btn-danger js-delete">Delete</button>
                </div>`;
            el.querySelector('.js-run').addEventListener('click', () => runJob(job));
            el.querySelector('.js-check').addEventListener('click', () => checkReadiness(job));
            el.querySelector('.js-edit').addEventListener('click', () => openJobModal(job));
            el.querySelector('.js-delete').addEventListener('click', () => confirmDeleteJob(job));
            el.querySelector('.js-enabled').addEventListener('change', ev => toggleEnabled(job, ev.target.checked));
            list.appendChild(el);
        });
    }

    // Repaint just one job's last-run badge without re-rendering the whole list.
    function paintBadge(jobName) {
        const row = document.querySelector(`.bk-item[data-job="${CSS.escape(jobName)}"] .bk-run-badge`);
        if (row) row.innerHTML = statusBadge(latestRuns[jobName]);
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
            enabled: document.getElementById('jobEnabled').checked
        };
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
                stopPoll(job.name);
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
            msg('Run started for "' + job.name + '".', 'success');
            startPoll(job.name);
        } catch (e) {
            msg(e.message, 'error');
        }
    }

    function startPoll(jobName) {
        stopPoll(jobName);
        pollTimers[jobName] = setInterval(async () => {
            try {
                const res = await fetch('/backup-jobs/' + encodeURIComponent(jobName) + '/runs', { cache: 'no-store' });
                if (!res.ok) return;
                const run = await res.json();
                latestRuns[jobName] = run;
                paintBadge(jobName);
                if (run.status !== 'RUNNING') {
                    stopPoll(jobName);
                    if (run.status === 'SUCCESS') msg('Backup of "' + jobName + '" finished.', 'success');
                    else if (run.status === 'FAILED') msg('Backup of "' + jobName + '" failed — admins were emailed.', 'error');
                }
            } catch (_) { /* keep polling */ }
        }, 3000);
    }

    function stopPoll(jobName) {
        if (pollTimers[jobName]) { clearInterval(pollTimers[jobName]); delete pollTimers[jobName]; }
    }

    async function checkReadiness(job) {
        document.getElementById('provisionModalTitle').textContent = 'Host readiness · ' + job.name;
        const body = document.getElementById('provisionBody');
        body.innerHTML = '<div class="loading">Checking…</div>';
        openModal('provisionModal');
        try {
            const c = await jsonOrThrow(await fetch('/backup-jobs/' + encodeURIComponent(job.name) + '/provision/check', { cache: 'no-store' }));
            const mark = ok => ok ? '<span class="bk-check-mark pass">✓</span>' : '<span class="bk-check-mark fail">✕</span>';
            const version = c.borgVersion ? `<span class="bk-check-sub">${escapeHtml(c.borgVersion)}</span>` : '';
            body.innerHTML = `<div class="bk-check-list">
                <div class="bk-check-row">${mark(c.borgInstalled)}<span>borg installed on ${escapeHtml(job.machineName)}</span>${version}</div>
                <div class="bk-check-row">${mark(c.borgSupported)}<span>borg version supported</span></div>
                <div class="bk-check-row">${mark(c.nasReachable)}<span>NAS reachable from the machine</span></div>
            </div>`;
        } catch (e) {
            body.innerHTML = `<div class="error">${escapeHtml(e.message)}</div>`;
        }
    }

    // --- Machines (job picker) ---
    async function loadMachines() {
        try {
            const peers = await jsonOrThrow(await fetch('/vpn/peers', { cache: 'no-store' }));
            machines = peers.map(p => p.name).filter(Boolean).sort((a, b) => a.localeCompare(b));
        } catch (_) {
            machines = [];
        }
    }

    // --- Nightly schedule ---
    async function loadSchedule() {
        const sel = document.getElementById('scheduleHour');
        sel.innerHTML = Array.from({ length: 24 }, (_, h) =>
            `<option value="${h}">${String(h).padStart(2, '0')}:00</option>`).join('');
        try {
            const cfg = await jsonOrThrow(await fetch('/settings/config', { cache: 'no-store' }));
            const hour = Number.isInteger(cfg.backupScheduleHour) ? cfg.backupScheduleHour : 2;
            sel.value = String(hour);
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
        document.getElementById('newRepoBtn').addEventListener('click', () => openRepoModal(null));
        document.getElementById('repoCancelBtn').addEventListener('click', () => closeModal('repoModal'));
        document.getElementById('repoSaveBtn').addEventListener('click', saveRepo);

        document.getElementById('newJobBtn').addEventListener('click', () => openJobModal(null));
        document.getElementById('jobCancelBtn').addEventListener('click', () => closeModal('jobModal'));
        document.getElementById('jobSaveBtn').addEventListener('click', saveJob);

        document.getElementById('archivesCloseBtn').addEventListener('click', () => closeModal('archivesModal'));
        document.getElementById('provisionCloseBtn').addEventListener('click', () => closeModal('provisionModal'));

        document.getElementById('confirmCancelBtn').addEventListener('click', () => { closeModal('confirmModal'); confirmCallback = null; });
        document.getElementById('confirmOkBtn').addEventListener('click', runConfirm);

        document.getElementById('saveScheduleBtn').addEventListener('click', saveSchedule);

        // Click on the dim backdrop closes a modal (matches vpn-peers/users).
        window.addEventListener('click', e => {
            if (e.target.classList.contains('modal')) e.target.classList.remove('active');
        });
    }

    async function init() {
        wire();
        if (!(await isEnterprise())) { showGate(); return; }
        document.getElementById('bk-app').style.display = '';
        await loadMachines();
        await Promise.all([loadRepositories(), loadJobs(), loadSchedule()]);
    }

    init();
})();
