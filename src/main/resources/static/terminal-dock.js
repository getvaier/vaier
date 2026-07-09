// The terminal dock — lives in the admin shell (admin.html), not in any section page, so its live SSH
// sessions survive navigating between Infrastructure / Users / Settings. Rendered into the #terminalPanel
// the shell shows/hides via the Terminal nav tab.
//
// One tab per open shell. On desktop the pane area is a 2-D split grid (rows of columns): clicking a tab
// focuses a shell alone; dragging a tab onto a pane's left/right edge adds a column, onto its top/bottom
// edge adds a row, and dividers between rows and columns resize them. On a phone the grid collapses to a
// single full-screen pane switched by tab. A machine's Terminal button (Infrastructure iframe) reaches
// this via window.parent.vaierOpenTerminal. Keystrokes go out as binary WebSocket frames, resize as a
// JSON control frame; remote output arrives as binary frames. Vaier authenticates server-side from the
// credential vault — no secret reaches the browser. A dropped connection reconnects automatically.
(function () {
    const _terminals = new Map();   // id -> { pane, tab, term, fit, ws, machine, retries, reconnectTimer, raf }
    let _seq = 0;
    let _rows = [];                 // [{ h, cols: [{ id, w }] }] — the 2-D split grid; h/w are flex-grow weights
    let _focusedId = null;          // the pane that has keyboard focus
    const _recent = [];             // ids most-recently-focused first — picks a partner when splitting
    let _fitRaf = 0;
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
    // Codes that won't succeed on retry — don't auto-reconnect these (a clean exit or a permanent error).
    const PERMANENT = new Set([1000, 4401, 4402, 4403, 4404]);
    const MAX_RECONNECTS = 8;

    // A phone's narrow screen fits far more columns at a smaller font, so shells are legible without
    // horizontal scrolling. The size follows viewport width live (rotating a phone re-fits every shell),
    // and crossing the breakpoint re-lays out (a desktop split collapses to a single pane on a phone).
    const _phone = window.matchMedia('(max-width: 720px)');
    function isPhone() { return _phone.matches; }
    function termFontSize() { return isPhone() ? 10 : 13; }
    try {
        _phone.addEventListener('change', () => {
            const size = termFontSize();
            for (const [, s] of _terminals) s.term.options.fontSize = size;
            layout();
        });
    } catch (e) { /* older browsers: font is set at shell-open time only */ }

    function tabs() { return document.getElementById('terminalTabs'); }
    function panes() { return document.getElementById('terminalPanes'); }

    function open(machineName) {
        const id = ++_seq;
        const pane = buildPane(id, machineName);
        const tab = buildTab(id, machineName);
        panes().appendChild(pane);
        tabs().appendChild(tab);

        const term = new Terminal({
            cursorBlink: true, fontFamily: 'var(--mono, monospace)', fontSize: termFontSize(),
            theme: { background: '#000000' }, scrollback: 5000,
        });
        const fit = new FitAddon.FitAddon();
        term.loadAddon(fit);
        const body = pane.querySelector('.term-window-body');
        term.open(body);
        attachTouchScroll(body, term);

        const state = { id, pane, tab, term, fit, ws: null, machine: machineName, retries: 0, reconnectTimer: 0, raf: 0 };
        _terminals.set(id, state);

        term.onData((data) => { if (state.ws && state.ws.readyState === WebSocket.OPEN) state.ws.send(new TextEncoder().encode(data)); });
        term.onResize(() => sendResize(id));
        connect(state);

        focusSolo(id);
        notifyChange();
    }

    // (Re)open the WebSocket for a session. On an unexpected drop this is called again after a backoff, so
    // a server restart or a flaky tunnel heals itself; a clean exit or a permanent error is left alone.
    function connect(state) {
        const { id, machine } = state;
        const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const ws = new WebSocket(`${proto}//${window.location.host}/machines/${encodeURIComponent(machine)}/terminal`);
        ws.binaryType = 'arraybuffer';
        state.ws = ws;

        ws.onopen = () => {
            const reconnected = state.retries > 0;
            state.retries = 0;
            setDot(id, null);
            setStatus(id, null);
            if (reconnected) state.term.write('\r\n\x1b[32m[reconnected]\x1b[0m\r\n');
            if (isVisible(id)) { fitOne(id); sendResize(id); if (_focusedId === id) state.term.focus(); }
        };
        // Binary frames are shell output (write to xterm); text frames are JSON control replies from the
        // server (e.g. the password-result). Keep them apart so a control reply never corrupts the pty stream.
        ws.onmessage = (ev) => {
            if (ev.data instanceof ArrayBuffer) { state.term.write(new Uint8Array(ev.data)); return; }
            if (typeof ev.data === 'string') handleControlMessage(state, ev.data);
        };
        ws.onclose = (ev) => {
            if (!_terminals.has(id)) return;   // closed by the user; nothing to report or retry
            // A clean exit (the remote shell ended) closes the pane outright — no dead window to tidy by hand.
            // Deferred to a macrotask so we never mutate _terminals / the DOM from inside the ws's own onclose
            // dispatch; closeShell then nulls this handler and closes an already-closed socket (a safe no-op).
            if (ev.code === 1000) { setTimeout(() => { if (_terminals.has(id)) closeShell(id); }, 0); return; }
            setDot(id, 'error');
            if (!PERMANENT.has(ev.code) && state.retries < MAX_RECONNECTS) {
                state.retries++;
                const delay = Math.min(8000, 1000 * 2 ** (state.retries - 1));
                setStatus(id, `Connection lost — reconnecting (attempt ${state.retries})…`, true);
                state.reconnectTimer = setTimeout(() => { if (_terminals.has(id)) connect(state); }, delay);
            } else if (!PERMANENT.has(ev.code)) {
                setStatus(id, 'Connection lost. The host did not come back.', true, 0, machine, () => { state.retries = 0; connect(state); });
            } else {
                setStatus(id, CLOSE_REASONS[ev.code] || (ev.reason || 'The terminal connection closed.'), true, ev.code, machine);
            }
        };
        ws.onerror = () => { /* the close handler reports the reason */ };
    }

    function buildPane(id, machineName) {
        const pane = document.createElement('div');
        pane.className = 'term-pane';
        pane.dataset.termId = id;
        pane.addEventListener('mousedown', () => { if (_focusedId !== id && isVisible(id)) { _focusedId = id; markFocus(); } });

        const head = document.createElement('div');
        head.className = 'term-pane-head';
        const hname = document.createElement('span');
        hname.className = 'term-pane-head-name';
        hname.textContent = machineName;             // textContent — never inject a name as HTML
        const hclose = document.createElement('button');
        hclose.type = 'button';
        hclose.className = 'term-pane-head-close';
        hclose.textContent = '✕';
        hclose.title = 'Remove from split';
        hclose.setAttribute('aria-label', 'Remove ' + machineName + ' from the split view');
        hclose.onclick = (e) => { e.stopPropagation(); removeFromView(id); };
        head.append(hname, hclose);

        const status = document.createElement('div');
        status.className = 'term-window-status';
        status.style.display = 'none';

        // A per-pane action row above the viewport — a left-aligned menu line that stays with the shell and
        // will hold future terminal actions. Send password leads it (the key sends the machine's stored
        // password straight from Vaier into the shell; it never touches the browser).
        const toolbar = document.createElement('div');
        toolbar.className = 'term-pane-toolbar';
        const sendPw = document.createElement('button');
        sendPw.type = 'button';
        sendPw.className = 'term-toolbar-btn';
        sendPw.title = 'Send stored password';
        sendPw.setAttribute('aria-label', 'Send the stored password to ' + machineName);
        const sendPwGlyph = document.createElement('span');
        sendPwGlyph.className = 'term-toolbar-btn-glyph';
        sendPwGlyph.setAttribute('aria-hidden', 'true');
        sendPwGlyph.textContent = '🔑';
        const sendPwLabel = document.createElement('span');
        sendPwLabel.textContent = 'Send password';
        sendPw.append(sendPwGlyph, sendPwLabel);
        sendPw.onclick = (e) => { e.stopPropagation(); sendPassword(id); };
        toolbar.appendChild(sendPw);

        const body = document.createElement('div');
        body.className = 'term-window-body';
        pane.append(head, status, toolbar, body);
        return pane;
    }

    function buildTab(id, machineName) {
        const tab = document.createElement('div');
        tab.className = 'term-tab';
        tab.dataset.termId = id;
        tab.addEventListener('pointerdown', (e) => onTabDragStart(e, id, tab));

        const dot = document.createElement('span');
        dot.className = 'term-window-dot';
        const name = document.createElement('span');
        name.className = 'term-tab-name';
        name.textContent = machineName;              // textContent — never inject a name as HTML
        const label = document.createElement('button');
        label.type = 'button';
        label.className = 'term-tab-label';
        label.append(dot, name);
        label.onclick = () => focus(id);
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

    // --- the split grid ------------------------------------------------------------------------------

    function visibleIds() { const out = []; for (const r of _rows) for (const c of r.cols) out.push(c.id); return out; }
    function isVisible(id) { for (const r of _rows) for (const c of r.cols) if (c.id === id) return true; return false; }
    function findCell(id) {
        for (let ri = 0; ri < _rows.length; ri++) {
            const ci = _rows[ri].cols.findIndex((c) => c.id === id);
            if (ci >= 0) return { ri, ci };
        }
        return null;
    }
    function firstId() { const it = _terminals.keys().next(); return it.done ? null : it.value; }
    function setSingle(id) { _rows = [{ h: 1, cols: [{ id, w: 1 }] }]; _focusedId = id; }
    function removeFromGrid(id) {
        for (const r of _rows) r.cols = r.cols.filter((c) => c.id !== id);
        _rows = _rows.filter((r) => r.cols.length > 0);
    }
    function normalizeGrid() {
        for (const r of _rows) r.cols = r.cols.filter((c) => _terminals.has(c.id));
        _rows = _rows.filter((r) => r.cols.length > 0);
    }

    // Clicking a tab focuses it. Already on screen → just take keyboard focus (keep the split); otherwise
    // show it alone. Building a split is done by dragging a tab into the pane area, not by clicking.
    function focus(id) {
        if (!_terminals.has(id)) return;
        if (isVisible(id)) { _focusedId = id; markFocus(); focusTerm(id); }
        else focusSolo(id);
    }

    function focusSolo(id) { setSingle(id); layout(); focusTerm(id); }

    // Drop a dragged tab into the grid at a target pane's edge (left/right → new column, top/bottom → new
    // row). Dropping the only on-screen shell splits it against the most-recently-used other shell.
    function dropTab(id, target) {
        if (!_terminals.has(id) || isPhone()) return;
        removeFromGrid(id);

        if (_rows.length === 0) {
            const partner = _recent.find((r) => r !== id && _terminals.has(r));
            if (partner == null) { setSingle(id); layout(); focusTerm(id); return; }
            const dir = target ? target.dir : 'right';
            if (dir === 'top' || dir === 'bottom') {
                const a = { h: 1, cols: [{ id: partner, w: 1 }] }, b = { h: 1, cols: [{ id, w: 1 }] };
                _rows = dir === 'top' ? [b, a] : [a, b];
            } else {
                const cols = dir === 'left' ? [{ id, w: 1 }, { id: partner, w: 1 }] : [{ id: partner, w: 1 }, { id, w: 1 }];
                _rows = [{ h: 1, cols }];
            }
            _focusedId = id; layout(); focusTerm(id); return;
        }

        const cell = target ? findCell(target.targetId) : null;
        if (!cell) { _rows.push({ h: 1, cols: [{ id, w: 1 }] }); }   // dropped on empty space → new bottom row
        else if (target.dir === 'left' || target.dir === 'right') {
            const at = target.dir === 'right' ? cell.ci + 1 : cell.ci;
            _rows[cell.ri].cols.splice(at, 0, { id, w: 1 });
        } else {
            const at = target.dir === 'bottom' ? cell.ri + 1 : cell.ri;
            _rows.splice(at, 0, { h: 1, cols: [{ id, w: 1 }] });
        }
        _focusedId = id; layout(); focusTerm(id);
    }

    function removeFromView(id) {
        removeFromGrid(id);
        if (visibleIds().length === 0 && _terminals.size) setSingle(firstId());
        else if (_focusedId === id) _focusedId = visibleIds()[0] ?? null;
        layout();
        if (_focusedId != null) focusTerm(_focusedId);
    }

    // Close one shell entirely: stop reconnects, drop its session and tab, then re-tile whatever's left.
    function closeShell(id) {
        const s = _terminals.get(id);
        if (!s) return;
        clearTimeout(s.reconnectTimer);
        if (s.ws) try { s.ws.onclose = null; s.ws.close(); } catch (e) { /* ignore */ }
        if (s.term) try { s.term.dispose(); } catch (e) { /* ignore */ }
        if (s.pane && s.pane.parentNode) s.pane.parentNode.removeChild(s.pane);
        if (s.tab && s.tab.parentNode) s.tab.parentNode.removeChild(s.tab);
        _terminals.delete(id);
        removeFromGrid(id);
        const ri = _recent.indexOf(id);
        if (ri >= 0) _recent.splice(ri, 1);
        if (_focusedId === id) _focusedId = null;
        if (visibleIds().length === 0 && _terminals.size) setSingle(firstId());
        else if (_focusedId == null) _focusedId = visibleIds()[0] ?? null;
        layout();
        if (_focusedId != null) focusTerm(_focusedId);
        notifyChange();
    }

    // Render the grid: a vertical stack of rows (each a horizontal strip of panes) with a drag-divider
    // between every adjacent pair. Hidden shells keep running; the empty state shows when nothing is open.
    function layout() {
        const p = panes();
        const empty = document.getElementById('terminalEmpty');

        normalizeGrid();
        if (isPhone() || (_rows.length === 0 && _terminals.size)) {
            const keep = (_focusedId != null && _terminals.has(_focusedId)) ? _focusedId : firstId();
            _rows = keep != null ? [{ h: 1, cols: [{ id: keep, w: 1 }] }] : [];
        }
        const vis = visibleIds();
        if (_focusedId == null || !vis.includes(_focusedId)) _focusedId = vis[0] ?? null;

        p.querySelectorAll('.term-row, .term-split-divider').forEach((el) => el.remove());
        const split = vis.length > 1;

        _rows.forEach((row, ri) => {
            if (ri > 0) p.appendChild(makeRowDivider(ri - 1));
            const rowEl = document.createElement('div');
            rowEl.className = 'term-row';
            rowEl.style.flexGrow = row.h || 1;
            row.cols.forEach((col, ci) => {
                if (ci > 0) rowEl.appendChild(makeColDivider(ri, ci - 1));
                const s = _terminals.get(col.id);
                s.pane.style.flexGrow = col.w || 1;
                s.pane.classList.add('is-visible');
                s.pane.classList.toggle('show-head', split);
                s.pane.classList.toggle('is-focused', col.id === _focusedId);
                s.tab.classList.toggle('is-active', col.id === _focusedId);
                rowEl.appendChild(s.pane);
            });
            p.appendChild(rowEl);
        });
        for (const [id, s] of _terminals) {
            if (vis.includes(id)) continue;
            s.pane.classList.remove('is-visible', 'is-focused', 'show-head');
            p.appendChild(s.pane);
            s.tab.classList.remove('is-active');
        }
        if (empty) { p.appendChild(empty); empty.style.display = _terminals.size === 0 ? 'flex' : 'none'; }

        fitVisibleSoon();
    }

    function markFocus() {
        for (const [id, s] of _terminals) {
            const on = id === _focusedId;
            s.pane.classList.toggle('is-focused', on && isVisible(id));
            s.tab.classList.toggle('is-active', on);
        }
    }

    // --- dividers (drag to resize adjacent rows / columns) -------------------------------------------

    function makeColDivider(ri, leftCi) {
        const d = document.createElement('div');
        d.className = 'term-split-divider';
        d.title = 'Drag to resize';
        d.addEventListener('pointerdown', (e) => startColDrag(e, d, ri, leftCi));
        return d;
    }
    function makeRowDivider(topRi) {
        const d = document.createElement('div');
        d.className = 'term-split-divider horizontal';
        d.title = 'Drag to resize';
        d.addEventListener('pointerdown', (e) => startRowDrag(e, d, topRi));
        return d;
    }

    function startColDrag(e, d, ri, leftCi) {
        const row = _rows[ri];
        if (!row) return;
        const A = row.cols[leftCi], B = row.cols[leftCi + 1];
        if (!A || !B) return;
        e.preventDefault();
        const pa = _terminals.get(A.id).pane, pb = _terminals.get(B.id).pane;
        const aw = pa.getBoundingClientRect().width, bw = pb.getBoundingClientRect().width, total = aw + bw;
        const startX = e.clientX;
        d.setPointerCapture(e.pointerId);
        const move = (me) => {
            const na = Math.max(140, Math.min(total - 140, aw + (me.clientX - startX)));
            A.w = na; B.w = total - na;
            pa.style.flexGrow = na; pb.style.flexGrow = total - na;
            fitVisibleSoon();
        };
        const up = () => { d.removeEventListener('pointermove', move); d.removeEventListener('pointerup', up); fitVisibleSoon(); };
        d.addEventListener('pointermove', move);
        d.addEventListener('pointerup', up);
    }

    function startRowDrag(e, d, topRi) {
        const A = _rows[topRi], B = _rows[topRi + 1];
        if (!A || !B) return;
        e.preventDefault();
        const rowEls = [...panes().querySelectorAll('.term-row')];
        const ea = rowEls[topRi], eb = rowEls[topRi + 1];
        if (!ea || !eb) return;
        const ah = ea.getBoundingClientRect().height, bh = eb.getBoundingClientRect().height, total = ah + bh;
        const startY = e.clientY;
        d.setPointerCapture(e.pointerId);
        const move = (me) => {
            const na = Math.max(90, Math.min(total - 90, ah + (me.clientY - startY)));
            A.h = na; B.h = total - na;
            ea.style.flexGrow = na; eb.style.flexGrow = total - na;
            fitVisibleSoon();
        };
        const up = () => { d.removeEventListener('pointermove', move); d.removeEventListener('pointerup', up); fitVisibleSoon(); };
        d.addEventListener('pointermove', move);
        d.addEventListener('pointerup', up);
    }

    // --- drag a tab into the grid --------------------------------------------------------------------

    function onTabDragStart(e, id, tab) {
        if (e.button !== 0 || isPhone()) return;
        if (e.target.closest('.term-tab-close')) return;
        const startX = e.clientX, startY = e.clientY, pid = e.pointerId;
        let moved = false, ghost = null;
        const move = (me) => {
            if (!moved) {
                if (Math.hypot(me.clientX - startX, me.clientY - startY) < 6) return;
                moved = true;
                // Capture the pointer so the drop still lands on release, and preventDefault below so the
                // browser doesn't start a text selection over the terminal (which would cancel the drag).
                try { tab.setPointerCapture(pid); } catch (_) { /* ignore */ }
                tab.classList.add('dragging');
                ghost = document.createElement('div');
                ghost.className = 'term-drag-ghost';
                ghost.textContent = tab.querySelector('.term-tab-name').textContent;
                document.body.appendChild(ghost);
            }
            me.preventDefault();
            ghost.style.left = me.clientX + 'px';
            ghost.style.top = me.clientY + 'px';
            showDropZone(me);
        };
        const end = (ue, drop) => {
            document.removeEventListener('pointermove', move);
            document.removeEventListener('pointerup', up);
            document.removeEventListener('pointercancel', cancel);
            if (!moved) return;                 // a plain click → the tab's own click focuses it
            if (ghost) ghost.remove();
            tab.classList.remove('dragging');
            hideDropZone();
            if (drop && pointInPanes(ue)) dropTab(id, dropTarget(ue));
            suppressNextClick(tab);
        };
        const up = (ue) => end(ue, true);
        const cancel = (ue) => end(ue, false);
        document.addEventListener('pointermove', move);
        document.addEventListener('pointerup', up);
        document.addEventListener('pointercancel', cancel);
    }

    function pointInPanes(e) {
        const r = panes().getBoundingClientRect();
        return e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom;
    }

    // The pane under the pointer and which edge is nearest → where a drop would land.
    function dropTarget(e) {
        for (const id of visibleIds()) {
            const r = _terminals.get(id).pane.getBoundingClientRect();
            if (e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom) {
                const d = { left: (e.clientX - r.left) / r.width, right: (r.right - e.clientX) / r.width,
                            top: (e.clientY - r.top) / r.height, bottom: (r.bottom - e.clientY) / r.height };
                const dir = Object.keys(d).reduce((a, b) => (d[b] < d[a] ? b : a));
                return { targetId: id, dir };
            }
        }
        return null;   // empty space → append as a new row
    }

    function showDropZone(e) {
        const ind = dropIndicator();
        const t = pointInPanes(e) ? dropTarget(e) : null;
        if (!t) { ind.style.display = 'none'; return; }
        const pr = _terminals.get(t.targetId).pane.getBoundingClientRect();
        const cr = panes().getBoundingClientRect();
        let x = pr.left - cr.left, y = pr.top - cr.top, w = pr.width, h = pr.height;
        if (t.dir === 'left') w = pr.width / 2;
        else if (t.dir === 'right') { x += pr.width / 2; w = pr.width / 2; }
        else if (t.dir === 'top') h = pr.height / 2;
        else if (t.dir === 'bottom') { y += pr.height / 2; h = pr.height / 2; }
        ind.style.display = 'block';
        ind.style.left = x + 'px'; ind.style.top = y + 'px';
        ind.style.width = w + 'px'; ind.style.height = h + 'px';
    }

    function dropIndicator() {
        let ind = panes().querySelector('.term-drop-indicator');
        if (!ind) { ind = document.createElement('div'); ind.className = 'term-drop-indicator'; panes().appendChild(ind); }
        return ind;
    }
    function hideDropZone() { const ind = panes().querySelector('.term-drop-indicator'); if (ind) ind.style.display = 'none'; }

    // A drag ends with a synthetic click on the tab; swallow that one so it doesn't also re-focus.
    function suppressNextClick(el) {
        const h = (ev) => { ev.stopPropagation(); ev.preventDefault(); el.removeEventListener('click', h, true); };
        el.addEventListener('click', h, true);
        setTimeout(() => el.removeEventListener('click', h, true), 0);
    }

    // --- fit / resize / touch scroll -----------------------------------------------------------------

    function fitOne(id) {
        const s = _terminals.get(id);
        if (!s) return;
        try { s.fit.fit(); } catch (e) { /* not laid out yet */ }
    }

    function sendResize(id) {
        const s = _terminals.get(id);
        if (!s || !s.ws || s.ws.readyState !== WebSocket.OPEN) return;
        s.ws.send(JSON.stringify({ type: 'resize', cols: s.term.cols, rows: s.term.rows }));
    }

    function focusTerm(id) {
        touchRecent(id);
        const s = _terminals.get(id);
        if (s) requestAnimationFrame(() => { fitOne(id); sendResize(id); s.term.focus(); });
    }

    function touchRecent(id) {
        const i = _recent.indexOf(id);
        if (i >= 0) _recent.splice(i, 1);
        _recent.unshift(id);
    }

    function fitVisibleSoon() {
        cancelAnimationFrame(_fitRaf);
        _fitRaf = requestAnimationFrame(() => { for (const id of visibleIds()) { fitOne(id); sendResize(id); } });
    }

    // xterm's scrollable viewport is a sibling of its text layer, so a touch landing on the text has no
    // scrollable ancestor but the page — which is why the page scrolled instead of the shell. Drive the
    // scrollback from the finger directly and swallow the gesture so the page never moves.
    function attachTouchScroll(el, term) {
        let lastY = null, acc = 0;
        el.addEventListener('touchstart', (e) => {
            if (e.touches.length !== 1) { lastY = null; return; }
            lastY = e.touches[0].clientY; acc = 0;
        }, { passive: true });
        el.addEventListener('touchmove', (e) => {
            if (lastY == null || e.touches.length !== 1) return;
            const y = e.touches[0].clientY;
            acc += lastY - y; lastY = y;
            const cell = Math.max(8, el.clientHeight / Math.max(1, term.rows));
            if (Math.abs(acc) >= cell) {
                const lines = Math.trunc(acc / cell);
                term.scrollLines(lines);   // +down (finger up reveals newer lines), matching native feel
                acc -= lines * cell;
            }
            e.preventDefault();
        }, { passive: false });
        const clear = () => { lastY = null; };
        el.addEventListener('touchend', clear);
        el.addEventListener('touchcancel', clear);
    }

    // --- status / chrome -----------------------------------------------------------------------------

    function setDot(id, kind) {
        const s = _terminals.get(id);
        if (!s) return;
        const dot = s.tab.querySelector('.term-window-dot');
        dot.classList.remove('error', 'closed');
        if (kind) dot.classList.add(kind);
    }

    // The per-shell status line: a plain notice, or an error with an optional inline action — a host-key
    // mismatch (4403) offers "Clear pinned key & retry", a stalled auto-reconnect offers "Reconnect", and
    // the no-credential case (4401) points at Infrastructure, where that credential modal lives.
    function setStatus(id, message, isError, code, machineName, retry) {
        const s = _terminals.get(id);
        if (!s) return;
        const el = s.pane.querySelector('.term-window-status');
        if (!message) { el.style.display = 'none'; el.textContent = ''; el.classList.remove('error'); return; }
        el.classList.toggle('error', !!isError);
        el.textContent = message + ' ';
        if (retry) {
            el.appendChild(statusButton('Reconnect', () => { setStatus(id, null); retry(); }));
        } else if (code === 4403 && machineName) {
            el.appendChild(statusButton('Clear pinned key & retry', async () => {
                await fetch(`/machines/${encodeURIComponent(machineName)}/host-key`, { method: 'DELETE' });
                closeShell(id);
                open(machineName);
            }));
        }
        el.style.display = 'flex';
        if (isVisible(id)) fitOne(id);   // the status line changed the body height
    }

    function statusButton(label, onClick) {
        const btn = document.createElement('button');
        btn.className = 'btn btn-small btn-secondary';
        btn.textContent = label;
        btn.onclick = onClick;
        return btn;
    }

    // A server control reply (text frame). Today the only one is the password-result — the outcome of a
    // Send-password request. The frame never carries the secret; Vaier writes it straight into the remote
    // PTY server-side, so the browser only ever learns whether it went.
    function handleControlMessage(state, raw) {
        let msg;
        try { msg = JSON.parse(raw); } catch (e) { return; }   // ignore anything unparseable
        if (msg && msg.type === 'password-result') showPasswordResult(state.id, msg.status);
    }

    // Ask Vaier to send this machine's stored password into its shell. The password never touches the
    // browser — we send only the request; Vaier checks the remote is at a prompt and writes it there.
    function sendPassword(id) {
        const s = _terminals.get(id);
        if (!s || !s.ws || s.ws.readyState !== WebSocket.OPEN) return;
        s.ws.send(JSON.stringify({ type: 'send-password' }));
    }

    const PASSWORD_RESULTS = {
        SENT: { message: 'Password sent.', error: false },
        NOT_AT_PROMPT: { message: "The remote isn't asking for a password right now.", error: true },
        NO_PASSWORD_CREDENTIAL: { message: 'This machine has no stored password — it uses key auth.', error: true },
        FAILED: { message: "Couldn't send the password. Check the Vaier logs.", error: true },
    };

    function showPasswordResult(id, status) {
        const r = PASSWORD_RESULTS[status] || PASSWORD_RESULTS.FAILED;
        setStatus(id, r.message, r.error);
        // A quiet confirmation clears itself; the advisory cases stay until the next status change so the
        // operator can read why nothing was sent.
        if (!r.error) setTimeout(() => { const s = _terminals.get(id); if (s) setStatus(id, null); }, 2500);
    }

    function notifyChange() { if (_onChange) _onChange(_terminals.size); }

    // Reflow the visible shells whenever the panel changes size (shown via nav, window resized/rotated).
    function initResizeWatch() {
        const p = panes();
        if (p && 'ResizeObserver' in window) new ResizeObserver(() => fitVisibleSoon()).observe(p);
        window.addEventListener('resize', fitVisibleSoon);
    }

    function init() { layout(); initResizeWatch(); }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

    window.TerminalDock = {
        open,
        refitActive: fitVisibleSoon,
        count: () => _terminals.size,
        set onChange(fn) { _onChange = fn; },
    };
})();
