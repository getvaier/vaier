package net.vaier.application.service;

import net.vaier.application.GetFaviconUseCase;
import net.vaier.domain.FaviconResolution;
import net.vaier.domain.port.ForFetchingFavicons;
import net.vaier.domain.port.ForFetchingFavicons.FetchedBytes;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FaviconService implements GetFaviconUseCase {

    private final ForFetchingFavicons forFetchingFavicons;
    final Map<String, Optional<Favicon>> cache = new ConcurrentHashMap<>();

    public FaviconService(ForFetchingFavicons forFetchingFavicons) {
        this.forFetchingFavicons = forFetchingFavicons;
    }

    @Override
    public Optional<Favicon> getFavicon(String host, String pathPrefix) {
        String prefix = (pathPrefix == null) ? "" : pathPrefix;
        String cacheKey = host + prefix;
        Optional<Favicon> cached = cache.get(cacheKey);
        if (cached != null) return cached;

        String hostUrl = "https://" + host;
        String prefixedUrl = hostUrl + prefix;

        Optional<Favicon> result = fromHtmlHint(prefixedUrl);
        if (result.isEmpty() && !prefix.isEmpty()) result = fetchIcon(prefixedUrl + "/favicon.ico");
        if (result.isEmpty()) result = fetchIcon(hostUrl + "/favicon.ico");
        if (result.isEmpty()) result = fetchIcon(hostUrl + "/apple-touch-icon.png");
        if (result.isEmpty()) result = fetchIcon(hostUrl + "/apple-touch-icon-precomposed.png");
        if (result.isEmpty()) {
            for (String iconUrl : FaviconResolution.internetIconUrls(
                    FaviconResolution.cdnLookupName(host, prefix))) {
                result = fetchIcon(iconUrl);
                if (result.isPresent()) break;
            }
        }
        cache.put(cacheKey, result);
        return result;
    }

    private Optional<Favicon> fromHtmlHint(String prefixedUrl) {
        Optional<String> html = forFetchingFavicons.fetchHtml(prefixedUrl + "/");
        if (html.isEmpty()) return Optional.empty();
        return FaviconResolution.extractFaviconUrl(html.get(), prefixedUrl)
            .flatMap(this::fetchIcon);
    }

    private Optional<Favicon> fetchIcon(String url) {
        return forFetchingFavicons.fetchBytes(url)
            .filter(b -> b.body() != null && b.body().length > 0)
            .filter(b -> FaviconResolution.looksLikeImage(b.contentType(), b.body()))
            .map(FaviconService::toFavicon);
    }

    private static Favicon toFavicon(FetchedBytes b) {
        return new Favicon(b.body(), FaviconResolution.contentType(b.body()));
    }
}
