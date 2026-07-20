// A pop-out terminal: one shell, filling its own browser window, so an operator can spread several across a
// wide screen and size each by sizing the window. It speaks the same protocol as the dock's panes
// (terminal-dock.js) — binary WebSocket frames for I/O, JSON control frames for the send-password action —
// and shares session ownership with the dock through terminal-panes.js, so a shell popped out of the dock is
// the very same tmux session, reattached, and closing the window hands it back rather than orphaning it.
//
// This is a deliberately single-session page: no grid, no tabs, no drag. The window IS the pane, and the OS
// window manager does the arranging the dock's split grid does inside one page.
(function () {
    'use strict';

    const params = new URLSearchParams(window.location.search);
    const machine = params.get('machine');
    const $ = (id) => document.getElementById(id);

    if (!machine) {
        $('twStatus').textContent = 'No machine was named for this terminal.';
        return;
    }
    document.title = machine + ' · shell';
    $('twName').textContent = machine;

    // The session id. Handed in for a pop-out (reattach the dock's shell) or on reload (reattach our own); a
    // fresh window mints one and writes it into its own URL, so a reload of this window reattaches too.
    let paneId = params.get('pane');
    if (paneId) {
        VaierPanes.adopt(machine, paneId);
    } else {
        paneId = VaierPanes.claim(machine, []);
        const url = new URL(window.location.href);
        url.searchParams.set('pane', paneId);
        window.history.replaceState(null, '', url);
    }

    // --- the same protocol constants the dock uses ----------------------------------------------------
    const CLOSE_REASONS = {
        4401: 'No SSH credential is stored for this machine. Add one from its page in the Explorer.',
        4402: 'Authentication failed — check the stored SSH credential.',
        4403: 'The host key changed and was refused. If you rebuilt this host, clear its pinned key and reconnect.',
        4404: 'Machine not found.',
        4408: 'Could not reach the host (connection refused or timed out).',
        4500: 'The terminal failed to open. Check the Vaier logs.',
    };
    const PERMANENT = new Set([1000, 4401, 4402, 4403, 4404]);
    const MAX_RECONNECTS = 8;

    function monoFontStack() {
        const v = getComputedStyle(document.documentElement).getPropertyValue('--mono').trim();
        return v || 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';
    }

    // A phone fits more columns at a smaller font, and the size follows the viewport live (rotating re-fits).
    const _phone = window.matchMedia('(max-width: 720px)');
    const isPhone = () => _phone.matches;
    const termFontSize = () => (isPhone() ? 10 : 13);

    // Keep a phone's screen awake while the shell is open — you watch a terminal more than you touch it, and a
    // display dimming mid-command is exactly what a persistent shell cannot ride out. A phone concern only, and
    // the browser drops the lock whenever the tab is backgrounded, so it is re-acquired on the way back.
    let _wakeLock = null;
    async function acquireWakeLock() {
        if (!isPhone() || !('wakeLock' in navigator) || _wakeLock) return;
        try {
            _wakeLock = await navigator.wakeLock.request('screen');
            _wakeLock.addEventListener('release', () => { _wakeLock = null; });
        } catch (e) { _wakeLock = null; /* denied or unsupported — the screen just dims as it always did */ }
    }
    function releaseWakeLock() {
        if (!_wakeLock) return;
        try { _wakeLock.release(); } catch (e) { /* ignore */ }
        _wakeLock = null;
    }

    // --- state ----------------------------------------------------------------------------------------
    const state = {
        ws: null, term: null, fit: null, retries: 0, reconnectTimer: 0,
        ended: false, shellMode: null, pendingBanner: false, bannerTimer: 0,
        promptShowing: false,
    };

    const term = new Terminal({
        cursorBlink: true, fontFamily: monoFontStack(), fontSize: termFontSize(),
        theme: { background: '#000000' }, scrollback: 5000,
    });
    const fit = new FitAddon.FitAddon();
    term.loadAddon(fit);
    term.loadAddon(new WebLinksAddon.WebLinksAddon((_e, uri) => window.open(uri, '_blank', 'noopener,noreferrer')));
    state.term = term; state.fit = fit;
    term.open($('twTerm'));
    // Route input through sendTyped so an armed on-screen Ctrl/Alt (the phone key row) folds into the next
    // keystroke; with nothing armed it is a plain pass-through, so a physical keyboard is unaffected.
    term.onData((data) => sendTyped(data));
    term.onResize(() => sendResize());
    attachTouchScroll($('twTerm'), term);

    // --- the top bar's actions ------------------------------------------------------------------------
    const PASSWORD_DISABLED_REASON = 'Available only while the remote is asking for a password';

    // Duplicate opens another, separate shell on this same machine in its own window — a fresh session id and a
    // unique window name, so several shells on one machine can be open side by side.
    const btnDup = actionButton('Duplicate', () => {
        const pane = VaierPanes.newId();
        window.open('terminal.html?machine=' + encodeURIComponent(machine) + '&pane=' + encodeURIComponent(pane),
            'vaier-shell-' + encodeURIComponent(pane), 'popup,width=1024,height=680');
    });
    btnDup.title = 'Open a second, separate shell on ' + machine;
    const btnPassword = actionButton('Send password', () => send({ type: 'send-password' }));
    const btnEnd = actionButton('Exit shell', endShell);
    btnEnd.classList.add('tw-danger');
    // The one distinction people miss: closing the window keeps the shell alive to reattach; Exit stops it.
    btnEnd.title = 'Stop this shell for good on ' + machine + '. Just closing the window keeps it running — and '
        + 'it even survives a Vaier restart — so reopening reattaches right where you left off.';
    $('twActions').append(btnDup, btnPassword, btnEnd);
    refreshActions();

    function actionButton(label, onClick) {
        const b = document.createElement('button');
        b.type = 'button'; b.className = 'tw-btn'; b.textContent = label; b.onclick = onClick;
        return b;
    }
    function refreshActions() {
        btnPassword.disabled = !state.promptShowing;
        btnPassword.title = state.promptShowing ? 'Send the stored password' : PASSWORD_DISABLED_REASON;
    }

    function send(msg) {
        if (state.ws && state.ws.readyState === WebSocket.OPEN) state.ws.send(JSON.stringify(msg));
    }
    function wsSend(data) {
        if (state.ws && state.ws.readyState === WebSocket.OPEN) state.ws.send(new TextEncoder().encode(data));
    }

    // --- connect / reconnect --------------------------------------------------------------------------
    function connect() {
        const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const url = `${proto}//${window.location.host}/machines/${encodeURIComponent(machine)}`
            + `/terminal?pane=${encodeURIComponent(paneId)}`;
        const ws = new WebSocket(url);
        ws.binaryType = 'arraybuffer';
        state.ws = ws;

        ws.onopen = () => {
            const reconnected = state.retries > 0;
            state.retries = 0;
            state.shellMode = null;
            setDot(null);
            setStatus(null);
            setPasswordPrompt(false);
            VaierPanes.beat(paneId);
            acquireWakeLock();   // a phone holds its screen awake while the shell is connected
            if (reconnected) armReconnectBanner();
            refit(); sendResize(); term.focus();
        };
        ws.onmessage = (ev) => {
            if (ev.data instanceof ArrayBuffer) { term.write(new Uint8Array(ev.data)); return; }
            if (typeof ev.data === 'string') handleControl(ev.data);
        };
        ws.onclose = (ev) => {
            if (state.ended) return;
            // A clean exit means the remote shell ended — the session is gone, so let it go and close the window.
            if (ev.code === 1000) { state.ended = true; VaierPanes.release(machine, paneId); window.close(); setStatus('The shell ended.'); return; }
            setDot('error');
            setPasswordPrompt(false);
            if (!PERMANENT.has(ev.code) && state.retries < MAX_RECONNECTS) {
                state.retries++;
                const delay = Math.min(8000, 1000 * 2 ** (state.retries - 1));
                setStatus(`Connection lost — reconnecting (attempt ${state.retries})…`, true);
                state.reconnectTimer = setTimeout(() => { if (!state.ended) connect(); }, delay);
            } else if (!PERMANENT.has(ev.code)) {
                setStatus('Connection lost. The host did not come back.', true, () => { state.retries = 0; connect(); });
            } else {
                setStatus(CLOSE_REASONS[ev.code] || (ev.reason || 'The terminal connection closed.'), true);
            }
        };
        ws.onerror = () => { /* the close handler reports the reason */ };
    }

    // --- the truthful reconnect banner ----------------------------------------------------------------
    function armReconnectBanner() {
        state.pendingBanner = true;
        clearTimeout(state.bannerTimer);
        state.bannerTimer = setTimeout(() => writeReconnectBanner(null), 1500);
    }
    function writeReconnectBanner(mode) {
        if (!state.pendingBanner) return;
        state.pendingBanner = false;
        clearTimeout(state.bannerTimer);
        const GREEN = '\x1b[32m', AMBER = '\x1b[33m', RESET = '\x1b[0m';
        let color, text;
        if (mode === 'reattached') { color = GREEN; text = '[reattached — session resumed]'; }
        else if (mode === 'plain') { color = AMBER; text = '[reconnected — new shell; tmux is not installed, so the previous session was lost]'; }
        else if (mode === 'new') { color = AMBER; text = '[reconnected — new shell; the previous session had ended]'; }
        else { color = GREEN; text = '[reconnected]'; }
        term.write('\r\n' + color + text + RESET + '\r\n');
    }

    // --- control frames -------------------------------------------------------------------------------
    function handleControl(raw) {
        let msg;
        try { msg = JSON.parse(raw); } catch (e) { return; }
        if (!msg) return;
        if (msg.type === 'password-result') showPasswordResult(msg.status);
        else if (msg.type === 'password-prompt') setPasswordPrompt(!!msg.showing);
        else if (msg.type === 'shell-mode') { state.shellMode = msg.mode; if (state.pendingBanner) writeReconnectBanner(msg.mode); }
    }

    function setPasswordPrompt(showing) { state.promptShowing = !!showing; refreshActions(); }

    const PASSWORD_RESULTS = {
        SENT: { message: 'Password sent.', error: false },
        NOT_AT_PROMPT: { message: "The remote isn't asking for a password right now.", error: true },
        NO_PASSWORD_CREDENTIAL: { message: 'This machine has no stored password — it uses key auth.', error: true },
        FAILED: { message: "Couldn't send the password. Check the Vaier logs.", error: true },
    };
    function showPasswordResult(status) {
        const r = PASSWORD_RESULTS[status] || PASSWORD_RESULTS.FAILED;
        setStatus(r.message, r.error);
        if (!r.error) setTimeout(() => setStatus(null), 2500);
    }
    // --- chrome: dot, status, fit ---------------------------------------------------------------------
    function setDot(kind) {
        const dot = $('twDot');
        dot.classList.remove('error');
        if (kind) dot.classList.add(kind);
    }
    function setStatus(message, isError, retry) {
        const el = $('twStatus');
        el.classList.toggle('error', !!isError);
        el.textContent = '';
        if (!message) return;
        el.append(document.createTextNode(message + ' '));
        if (retry) {
            const b = document.createElement('button');
            b.className = 'tw-btn tw-status-btn'; b.textContent = 'Reconnect';
            b.onclick = () => { setStatus(null); retry(); };
            el.appendChild(b);
        }
    }
    function refit() { try { fit.fit(); } catch (e) { /* not laid out yet */ } }
    function sendResize() {
        if (state.ws && state.ws.readyState === WebSocket.OPEN) {
            state.ws.send(JSON.stringify({ type: 'resize', cols: term.cols, rows: term.rows }));
        }
    }

    // Exiting the shell is the operator saying "kill this session" — send the frame that tears down tmux, so it
    // is not left running on the host, then forget the id and close the window.
    function endShell() {
        state.ended = true;
        releaseWakeLock();
        send({ type: 'end-shell' });
        VaierPanes.release(machine, paneId);
        if (state.ws) try { state.ws.onclose = null; state.ws.close(); } catch (e) { /* ignore */ }
        window.close();
        // If the browser refuses to close a window it did not script-open, leave a clear end state behind.
        setStatus('Shell exited — you can close this window.');
        term.dispose();
    }

    // --- touch scroll (a phone scrolls the shell, never the page) ------------------------------------
    // xterm's scrollable viewport is a sibling of its text layer, so a touch landing on the text has no
    // scrollable ancestor but the page. Drive the scrollback from the finger directly and swallow the gesture.
    function attachTouchScroll(el, t) {
        let lastY = null, acc = 0;
        el.addEventListener('touchstart', (e) => {
            if (e.touches.length !== 1) { lastY = null; return; }
            lastY = e.touches[0].clientY; acc = 0;
        }, { passive: true });
        el.addEventListener('touchmove', (e) => {
            if (lastY == null || e.touches.length !== 1) return;
            const y = e.touches[0].clientY;
            acc += lastY - y; lastY = y;
            const cell = Math.max(8, el.clientHeight / Math.max(1, t.rows));
            if (Math.abs(acc) >= cell) {
                const lines = Math.trunc(acc / cell);
                t.scrollLines(lines);
                acc -= lines * cell;
            }
            e.preventDefault();
        }, { passive: false });
        const clear = () => { lastY = null; };
        el.addEventListener('touchend', clear);
        el.addEventListener('touchcancel', clear);
    }

    // --- the phone key row ---------------------------------------------------------------------------
    // A soft keyboard has no Esc, Tab, Ctrl, Alt or arrows — the keys a shell needs most. This row sends those
    // over the same socket as a keystroke. Ctrl and Alt are sticky: tapping one arms it (it glows), and it
    // modifies the very next key — tapped here or typed on the soft keyboard — then disarms.
    let _kbCtrl = false, _kbAlt = false;
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

    // A keystroke (soft or physical): fold in any armed sticky modifier, then send.
    function sendTyped(data) {
        if ((_kbCtrl || _kbAlt) && data.length === 1) {
            if (_kbCtrl) data = ctrlByte(data);
            if (_kbAlt) data = '\x1b' + data;   // Alt/Meta is an ESC prefix, outside the control fold
            disarmMods();
        }
        wsSend(data);
    }
    // A printable character folded to its control code: a–z → 0x01–0x1a, plus the symbols the terminal defines.
    function ctrlByte(ch) {
        const code = ch.charCodeAt(0);
        if (code >= 97 && code <= 122) return String.fromCharCode(code - 96);
        if (code >= 65 && code <= 90) return String.fromCharCode(code - 64);
        const map = { '@': '\x00', ' ': '\x00', '[': '\x1b', '\\': '\x1c', ']': '\x1d', '^': '\x1e', '_': '\x1f', '?': '\x7f' };
        return map[ch] != null ? map[ch] : ch;
    }
    function modifierParam() { return 1 + (_kbAlt ? 2 : 0) + (_kbCtrl ? 4 : 0); }
    // A cursor key: the CSI modified form when a modifier is armed, else the shell's DECCKM-aware default.
    function sendArrow(letter) {
        const mod = modifierParam();
        let seq;
        if (mod > 1) { seq = '\x1b[1;' + mod + letter; }
        else { const app = term.modes && term.modes.applicationCursorKeysMode; seq = (app ? '\x1bO' : '\x1b[') + letter; }
        wsSend(seq);
        disarmMods();
    }
    function toggleMod(which) { if (which === 'ctrl') _kbCtrl = !_kbCtrl; else if (which === 'alt') _kbAlt = !_kbAlt; paintMods(); }
    function disarmMods() { if (_kbCtrl || _kbAlt) { _kbCtrl = false; _kbAlt = false; paintMods(); } }
    function paintMods() {
        for (const btn of $('twKeys').querySelectorAll('.tw-key-mod')) {
            const armed = (btn.dataset.mod === 'ctrl' && _kbCtrl) || (btn.dataset.mod === 'alt' && _kbAlt);
            btn.classList.toggle('is-armed', armed);
            btn.setAttribute('aria-pressed', armed ? 'true' : 'false');
        }
    }
    function onKeyBarPress(spec) {
        if (spec.mod) { toggleMod(spec.mod); return; }
        if (spec.arrow) { sendArrow(spec.arrow); return; }
        let seq = spec.special === 'tab' ? '\t' : '\x1b';
        if (_kbAlt) seq = '\x1b' + seq;
        wsSend(seq);
        disarmMods();
    }
    // Acting on pointerdown (and preventing its default) keeps focus in the terminal, so the soft keyboard
    // never drops between taps and typing continues uninterrupted.
    function buildKeyBar() {
        const bar = $('twKeys');
        for (const spec of KEY_BAR) {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'tw-key' + (spec.mod ? ' tw-key-mod' : '');
            btn.textContent = spec.label;
            btn.setAttribute('aria-label', spec.aria || spec.label);
            if (spec.mod) { btn.dataset.mod = spec.mod; btn.setAttribute('aria-pressed', 'false'); }
            btn.addEventListener('pointerdown', (e) => { e.preventDefault(); onKeyBarPress(spec); });
            bar.appendChild(btn);
        }
    }

    // --- keep the session claimed while this window holds it ------------------------------------------
    // Beat every few seconds so the dock (and other windows) know this session is held; the interval simply
    // stops when the window closes, and the id goes stale so the session becomes reattachable elsewhere.
    setInterval(() => { if (state.ws && state.ws.readyState === WebSocket.OPEN) VaierPanes.beat(paneId); }, 5000);

    buildKeyBar();
    window.addEventListener('resize', refit);
    // The soft keyboard shrinks the visual viewport but not the layout viewport, which would leave the terminal
    // (and the key row) hidden behind the keyboard. Bind the page height to the visual viewport so the content —
    // and the focused shell — sits above it. Desktop has no keyboard, so this just tracks the window.
    if (window.visualViewport) {
        const syncViewport = () => { document.body.style.height = window.visualViewport.height + 'px'; refit(); };
        window.visualViewport.addEventListener('resize', syncViewport);
        window.visualViewport.addEventListener('scroll', syncViewport);
        syncViewport();
    }
    // Crossing the phone/desktop breakpoint re-sizes the font and re-fits; the key row shows/hides by CSS.
    try {
        _phone.addEventListener('change', () => { term.options.fontSize = termFontSize(); refit(); sendResize(); });
    } catch (e) { /* older browsers: font is set at open time only */ }
    document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible' && !state.ended) acquireWakeLock();
    });

    connect();
})();
