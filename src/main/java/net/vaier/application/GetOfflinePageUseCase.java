package net.vaier.application;

public interface GetOfflinePageUseCase {

    /**
     * Render Vaier's branded <b>offline page</b> for a published service whose backend is
     * unreachable. {@code status} is the upstream gateway status Traefik hit (502/503/504, or
     * other); {@code serviceHost} is the value of the failed request's {@code X-Forwarded-Host}
     * header (may be null when absent), used to name the unavailable service on the page.
     */
    OfflinePage render(int status, String serviceHost);

    /**
     * @param status      the HTTP status the page should be served with (mirrors the upstream gateway status).
     * @param contentType the MIME type of {@link #html()} (e.g. {@code text/html; charset=utf-8}).
     * @param html        a fully self-contained HTML document with inline styles — no cross-origin asset links.
     */
    record OfflinePage(int status, String contentType, String html) {}
}
