// The terminal dock — lives in the admin shell (admin.html), not in any section page, so its live SSH
// sessions survive navigating between Infrastructure / Users / Settings. Rendered into the #terminalPanel
// the shell shows/hides via the Terminal nav tab. One tab per open shell; only the active pane renders.
//
// A machine's Terminal button (in the Infrastructure iframe) reaches this through window.vaierOpenTerminal,
// which the shell defines. Keystrokes go out as binary WebSocket frames, resize as a JSON control frame;
// remote output arrives as binary frames. Vaier authenticates server-side from the credential vault — no
// secret ever reaches the browser.
(function () {
    const _terminals = new Map();   // id -> { pane, tab, term, fit, ws, machine, raf }
    let _seq = 0;
    let _activeId = null;
    let _onChange = null;

    // Distinct WebSocket close codes → operator-legible reasons (mirrors TerminalWebSocketHandler).
    const CLOSE_REASONS = {
        4401: 'No SSH credential is stored for this machine. Add one from its Infrastructure card.',
        4402: 'Authentication failed — check the stored SSH credential.',
        4403: 'The host key changed and was refused. If you rebuilt this host, clear its pinned key and reconnect.',
        4404: 'Machine not found.',
        4408: 'Could not reach the host (connection refused or timed out).',
        4500: 'The terminal failed to open. Check the Vaier logs.',
    };

    function tabs() { return document.getElementById('terminalTabs'); }
    function panes() { return document.getElementById('terminalPanes'); }

    function open(machineName) {
        const id = ++_seq;
        const pane = buildPane(id);
        const tab = buildTab(id, machineName);
        panes().appendChild(pane);
        tabs().appendChild(tab);

        const term = new Terminal({
            cursorBlink: true, fontFamily: 'var(--mono, monospace)', fontSize: 13,
            theme: { background: '#000000' }, scrollback: 5000,
        });
        const fit = new FitAddon.FitAddon();
        term.loadAddon(fit);
        term.open(pane.querySelector('.term-window-body'));

        const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const ws = new WebSocket(`${proto}//${window.location.host}/machines/${encodeURIComponent(machineName)}/terminal`);
        ws.binaryType = 'arraybuffer';

        _terminals.set(id, { pane, tab, term, fit, ws, machine: machineName, raf: 0 });

        // Only fit/focus while this shell is the visible one — xterm can't measure a hidden pane.
        ws.onopen = () => { if (_activeId === id) { fitOne(id); sendResize(id); term.focus(); } };
        ws.onmessage = (ev) => { if (ev.data instanceof ArrayBuffer) term.write(new Uint8Array(ev.data)); };
        term.onData((data) => { if (ws.readyState === WebSocket.OPEN) ws.send(new TextEncoder().encode(data)); });
        term.onResize(() => sendResize(id));
        ws.onclose = (ev) => {
            setDot(id, ev.code === 1000 ? 'closed' : 'error');
            if (ev.code === 1000) {
                setStatus(id, 'Session ended.', false);
            } else {
                const reason = CLOSE_REASONS[ev.code] || (ev.reason || 'The terminal connection closed.');
                setStatus(id, reason, true, ev.code, machineName);
            }
        };
        ws.onerror = () => { /* the close handler reports the reason */ };

        activate(id);
        notifyChange();
    }

    function buildPane(id) {
        const pane = document.createElement('div');
        pane.className = 'term-pane';
        pane.dataset.termId = id;
        const status = document.createElement('div');
        status.className = 'term-window-status';
        status.style.display = 'none';
        const body = document.createElement('div');
        body.className = 'term-window-body';
        pane.append(status, body);
        return pane;
    }

    function buildTab(id, machineName) {
        const tab = document.createElement('div');
        tab.className = 'term-tab';
        tab.dataset.termId = id;
        const dot = document.createElement('span');
        dot.className = 'term-window-dot';
        const name = document.createElement('span');
        name.className = 'term-tab-name';
        name.textContent = machineName;              // textContent — never inject a name as HTML
        const label = document.createElement('button');
        label.type = 'button';
        label.className = 'term-tab-label';
        label.append(dot, name);
        label.onclick = () => activate(id);
        const close = document.createElement('button');
        close.type = 'button';
        close.className = 'term-tab-close';
        close.title = 'Close shell';
        close.setAttribute('aria-label', 'Close shell to ' + machineName);
        close.textContent = '✕';
        close.onclick = (e) => { e.stopPropagation(); closeShell(id); };
        tab.append(label, close);
        return tab;
    }

    // Show one shell: mark its tab + pane active, hide the rest, and re-fit its PTY (xterm can only
    // measure a visible element, so a pane fits only once it becomes the shown one).
    function activate(id) {
        const s = _terminals.get(id);
        if (!s) return;
        _activeId = id;
        for (const [tid, st] of _terminals) {
            const on = tid === id;
            st.pane.classList.toggle('is-active', on);
            st.tab.classList.toggle('is-active', on);
        }
        renderEmpty();
        requestAnimationFrame(() => { fitOne(id); sendResize(id); s.term.focus(); });
    }

    // Close one shell: drop its session and tab. If it was the visible one, fall through to another
    // open shell; when the last one closes, the empty state shows.
    function closeShell(id) {
        const s = _terminals.get(id);
        if (!s) return;
        if (s.ws) try { s.ws.close(); } catch (e) { /* ignore */ }
        if (s.term) try { s.term.dispose(); } catch (e) { /* ignore */ }
        if (s.pane && s.pane.parentNode) s.pane.parentNode.removeChild(s.pane);
        if (s.tab && s.tab.parentNode) s.tab.parentNode.removeChild(s.tab);
        _terminals.delete(id);
        if (_activeId === id) {
            _activeId = null;
            const next = _terminals.keys().next();
            if (!next.done) activate(next.value);
        }
        renderEmpty();
        notifyChange();
    }

    function fitOne(id) {
        const s = _terminals.get(id);
        if (!s) return;
        try { s.fit.fit(); } catch (e) { /* not laid out yet */ }
    }

    function sendResize(id) {
        const s = _terminals.get(id);
        if (!s || s.ws.readyState !== WebSocket.OPEN) return;
        s.ws.send(JSON.stringify({ type: 'resize', cols: s.term.cols, rows: s.term.rows }));
    }

    function refitActive() {
        if (_activeId == null) return;
        const s = _terminals.get(_activeId);
        if (!s) return;
        cancelAnimationFrame(s.raf);
        s.raf = requestAnimationFrame(() => { fitOne(_activeId); sendResize(_activeId); });
    }

    function setDot(id, kind) {
        const s = _terminals.get(id);
        if (!s) return;
        const dot = s.tab.querySelector('.term-window-dot');
        dot.classList.remove('error', 'closed');
        if (kind) dot.classList.add(kind);
    }

    // The per-shell status line: a plain notice, or an error. A host-key mismatch (4403) offers a
    // self-contained "Clear pinned key & retry"; the no-credential case (4401) points at Infrastructure,
    // where the credential is stored (that modal lives on the Infrastructure page, not here).
    function setStatus(id, message, isError, code, machineName) {
        const s = _terminals.get(id);
        if (!s) return;
        const el = s.pane.querySelector('.term-window-status');
        if (!message) { el.style.display = 'none'; el.textContent = ''; el.classList.remove('error'); return; }
        el.classList.toggle('error', !!isError);
        el.textContent = message + ' ';
        if (code === 4403 && machineName) {
            el.appendChild(statusButton('Clear pinned key & retry', async () => {
                await fetch(`/machines/${encodeURIComponent(machineName)}/host-key`, { method: 'DELETE' });
                closeShell(id);
                open(machineName);
            }));
        }
        el.style.display = 'flex';
        if (_activeId === id) fitOne(id);   // the status line changed the body height
    }

    function statusButton(label, onClick) {
        const btn = document.createElement('button');
        btn.className = 'btn btn-small btn-secondary';
        btn.textContent = label;
        btn.onclick = onClick;
        return btn;
    }

    function renderEmpty() {
        const empty = document.getElementById('terminalEmpty');
        if (empty) empty.style.display = _terminals.size === 0 ? 'flex' : 'none';
    }

    function notifyChange() { if (_onChange) _onChange(_terminals.size); }

    // Reflow the visible shell whenever the panel changes size (shown via nav, window resized/rotated).
    function initResizeWatch() {
        const p = panes();
        if (p && 'ResizeObserver' in window) new ResizeObserver(() => refitActive()).observe(p);
        window.addEventListener('resize', refitActive);
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => { renderEmpty(); initResizeWatch(); });
    } else {
        renderEmpty();
        initResizeWatch();
    }

    window.TerminalDock = {
        open,
        refitActive,
        count: () => _terminals.size,
        set onChange(fn) { _onChange = fn; },
    };
})();
