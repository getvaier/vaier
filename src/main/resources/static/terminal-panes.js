// Shared ownership of persistent shell sessions, across the dock and the pop-out windows.
//
// A shell runs inside a tmux session on the machine, named by a pane id (see terminal-dock.js). That id has to
// outlive the browser so a reload — or a pop-out into a separate window — reattaches to the same session rather
// than orphaning it on the host, running forever and invisible. The dock and every pop-out window are separate
// documents on one origin, so they coordinate through localStorage:
//
//   OWNED: machine -> [paneId]      the sessions this browser believes exist
//   LIVE:  paneId  -> epochMillis   the last heartbeat from whichever document is attached right now
//
// A document beats LIVE for the session it is attached to every few seconds. An owned id is only handed back
// out when no document currently holds it (its heartbeat is stale or gone) — so a pop-out window and the dock
// can never fight over one session. When a window is popped out, the dock stops beating that id and the window
// starts, so the claim passes cleanly; when the window closes, the beat stops and the dock can reattach.
(function () {
    'use strict';

    // The dock shipped with this key; keep it so shells open before pop-out existed still reattach.
    const OWNED = 'vaier.terminal.panes';
    const LIVE = 'vaier.terminal.live';
    // A machine's one *primary* shell — the session "Open shell window" always returns to. Stored so it is the
    // same id every time, on this browser, across reloads and redeploys: a machine's shell should be one place
    // you go back to, never a fresh shell one time and an old one the next.
    const PRIMARY = 'vaier.terminal.primary';
    // Longer than the beat interval (5s) with room for a backgrounded tab that beats late, short enough that a
    // closed window's session is reattachable within a few seconds.
    const STALE_MS = 15000;

    function read(key) {
        try { return JSON.parse(localStorage.getItem(key)) || {}; } catch (e) { return {}; }
    }
    function write(key, value) {
        try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* private mode — best effort */ }
    }

    function newId() {
        try { if (window.crypto && crypto.randomUUID) return crypto.randomUUID(); } catch (e) { /* fall back */ }
        return 'p-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
    }

    function isLive(paneId) {
        const t = read(LIVE)[paneId];
        return t != null && (Date.now() - t) < STALE_MS;
    }

    // A pane id to open a *new* shell on `machine`: always a fresh session, registered so the browser keeps it.
    // Opening a shell is deliberately deterministic — it never scavenges "some stale owned id" to reattach to.
    // That scavenging was the source of the "sometimes fresh, sometimes not" surprise: whether you landed in a
    // new shell or an old one depended only on whether an orphaned id happened to be lying around. Reattaching
    // now happens solely through an *explicit* id — a machine's stable {@link primary}, or a pane id carried in
    // a window's own URL across a reload or a pop-out — never by chance. (`onScreenIds` is accepted for callers'
    // sake and no longer consulted.)
    function claim(machine, onScreenIds) {   // eslint-disable-line no-unused-vars
        const store = read(OWNED);
        const fresh = newId();
        store[machine] = (store[machine] || []).concat(fresh);
        write(OWNED, store);
        return fresh;
    }

    // The machine's stable primary pane id — minted once and remembered, so "Open shell window" reattaches to
    // the very same session every time instead of claiming whatever orphan was around. Kept in OWNED too, so it
    // is heartbeat-tracked and reattachable like any other session.
    function primary(machine) {
        const store = read(PRIMARY);
        let pid = store[machine];
        if (!pid) { pid = newId(); store[machine] = pid; write(PRIMARY, store); }
        adopt(machine, pid);
        return pid;
    }

    // Ensure the store owns this (machine, paneId). Used when a window is opened on an id handed to it in its
    // URL (a pop-out, or a reload), so the browser keeps the session even if that window later goes away.
    function adopt(machine, paneId) {
        const store = read(OWNED);
        const owned = store[machine] || [];
        if (!owned.includes(paneId)) { store[machine] = owned.concat(paneId); write(OWNED, store); }
    }

    // The session was ended for good (the shell exited, or the operator ended it) — forget it everywhere.
    function release(machine, paneId) {
        const store = read(OWNED);
        const owned = (store[machine] || []).filter((pid) => pid !== paneId);
        if (owned.length) store[machine] = owned; else delete store[machine];
        write(OWNED, store);
        // If the primary session itself was ended, forget it — the next "Open shell window" should mint a fresh
        // primary rather than try to return to a session the operator deliberately closed.
        const prim = read(PRIMARY);
        if (prim[machine] === paneId) { delete prim[machine]; write(PRIMARY, prim); }
        stopBeat(paneId);
    }

    function beat(paneId) { const l = read(LIVE); l[paneId] = Date.now(); write(LIVE, l); }
    function stopBeat(paneId) { const l = read(LIVE); if (l[paneId] != null) { delete l[paneId]; write(LIVE, l); } }

    window.VaierPanes = { claim, primary, adopt, release, beat, stopBeat, newId };
})();
