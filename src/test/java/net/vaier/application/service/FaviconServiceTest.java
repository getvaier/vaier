package net.vaier.application.service;

import net.vaier.application.GetFaviconUseCase.Favicon;
import net.vaier.domain.port.ForFetchingFavicons;
import net.vaier.domain.port.ForFetchingFavicons.FetchedBytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FaviconServiceTest {

    @Mock ForFetchingFavicons forFetchingFavicons;

    @InjectMocks FaviconService service;

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    }

    @Test
    void getFavicon_returnsCachedResultWithoutFetching() {
        Favicon cached = new Favicon(png(), "image/png");
        service.cache.put("cached.example.com", Optional.of(cached));

        assertThat(service.getFavicon("cached.example.com", null)).contains(cached);
        verify(forFetchingFavicons, never()).fetchHtml(org.mockito.ArgumentMatchers.anyString());
        verify(forFetchingFavicons, never()).fetchBytes(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void getFavicon_cachesNegativeResults() {
        // No HTML, no bytes anywhere — caching empty prevents repeated lookups against dead hosts.
        when(forFetchingFavicons.fetchHtml(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty());
        when(forFetchingFavicons.fetchBytes(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty());

        Optional<Favicon> first = service.getFavicon("dead.example.com", null);
        Optional<Favicon> second = service.getFavicon("dead.example.com", null);

        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        assertThat(service.cache).containsKey("dead.example.com");
    }

    @Test
    void getFavicon_cachesSeparatelyPerPathPrefix() {
        // Path-routed services share a hostname; each must resolve independently.
        Favicon grafana = new Favicon(png(), "image/png");
        Favicon jenkins = new Favicon(new byte[]{(byte) 0x89, 'P', 'N', 'J'}, "image/png");
        service.cache.put("services.example.com/grafana", Optional.of(grafana));
        service.cache.put("services.example.com/jenkins", Optional.of(jenkins));

        assertThat(service.getFavicon("services.example.com", "/grafana")).contains(grafana);
        assertThat(service.getFavicon("services.example.com", "/jenkins")).contains(jenkins);
    }

    @Test
    void getFavicon_hostOnlyAndNullAndEmptyPathPrefixShareCacheEntry() {
        Favicon cached = new Favicon(png(), "image/png");
        service.cache.put("solo.example.com", Optional.of(cached));

        assertThat(service.getFavicon("solo.example.com", null)).contains(cached);
        assertThat(service.getFavicon("solo.example.com", "")).contains(cached);
    }

    @Test
    void getFavicon_extractsFromHtmlHintWhenPresent() {
        String html = "<html><head><link rel=\"icon\" href=\"/favicon.png\"></head></html>";
        when(forFetchingFavicons.fetchHtml("https://app.example.com/"))
            .thenReturn(Optional.of(html));
        when(forFetchingFavicons.fetchBytes("https://app.example.com/favicon.png"))
            .thenReturn(Optional.of(new FetchedBytes(png(), "image/png")));

        Optional<Favicon> result = service.getFavicon("app.example.com", null);

        assertThat(result).isPresent();
        assertThat(result.get().contentType()).isEqualTo("image/png");
    }

    @Test
    void getFavicon_fallsThroughToFaviconIcoWhenHtmlHintMissing() {
        when(forFetchingFavicons.fetchHtml(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty());
        byte[] ico = {0, 0, 1, 0, 1, 0};
        when(forFetchingFavicons.fetchBytes("https://app.example.com/favicon.ico"))
            .thenReturn(Optional.of(new FetchedBytes(ico, null)));

        Optional<Favicon> result = service.getFavicon("app.example.com", null);

        assertThat(result).isPresent();
        assertThat(result.get().contentType()).isEqualTo("image/x-icon");
    }

    @Test
    void getFavicon_rejectsNonImageResponses() {
        // A 200 from an SPA that returns its index.html for any URL — bytes are HTML, not an icon.
        when(forFetchingFavicons.fetchHtml(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty());
        when(forFetchingFavicons.fetchBytes(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.of(new FetchedBytes("not-an-image".getBytes(), "text/html")));

        assertThat(service.getFavicon("app.example.com", null)).isEmpty();
    }

    @Test
    void getFavicon_fallsBackToCdnUrlsWhenAllDirectFetchesFail() {
        when(forFetchingFavicons.fetchHtml(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty());
        when(forFetchingFavicons.fetchBytes(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty());
        when(forFetchingFavicons.fetchBytes("https://cdn.jsdelivr.net/gh/walkxcode/dashboard-icons@main/png/pihole.png"))
            .thenReturn(Optional.of(new FetchedBytes(png(), "image/png")));

        Optional<Favicon> result = service.getFavicon("pihole.example.com", null);

        assertThat(result).isPresent();
        assertThat(result.get().contentType()).isEqualTo("image/png");
    }
}
