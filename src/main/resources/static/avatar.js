// Shared profile-photo resolution for the Users cards and the topbar, so the avatar chain lives
// in one place instead of being copied per page.
//
// The photo chain: a GitHub sign-in with a known provider user id → the GitHub account photo;
// otherwise Gravatar keyed on the email's SHA-256 (d=404 so a miss errors out and the caller falls
// back to its own placeholder — the roster's initials monogram, or the topbar's name text).
// Returns null to stay on the placeholder (no provider id and no usable email, or no crypto.subtle).
const VaierAvatar = (() => {
    // SHA-256 hex of a string, for the Gravatar avatar hash. crypto.subtle needs a secure context
    // (Vaier is served over HTTPS via Traefik); returns null if unavailable so callers stay on the
    // placeholder rather than break.
    async function sha256Hex(str) {
        if (!crypto.subtle) return null;
        const bytes = new TextEncoder().encode(str);
        const digest = await crypto.subtle.digest('SHA-256', bytes);
        return Array.from(new Uint8Array(digest)).map(b => b.toString(16).padStart(2, '0')).join('');
    }

    // The photo URL for an identity, or null to stay on the placeholder. `size` is the requested
    // pixel size passed through to the provider (GitHub `s=`, Gravatar `s=`).
    async function photoUrl({ provider, providerUserId, email, size = 80 } = {}) {
        if (provider === 'github' && providerUserId) {
            return `https://avatars.githubusercontent.com/u/${encodeURIComponent(providerUserId)}?s=${size}`;
        }
        const hash = await sha256Hex((email || '').trim().toLowerCase());
        return hash ? `https://www.gravatar.com/avatar/${hash}?s=${size}&d=404` : null;
    }

    return { sha256Hex, photoUrl };
})();
