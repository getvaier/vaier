// Reading a directory on a machine — the one copy of it (#323 slice A).
//
// A machine is addressed by its identity here, because that is what the tree stands on and what every
// /machines path is keyed by. Its name appears only in what a person reads, and is asked for at that moment.
//
// One module reads the fleet's filesystems for the Explorer (explorer.html); it began life shared with the
// #321 file browser the shell replaced. A second copy of this would be a second place the size
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

        // Resolves to { root, path, entries }, { error, errorCode, errorDetail } — or { stale: true } when
        // a newer listing has been asked for since, which the caller must simply drop on the floor.
        //
        // `path` may be null, and that is a question rather than a default: "where does this machine's tree
        // begin?" (#326). A machine whose SFTP subsystem is chrooted — the NAS is jailed into /volume1 —
        // cannot be asked about "/" at all, so the reader must never invent one. The answer comes back with
        // the root that resolved it, and the caller learns where it is standing from the reply.
        //
        // `at` names an archive to read the same path *inside*, rather than live (#323 slice D) — the machine's
        // past. Absent, the present, unchanged. The two coordinates travel as query params; a null of either is
        // simply not sent, so the present-day root question is byte-for-byte the request it always was.
        // What to call a machine, asked of whichever page hosts this reader. The Explorer knows the fleet;
        // a page that does not simply shows the identity, which is honest rather than wrong.
        function machineName(machineId) {
            return window.vaierMachineName ? window.vaierMachineName(machineId) : machineId;
        }

        async function list(machineId, path, at) {
            const ticket = ++inFlight;
            const where = path == null ? 'the file root' : path;
            const params = new URLSearchParams();
            if (path != null) params.set('path', path);
            if (at) params.set('at', at);
            const query = params.toString();
            try {
                const res = await fetch('/machines/' + encodeURIComponent(machineId) + '/files'
                    + (query ? '?' + query : ''));
                if (ticket !== inFlight) return { stale: true };

                if (!res.ok) {
                    // Vaier answers a failure with an ApiError envelope, and its message is already written
                    // for the operator — "Not allowed to read /root as geir.", or "/volume2 is not reachable
                    // over SFTP; this machine's SFTP service is rooted at /volume1." — which says more than
                    // any status code could. Pass it through verbatim; only a silent server gets a message of
                    // ours. A refusal is never painted as an empty folder.
                    // The envelope's `code` and `detail` travel alongside the sentence (#345): a refused
                    // host key is the one failure with a remedy Vaier can offer, and the caller can only
                    // offer it if it can tell that failure from the others without reading the prose.
                    const err = await res.json().catch(() => null);
                    if (ticket !== inFlight) return { stale: true };
                    return { error: (err && err.message)
                            || 'Could not list ' + where + ' on ' + machineName(machineId) + '.',
                        errorCode: err && err.code, errorDetail: err && err.detail };
                }

                const body = await res.json();
                if (ticket !== inFlight) return { stale: true };
                // The entries are at the machine's TRUE coordinates — the ones df, borg and the operator's own
                // terminal use — and `root` says where its tree begins. In the past those are the paths the
                // archive captured, and `root` is "/", the archive's own beginning; `at` echoes the archive read.
                return { root: body.root, path: body.path, at: body.at, entries: body.entries };
            } catch (e) {
                if (ticket !== inFlight) return { stale: true };
                return { error: 'Could not reach ' + machineName(machineId) + '.' };
            }
        }

        return { list: list };
    }

    window.VaierListing = { formatSize: formatSize, formatTime: formatTime, createBrowser: createBrowser };
})();
