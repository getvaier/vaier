package net.vaier.application.service;

import net.vaier.application.GetIconUseCase.Icon;
import net.vaier.domain.port.ForFetchingIcons;
import net.vaier.domain.port.ForFetchingIcons.FetchedBytes;
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
class IconServiceTest {

    @Mock ForFetchingIcons forFetchingIcons;

    @InjectMocks IconService service;

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    }

    @Test
    void getIcon_returnsCachedResultWithoutFetching() {
        Icon cached = new Icon(png(), "image/png");
        service.cache.put("cached.example.com", Optional.of(cached));

        assertThat(service.getIcon("cached.example.com", null)).contains(cached);
        verify(forFetchingIcons, never()).fetchHtml(org.mockito.ArgumentMatchers.anyString());
        verify(forFetchingIcons, never()).fetchBytes(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void getIcon_cachesNegativeResults() {
        // No HTML, no bytes anywhere — caching empty prevents repeated lookups against dead hosts.
        when(forFetchingIcons.fetchHtml(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty());
        when(forFetchingIcons.fetchBytes(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty());

        Optional<Icon> first = service.getIcon("dead.example.com", null);
        Optional<Icon> second = service.getIcon("dead.example.com", null);

        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        assertThat(service.cache).containsKey("dead.example.com");
    }

    @Test
    void getIcon_cachesSeparatelyPerPathPrefix() {
        // Path-routed services share a hostname; each must resolve independently.
        Icon grafana = new Icon(png(), "image/png");
        Icon jenkins = new Icon(new byte[]{(byte) 0x89, 'P', 'N', 'J'}, "image/png");
        service.cache.put("services.example.com/grafana", Optional.of(grafana));
        service.cache.put("services.example.com/jenkins", Optional.of(jenkins));

        assertThat(service.getIcon("services.example.com", "/grafana")).contains(grafana);
        assertThat(service.getIcon("services.example.com", "/jenkins")).contains(jenkins);
    }

    @Test
    void getIcon_hostOnlyAndNullAndEmptyPathPrefixShareCacheEntry() {
        Icon cached = new Icon(png(), "image/png");
        service.cache.put("solo.example.com", Optional.of(cached));

        assertThat(service.getIcon("solo.example.com", null)).contains(cached);
        assertThat(service.getIcon("solo.example.com", "")).contains(cached);
    }

    @Test
    void getIcon_extractsFromHtmlHintWhenPresent() {
        String html = "<html><head><link rel=\"icon\" href=\"/favicon.png\"></head></html>";
        when(forFetchingIcons.fetchHtml("https://app.example.com/"))
            .thenReturn(Optional.of(html));
        when(forFetchingIcons.fetchBytes("https://app.example.com/favicon.png"))
            .thenReturn(Optional.of(new FetchedBytes(png(), "image/png")));

        Optional<Icon> result = service.getIcon("app.example.com", null);

        assertThat(result).isPresent();
        assertThat(result.get().contentType()).isEqualTo("image/png");
    }

    @Test
    void getIcon_fallsThroughToIconIcoWhenHtmlHintMissing() {
        when(forFetchingIcons.fetchHtml(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty());
        byte[] ico = {0, 0, 1, 0, 1, 0};
        when(forFetchingIcons.fetchBytes("https://app.example.com/favicon.ico"))
            .thenReturn(Optional.of(new FetchedBytes(ico, null)));

        Optional<Icon> result = service.getIcon("app.example.com", null);

        assertThat(result).isPresent();
        assertThat(result.get().contentType()).isEqualTo("image/x-icon");
    }

    @Test
    void getIcon_rejectsNonImageResponses() {
        // A 200 from an SPA that returns its index.html for any URL — bytes are HTML, not an icon.
        when(forFetchingIcons.fetchHtml(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty());
        when(forFetchingIcons.fetchBytes(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.of(new FetchedBytes("not-an-image".getBytes(), "text/html")));

        assertThat(service.getIcon("app.example.com", null)).isEmpty();
    }

    @Test
    void getIcon_fallsBackToCdnUrlsWhenAllDirectFetchesFail() {
        when(forFetchingIcons.fetchHtml(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty());
        when(forFetchingIcons.fetchBytes(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty());
        when(forFetchingIcons.fetchBytes("https://cdn.jsdelivr.net/gh/walkxcode/dashboard-icons@main/png/pihole.png"))
            .thenReturn(Optional.of(new FetchedBytes(png(), "image/png")));

        Optional<Icon> result = service.getIcon("pihole.example.com", null);

        assertThat(result).isPresent();
        assertThat(result.get().contentType()).isEqualTo("image/png");
    }
}
