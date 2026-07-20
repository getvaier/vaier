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

    // A pane id to open `machine` with: reuse the first owned id that no live document holds and that this
    // document is not already showing (so its session is reattached, not doubled onto itself), else mint and
    // register a fresh one. `onScreenIds` are the ids this document already has open for the machine.
    function claim(machine, onScreenIds) {
        const store = read(OWNED);
        const owned = store[machine] || [];
        const onScreen = new Set(onScreenIds || []);
        const reusable = owned.find((pid) => !onScreen.has(pid) && !isLive(pid));
        if (reusable) return reusable;
        const fresh = newId();
        store[machine] = owned.concat(fresh);
        write(OWNED, store);
        return fresh;
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
        stopBeat(paneId);
    }

    function beat(paneId) { const l = read(LIVE); l[paneId] = Date.now(); write(LIVE, l); }
    function stopBeat(paneId) { const l = read(LIVE); if (l[paneId] != null) { delete l[paneId]; write(LIVE, l); } }

    window.VaierPanes = { claim, adopt, release, beat, stopBeat, newId };
})();
