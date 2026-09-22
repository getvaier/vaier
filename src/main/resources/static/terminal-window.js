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
    // The identity the socket is opened against. A name is what a person reads; it is not what addresses a
    // machine, and putting one in the path closes the socket as "Machine not found" — the machine store is
    // keyed by id. Carried in the URL by whoever opened this window, and re-resolved from /machines when it
    // is missing, so a window bookmarked before ids existed still opens.
    let machineId = params.get('id');
    const $ = (id) => document.getElementById(id);

    if (!machine) {
        $('twStatus').textContent = 'No machine was named for this terminal.';
        return;
    }
    document.title = machine + ' · shell';
    $('twName').textContent = machine;

    // The session id. Handed in for a pop-out (reattach the dock's shell) or on reload (reattach our own); a
    // fresh window mints one and writes it into its own URL, so a reload of this window reattaches too.
    //
    // Deliberately NOT claimed at parse time: sessions are owned per machine identity, and a window opened
    // from a pre-identity bookmark does not know its identity yet — it resolves one from /machines below.
    // Claiming before that would file the session under `null`, where every such window would share one
    // bucket and the first reload could hand one machine's shell to another.
    let paneId = params.get('pane');
    function claimPane() {
        if (paneId) {
            VaierPanes.adopt(machineId, paneId, machine);
            return;
        }
        paneId = VaierPanes.claim(machineId, machine);
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
    const CLOSE_HOST_KEY_MISMATCH = 4403;
    const PERMANENT = new Set([1000, 4401, 4402, CLOSE_HOST_KEY_MISMATCH, 4404]);
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
        promptShowing: false, connected: false,
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
    // xterm.js's default keydown handling always sends Ctrl+C as a literal SIGINT and prevents the
    // keydown's default action — even with text selected — so the browser's native copy never fires.
    // Let Ctrl/Cmd+C through to the browser when there's a selection (xterm's own 'copy' listener then
    // copies it), and let Ctrl/Cmd+V through always (xterm's own 'paste' listener already handles a real
    // paste); every other key keeps going to the shell exactly as before.
    term.attachCustomKeyEventHandler((e) => {
        if (e.type !== 'keydown' || e.altKey || e.shiftKey || !(e.ctrlKey || e.metaKey)) return true;
        const key = e.key.toLowerCase();
        if (key === 'c' && term.hasSelection()) return false;
        return key !== 'v';
    });
    // Route input through sendTyped so an armed on-screen Ctrl/Alt (the phone key row) folds into the next
    // keystroke; with nothing armed it is a plain pass-through, so a physical keyboard is unaffected.
    term.onData((data) => sendTyped(data));
    term.onResize(() => sendResize());
    attachTouchScroll($('twTerm'), term);

    // --- the top bar's actions ------------------------------------------------------------------------

    // The bar's own glyphs. A phone has room for five verbs or for five words, not both, and dropping a verb
    // to fit is the wrong half to lose — so on a phone the labels go and the glyphs carry them, with the word
    // still on aria-label and title. The drawings are the Explorer's, at the same weight, because a machine's
    // Claude standing must not be one shape on its fleet card and another in its shell (there is a test).
    const ICON = {
        claude: '<path d="M8 1.7v12.6M1.7 8h12.6"/><path d="M5.2 5.2l5.6 5.6M10.8 5.2l-5.6 5.6"/>',
        copy:   '<rect x="5.5" y="5.5" width="8" height="8.2" rx="1.2"/><path d="M3.4 10.5H3a1 1 0 0 1-1-1V3.2a1 1 0 0 1 1-1h6.3a1 1 0 0 1 1 1v.4"/>',
        window: '<path d="M6.8 2.6H3.2a1 1 0 0 0-1 1v9.2a1 1 0 0 0 1 1h9.6a1 1 0 0 0 1-1V9.2"/><path d="M9.6 2.6h4.2v4.2M13.8 2.6 8.2 8.2"/>',
        clip:   '<rect x="3" y="2.6" width="10" height="11.4" rx="1.2"/><path d="M5.8 2.6V2a.9.9 0 0 1 .9-.9h2.6a.9.9 0 0 1 .9.9v.6z"/><path d="M5.6 7.2h4.8M5.6 9.7h4.8M5.6 12.2h2.8"/>',
        key:    '<circle cx="5.4" cy="10.6" r="2.9"/><path d="M7.5 8.5l5.7-5.7"/><path d="M11.1 4.9l1.5 1.5M12.3 3.7l1.5 1.5"/>',
        cross:  '<path d="M4 4l8 8M12 4l-8 8"/>',
    };
    const glyph = (name) => '<svg class="tw-ico" viewBox="0 0 16 16" fill="none" stroke="currentColor" '
        + 'stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">' + ICON[name] + '</svg>';

    const PASSWORD_DISABLED_REASON = 'Available only while the remote is asking for a password';
    const PASTE_DISABLED_REASON = 'Available once the shell is connected';
    const PASTE_HELP = 'Press Ctrl+V (⌘V on a Mac) to paste.';

    // Duplicate opens another, separate shell on this same machine in its own window — a fresh session id and a
    // unique window name, so several shells on one machine can be open side by side.
    const btnDup = actionButton('Duplicate', () => {
        const pane = VaierPanes.newId();
        window.open('terminal.html?machine=' + encodeURIComponent(machine)
            + '&id=' + encodeURIComponent(machineId) + '&pane=' + encodeURIComponent(pane),
            'vaier-shell-' + encodeURIComponent(pane), 'popup,width=1024,height=680');
    }, 'window');
    btnDup.title = 'Open a second, separate shell on ' + machine;
    const btnCopy = actionButton('Copy', copyFromShell, 'copy');
    btnCopy.title = 'Copy from this shell: the selection if there is one, otherwise the screen as text you can select';
    const btnPaste = actionButton('Paste', pasteClipboard, 'clip');
    const btnPassword = actionButton('Send password', () => send({ type: 'send-password' }), 'key');
    const btnEnd = actionButton('Exit shell', endShell, 'cross');
    btnEnd.classList.add('tw-danger');
    // The one distinction people miss: closing the window keeps the shell alive to reattach; Exit stops it.
    btnEnd.title = 'Stop this shell for good on ' + machine + '. Just closing the window keeps it running — and '
        + 'it even survives a Vaier restart — so reopening reattaches right where you left off.';
    // Where this machine stands on Claude, and the way in. Hidden until something is known — and left hidden
    // where the server says a sign-in is impossible here, because a control explaining an impossibility is
    // worth less than no control.
    const btnClaude = actionButton('Claude', toggleClaude, 'claude');
    // The three ways out of a modal, all of them expected: the ×, the ground around it, and Escape.
    $('twClaudeClose').onclick = () => setClaudePanel(false);
    $('twScrim').onclick = (e) => { if (e.target === $('twScrim')) setClaudePanel(false); };
    document.addEventListener('keydown', (e) => {
        if (e.key !== 'Escape') return;
        if (!$('twScrim').hidden) { e.preventDefault(); setClaudePanel(false); }
        if (!$('twCopyScrim').hidden) { e.preventDefault(); setCopySheet(false); }
    });
    btnClaude.hidden = true;
    btnClaude.setAttribute('aria-expanded', 'false');
    btnClaude.setAttribute('aria-controls', 'twScrim');
    $('twActions').append(btnClaude, btnDup, btnCopy, btnPaste, btnPassword, btnEnd);
    refreshActions();

    function actionButton(label, onClick, icon) {
        const b = document.createElement('button');
        b.type = 'button'; b.className = 'tw-btn'; b.onclick = onClick;
        b.innerHTML = glyph(icon);
        const lbl = document.createElement('span');
        lbl.className = 'tw-btn-lbl';
        lbl.textContent = label;
        b.appendChild(lbl);
        // The word survives losing its label: it is the button's accessible name either way, and the title is
        // what a pointer gets. Both are set here so no caller can ship a glyph nobody can name.
        b.setAttribute('aria-label', label);
        b.title = label;
        return b;
    }
    function refreshActions() {
        btnPassword.disabled = !state.promptShowing;
        btnPassword.title = state.promptShowing ? 'Send the stored password' : PASSWORD_DISABLED_REASON;
        btnPaste.disabled = !state.connected;
        btnPaste.title = state.connected ? 'Paste the clipboard into this shell' : PASTE_DISABLED_REASON;
    }

    // --- Claude, in the window that is this machine's terminal ------------------------------------------
    //
    // Signing in to Claude runs the CLI in one OS user's home on one machine. This window IS that machine as
    // that user, so this is where it belongs — claude-sign-in.js draws and drives the whole thing, and all
    // that is left here is the bar control and the terminal's size.
    let claude = null;

    function startClaude() {
        claude = window.VaierClaude.mount($('twClaude'), machineId, machine);
        claude.onUpdate(showClaudeStanding);
    }

    function showClaudeStanding(view) {
        btnClaude.hidden = !view.draw;
        if (!view.draw) { setClaudePanel(false); return; }
        // The word is only "Claude". The standing is said by the clay below and spelled out on the title, and
        // saying it on the button as well made it half again the width of every other control in the bar.
        btnClaude.className = 'tw-btn tw-claude-btn ' + window.VaierClaude.words(view.state).tone;
        btnClaude.title = view.title;
    }

    function toggleClaude() { setClaudePanel($('twScrim').hidden); }

    // Over the shell, not in it: the sign-in takes no rows from the terminal, so nothing here re-fits and
    // nothing is told to the far side. That is the point of the modal — a terminal redrawn at the wrong size
    // under a running command is a corrupted screen, and now it cannot happen from this control at all.
    function setClaudePanel(open) {
        const scrim = $('twScrim');
        if (open === !scrim.hidden) return;
        scrim.hidden = !open;
        btnClaude.setAttribute('aria-expanded', String(open));
        if (open) {
            // Focus leaves the terminal, or the first thing typed into the code box would go to the shell.
            $('twClaudeClose').focus();
        } else {
            // Putting it away abandons a sign-in in progress, exactly as leaving the machine used to.
            if (claude) claude.leave();
            term.focus();
        }
    }

    // --- Copy, for a finger ---------------------------------------------------------------------------------
    //
    // xterm.js 5 has no touch selection, and attachTouchScroll turns a drag into a scroll on purpose, so on a
    // phone nothing in the shell can be selected at all. Copy therefore does the obvious thing twice over: with
    // a selection (a mouse) it copies that and stops; without one it shows the screen as plain page text the OS
    // already knows how to select, plus one button for the whole of it.
    let _copyAll = false;
    $('twCopyClose').onclick = () => setCopySheet(false);
    $('twCopyScrim').onclick = (e) => { if (e.target === $('twCopyScrim')) setCopySheet(false); };
    $('twCopyEarlier').onclick = () => { _copyAll = !_copyAll; fillCopySheet(); };
    $('twCopyAll').onclick = () => writeClipboard($('twCopyText').textContent, true);

    function copyFromShell() {
        if (term.hasSelection()) { writeClipboard(term.getSelection(), false); return; }
        _copyAll = false;
        setCopySheet(true);
    }

    // The buffer's lines as text. A wrapped continuation is glued back onto its line, so a long command copies
    // as the one line it was typed as; trailing blank rows below the last output are dropped.
    function shellText(all) {
        const buf = term.buffer.active;
        const from = all ? 0 : buf.viewportY;
        const to = all ? buf.length : Math.min(buf.length, buf.viewportY + term.rows);
        const lines = [];
        for (let i = from; i < to; i++) {
            const line = buf.getLine(i);
            const text = line ? line.translateToString(true) : '';
            if (line && line.isWrapped && lines.length) lines[lines.length - 1] += text;
            else lines.push(text);
        }
        while (lines.length && !lines[lines.length - 1]) lines.pop();
        return lines.join('\n');
    }

    function fillCopySheet() {
        const pre = $('twCopyText');
        pre.textContent = shellText(_copyAll);
        $('twCopyEarlier').textContent = _copyAll ? 'Show only the screen' : 'Show earlier output';
        $('twCopyEarlier').hidden = !_copyAll && term.buffer.active.viewportY === 0;
        pre.scrollTop = pre.scrollHeight;   // the newest output is what was just asked for
    }

    function setCopySheet(open) {
        const scrim = $('twCopyScrim');
        if (open === !scrim.hidden) return;
        scrim.hidden = !open;
        if (open) { fillCopySheet(); $('twCopyText').focus(); } else { term.focus(); }
    }

    // The clipboard is asked politely first; a browser that refuses (or has no navigator.clipboard) gets the
    // page's own copy command over a selection of the sheet's text, which every browser still honours.
    async function writeClipboard(text, fromSheet) {
        if (!text) { setStatus('Nothing to copy.', true); return; }
        let ok = false;
        try { await navigator.clipboard.writeText(text); ok = true; } catch (e) { ok = false; }
        if (!ok && fromSheet) {
            const range = document.createRange(); range.selectNodeContents($('twCopyText'));
            const sel = window.getSelection(); sel.removeAllRanges(); sel.addRange(range);
            try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
            sel.removeAllRanges();
        }
        if (ok) { setStatus('Copied.'); setCopySheet(false); return; }
        if (!fromSheet) { _copyAll = false; setCopySheet(true); }
        setStatus('The browser did not share the clipboard. Select the text and copy it by hand.', true);
    }

    // Pasting from a button, not only from Ctrl/Cmd+V: a soft keyboard has no Ctrl at all, and a browser
    // may refuse the keystroke path outright. Routed through term.paste so a multi-line paste arrives
    // bracketed — text the remote receives as text, not a queue of lines it starts running.
    async function pasteClipboard() {
        let text;
        try {
            text = await navigator.clipboard.readText();
        } catch (e) {
            // Refused, dismissed, or unimplemented: indistinguishable here, and one remedy covers all three.
            setStatus('The browser did not share the clipboard. ' + PASTE_HELP, true);
            return;
        }
        if (!text) { setStatus('The clipboard is empty.', true); return; }
        disarmMods();   // a paste is not a keystroke, so an armed sticky modifier must not fold into it
        setStatus(null);
        term.paste(text);
        term.focus();
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
        const url = `${proto}//${window.location.host}/machines/${encodeURIComponent(machineId)}`
            + `/terminal?pane=${encodeURIComponent(paneId)}`;
        const ws = new WebSocket(url);
        ws.binaryType = 'arraybuffer';
        state.ws = ws;

        ws.onopen = () => {
            const reconnected = state.retries > 0;
            state.retries = 0;
            state.connected = true;
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
            state.connected = false;
            if (state.ended) return;
            // A clean exit means the remote shell ended — the session is gone, so let it go and close the window.
            if (ev.code === 1000) { state.ended = true; VaierPanes.release(machineId, paneId, machine); window.close(); setStatus('The shell ended.'); return; }
            setDot('error');
            setPasswordPrompt(false);
            if (!PERMANENT.has(ev.code) && state.retries < MAX_RECONNECTS) {
                state.retries++;
                const delay = Math.min(8000, 1000 * 2 ** (state.retries - 1));
                setStatus(`Connection lost — reconnecting (attempt ${state.retries})…`, true);
                state.reconnectTimer = setTimeout(() => { if (!state.ended) connect(); }, delay);
            } else if (!PERMANENT.has(ev.code)) {
                setStatus('Connection lost. The host did not come back.', true, () => { state.retries = 0; connect(); });
            } else if (ev.code === CLOSE_HOST_KEY_MISMATCH) {
                // The one permanent close with a remedy Vaier can carry out. Offered here rather than only
                // in the Explorer: this window is where the operator met the refusal.
                setStatus(CLOSE_REASONS[ev.code], true, null, {
                    label: 'Clear pinned key',
                    armedLabel: 'Confirm — I changed this machine',
                    run: clearPinnedKey,
                });
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
    // `action` is a two-step button: the first click arms it and re-labels it with what is actually being
    // asserted, the second click performs it. This window has no dialog primitives and should not grow a
    // modal for one verb — but the verb it needs (clearing a host-key pin) is exactly the kind that must not
    // be a reflex, so the assertion is made in place instead of skipped.
    function setStatus(message, isError, retry, action) {
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
        if (action) {
            const b = document.createElement('button');
            b.className = 'tw-btn tw-status-btn'; b.textContent = action.label;
            let armed = false;
            b.onclick = () => {
                if (!armed) { armed = true; b.textContent = action.armedLabel; b.classList.add('is-armed'); return; }
                action.run(b);
            };
            el.appendChild(b);
        }
    }

    // Clearing this machine's pinned host key, from the one place the refusal is actually read. Vaier has
    // always named this remedy here and never offered it, so the only way out was an API client.
    //
    // The pin is not left off: the next connect pins whatever key the machine presents (trust on first use),
    // which is why the success line points straight at Reconnect.
    async function clearPinnedKey(btn) {
        btn.disabled = true;
        try {
            const res = await fetch('/machines/' + encodeURIComponent(machineId) + '/host-key',
                { method: 'DELETE' });
            if (!res.ok && res.status !== 204) {
                setStatus('Could not clear the pinned key. Check the Vaier logs.', true);
                return;
            }
            setStatus(`Pinned key cleared for ${machine}. Reconnect to pin the key it presents now.`, false,
                () => { state.retries = 0; connect(); });
        } catch (e) {
            setStatus('Could not reach Vaier to clear the pinned key.', true);
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
        VaierPanes.release(machineId, paneId, machine);
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
    // The bar grows a row when the status speaks (a lost connection, on a phone) and loses it when the shell
    // is back. Neither is a window resize, so the terminal is watched directly or its last rows would be cut
    // off under the message.
    if (window.ResizeObserver) new ResizeObserver(refit).observe($('twTerm'));
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

    // A window opened without an id (a bookmark from before this carried one) resolves the machine by name
    // once, here, rather than handing the name to a socket that can only reject it.
    if (machineId) {
        claimPane();
        connect();
        startClaude();
    } else {
        setStatus('Finding ' + machine + '…');
        fetch('/machines')
            .then((res) => (res.ok ? res.json() : []))
            .then((fleet) => {
                // A bookmark from before shells travelled by identity. Resolving a name is the only thing
                // left to try — but it must never GUESS: opening a shell on the wrong host is the one
                // outcome worse than not opening one, and machine names are no longer unique.
                const matches = (fleet || []).filter((m) => m.name === machine);
                if (matches.length === 0) {
                    setStatus('There is no machine called "' + machine + '" in this fleet any more.', true);
                    return;
                }
                if (matches.length > 1) {
                    setStatus('More than one machine is called "' + machine + '". Open its shell from the '
                        + 'Explorer so Vaier knows which one you mean.', true);
                    return;
                }
                machineId = matches[0].id;
                // Written back into this window's own URL, so a reload does not resolve it a second time.
                const url = new URL(window.location.href);
                url.searchParams.set('id', machineId);
                window.history.replaceState(null, '', url);
                setStatus(null);
                claimPane();   // only now is there an identity to file the session under
                connect();
                startClaude();
            })
            .catch(() => setStatus('Could not reach Vaier to find ' + machine + '.', true));
    }
})();
