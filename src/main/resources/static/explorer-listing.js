// Reading a directory on a machine — the one copy of it (#323 slice A).
//
// Two Explorers now read the fleet's filesystems: the file browser shipped in #321 (explorer-files.html) and
// the tree shell that is replacing it (explorer.html). A second copy of this would be a second place the size
// humanising, the clock format, the newest-listing-wins guard and the server's own error message could quietly
// drift apart — so both read a directory through here.
//
// It knows nothing about how a listing is painted. It answers what a directory holds, or why it could not say.
(function () {
    'use strict';

    // Sizes are read at a glance, not audited — a rounded scale beats an exact byte count in a listing.
    function formatSize(entry) {
        if (entry.directory) return '—';
        const bytes = entry.size;
        if (bytes == null) return '—';
        const units = ['B', 'K', 'M', 'G', 'T'];
        let n = bytes;
        let i = 0;
        while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
        return (n < 10 && i > 0 ? n.toFixed(1) : Math.round(n)) + units[i];
    }

    // The operator's own locale, but never a 12-hour clock: a fleet's timestamps are read against each other,
    // and "3:00" twice a day is a worse answer than "03:00" once.
    function formatTime(iso) {
        if (!iso) return '';
        const d = new Date(iso);
        if (isNaN(d)) return '';
        return d.toLocaleString(undefined, {
            day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false,
        });
    }

    // A browser owns one monotonic ticket: only the newest listing asked for may paint. A machine that is
    // asleep behind a tunnel can take seconds to answer, and a slow host must never overwrite the directory
    // the operator has already moved on to.
    function createBrowser() {
        let inFlight = 0;

        // Resolves to { entries }, { error } — or { stale: true } when a newer listing has been asked for
        // since, which the caller must simply drop on the floor.
        async function list(machine, path) {
            const ticket = ++inFlight;
            try {
                const res = await fetch('/machines/' + encodeURIComponent(machine)
                    + '/files?path=' + encodeURIComponent(path));
                if (ticket !== inFlight) return { stale: true };

                if (!res.ok) {
                    // Vaier answers a failure with an ApiError envelope, and its message is already written
                    // for the operator — "Not allowed to read /root as geir." says more than any status code
                    // could. Pass it through verbatim; only a silent server gets a message of ours.
                    const err = await res.json().catch(() => null);
                    if (ticket !== inFlight) return { stale: true };
                    return { error: (err && err.message)
                        || 'Could not list ' + path + ' on ' + machine + '.' };
                }

                const entries = await res.json();
                if (ticket !== inFlight) return { stale: true };
                return { entries: entries };
            } catch (e) {
                if (ticket !== inFlight) return { stale: true };
                return { error: 'Could not reach ' + machine + '.' };
            }
        }

        return { list: list };
    }

    window.VaierListing = { formatSize: formatSize, formatTime: formatTime, createBrowser: createBrowser };
})();
