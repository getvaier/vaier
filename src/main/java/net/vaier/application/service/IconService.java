package net.vaier.application.service;

import net.vaier.application.GetIconUseCase;
import net.vaier.domain.IconResolution;
import net.vaier.domain.port.ForFetchingIcons;
import net.vaier.domain.port.ForFetchingIcons.FetchedBytes;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IconService implements GetIconUseCase {

    private final ForFetchingIcons forFetchingIcons;
    final Map<String, Optional<Icon>> cache = new ConcurrentHashMap<>();

    public IconService(ForFetchingIcons forFetchingIcons) {
        this.forFetchingIcons = forFetchingIcons;
    }

    @Override
    public Optional<Icon> getIcon(String host, String pathPrefix) {
        String prefix = (pathPrefix == null) ? "" : pathPrefix;
        String cacheKey = host + prefix;
        Optional<Icon> cached = cache.get(cacheKey);
        if (cached != null) return cached;

        String hostUrl = "https://" + host;
        String prefixedUrl = hostUrl + prefix;

        Optional<Icon> result = fromHtmlHint(prefixedUrl);
        if (result.isEmpty() && !prefix.isEmpty()) result = fetchIcon(prefixedUrl + "/favicon.ico");
        if (result.isEmpty()) result = fetchIcon(hostUrl + "/favicon.ico");
        if (result.isEmpty()) result = fetchIcon(hostUrl + "/apple-touch-icon.png");
        if (result.isEmpty()) result = fetchIcon(hostUrl + "/apple-touch-icon-precomposed.png");
        if (result.isEmpty()) {
            for (String iconUrl : IconResolution.internetIconUrls(
                    IconResolution.cdnLookupName(host, prefix))) {
                result = fetchIcon(iconUrl);
                if (result.isPresent()) break;
            }
        }
        cache.put(cacheKey, result);
        return result;
    }

    private Optional<Icon> fromHtmlHint(String prefixedUrl) {
        Optional<String> html = forFetchingIcons.fetchHtml(prefixedUrl + "/");
        if (html.isEmpty()) return Optional.empty();
        return IconResolution.extractIconUrl(html.get(), prefixedUrl)
            .flatMap(this::fetchIcon);
    }

    private Optional<Icon> fetchIcon(String url) {
        return forFetchingIcons.fetchBytes(url)
            .filter(b -> b.body() != null && b.body().length > 0)
            .filter(b -> IconResolution.looksLikeImage(b.contentType(), b.body()))
            .map(IconService::toIcon);
    }

    private static Icon toIcon(FetchedBytes b) {
        return new Icon(b.body(), IconResolution.contentType(b.body()));
    }
}
