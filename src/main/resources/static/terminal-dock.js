// The terminal dock — lives in the admin shell (admin.html), not in any section page, so its live SSH
// sessions survive navigating between Infrastructure / Users / Settings. Rendered into the #terminalPanel
// the shell shows/hides via the Terminal nav tab.
//
// Every open shell is a pane, always — opening one tiles it into the grid, closing one removes it. A shell
// therefore never hides behind a tab it has to be recalled by, and the pane area alone says what is open.
// Each pane wears its own tab as its header: one line above the terminal, flush with the pane's left edge,
// carrying that shell's status dot, name, actions menu and close. The name is written once, where the shell
// it names actually is.
//
// On desktop the pane area is a 2-D split grid (rows of columns): dragging a pane's tab onto another pane's
// left/right edge adds a column, onto its top/bottom edge adds a row, and dividers between rows and columns
// resize them. A shell can be maximized to fill the panel and restored again. On a phone the grid collapses
// to a single full-screen pane, and that pane's header carries every shell's tab so the ones it covers stay
// reachable — the one place a tab is not above its own terminal.
//
// A machine's Terminal button (Infrastructure iframe) reaches this via window.parent.vaierOpenTerminal.
// Keystrokes go out as binary WebSocket frames, resize as a JSON control frame; remote output arrives as
// binary frames. Vaier authenticates server-side from the credential vault — no secret reaches the browser.
// A dropped connection reconnects automatically, and because each pane's shell runs inside a tmux session on
// the machine (a persistent shell), the reconnect reattaches to the same session — surviving a redeploy of
// Vaier itself. Each pane carries a stable id so its reconnects always find the same session; two panes on
// one machine never share one.
(function () {
    const _terminals = new Map();   // id -> { pane, tab, term, fit, ws, machine, retries, reconnectTimer, raf }
    let _seq = 0;
    let _rows = [];                 // [{ h, cols: [{ id, w }] }] — the 2-D split grid; h/w are flex-grow weights
    let _focusedId = null;          // the pane that has keyboard focus
    let _maximizedId = null;        // a shell filling the panel; the grid is kept intact underneath, to restore
    let _visible = [];              // ids actually on screen after the last layout (a maximize or a phone hides the rest)
    let _fitRaf = 0;
    let _onChange = null;

    // A newly opened shell tiles in beside the focused one; a row this wide starts a new row below instead.
    // Two is the point where a terminal still holds a usable number of columns — past it, drag to taste.
    const AUTO_TILE_COLUMNS = 2;

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

    function panes() { return document.getElementById('terminalPanes'); }

    function open(machineName) {
        const id = ++_seq;
        const pane = buildPane(id, machineName);
        const tab = buildTab(id, machineName);
        panes().appendChild(pane);
        pane.querySelector('.term-pane-head').appendChild(tab);

        const term = new Terminal({
            cursorBlink: true, fontFamily: 'var(--mono, monospace)', fontSize: termFontSize(),
            theme: { background: '#000000' }, scrollback: 5000,
        });
        const fit = new FitAddon.FitAddon();
        term.loadAddon(fit);
        // Login URLs (claude, gh auth) are emitted by the shell and often soft-wrap across rows; the
        // addon rejoins wrapped lines before matching, so the whole URL stays one clickable link.
        term.loadAddon(new WebLinksAddon.WebLinksAddon((_e, uri) => window.open(uri, '_blank', 'noopener,noreferrer')));
        const body = pane.querySelector('.term-window-body');
        term.open(body);
        attachTouchScroll(body, term);

        // promptShowing / claude are this shell's action state, pushed by the server. They live on the
        // shell rather than in the menu because the menu is transient — it must be able to open at any
        // moment and show the truth immediately, without asking the server again.
        const state = { id, pane, tab, term, fit, ws: null, machine: machineName, paneId: randomPaneId(),
            retries: 0, reconnectTimer: 0, raf: 0, shellMode: null, pendingBanner: false, bannerTimer: 0,
            promptShowing: false, claude: null,
            loginScanTimer: 0, claudeLoginUrl: null, awaitingLoginCode: false, loginBanner: null };
        _terminals.set(id, state);

        // Route typed input through sendTyped so an armed on-screen Ctrl/Alt (the phone key bar) modifies
        // the next keystroke the soft keyboard produces. With nothing armed it is a plain pass-through, so
        // a physical keyboard on desktop is unaffected.
        term.onData((data) => sendTyped(state, data));
        term.onResize(() => sendResize(id));
        connect(state);

        addToGrid(id);
        layout();
        focusTerm(id);
        notifyChange();
    }

    // (Re)open the WebSocket for a session. On an unexpected drop this is called again after a backoff, so
    // a server restart or a flaky tunnel heals itself; a clean exit or a permanent error is left alone.
    function connect(state) {
        const { id, machine } = state;
        const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        // The pane id names the machine's tmux session, so this pane's reconnects reattach to the same shell.
        const url = `${proto}//${window.location.host}/machines/${encodeURIComponent(machine)}`
            + `/terminal?pane=${encodeURIComponent(state.paneId)}`;
        const ws = new WebSocket(url);
        ws.binaryType = 'arraybuffer';
        state.ws = ws;

        ws.onopen = () => {
            const reconnected = state.retries > 0;
            state.retries = 0;
            state.shellMode = null;         // the server re-reports how this connection resolved
            setDot(id, null);
            setStatus(id, null);
            // A fresh connection re-reports both: prompt state as output flows, availability right away.
            setPasswordPrompt(id, false);
            setClaudeAvailability(id, null);
            // Defer the banner until the server says whether it reattached — so it can tell the truth
            // rather than always implying continuity. A fallback fires if no shell-mode frame arrives.
            if (reconnected) armReconnectBanner(state);
            if (isVisible(id)) { fitOne(id); sendResize(id); if (_focusedId === id) state.term.focus(); }
        };
        // Binary frames are shell output (write to xterm); text frames are JSON control replies from the
        // server (e.g. the password-result). Keep them apart so a control reply never corrupts the pty stream.
        ws.onmessage = (ev) => {
            if (ev.data instanceof ArrayBuffer) { state.term.write(new Uint8Array(ev.data)); scheduleClaudeLoginScan(state); return; }
            if (typeof ev.data === 'string') handleControlMessage(state, ev.data);
        };
        ws.onclose = (ev) => {
            if (!_terminals.has(id)) return;   // closed by the user; nothing to report or retry
            // A clean exit (the remote shell ended) closes the pane outright — no dead window to tidy by hand.
            // Deferred to a macrotask so we never mutate _terminals / the DOM from inside the ws's own onclose
            // dispatch; closeShell then nulls this handler and closes an already-closed socket (a safe no-op).
            if (ev.code === 1000) { setTimeout(() => { if (_terminals.has(id)) closeShell(id); }, 0); return; }
            setDot(id, 'error');
            // Nothing can be sent into a dead shell — the actions go dark, and the login banner comes down,
            // until the socket is back.
            setPasswordPrompt(id, false);
            setClaudeAvailability(id, null);
            clearClaudeLogin(state);
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

    // A stable, per-pane id used to name the machine's tmux session. Random per pane so two panes never
    // share a session; held on the pane's state so every reconnect (including across a Vaier redeploy, the
    // page stays loaded) sends the same id and reattaches to the same shell.
    function randomPaneId() {
        try { if (window.crypto && crypto.randomUUID) return crypto.randomUUID(); } catch (e) { /* fall back */ }
        return 'p-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
    }

    // On a reconnect, wait for the server's shell-mode frame before writing the banner. If it never comes
    // (an older server), fall back to a neutral banner that claims no continuity it can't prove.
    function armReconnectBanner(state) {
        state.pendingBanner = true;
        clearTimeout(state.bannerTimer);
        state.bannerTimer = setTimeout(() => writeReconnectBanner(state, null), 1500);
    }

    // The truthful reconnect banner: green when the session was genuinely resumed, amber when it's a new
    // shell (the previous one was lost), neutral when the server didn't say.
    function writeReconnectBanner(state, mode) {
        if (!state.pendingBanner) return;
        state.pendingBanner = false;
        clearTimeout(state.bannerTimer);
        const GREEN = '\x1b[32m', AMBER = '\x1b[33m', RESET = '\x1b[0m';
        let color, text;
        if (mode === 'reattached') { color = GREEN; text = '[reattached — session resumed]'; }
        else if (mode === 'plain') { color = AMBER; text = '[reconnected — new shell; tmux is not installed, so the previous session was lost]'; }
        else if (mode === 'new') { color = AMBER; text = '[reconnected — new shell; the previous session had ended]'; }
        else { color = GREEN; text = '[reconnected]'; }
        state.term.write('\r\n' + color + text + RESET + '\r\n');
    }

    function buildPane(id, machineName) {
        const pane = document.createElement('div');
        pane.className = 'term-pane';
        pane.dataset.termId = id;
        pane.addEventListener('mousedown', () => { if (_focusedId !== id && isVisible(id)) { _focusedId = id; markFocus(); } });

        // The header holds this pane's tab and nothing else — the shell is named once, above itself. (On a
        // phone layout() fills this header with every shell's tab, since only one pane is on screen.)
        const head = document.createElement('div');
        head.className = 'term-pane-head';

        const status = document.createElement('div');
        status.className = 'term-window-status';
        status.style.display = 'none';

        const body = document.createElement('div');
        body.className = 'term-window-body';
        pane.append(head, status, body);
        return pane;
    }

    function buildTab(id, machineName) {
        const tab = document.createElement('div');
        tab.className = 'term-tab';
        tab.dataset.termId = id;
        tab.addEventListener('pointerdown', (e) => onTabDragStart(e, id, tab));
        // The window-manager gesture an operator already expects from a title bar.
        tab.addEventListener('dblclick', (e) => {
            if (e.target.closest('button') && !e.target.closest('.term-tab-label')) return;
            toggleMaximize(id);
        });

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

        // The shell's actions live behind this menu rather than on a strip of their own. They are rare —
        // a password prompt, a Claude Code launch — and a permanent row of buttons would cost every shell
        // a line of output forever to serve them. The menu hangs off the tab, so an action always belongs
        // to a named shell; there is never a question of which pane a click meant.
        const menu = document.createElement('button');
        menu.type = 'button';
        menu.className = 'term-tab-menu-btn';
        menu.title = 'Shell actions';
        menu.textContent = '⋯';
        menu.setAttribute('aria-haspopup', 'menu');
        menu.setAttribute('aria-expanded', 'false');
        menu.setAttribute('aria-label', 'Actions for ' + machineName);
        menu.onclick = (e) => { e.stopPropagation(); toggleTabMenu(id, menu); };

        const close = document.createElement('button');
        close.type = 'button';
        close.className = 'term-tab-close';
        close.title = 'Close shell';
        close.setAttribute('aria-label', 'Close shell to ' + machineName);
        close.textContent = '✕';
        close.onclick = (e) => { e.stopPropagation(); closeShell(id); };
        tab.append(label, menu, close);
        return tab;
    }

    // --- the split grid ------------------------------------------------------------------------------

    // What is on screen, as of the last layout() — a maximize or a phone shows fewer panes than the grid holds.
    function visibleIds() { return _visible; }
    function isVisible(id) { return _visible.includes(id); }
    function gridIds() { const out = []; for (const r of _rows) for (const c of r.cols) out.push(c.id); return out; }
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
        // Every open shell is a pane: anything missing (a shell opened while the grid was empty) gets a row.
        for (const id of _terminals.keys()) if (!findCell(id)) _rows.push({ h: 1, cols: [{ id, w: 1 }] });
    }

    // Tile a newly opened shell in beside the one you were working in, so it lands where you are looking.
    // A full row starts a new row beneath it instead of squeezing a third terminal into the same width.
    function addToGrid(id) {
        _maximizedId = null;                 // a new shell must be visible; it cannot open under a maximized one
        const cell = _focusedId != null ? findCell(_focusedId) : null;
        if (!cell) _rows.push({ h: 1, cols: [{ id, w: 1 }] });
        else if (_rows[cell.ri].cols.length < AUTO_TILE_COLUMNS) _rows[cell.ri].cols.splice(cell.ci + 1, 0, { id, w: 1 });
        else _rows.splice(cell.ri + 1, 0, { h: 1, cols: [{ id, w: 1 }] });
        _focusedId = id;
    }

    // Clicking a tab focuses its shell. Every shell is already a pane, so this only takes keyboard focus —
    // except where a pane is covered (maximized, or a phone), when it also brings that shell to the front.
    function focus(id) {
        if (!_terminals.has(id)) return;
        if (id !== _focusedId) disarmMods();   // a modifier armed for one shell must not fire in another
        _focusedId = id;
        if (isVisible(id)) { markFocus(); focusTerm(id); return; }
        if (_maximizedId != null) _maximizedId = id;
        layout();
        focusTerm(id);
    }

    // One shell fills the panel; the grid is left intact underneath so restoring puts every pane back where
    // the operator had dragged it.
    function toggleMaximize(id) {
        if (!_terminals.has(id) || isPhone()) return;   // a phone is always maximized — nothing to toggle
        _maximizedId = _maximizedId === id ? null : id;
        _focusedId = id;
        layout();
        focusTerm(id);
    }

    // Drop a dragged tab into the grid at a target pane's edge (left/right → new column, top/bottom → new row).
    function dropTab(id, target) {
        if (!_terminals.has(id) || isPhone()) return;
        if (target && target.targetId === id) return;   // dropped on its own pane — the operator changed nothing
        _maximizedId = null;                 // you cannot arrange panes you cannot see
        removeFromGrid(id);

        const cell = target ? findCell(target.targetId) : null;
        if (_rows.length === 0) setSingle(id);                        // the only shell — nothing to split against
        else if (!cell) _rows.push({ h: 1, cols: [{ id, w: 1 }] });   // dropped on empty space → new bottom row
        else if (target.dir === 'left' || target.dir === 'right') {
            const at = target.dir === 'right' ? cell.ci + 1 : cell.ci;
            _rows[cell.ri].cols.splice(at, 0, { id, w: 1 });
        } else {
            const at = target.dir === 'bottom' ? cell.ri + 1 : cell.ri;
            _rows.splice(at, 0, { h: 1, cols: [{ id, w: 1 }] });
        }
        _focusedId = id; layout(); focusTerm(id);
    }

    // Close one shell entirely: stop reconnects, drop its session and tab, then re-tile whatever's left.
    function closeShell(id) {
        const s = _terminals.get(id);
        if (!s) return;
        if (_menu && _menu.id === id) closeTabMenu();   // never leave a menu hanging off a dead tab
        clearTimeout(s.reconnectTimer);
        clearTimeout(s.bannerTimer);
        clearTimeout(s.loginScanTimer);
        if (s.ws) try { s.ws.onclose = null; s.ws.close(); } catch (e) { /* ignore */ }
        if (s.term) try { s.term.dispose(); } catch (e) { /* ignore */ }
        if (s.pane && s.pane.parentNode) s.pane.parentNode.removeChild(s.pane);
        if (s.tab && s.tab.parentNode) s.tab.parentNode.removeChild(s.tab);
        _terminals.delete(id);
        removeFromGrid(id);
        if (_maximizedId === id) _maximizedId = null;
        if (_focusedId === id) _focusedId = gridIds()[0] ?? null;
        layout();
        if (_focusedId != null) focusTerm(_focusedId);
        notifyChange();
    }

    // Render the grid: a vertical stack of rows (each a horizontal strip of panes) with a drag-divider
    // between every adjacent pair. A maximized shell — and every shell on a phone — renders as the lone pane
    // instead, leaving _rows untouched so restoring returns the operator's own arrangement. Covered shells
    // keep running; the empty state shows when nothing is open.
    function layout() {
        const p = panes();
        const empty = document.getElementById('terminalEmpty');

        normalizeGrid();
        if (_maximizedId != null && !_terminals.has(_maximizedId)) _maximizedId = null;
        if (_focusedId == null || !_terminals.has(_focusedId)) _focusedId = firstId();

        // A phone has room for one terminal; a maximize asks for one. Either way, one pane fills the panel.
        const solo = isPhone() ? _focusedId : _maximizedId;
        const rows = solo != null ? [{ h: 1, cols: [{ id: solo, w: 1 }] }] : _rows;
        if (solo != null) _focusedId = solo;

        p.querySelectorAll('.term-row, .term-split-divider').forEach((el) => el.remove());
        _visible = [];

        rows.forEach((row, ri) => {
            if (ri > 0) p.appendChild(makeRowDivider(ri - 1));
            const rowEl = document.createElement('div');
            rowEl.className = 'term-row';
            rowEl.style.flexGrow = row.h || 1;
            row.cols.forEach((col, ci) => {
                if (ci > 0) rowEl.appendChild(makeColDivider(ri, ci - 1));
                const s = _terminals.get(col.id);
                s.pane.style.flexGrow = solo != null ? 1 : (col.w || 1);
                s.pane.classList.add('is-visible');
                s.pane.classList.toggle('is-focused', col.id === _focusedId);
                rowEl.appendChild(s.pane);
                _visible.push(col.id);
            });
            p.appendChild(rowEl);
        });
        for (const [id, s] of _terminals) {
            if (_visible.includes(id)) continue;
            s.pane.classList.remove('is-visible', 'is-focused');
            p.appendChild(s.pane);
        }
        placeTabs();
        p.classList.toggle('is-split', _visible.length > 1);
        if (empty) { p.appendChild(empty); empty.style.display = _terminals.size === 0 ? 'flex' : 'none'; }
        updateKeyBarVisibility();

        fitVisibleSoon();
    }

    // A tab belongs above its own terminal — so each visible pane's header holds its own tab, and the name is
    // written exactly once. The exception is the covered shells: when a single pane fills the panel it adopts
    // every tab, because a shell you cannot see still needs a handle to come back by.
    function placeTabs() {
        const host = _visible.length === 1 ? _terminals.get(_visible[0]) : null;
        const adopting = host && _terminals.size > 1;
        for (const [id, s] of _terminals) {
            const head = adopting ? host.pane.querySelector('.term-pane-head') : s.pane.querySelector('.term-pane-head');
            if (s.tab.parentNode !== head) head.appendChild(s.tab);
            s.tab.classList.toggle('is-active', id === _focusedId);
        }
        for (const [, s] of _terminals) s.pane.classList.toggle('has-tabstrip', !!adopting && s === host);
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
        if (e.target.closest('.term-tab-close') || e.target.closest('.term-tab-menu-btn')) return;
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
        const s = _terminals.get(id);
        if (s) requestAnimationFrame(() => { fitOne(id); sendResize(id); s.term.focus(); });
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

    // --- the phone key bar ---------------------------------------------------------------------------

    // A phone's soft keyboard has no Esc, Tab, Ctrl, Alt or arrows — the keys a shell needs most. This bar
    // sits under the panes on a phone and sends those sequences to the focused shell over the same WebSocket
    // as a keystroke. Ctrl and Alt are sticky: tapping one arms it (it glows), and it modifies the very next
    // key — whether tapped here or typed on the soft keyboard — then disarms. Arrow keys honour the shell's
    // application-cursor-keys mode (vim, less) so they navigate rather than print stray characters.
    let _kbCtrl = false, _kbAlt = false;

    // label: the key face; arrow: the CSI final byte for a cursor key; mod: a sticky modifier; special: Esc/Tab.
    const KEY_BAR = [
        { label: 'Esc', special: 'esc', aria: 'Escape' },
        { label: 'Tab', special: 'tab', aria: 'Tab' },
        { label: 'Ctrl', mod: 'ctrl', aria: 'Control (sticky — modifies the next key)' },
        { label: 'Alt', mod: 'alt', aria: 'Alt (sticky — modifies the next key)' },
        { label: '←', arrow: 'D', aria: 'Left arrow' },
        { label: '↑', arrow: 'A', aria: 'Up arrow' },
        { label: '↓', arrow: 'B', aria: 'Down arrow' },
        { label: '→', arrow: 'C', aria: 'Right arrow' },
    ];

    function keybar() { return document.getElementById('terminalKeys'); }
    function focusedState() { return _focusedId != null ? _terminals.get(_focusedId) : null; }

    // Send bytes straight to the focused shell's PTY (independent of xterm's own input path).
    function wsSend(state, data) {
        if (state && state.ws && state.ws.readyState === WebSocket.OPEN) {
            state.ws.send(new TextEncoder().encode(data));
        }
    }

    // A keystroke from the soft keyboard (or a physical one): fold in any armed sticky modifier, then send.
    function sendTyped(state, data) {
        if ((_kbCtrl || _kbAlt) && data.length === 1) {
            if (_kbCtrl) data = ctrlByte(data);
            if (_kbAlt) data = '\x1b' + data;   // Alt/Meta is an ESC prefix, applied outside the control fold
            disarmMods();
        }
        wsSend(state, data);
    }

    // A printable character folded to its control code: a–z → 0x01–0x1a, and the handful of symbols the
    // terminal also defines (Ctrl-[ = ESC, Ctrl-Space/@ = NUL, Ctrl-? = DEL). Anything else passes through.
    function ctrlByte(ch) {
        const code = ch.charCodeAt(0);
        if (code >= 97 && code <= 122) return String.fromCharCode(code - 96);   // a–z
        if (code >= 65 && code <= 90) return String.fromCharCode(code - 64);    // A–Z
        const map = { '@': '\x00', ' ': '\x00', '[': '\x1b', '\\': '\x1c', ']': '\x1d', '^': '\x1e', '_': '\x1f', '?': '\x7f' };
        return map[ch] != null ? map[ch] : ch;
    }

    // The xterm modifier parameter for a cursor key: 1 + Alt(2) + Ctrl(4). No Shift is offered on the bar.
    function modifierParam() { return 1 + (_kbAlt ? 2 : 0) + (_kbCtrl ? 4 : 0); }

    // A cursor key. With a modifier armed it takes the CSI `ESC [ 1 ; <mod> <letter>` form; unmodified it
    // follows the shell's DECCKM state — `ESC O <letter>` in application-cursor mode (vim/less), else `ESC [ <letter>`.
    function sendArrow(state, letter) {
        const mod = modifierParam();
        let seq;
        if (mod > 1) {
            seq = '\x1b[1;' + mod + letter;
        } else {
            const app = state.term.modes && state.term.modes.applicationCursorKeysMode;
            seq = (app ? '\x1bO' : '\x1b[') + letter;
        }
        wsSend(state, seq);
        disarmMods();
    }

    function toggleMod(which) {
        if (which === 'ctrl') _kbCtrl = !_kbCtrl;
        else if (which === 'alt') _kbAlt = !_kbAlt;
        paintMods();
    }

    function disarmMods() {
        if (_kbCtrl || _kbAlt) { _kbCtrl = false; _kbAlt = false; paintMods(); }
    }

    function paintMods() {
        const bar = keybar();
        if (!bar) return;
        for (const btn of bar.querySelectorAll('.term-key-mod')) {
            const armed = (btn.dataset.mod === 'ctrl' && _kbCtrl) || (btn.dataset.mod === 'alt' && _kbAlt);
            btn.classList.toggle('is-armed', armed);
            btn.setAttribute('aria-pressed', armed ? 'true' : 'false');
        }
    }

    function onKeyBarPress(spec) {
        if (spec.mod) { toggleMod(spec.mod); return; }
        const s = focusedState();
        if (!s) return;
        if (spec.arrow) { sendArrow(s, spec.arrow); return; }
        // Esc and Tab: an armed Alt still prefixes ESC (Alt-Esc / Alt-Tab); Ctrl is consumed but has no effect.
        let seq = spec.special === 'tab' ? '\t' : '\x1b';
        if (_kbAlt) seq = '\x1b' + seq;
        wsSend(s, seq);
        disarmMods();
    }

    // Build the bar's buttons once. Acting on pointerdown (and preventing its default) keeps focus in the
    // terminal's textarea, so the soft keyboard never drops between taps and typing continues uninterrupted.
    function buildKeyBar() {
        const bar = keybar();
        if (!bar) return;
        for (const spec of KEY_BAR) {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'term-key' + (spec.mod ? ' term-key-mod' : '');
            btn.textContent = spec.label;
            btn.setAttribute('aria-label', spec.aria || spec.label);
            if (spec.mod) { btn.dataset.mod = spec.mod; btn.setAttribute('aria-pressed', 'false'); }
            btn.addEventListener('pointerdown', (e) => { e.preventDefault(); onKeyBarPress(spec); });
            bar.appendChild(btn);
        }
    }

    // The bar shows only on a phone and only while a shell is open — an empty panel needs no keys.
    function updateKeyBarVisibility() {
        const panel = document.querySelector('.term-panel');
        if (panel) panel.classList.toggle('term-no-shells', _terminals.size === 0);
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
        // No refit: the message floats over the pane's bottom rather than taking height from the terminal.
    }

    function statusButton(label, onClick) {
        const btn = document.createElement('button');
        btn.className = 'btn btn-small btn-secondary';
        btn.textContent = label;
        btn.onclick = onClick;
        return btn;
    }

    // A server control reply (text frame): the outcome of a Send-password or Run-Claude request, the live
    // password-prompt state, this machine's Claude Code availability, or how the shell resolved on connect.
    // No frame ever carries a secret; Vaier writes the password straight into the remote PTY server-side,
    // so the browser only ever learns whether it went.
    function handleControlMessage(state, raw) {
        let msg;
        try { msg = JSON.parse(raw); } catch (e) { return; }   // ignore anything unparseable
        if (!msg) return;
        if (msg.type === 'password-result') showPasswordResult(state.id, msg.status);
        // The server watches the shell output and flips this when the remote starts/stops awaiting a
        // password; the menu's Send-password action is only enabled while it is showing.
        else if (msg.type === 'password-prompt') setPasswordPrompt(state.id, !!msg.showing);
        // Whether this machine has Claude Code, could install it, or neither — probed server-side on open.
        else if (msg.type === 'claude-availability') setClaudeAvailability(state.id, msg.state);
        else if (msg.type === 'claude-result') showClaudeResult(state.id, msg.status);
        // How the shell resolved on (re)connect: reattached / new / plain. Drives the truthful banner.
        else if (msg.type === 'shell-mode') { state.shellMode = msg.mode; if (state.pendingBanner) writeReconnectBanner(state, msg.mode); }
    }

    const PASSWORD_DISABLED_REASON = 'Available only while the remote is asking for a password';

    // Record whether this shell's remote is at a password prompt. Driven solely by the server — the
    // browser never guesses. The tab's menu button lights up while it holds a newly-usable action, because
    // an action buried in a closed menu is otherwise invisible exactly when it becomes safe to use.
    function setPasswordPrompt(id, showing) {
        const s = _terminals.get(id);
        if (!s) return;
        s.promptShowing = !!showing;
        s.tab.querySelector('.term-tab-menu-btn').classList.toggle('has-action', s.promptShowing);
        refreshTabMenu(id);
    }

    // What the Run Claude action promises in each state the server can report. `unknown` covers the gap
    // before the first frame arrives and any moment the shell is not connected.
    const CLAUDE_TITLES = {
        unknown: 'Checking whether this machine can run Claude Code…',
        ready: 'Start Claude Code in this shell',
        installable: 'Install Claude Code on this machine, then start it',
        unavailable: "Claude Code isn't installed here, and can't be installed (no curl)",
    };
    const CLAUDE_ENABLED = new Set(['ready', 'installable']);

    // Record whether this machine can run Claude Code, from the server's probe. Never guessed in the
    // browser: a wrongly-enabled action would pipe an installer into a shell that cannot run it.
    function setClaudeAvailability(id, state) {
        const s = _terminals.get(id);
        if (!s) return;
        s.claude = CLAUDE_TITLES[state] ? state : 'unknown';
        refreshTabMenu(id);
    }

    // --- the tab's actions menu ----------------------------------------------------------------------

    let _menu = null;   // { id, el, anchor } — at most one is open at a time

    // The shell's actions, in the order an operator reaches for them. Each names itself, says when it is
    // usable, and says why when it isn't — a greyed row with no explanation reads as a bug, not a rule.
    const TAB_ACTIONS = [
        {
            glyph: '🔑', label: 'Send password',
            enabled: (s) => s.promptShowing,
            title: (s) => (s.promptShowing ? 'Send the stored password' : PASSWORD_DISABLED_REASON),
            run: (id) => sendPassword(id),
        },
        {
            glyph: '✳', label: 'Run Claude',
            enabled: (s) => CLAUDE_ENABLED.has(s.claude),
            title: (s) => CLAUDE_TITLES[s.claude] || CLAUDE_TITLES.unknown,
            run: (id) => startClaude(id),
        },
        {
            glyph: '⤢',
            label: (s) => (_maximizedId === s.id ? 'Restore' : 'Maximize'),
            enabled: () => !isPhone() && _terminals.size > 1,
            title: (s) => {
                if (isPhone()) return 'One shell already fills the screen on a phone';
                if (_terminals.size < 2) return 'Only one shell is open — it already fills the panel';
                return _maximizedId === s.id ? 'Put every pane back where you arranged it' : 'Fill the panel with this shell';
            },
            run: (id) => toggleMaximize(id),
        },
    ];

    function toggleTabMenu(id, anchor) {
        if (_menu && _menu.id === id) { closeTabMenu(); return; }
        closeTabMenu();
        openTabMenu(id, anchor);
    }

    // The menu is fixed-position and lives on <body>, not inside the tab: a pane header that has adopted every
    // tab scrolls horizontally with `overflow-x: auto`, which would clip any menu positioned within it.
    function openTabMenu(id, anchor) {
        const s = _terminals.get(id);
        if (!s) return;
        const el = document.createElement('div');
        el.className = 'term-tab-menu';
        el.setAttribute('role', 'menu');
        document.body.appendChild(el);
        const strip = anchor.closest('.term-pane-head');
        _menu = { id, el, anchor, strip };
        anchor.setAttribute('aria-expanded', 'true');
        renderTabMenu();
        positionTabMenu();

        document.addEventListener('pointerdown', onMenuOutsidePointer, true);
        document.addEventListener('keydown', onMenuKeydown, true);
        window.addEventListener('resize', closeTabMenu);
        // A scrolling header would leave the menu stranded beside the wrong tab; close it instead.
        if (strip) strip.addEventListener('scroll', closeTabMenu, { passive: true });
        const first = el.querySelector('.term-menu-item:not(:disabled)');
        if (first) first.focus();
    }

    function closeTabMenu() {
        if (!_menu) return;
        document.removeEventListener('pointerdown', onMenuOutsidePointer, true);
        document.removeEventListener('keydown', onMenuKeydown, true);
        window.removeEventListener('resize', closeTabMenu);
        if (_menu.strip) _menu.strip.removeEventListener('scroll', closeTabMenu);
        if (_menu.anchor) _menu.anchor.setAttribute('aria-expanded', 'false');
        _menu.el.remove();
        _menu = null;
    }

    // A server frame can flip an action's state while its menu sits open — redraw so the operator never
    // clicks a row that went stale under them.
    function refreshTabMenu(id) { if (_menu && _menu.id === id) renderTabMenu(); }

    function renderTabMenu() {
        const s = _terminals.get(_menu.id);
        if (!s) { closeTabMenu(); return; }
        _menu.el.replaceChildren(...TAB_ACTIONS.map((action) => {
            const item = document.createElement('button');
            item.type = 'button';
            item.className = 'term-menu-item';
            item.setAttribute('role', 'menuitem');
            item.disabled = !action.enabled(s);
            item.title = action.title(s);
            const glyph = document.createElement('span');
            glyph.className = 'term-menu-item-glyph';
            glyph.setAttribute('aria-hidden', 'true');
            glyph.textContent = action.glyph;
            const label = document.createElement('span');
            label.textContent = typeof action.label === 'function' ? action.label(s) : action.label;
            item.append(glyph, label);
            item.onclick = () => { const id = _menu.id; closeTabMenu(); action.run(id); };
            return item;
        }));
    }

    // Hang the menu under its tab, nudged left if it would otherwise run off the right of the viewport.
    function positionTabMenu() {
        const r = _menu.anchor.getBoundingClientRect();
        const w = _menu.el.offsetWidth;
        const left = Math.max(4, Math.min(r.left, window.innerWidth - w - 4));
        _menu.el.style.left = left + 'px';
        _menu.el.style.top = (r.bottom + 2) + 'px';
    }

    function onMenuOutsidePointer(e) {
        if (_menu && !_menu.el.contains(e.target) && e.target !== _menu.anchor) closeTabMenu();
    }

    function onMenuKeydown(e) {
        if (!_menu) return;
        if (e.key === 'Escape') {
            e.stopPropagation();      // don't let the shell see it — the operator meant "close the menu"
            const anchor = _menu.anchor;
            closeTabMenu();
            if (anchor) anchor.focus();
        }
    }

    // Ask Vaier to start Claude Code in this shell, installing it first if the machine hasn't got it.
    // Vaier types the line into the remote PTY, so the operator watches it run and can interrupt it.
    function startClaude(id) {
        const s = _terminals.get(id);
        if (!s || !s.ws || s.ws.readyState !== WebSocket.OPEN) return;
        s.ws.send(JSON.stringify({ type: 'run-claude' }));
    }

    const CLAUDE_RESULTS = {
        LAUNCHED: { message: null, error: false },
        INSTALLING: { message: 'Installing Claude Code — watch the shell.', error: false },
        UNAVAILABLE: { message: "Claude Code can't be installed on this machine.", error: true },
        FAILED: { message: "Couldn't start Claude Code. Check the Vaier logs.", error: true },
    };

    // LAUNCHED says nothing: the shell itself is the confirmation, and a status line over it would only
    // cover the output the operator just asked for.
    function showClaudeResult(id, status) {
        const r = CLAUDE_RESULTS[status] || CLAUDE_RESULTS.FAILED;
        if (!r.message) return;
        setStatus(id, r.message, r.error);
        if (!r.error) setTimeout(() => { const s = _terminals.get(id); if (s) setStatus(id, null); }, 4000);
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

    // --- Claude login banner -------------------------------------------------------------------------

    // Why this one detection lives in the browser, when the password prompt is watched server-side: the
    // password prompt guards a secret the server holds, so only the server may decide the moment it is safe
    // to release it — the browser is never trusted with that call. A Claude login guards nothing. The
    // authorize URL is already on the operator's own screen and the code comes back from the operator's own
    // browser, so there is nothing here to leak by reading it in the page. It is also the more reliable place
    // to read it: that URL runs to ~300 characters and soft-wraps across several PTY rows, and xterm marks
    // each row that is a wrapped continuation — so the logical line can be rejoined exactly. A server-side
    // regex would be matching raw ANSI from an ink TUI that repaints itself, and would have to guess where
    // the wraps fell.
    const CLAUDE_AUTHORIZE_PREFIXES = [
        'https://claude.com/cai/oauth/authorize',
        'https://platform.claude.com/oauth/authorize',
    ];
    // The literal line claude prints while it waits for the operator to paste the code back (claude v2.1.206).
    const CLAUDE_LOGIN_PROMPT = 'Paste code here if prompted';
    const CLAUDE_LOGIN_SCAN_ROWS = 40;   // logical lines; the URL and the prompt sit together in the ink UI

    // Rescanning the whole buffer on every PTY chunk would burn cycles all through a noisy build; coalesce to
    // one scan once the output settles.
    function scheduleClaudeLoginScan(state) {
        clearTimeout(state.loginScanTimer);
        state.loginScanTimer = setTimeout(() => scanClaudeLogin(state), 120);
    }

    // Rejoin the bottom of the scrollback into logical lines — a row flagged isWrapped is the continuation of
    // the row above it — then read out the login URL and whether claude is still asking for the code.
    function scanClaudeLogin(state) {
        const buf = state.term.buffer.active;
        const total = buf.length;
        const start = Math.max(0, total - CLAUDE_LOGIN_SCAN_ROWS);
        const lines = [];
        for (let i = start; i < total; i++) {
            const line = buf.getLine(i);
            if (!line) continue;
            const text = line.translateToString(true);   // trims the row's padding, never the URL's own chars
            if (lines.length && i > start && line.isWrapped) lines[lines.length - 1] += text;
            else lines.push(text);
        }
        let url = null;
        for (let i = lines.length - 1; i >= 0 && !url; i--) url = authorizeUrlIn(lines[i]);
        const awaiting = lines.slice(-8).some((l) => l.includes(CLAUDE_LOGIN_PROMPT));
        // Hold on to the last URL we saw for as long as claude is still waiting: it can scroll just past the
        // window while the prompt stays put, and the banner still needs somewhere to send the operator.
        if (url) state.claudeLoginUrl = url;
        state.awaitingLoginCode = awaiting;
        if (awaiting && state.claudeLoginUrl) showClaudeLoginBanner(state);
        else clearClaudeLogin(state);
    }

    // The authorize URL out of one logical line: from a known prefix up to the first whitespace (the token
    // never contains a space, so the first space ends it), or null when the line carries none.
    function authorizeUrlIn(line) {
        for (const prefix of CLAUDE_AUTHORIZE_PREFIXES) {
            const at = line.indexOf(prefix);
            if (at < 0) continue;
            const rest = line.slice(at);
            const end = rest.search(/\s/);
            return end < 0 ? rest : rest.slice(0, end);
        }
        return null;
    }

    // The banner belongs to this shell's pane and this shell's state alone — a login waiting on one machine
    // never surfaces over another. Built once per shell, then shown and hidden as the prompt comes and goes.
    function showClaudeLoginBanner(state) {
        const el = state.loginBanner || (state.loginBanner = buildClaudeLoginBanner(state));
        if (el.parentNode !== state.pane) {
            state.pane.insertBefore(el, state.pane.querySelector('.term-window-body'));
            fitOne(state.id); sendResize(state.id);   // the banner took a strip from the terminal — reflow it
        }
        // Autofocus the field only as the banner first appears, so a rescan mid-paste never yanks the cursor.
        if (el.dataset.shown !== 'true') {
            el.dataset.shown = 'true';
            el.querySelector('.term-login-input').focus();
        }
    }

    function buildClaudeLoginBanner(state) {
        const el = document.createElement('div');
        el.className = 'term-login-banner';

        const copy = document.createElement('div');
        copy.className = 'term-login-copy';
        copy.textContent = 'Claude is waiting for a login code. Open the login page, then paste the code it shows.';

        const open = document.createElement('button');
        open.type = 'button';
        open.className = 'btn btn-small btn-primary term-login-open';
        open.textContent = 'Open Claude login';
        // A click is a real user gesture, so the browser never pops-up-blocks it — which is the whole reason
        // this is a button the operator presses and not a silent auto-open they never asked for.
        open.onclick = () => { if (state.claudeLoginUrl) window.open(state.claudeLoginUrl, '_blank', 'noopener,noreferrer'); };

        const form = document.createElement('form');
        form.className = 'term-login-form';
        const input = document.createElement('input');
        input.type = 'text';
        input.className = 'term-login-input';
        input.placeholder = 'Paste the code from your browser';
        input.autocomplete = 'off';
        input.spellcheck = false;
        const send = document.createElement('button');
        send.type = 'submit';
        send.className = 'btn btn-small btn-secondary term-login-send';
        send.textContent = 'Send';
        form.append(input, send);
        form.onsubmit = (e) => { e.preventDefault(); sendClaudeLoginCode(state); };

        el.append(copy, open, form);
        return el;
    }

    function hideClaudeLoginBanner(state) {
        const el = state.loginBanner;
        if (!el) return;
        if (el.parentNode) {
            el.parentNode.removeChild(el);
            fitOne(state.id); sendResize(state.id);   // the terminal gets its row back
        }
        el.dataset.shown = 'false';
        const input = el.querySelector('.term-login-input');
        if (input) input.value = '';
    }

    // Drop any captured login state and take the banner down. Runs when claude stops asking, and whenever the
    // shell disconnects — a dead socket can accept no code.
    function clearClaudeLogin(state) {
        state.claudeLoginUrl = null;
        state.awaitingLoginCode = false;
        hideClaudeLoginBanner(state);
    }

    // The code is written into the PTY as keystrokes, so it arrives wherever the shell is reading — and a
    // newline buried in it would end the paste and hand everything after it to the shell as a command to run.
    // claude asks for a single whitespace-free token (`code#state`), so that is all we accept: any whitespace
    // or control character means it is not that token, and nothing is sent. The transport is the keystroke
    // path, not a control frame — a control frame is a request the server acts on, whereas this has to land in
    // the remote's input stream exactly as if the operator had typed it.
    function sendClaudeLoginCode(state) {
        if (!state.ws || state.ws.readyState !== WebSocket.OPEN) return;
        const input = state.loginBanner.querySelector('.term-login-input');
        const code = input.value.trim();
        if (!code || /[\s -]/.test(code)) return;
        state.ws.send(new TextEncoder().encode(code + '\r'));
        input.value = '';
    }

    function notifyChange() { if (_onChange) _onChange(_terminals.size); }

    // Reflow the visible shells whenever the panel changes size (shown via nav, window resized/rotated).
    function initResizeWatch() {
        const p = panes();
        if (p && 'ResizeObserver' in window) new ResizeObserver(() => fitVisibleSoon()).observe(p);
        window.addEventListener('resize', fitVisibleSoon);
    }

    function init() { buildKeyBar(); layout(); initResizeWatch(); }

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
